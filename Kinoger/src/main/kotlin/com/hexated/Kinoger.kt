package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.Chillx
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Kinoger : MainAPI() {
    override var name = "Kinoger"
    override var mainUrl = "https://kinoger.to"
    override var lang = "de"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
            "" to "Alle Filme",
            "stream/action" to "Action",
            "stream/fantasy" to "Fantasy",
            "stream/drama" to "Drama",
            "stream/mystery" to "Mystery",
            "stream/romance" to "Romance",
            "stream/animation" to "Animation",
            "stream/horror" to "Horror",
            "stream/familie" to "Familie",
            "stream/komdie" to "Komdie",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/page/$page").document
        val home = document.select("div#dle-content div.short").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperLink(uri: String): String {
        return if (uri.contains("-episode-")) {
            "$mainUrl/series/" + Regex("$mainUrl/(.+)-ep.+").find(uri)?.groupValues?.get(1)
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = getProperLink(this.selectFirst("a")?.attr("href") ?: return null)
        val title = this.selectFirst("a")?.text() ?: this.selectFirst("img")?.attr("alt")
        ?: this.selectFirst("a")?.attr("title") ?: return null
        val posterUrl = fixUrlNull(
            (this.selectFirst("div.content_text img") ?: this.nextElementSibling()?.selectFirst("div.content_text img") ?: this.selectFirst("img"))?.getImageAttr()
        )

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?do=search&subaction=search&titleonly=3&story=$query&x=0&y=0&submit=submit").document.select(
                "div#dle-content div.titlecontrol"
        ).mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1#news-title")?.text() ?: ""
        val poster = fixUrlNull(document.selectFirst("div.images-border img")?.getImageAttr())
        val description = document.select("div.images-border").text()
        val year = """\((\d{4})\)""".toRegex().find(title)?.groupValues?.get(1)?.toIntOrNull()
        val tags = document.select("li.category a").map { it.text() }

        val recommendations = document.select("ul.ul_related li").mapNotNull {
            it.toSearchResult()
        }

        val script = document.selectFirst("script:containsData(kinoger.ru)")?.data()
        val data = script?.substringAfter("[")?.substringBeforeLast("]")?.replace("\'", "\"")
        val json = AppUtils.tryParseJson<List<List<String>>>("[$data]")

        val type = if(script?.substringBeforeLast(")")?.substringAfterLast(",") == "0.2") TvType.Movie else TvType.TvSeries

        val episodes = json?.flatMapIndexed { season: Int, iframes: List<String> ->
            iframes.mapIndexed { episode, iframe ->
                Episode(
                    iframe.trim(),
                    season = season + 1,
                    episode = episode + 1
                )
            }
        } ?: emptyList()

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
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
        loadCustomExtractor(data, "$mainUrl/", subtitleCallback, callback)
        return true
    }

    private suspend fun loadCustomExtractor(
            url: String,
            referer: String? = null,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            quality: Int? = null,
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            if(link.quality == Qualities.Unknown.value) {
                callback.invoke(
                        ExtractorLink(
                                link.source,
                                link.name,
                                link.url,
                                link.referer,
                                when (link.type) {
                                    ExtractorLinkType.M3U8 -> link.quality
                                    else -> quality ?: link.quality
                                },
                                link.type,
                                link.headers,
                                link.extractorData
                        )
                )
            }
        }
    }

    private fun Element.getImageAttr(): String? {
        return when {
            this.hasAttr("data-src") -> this.attr("data-src")
            this.hasAttr("data-lazy-src") -> this.attr("data-lazy-src")
            this.hasAttr("srcset") -> this.attr("srcset").substringBefore(" ")
            else -> this.attr("src")
        }
    }

}

class Kinogeru : Chillx() {
    override val name = "Kinoger"
    override val mainUrl = "https://kinoger.ru"
}