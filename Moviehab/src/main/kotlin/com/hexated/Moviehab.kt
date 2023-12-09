package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Moviehab : MainAPI() {
    override var mainUrl = "https://nowshowing.to"
    override var name = "Moviehab"
    override val hasMainPage = true
    override var lang = "tl"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    companion object {
        private const val mainServer = "https://nowshowing.to"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/category/movies/" to "New Movies",
        "$mainUrl/category/tv-series/" to "New TV Shows",
        "$mainUrl/category/coming-soon/" to "Coming Soon",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.row div.col-6.col-md-4.col-lg-3").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("p.title")?.text() ?: return null
        val href = this.selectFirst("div.btn-list a")!!.attr("href")
        val posterUrl = fixUrlNull(
            this.select("div.poster-img").attr("data-bg-multi").substringAfter("url(")
                .substringBefore(")")
        )
        val quality = getQualityFromString(this.select("span.badge.bg-dark-dm").text())
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/search?term=$query",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).document
        return document.select("div.col-6.col-md-4.col-lg-3").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.content-title")?.text() ?: return null
        val poster = fixUrlNull(document.selectFirst("img.img-fluid.w-full")?.attr("src"))
        val tags = document.select("div.content div:nth-child(2) a").map { it.text() }

        val year = document.select("div.content div:nth-child(3) a").text().trim().toIntOrNull()
        val tvType = if (document.select("div.card.seasons-list")
                .isNullOrEmpty()
        ) TvType.Movie else TvType.TvSeries
        val description = document.select("div.card:contains(Storyline) p").text().trim()
        val trailer = document.selectFirst("div#trailer-modal iframe")?.attr("data-src")
        val rating = document.select("div.content div:nth-child(1) span").text().toRatingInt()

        return if (tvType == TvType.TvSeries) {
            val episodes =
                document.select("div.card.seasons-list select.episodes-select option").map { ele ->
                    val id = ele.attr("data-id")
                    val name = ele.text()
                    val episode = ele.attr("value").toIntOrNull()
                    val season = ele.parent()?.attr("id")?.filter { it.isDigit() }?.toIntOrNull()
                    Episode(
                        Episodes("$episode", "$season", id).toJson(),
                        name,
                        season,
                        episode,
                    )
                }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addTrailer(trailer)
            }
        } else {
            val link =
                document.select("div#direct-links-content input#link-1").attr("value").split("/")
                    .last()
            newMovieLoadResponse(title, url, TvType.Movie, Episodes(id = link).toJson()) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addTrailer(trailer)
            }
        }
    }

    private suspend fun invokeLokalSource(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url)
        res.document.select("video#player source").attr("src").let {
            val link = app.get("$mainServer/$it", referer = url).url
            M3u8Helper.generateM3u8(
                this.name,
                link,
                url
            ).forEach(callback)
        }

        Regex("src[\"|'],\\s[\"|'](\\S+)[\"|']\\)").find(res.text)?.groupValues?.get(1).let {sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    "English",
                    "$mainServer/$sub"
                )
            )
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val res = parseJson<Episodes>(data)
        val url = if (res.season.isNullOrBlank()) {
            "$mainUrl/embed/movie?id=${res.id}"
        } else {
            "$mainUrl/embed/series?id=${res.id}&sea=${res.season}&epi=${res.episode}"
        }

        app.get(url).document.select("div.dropdown-menu a.server").apmap { iframe ->
            safeApiCall {
                app.get(
                    "$mainUrl/ajax/get_stream_link?id=${iframe.attr("data-id")}&movie=${res.id}&is_init=false&captcha=&ref=",
                    referer = url,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).parsedSafe<Data>()?.data?.link?.let { link ->
                    if (link.startsWith(mainServer)) {
                        invokeLokalSource(link, subtitleCallback, callback)
                    } else {
                        loadExtractor(
                            link,
                            "$mainUrl/",
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }
        }

        return true
    }

    private data class Source(
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("_played") val _played: String? = null,
        @JsonProperty("token") val token: String? = null,
    )

    private data class Data(
        @JsonProperty("data") val data: Source? = null,
    )

    data class Episodes(
        val episode: String? = null,
        val season: String? = null,
        val id: String? = null,
    )

}
