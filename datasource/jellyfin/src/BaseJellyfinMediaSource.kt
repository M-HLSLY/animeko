/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.jellyfin

import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import me.him188.ani.datasources.api.*
import me.him188.ani.datasources.api.source.*
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.ktor.ScopedHttpClient
import java.util.concurrent.ConcurrentHashMap

abstract class BaseJellyfinMediaSource(
    private val client: ScopedHttpClient,
) : HttpMediaSource() {
    abstract val baseUrl: String
    abstract val userId: String
    abstract val apiKey: String

    // 缓存数据结构
    private val seriesCache = ConcurrentHashMap<String, List<Item>>()
    private val seasonCache = ConcurrentHashMap<String, List<Item>>()
    private val episodeCache = ConcurrentHashMap<String, List<Item>>()

    init {
        // 注册应用退出时的缓存清理（需框架支持）
        ApplicationLifecycle.addOnExitListener { clearCache() }
    }

    fun clearCache() {
        seriesCache.clear()
        seasonCache.clear()
        episodeCache.clear()
    }

    override suspend fun checkConnection(): ConnectionStatus {
        try {
            doSearchSeries("AA测试BB")
            return ConnectionStatus.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return ConnectionStatus.FAILED
        }
    }

    override suspend fun fetch(query: MediaFetchRequest): SizedSource<MediaMatch> {
        return SinglePagePagedSource {
            query.subjectNames.asFlow()
                .flatMapConcat { subjectName ->
                    val seriesList = getCachedOrFetch(subjectName, seriesCache) {
                        doSearchSeries(subjectName).Items
                    }.filter { it.Name.contains(subjectName, ignoreCase = true) }

                    seriesList.asFlow()
                        .flatMapConcat { series ->
                            val seasons = getCachedOrFetch(series.Id, seasonCache) {
                                doSeasons(series.Id).Items
                            }.filter { it.Name.contains(subjectName, ignoreCase = true) }

                            if (seasons.isEmpty()) {
                                getCachedOrFetch(series.Id, episodeCache) {
                                    doEpisodes(series.Id).Items
                                }.asFlow()
                            } else {
                                seasons.asFlow()
                                    .flatMapConcat { season ->
                                        getCachedOrFetch(season.Id, episodeCache) {
                                            doEpisodes(season.Id).Items
                                        }.asFlow()
                                            .filter { episode ->
                                                episode.Name.contains(subjectName, ignoreCase = true)
                                            }
                                    }
                            }
                        }
                }
                .filter { it.Type == "Episode" && it.CanDownload }
                .toList()
                .mapNotNull { item ->
                    val playbackInfo = getPlaybackInfo(item.Id)
                    val mediaSource = playbackInfo.MediaSources.firstOrNull()
                    val (resolution, languages) = parseMediaSourceName(mediaSource?.Name ?: "")

                    val (originalTitle, episodeRange) = when (item.Type) {
                        "Episode" -> {
                            val indexNumber = item.IndexNumber ?: return@mapNotNull null
                            Pair(
                                "$indexNumber ${item.Name}",
                                EpisodeRange.single(EpisodeSort(indexNumber)),
                            )
                        }
                        "Movie" -> Pair(item.Name, EpisodeRange.unknownSeason())
                        else -> return@mapNotNull null
                    }

                    MediaMatch(
                        media = DefaultMedia(
                            mediaId = item.Id,
                            mediaSourceId = mediaSourceId,
                            originalUrl = "$baseUrl/Items/${item.Id}",
                            download = ResourceLocation.HttpStreamingFile(
                                uri = mediaSource?.Path ?: getDownloadUri(item.Id),
                            ),
                            originalTitle = originalTitle,
                            publishedTime = 0,
                            properties = MediaProperties(
                                subjectName = item.SeasonName,
                                episodeName = item.Name,
                                subtitleLanguageIds = languages.ifEmpty { listOf("CHS") },
                                resolution = resolution ?: "1080P",
                                alliance = mediaSourceId,
                                size = FileSize.Unspecified,
                                subtitleKind = SubtitleKind.EXTERNAL_PROVIDED,
                            ),
                            extraFiles = MediaExtraFiles(
                                subtitles = getSubtitles(item.Id, item.MediaStreams),
                            ),
                            episodeRange = episodeRange,
                            location = MediaSourceLocation.Lan,
                            kind = MediaSourceKind.WEB,
                        ),
                        kind = MatchKind.FUZZY,
                    )
                }
                .filter { it.matches(query) != false }
                .asFlow()
        }
    }

    // 解析 MediaSource.Name 提取画质和语言
    private fun parseMediaSourceName(name: String): Pair<String?, List<String>> {
        val resolutionRegex = """(\d{3,4}[PpKk])""".toRegex()
        val languageRegex = """\[([A-Z]{2,3})\]""".toRegex()
        val resolution = resolutionRegex.find(name)?.value?.uppercase()
        val languages = languageRegex.findAll(name).map { it.groupValues[1] }.toList()
        return Pair(resolution, languages)
    }

    private suspend fun getCachedOrFetch(
        key: String,
        cache: ConcurrentHashMap<String, List<Item>>,
        fetch: suspend () -> List<Item>
    ): List<Item> = cache[key] ?: fetch().also { cache[key] = it }

    private fun getSubtitles(itemId: String, mediaStreams: List<MediaStream>): List<Subtitle> {
        return mediaStreams
            .filter { it.Type == "Subtitle" && it.IsTextSubtitleStream && it.IsExternal }
            .map { stream ->
                Subtitle(
                    uri = getSubtitleUri(itemId, stream.Index, stream.Codec),
                    language = stream.Language,
                    mimeType = when (stream.Codec.lowercase()) {
                        "ass" -> "text/x-ass"
                        else -> "application/octet-stream"
                    },
                    label = stream.Title,
                )
            }
    }

    private fun getSubtitleUri(itemId: String, index: Int, codec: String): String {
        return "$baseUrl/Videos/$itemId/$itemId/Subtitles/$index/0/Stream.$codec"
    }

    private suspend fun doSearchSeries(subjectName: String) = client.use {
        get("$baseUrl/Items") {
            parameter("searchTerm", subjectName)
            parameter("includeItemTypes", "Series")
            parameter("fields", "CanDownload,ParentId,MediaSources")
            parameter("enableImages", true)
            configureAuthorizationHeaders()
        }.body<SearchResponse>()
    }

    private suspend fun doSeasons(seriesId: String) = client.use {
        get("$baseUrl/Items") {
            parameter("parentId", seriesId)
            parameter("includeItemTypes", "Season")
            parameter("enableImages", true)
            configureAuthorizationHeaders()
        }.body<SearchResponse>()
    }

    private suspend fun doEpisodes(seasonId: String) = client.use {
        get("$baseUrl/Items") {
            parameter("parentId", seasonId)
            parameter("includeItemTypes", "Episode")
            parameter("fields", "MediaSources")
            parameter("enableImages", true)
            configureAuthorizationHeaders()
        }.body<SearchResponse>()
    }

    private suspend fun getPlaybackInfo(itemId: String) = client.use {
        get("$baseUrl/Items/$itemId/PlaybackInfo") {
            configureAuthorizationHeaders()
        }.body<PlaybackInfoResponse>()
    }

    private fun HttpRequestBuilder.configureAuthorizationHeaders() {
        header(HttpHeaders.Authorization, "MediaBrowser Token=\"$apiKey\"")
    }

    @Serializable
    private data class PlaybackInfoResponse(val MediaSources: List<MediaSourceInfo>)

    @Serializable
    private data class MediaSourceInfo(val Id: String, val Name: String, val Path: String)

    @Serializable
    private class SearchResponse(val Items: List<Item> = emptyList())

    @Serializable
    @Suppress("PropertyName")
    private data class MediaStream(
        val Title: String? = null,
        val Language: String? = null,
        val Type: String,
        val Codec: String,
        val Index: Int,
        val IsExternal: Boolean,
        val IsTextSubtitleStream: Boolean,
    )

    @Serializable
    @Suppress("PropertyName")
    private data class Item(
        val Name: String,
        val SeasonName: String? = null,
        val Id: String,
        val IndexNumber: Int? = null,
        val Type: String,
        val CanDownload: Boolean = false,
        val MediaStreams: List<MediaStream> = emptyList(),
    )
}
