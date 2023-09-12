package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class DramaSerial : MainAPI() {
    override var mainUrl = "https://dramaserial.sbs"
    override var name = "DramaSerial"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Movie",
        "$mainUrl/Genre/ongoing/page/" to "Ongoing",
        "$mainUrl/Genre/drama-serial-korea/page/" to "Drama Serial Korea",
        "$mainUrl/Genre/drama-serial-jepang/page/" to "Drama Serial Jepang",
        "$mainUrl/Genre/drama-serial-mandarin/page/" to "Drama Serial Mandarin",
        "$mainUrl/Genre/drama-serial-filipina/page/" to "Drama Serial Filipina",
        "$mainUrl/Genre/drama-serial-india/page/" to "Drama Serial India",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("main#main article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val title = this.selectFirst("h2.entry-title a")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val episode =
            this.selectFirst("div.gmr-episode-item")?.text()?.filter { it.isDigit() }?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
            addSub(episode)
        }
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

        val title = document.selectFirst("h1.entry-title")!!.text().trim()
        val poster = fixUrlNull(document.selectFirst("figure.pull-left img")?.attr("src"))
        val tags =
            document.select("div.gmr-movie-innermeta span:contains(Genre:) a").map { it.text() }
        val year =
            document.selectFirst("div.gmr-movie-innermeta span:contains(Year:) a")!!.text().trim()
                .toIntOrNull()
        val duration =
            document.selectFirst("div.gmr-movie-innermeta span:contains(Duration:)")?.text()
                ?.filter { it.isDigit() }?.toIntOrNull()
        val description = document.select("div.entry-content.entry-content-single div.entry-content.entry-content-single").text().trim()
        val type = if(document.select("div.page-links").isNullOrEmpty()) TvType.Movie else TvType.AsianDrama

        if (type == TvType.Movie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration
            }
        } else {
            val episodes = document.select("div.page-links span.page-link-number").mapNotNull { eps ->
                val episode = eps.text().filter { it.isDigit() }.toIntOrNull()
                val link = if(episode == 1) {
                    url
                } else {
                    eps.parent()?.attr("href")
                }
                Episode(
                    link ?: return@mapNotNull null,
                    episode = episode,
                )
            }
            return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes = episodes) {
                posterUrl = poster
                this.year = year
                this.duration = duration
                plot = description
                this.tags = tags
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

        val iframe = document.select("div.gmr-server-wrap iframe").attr("src")
        app.get(iframe, referer = "$mainUrl/").document.select("div#header-slider ul li").apmap { mLink ->
            mLink.attr("onclick").substringAfter("frame('").substringBefore("')").let { iLink ->
                val uLink = app.get(iLink, referer = iframe).document.select("script").find { it.data().contains("(document).ready") }?.data()?.substringAfter("replace(\"")?.substringBefore("\");") ?: return@apmap null
                val link = app.get(uLink, referer = iLink).document.selectFirst("iframe")?.attr("src") ?: return@apmap null
                loadExtractor(fixUrl(link), "https://juraganfilm.info/", subtitleCallback, callback)
            }
        }

        return true

    }

}

class Bk21 : Filesim() {
    override val name = "Bk21"
    override var mainUrl = "https://bk21.net"
}
