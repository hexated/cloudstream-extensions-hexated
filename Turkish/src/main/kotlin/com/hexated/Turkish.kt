package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Turkish : MainAPI() {
    override var mainUrl = "https://turkish123.com"
    override var name = "Turkish123"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    companion object {
        private const val mainServer = "https://tukipasti.com"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/series-list/page/" to "Series List",
        "$mainUrl/episodes-list/page/" to "Episodes List",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.movies-list div.ml-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperLink(uri: String): String {
        return when {
            uri.contains("-episode") -> uri.substringBefore("-episode")
            else -> uri
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = getProperLink(this.selectFirst("a")!!.attr("href"))
        val title = this.selectFirst("h2")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val quality = getQualityFromString(this.selectFirst("span.mli-quality")?.text())
        val episode = this.selectFirst("span.mli-eps i")?.text()?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
            this.quality = quality
            addSub(episode)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.movies-list div.ml-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.thumb.mvic-thumb img")?.attr("src"))
        val tags = document.select("div.mvici-left p:contains(Genre:) a").map { it.text() }

        val year = document.selectFirst("div.mvici-right p:contains(Year:) a")?.text()?.trim()
            ?.toIntOrNull()
        val description = document.select("p.f-desc").text().trim()
        val duration = document.selectFirst("div.mvici-right span[itemprop=duration]")?.text()
            ?.filter { it.isDigit() }?.toIntOrNull()
        val rating = document.select("span.imdb-r").text().trim().toRatingInt()
        val actors = document.select("div.mvici-left p:contains(Actors:) a").map { it.text() }

        val recommendations = document.select("div.movies-list div.ml-item").mapNotNull {
            it.toSearchResult()
        }

        val episodes = document.select("div.les-content a").map {
            Episode(
                it.attr("href"),
                it.text(),
            )
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.rating = rating
            this.duration = duration
            addActors(actors)
            this.recommendations = recommendations
        }
    }

    private suspend fun invokeLocalSource(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = "$mainUrl/").text

        Regex("var\\surlPlay\\s=\\s[\"|'](\\S+)[\"|'];").find(document)?.groupValues?.get(1)
            ?.let { link ->
                M3u8Helper.generateM3u8(
                    this.name,
                    link,
                    referer = "$mainServer/"
                ).forEach(callback)
            }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).text

        Regex("<iframe.*src=[\"|'](\\S+)[\"|']\\s").findAll(document).map { it.groupValues[1] }
            .toList().apmap { link ->
                if (link.startsWith(mainServer)) {
                    invokeLocalSource(link, callback)
                } else {
                    loadExtractor(link, "$mainUrl/", subtitleCallback, callback)
                }
            }

        return true

    }

}