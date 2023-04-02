package com.hexated

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.json.JSONObject
import java.net.URI

private const val TRACKER_LIST_URL =
    "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"

class Stremio : MainAPI() {
    override var mainUrl = "https://stremio.github.io/stremio-static-addon-example"
    override var name = "Stremio"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true
    private val cinemataUrl = "https://v3-cinemeta.strem.io"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        mainUrl = mainUrl.fixSourceUrl()
        val res = tryParseJson<Manifest>(app.get("${mainUrl}/manifest.json").text) ?: return null
        val lists = mutableListOf<HomePageList>()
        res.catalogs.forEach { catalog ->
            catalog.toHomePageList(this)?.let {
                lists.add(it)
            }
        }
        return HomePageResponse(
            lists,
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        mainUrl = mainUrl.fixSourceUrl()
        val res = tryParseJson<Manifest>(app.get("${mainUrl}/manifest.json").text) ?: return null
        val list = mutableListOf<SearchResponse>()
        res.catalogs.forEach { catalog ->
            list.addAll(catalog.search(query, this))
        }
        return list
    }

    override suspend fun load(url: String): LoadResponse? {
        val res = parseJson<CatalogEntry>(url)
        mainUrl = if((res.type == "movie" || res.type == "series") && isImdborTmdb(res.id)) cinemataUrl else mainUrl
        val json = app.get("${mainUrl}/meta/${res.type}/${res.id}.json")
            .parsedSafe<CatalogResponse>()?.meta ?: throw RuntimeException(url)
        return json.toLoadResponse(this)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = tryParseJson<StreamsResponse>(app.get(data).text) ?: return false
        res.streams.forEach { stream ->
            stream.runCallback(this, subtitleCallback, callback)
        }

        return true
    }

    // check if id is imdb/tmdb cause stremio addons like torrentio works base on imdbId
    private fun isImdborTmdb(url: String?): Boolean {
        return imdbUrlToIdNullable(url) != null || url?.startsWith("tmdb:") == true
    }

    private data class Manifest(val catalogs: List<Catalog>)
    private data class Catalog(
        var name: String?,
        val id: String,
        val type: String?,
        val types: MutableList<String> = mutableListOf()
    ) {
        init {
            if (type != null) types.add(type)
        }

        suspend fun search(query: String, provider: Stremio): List<SearchResponse> {
            val entries = mutableListOf<SearchResponse>()
            types.forEach { type ->
                val json =
                    app.get("${provider.mainUrl}/catalog/${type}/${id}/search=${query}.json").text
                val res =
                    tryParseJson<CatalogResponse>(json)
                        ?: return@forEach
                res.metas?.forEach { entry ->
                    entries.add(entry.toSearchResponse(provider))
                }
            }
            return entries
        }

        suspend fun toHomePageList(provider: Stremio): HomePageList? {
            val entries = mutableListOf<SearchResponse>()
            types.forEach { type ->
                val json = app.get("${provider.mainUrl}/catalog/${type}/${id}.json").text
                val res =
                    tryParseJson<CatalogResponse>(json)
                        ?: return@forEach
                res.metas?.forEach { entry ->
                    entries.add(entry.toSearchResponse(provider))
                }
            }
            return HomePageList(
                name ?: id,
                entries
            )
        }
    }

    private data class CatalogResponse(val metas: List<CatalogEntry>?, val meta: CatalogEntry?)
    private data class CatalogEntry(
        val name: String,
        val id: String,
        val poster: String?,
        val description: String?,
        val type: String?,
        val videos: List<Video>?
    ) {
        fun toSearchResponse(provider: Stremio): SearchResponse {
            return provider.newMovieSearchResponse(
                fixTitle(name),
                this.toJson(),
                TvType.Others
            ) {
                posterUrl = poster
            }
        }

        suspend fun toLoadResponse(provider: Stremio): LoadResponse {
            if (videos == null || videos.isEmpty()) {
                return provider.newMovieLoadResponse(
                    name,
                    "${provider.mainUrl}/meta/${type}/${id}.json",
                    TvType.Movie,
                    "${provider.mainUrl}/stream/${type}/${id}.json"
                ) {
                    posterUrl = poster
                    plot = description
                }
            } else {
                return provider.newTvSeriesLoadResponse(
                    name,
                    "${provider.mainUrl}/meta/${type}/${id}.json",
                    TvType.TvSeries,
                    videos.map {
                        it.toEpisode(provider, type)
                    }
                ) {
                    posterUrl = poster
                    plot = description
                }
            }

        }
    }

    private data class Video(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("season") val seasonNumber: Int? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("overview") val overview: String? = null,
    ) {
        fun toEpisode(provider: Stremio, type: String?): Episode {
            return provider.newEpisode(
                "${provider.mainUrl}/stream/${type}/${id}.json"
            ) {
                this.name = title ?: name
                this.posterUrl = thumbnail
                this.description = overview
                this.season = seasonNumber
                this.episode = episode ?: number
            }
        }
    }

    private data class StreamsResponse(val streams: List<Stream>)
    private data class Subtitle(
        val url: String?,
        val lang: String?,
        val id: String?,
    )

    private data class Stream(
        val name: String?,
        val title: String?,
        val url: String?,
        val description: String?,
        val ytId: String?,
        val externalUrl: String?,
        val behaviorHints: JSONObject?,
        val infoHash: String?,
        val sources: List<String> = emptyList(),
        val subtitles: List<Subtitle> = emptyList()
    ) {
        suspend fun runCallback(
            provider: Stremio,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            if (url != null) {
                var referer: String? = null
                try {
                    val headers = ((behaviorHints?.get("proxyHeaders") as? JSONObject)
                        ?.get("request") as? JSONObject)
                    referer =
                        headers?.get("referer") as? String ?: headers?.get("origin") as? String
                } catch (ex: Throwable) {
                    Log.e("Stremio", Log.getStackTraceString(ex))
                }
                callback.invoke(
                    ExtractorLink(
                        name ?: "",
                        title ?: name ?: "",
                        url,
                        if (provider.mainUrl.contains("kisskh")) "https://kisskh.me/" else referer
                            ?: "",
                        getQualityFromName(description),
                        isM3u8 = URI(url).path.endsWith(".m3u8")
                    )
                )
                subtitles.map { sub ->
                    subtitleCallback.invoke(
                        SubtitleFile(
                            SubtitleHelper.fromThreeLettersToLanguage(sub.lang ?: "") ?: sub.lang
                            ?: "",
                            sub.url ?: return@map
                        )
                    )
                }
            }
            if (ytId != null) {
                loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback)
            }
            if (externalUrl != null) {
                loadExtractor(externalUrl, subtitleCallback, callback)
            }
            if (infoHash != null) {
                val resp = app.get(TRACKER_LIST_URL).text
                val otherTrackers = resp
                    .split("\n")
                    .filterIndexed { i, s -> i % 2 == 0 }
                    .filter { s -> s.isNotEmpty() }.joinToString("") { "&tr=$it" }

                val sourceTrackers = sources
                    .filter { it.startsWith("tracker:") }
                    .map { it.removePrefix("tracker:") }
                    .filter { s -> s.isNotEmpty() }.joinToString("") { "&tr=$it" }

                val magnet = "magnet:?xt=urn:btih:${infoHash}${sourceTrackers}${otherTrackers}"
                callback.invoke(
                    ExtractorLink(
                        name ?: "",
                        title ?: name ?: "",
                        magnet,
                        "",
                        Qualities.Unknown.value
                    )
                )
            }
        }
    }
}