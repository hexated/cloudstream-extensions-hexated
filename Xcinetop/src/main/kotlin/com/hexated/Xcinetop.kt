package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

class Xcinetop : MainAPI() {
    override var mainUrl = "https://xcine.click"
    override var name = "Xcine.top"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override var lang = "de"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    companion object {
        private const val mainServer = "https://supervideo.tv"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/aktuelle-kinofilme-im-kino/page/" to "Kinofilme im kino",
        "$mainUrl/serienstream-deutsch/page/" to "Serien",
        "$mainUrl/animation/page/" to "Animation",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}$page/").document
        val home = document.select("div#dle-content div.movie-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val title = this.selectFirst("div.movie-item__title")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val quantity = this.selectFirst("div.movie-item__label")?.text() ?: return null
        val episode =
            this.selectFirst("span.ep-num")?.text()?.filter { it.isDigit() }?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.TvSeries) {
            addQuality(quantity)
            addDub(episode)
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            "$mainUrl/index.php?do=search", data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "search_start" to "0",
                "full_search" to "0",
                "result_from" to "1",
                "story" to query
            )
        ).document
        return document.select("div#dle-content div.movie-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.inner-page__title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.inner-page__img img")?.attr("src"))
        val tags = document.select("ul.inner-page__list li:contains(Genre:) a").map { it.text() }
        val year = document.selectFirst("ul.inner-page__list li:contains(Jahr:)")?.ownText()
            ?.toIntOrNull()
        val description = document.select("div.inner-page__text.text.clearfix").text()
        val duration = document.selectFirst("ul.inner-page__list li:contains(Zeit:)")?.ownText()
            ?.filter { it.isDigit() }?.toIntOrNull()
        val actors = document.selectFirst("ul.inner-page__list li:contains(Darsteller:)")?.ownText()
            ?.split(",")?.map { it.trim() }
        val recommendations = document.select("div.section__content.section__items div.movie-item")
            .mapNotNull { it.toSearchResult() }
        val type = if (document.select("ul.series-select-menu.ep-menu")
                .isNullOrEmpty()
        ) TvType.Movie else TvType.TvSeries
        val trailer = document.selectFirst("div.stretch-free-width.mirrors span:contains(Trailer)")
            ?.attr("data-link") ?: document.selectFirst("div#trailer iframe")?.attr("src")

        if (type == TvType.Movie) {
            val link = document.select("div.stretch-free-width.mirrors span").map {
                fixUrl(it.attr("data-link"))
            }
            return newMovieLoadResponse(title, url, TvType.Movie, Links(link).toJson()) {
                this.posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val episodes = document.select("ul.series-select-menu.ep-menu li[id*=serie]").map {
                val name = it.selectFirst("a")?.text()
                val link = it.select("li").map { eps ->
                    fixUrl(eps.select("a").attr("data-link"))
                }
                Episode(
                    Links(link).toJson(),
                    name
                )
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes = episodes) {
                this.posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private suspend fun invokeLocalSource(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val script = app.get(url, referer = "$mainUrl/").document.select("script")
            .find { it.data().contains("eval(function(p,a,c,k,e,d)") }?.data()
        val data = getAndUnpack(script ?: return).substringAfter("sources:[").substringBefore("],")
            .replace("file", "\"file\"")
            .replace("label", "\"label\"")

        tryParseJson<List<Sources>>("[$data]")?.map { link ->
            val source = "Supervideo"
            if (link.file?.contains(".m3u8") == true) {
                M3u8Helper.generateM3u8(
                    "$source (Main)",
                    link.file,
                    "$mainServer/"
                ).forEach(callback)
            } else {
                callback.invoke(
                    ExtractorLink(
                        "$source (Backup)",
                        "$source (Backup)",
                        link.file ?: return@map null,
                        "$mainServer/",
                        getQualityFromName(link.label)
                    )
                )
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        parseJson<Links>(data).url?.apmap { link ->
            safeApiCall {
                if (link.startsWith(mainServer)) {
                    invokeLocalSource(link, callback)
                } else {
                    loadExtractor(link, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }

        return true
    }

    private data class Links(
        val url: List<String>? = arrayListOf(),
    )

    private data class Sources(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null
    )

}