package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Ngefilm : MainAPI() {
    override var mainUrl = "https://ngefilm21.cfd"
    override var name = "Ngefilm"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "?s&search=advanced&post_type=movie&index&orderby&genre&movieyear&country&quality=" to "Movies Terbaru",
        "?s=&search=advanced&post_type=tv&index=&orderby=&genre=&movieyear=&country=&quality=" to "Series Terbaru",
        "?s=&search=advanced&post_type=tv&index=&orderby=&genre=drakor&movieyear=&country=&quality=" to "Series Korea",
        "?s=&search=advanced&post_type=tv&index=&orderby=&genre=&movieyear=&country=indonesia&quality=" to "Series Indonesia",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/page/$page/${request.data}").document
        val home = document.select("main#main article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query&post_type[]=post&post_type[]=tv"
        val document = app.get(link).document
        return document.select("main#main article").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title =
            document.selectFirst("h1.entry-title")?.text()?.substringBefore("Season")?.trim() ?: ""
        val poster = fixUrlNull(
            document.selectFirst("figure.pull-left > img")?.attr("src")?.fixImageQuality()
        )
        val tags = document.select("span.gmr-movie-genre:contains(Genre:) > a").map { it.text() }

        val year =
            document.select("span.gmr-movie-genre:contains(Year:) > a").text().trim().toIntOrNull()
        val tvType = if (url.contains("/tv/")) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("div[itemprop=description] > p")?.text()?.trim()
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating =
            document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")?.text()
                ?.toRatingInt()
        val actors = document.select("div.gmr-moviedata").last()?.select("span[itemprop=actors]")
            ?.map { it.select("a").text() }

        val recommendations = document.select("div.idmuvi-rp ul li").mapNotNull {
            it.toRecommendResult()
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("div.gmr-listseries > a")
                .filter { element -> !element.text().contains("Pilih Episode", true) }
                .map { eps ->
                    val href = fixUrl(eps.attr("href"))
                    val episode = eps.text().substringAfter("Eps").toIntOrNull()
                    val season =
                        eps.text().split(" ").first().substringAfter("S").toIntOrNull() ?: 1
                    Episode(
                        href,
                        season = season,
                        episode = episode,
                    )
                }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
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

        document.select("ul.muvipro-player-tabs li a").apmap { server ->
            val iframe = app.get(fixUrl(server.attr("href"))).document.selectFirst("div.gmr-embed-responsive iframe")
                    ?.attr("src")?.let { fixUrl(it) } ?: return@apmap
            loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
        }

        return true

    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.attr("src").fixImageQuality())
        val quality = this.select("div.gmr-quality-item > a").text().trim()
        return if (quality.isEmpty()) {
            val episode =
                this.select("div.gmr-numbeps > span").text().filter { it.isDigit() }.toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality.replace("-", ""))
            }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = this.selectFirst("a > span.idmuvi-rp-title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")!!.attr("href")
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.attr("src").fixImageQuality())
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    private fun String?.fixImageQuality(): String? {
        val quality = Regex("(-\\d*x\\d*)").find(this ?: return null)?.groupValues?.get(0)
        return this.replace(quality ?: return null, "")
    }

}