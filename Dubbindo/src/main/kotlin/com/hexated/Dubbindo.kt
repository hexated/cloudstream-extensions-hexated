package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Dubbindo : MainAPI() {
    override var mainUrl = "https://www.dubbindo.site"
    override var name = "Dubbindo"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Cartoon,
        TvType.Anime,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/videos/category/1" to "Movie",
        "$mainUrl/videos/category/3" to "TV Series",
        "$mainUrl/videos/category/5" to "Anime Series",
        "$mainUrl/videos/category/4" to "Anime Movie",
        "$mainUrl/videos/category/other" to "Other",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("${request.data}?page_id=$page").document
        val home = document.select("div.videos-latest-list.pt_timeline_vids div.video-wrapper")
            .mapNotNull {
                it.toSearchResult()
            }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): TvSeriesSearchResponse? {
        val title = this.selectFirst("h4,div.video-title")?.text()?.trim() ?: ""
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..10) {
            val document =
                app.get(
                    "$mainUrl/search?keyword=$query&page_id=$i",
                ).document
            val results = document.select("div.videos-latest-list.row div.video-wrapper")
                .mapNotNull {
                    it.toSearchResult()
                }
            searchResponse.addAll(results)
            if (results.isEmpty()) break
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.video-big-title h1")?.text() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = document.select("div.pt_categories li a").map { it.text() }
        val description = document.select("div.watch-video-description p").text()
        val recommendations = document.select("div.related-video-wrapper").mapNotNull {
            it.toSearchResult()
        }
        val video = document.select("video#my-video source").map {
            Video(
                it.attr("src"),
                it.attr("size"),
                it.attr("type"),
            )
        }

        return newMovieLoadResponse(title, url, TvType.Movie, video.toJson()) {
            posterUrl = poster
            plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        tryParseJson<List<Video>>(data)?.map { video ->
            if(video.type == "video/mp4" || video.type == "video/x-msvideo" || video.type == "video/x-matroska") {
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        video.src ?: return@map,
                        "",
                        video.res?.toIntOrNull() ?: Qualities.Unknown.value,
                    )
                )
            } else {
                loadExtractor(video.src ?: return@map, "", subtitleCallback, callback)
            }
        }

        return true
    }

    data class Video(
        val src: String? = null,
        val res: String? = null,
        val type: String? = null,
    )

}
