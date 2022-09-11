package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.net.URI

class IMoviehd : MainAPI() {
    override var mainUrl = "https://www.i-moviehd.com"
    override var name = "I-Moviehd"
    override val hasMainPage = true
    override var lang = "th"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "RECOMMENDATION",
        "$mainUrl/category/series-ซีรี่ส์/page/" to "NEW SERIES",
        "$mainUrl/top-movie/page/" to "TOP  MOVIES IMDB",
        "$mainUrl/top-series/page/" to "TOP SERIES IMDB",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.item-wrap.clearfix div.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(fixTitle(request.name), home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.substringAfter("$mainUrl/").contains("-ep-")) {
            val title = uri.substringAfter("$mainUrl/").replace(Regex("-ep-[0-9]+"), "")
            "$mainUrl/$title"
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a")?.attr("title") ?: return null
        val href = getProperAnimeLink(this.selectFirst("a")!!.attr("href"))
        val posterUrl = fixUrlNull(this.select("img").attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document

        return document.select("div.item-wrap.clearfix div.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text() ?: return null
        val poster = document.selectFirst("table#imdbinfo td img")?.attr("src")
        val tags = document.select("span.categories > a").map { it.text() }

        val tvType = if (document.select("table#Sequel").isNullOrEmpty()
        ) TvType.Movie else TvType.TvSeries
        val description = document.select("div.entry-content.post_content p").text().trim()
        val trailer = document.selectFirst("div#tabt iframe")?.attr("sub_src")
        val rating = document.selectFirst("div.imdb-rating-content span")?.text()?.toRatingInt()

        val recommendations = document.select("div.item-wrap.clearfix div.item").mapNotNull {
            it.toSearchResult()
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("table#Sequel tbody tr").mapNotNull {
                val href = fixUrl(it.selectFirst("a")?.attr("href") ?: return null)
                val name = it.selectFirst("a")?.text()?.trim() ?: return null
                Episode(
                    href,
                    name,
                )
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        document.select("script").find { it.data().contains("\$.getJSON(") }?.data()
            ?.substringAfter("\$.getJSON( \"")?.substringBefore("\"+")?.let { iframe ->
                val server = app.get("$iframe\\0&b=", referer = "$mainUrl/")
                    .parsedSafe<Server>()?.link
                val id = app.post(
                    "https://vlp.77player.xyz/initPlayer/${server?.substringAfter("key=")}",
                    referer = server,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).parsedSafe<Source>()?.data?.substringAfter("id=")

//                M3u8Helper.generateM3u8(
//                    this.name,
//                    "https://xxx.77player.xyz/iosplaylist/$id/$id.m3u8",
//                    referer = "$mainUrl/",
//                    headers = mapOf(
////                        "Origin" to "https://xxx.77player.xyz",
//                    )
//                ).forEach(callback)

                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        "https://xxx.77player.xyz/iosplaylist/$id/$id.m3u8",
                        referer = "$mainUrl/",
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )

            }


        return true
    }

    data class Server(
        @JsonProperty("ตัวเล่นไว") val link: String?,
    )

    data class Source(
        @JsonProperty("data") val data: String?,
    )


}