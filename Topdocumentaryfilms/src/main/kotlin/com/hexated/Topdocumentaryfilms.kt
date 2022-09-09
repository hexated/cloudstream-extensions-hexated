package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Topdocumentaryfilms : MainAPI() {
    override var mainUrl = "https://topdocumentaryfilms.com/"
    override var name = "Topdocumentaryfilms"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Documentary)


    override val mainPage = mainPageOf(
        "$mainUrl/category/technology/page/" to "Technology",
        "$mainUrl/category/military-war/page/" to " Military and War",
        "$mainUrl/category/sports/page/" to "Sports",
        "$mainUrl/category/media/page/" to "Media",
        "$mainUrl/category/society/page/" to "Society",
        "$mainUrl/category/history/page/" to "History",
        "$mainUrl/category/sex/page/" to "Sexuality",
        "$mainUrl/category/health/page/" to "Health",
        "$mainUrl/category/science-technology/page/" to "Science",
        "$mainUrl/category/environment/page/" to "Environment",
        "$mainUrl/category/religion/page/" to "Religion",
        "$mainUrl/category/economics/page/" to "Economics",
        "$mainUrl/category/psychology/page/" to "Psychology",
        "$mainUrl/category/drugs/page/" to "Drugs",
        "$mainUrl/category/politics/page/" to "Politics",
        "$mainUrl/category/crime/page/" to "Crime",
        "$mainUrl/category/philosophy/page/" to "Philosophy",
        "$mainUrl/category/crime-conspiracy/page/" to "Conspiracy",
        "$mainUrl/category/music-performing-arts/page/" to "Performing Arts",
        "$mainUrl/category/biography/page/" to "Biography",
        "$mainUrl/category/nature-wildlife/page/" to "Nature",
        "$mainUrl/category/art-artists/page/" to " Art and Artists",
        "$mainUrl/category/mystery/page/" to "Mystery",
        "$mainUrl/category/911/page/" to "9/11",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("main article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2 > a")?.text() ?: return null
        val href = this.selectFirst("h2 > a")!!.attr("href")
        val posterUrl = this.selectFirst("a img")?.let {
            if (it.attr("data-src").isNullOrBlank()) it.attr("src") else it.attr("data-src")
        }
        return newMovieSearchResponse(title, href, TvType.Documentary) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("header > h1")?.text() ?: return null
        val link = document.selectFirst("article meta[itemprop=embedUrl]")?.attr("content")?.split("/")?.last()?.let{
            "https://www.youtube.com/watch?v=$it"
        } ?: throw ErrorLoadingException("No link found")

        return newMovieLoadResponse(title, url, TvType.Movie, link) {
            this.posterUrl = document.select("div.player-content > img").attr("src")
            this.year = document.selectFirst("div.meta-bar.meta-single")?.ownText()?.filter { it.isDigit() }?.toIntOrNull()
            this.plot = document.select("div[itemprop=reviewBody] > p").text().trim()
            this.tags = document.select("div.meta-bar.meta-single > a").map { it.text() }
            this.rating = document.selectFirst("div.module div.star")?.text()?.toRatingInt()
            this.recommendations = document.select("ul.side-wrap.clear li").mapNotNull {
                val recName = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
                val recHref = it.selectFirst("a")!!.attr("href")
                newMovieSearchResponse(recName, recHref, TvType.Documentary) {
                    this.posterUrl = it.selectFirst("a img")?.attr("data-src").toString()
                }
            }
            addTrailer(link)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        loadExtractor(data, data, subtitleCallback, callback)

        return true
    }
}
