package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URI

class LayarKacaProvider : MainAPI() {
    override var mainUrl = "https://tv.lk21official.live"
    private var seriesUrl = "https://drama2.nontondrama.lol"
    override var name = "LayarKaca"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    companion object {
        const val filemoon = "https://filemoon.sx"
        const val streamhide = "https://streamhide.to"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/populer/page/" to "Film Terplopuler",
        "$mainUrl/rating/page/" to "Film Berdasarkan IMDb Rating",
        "$mainUrl/most-commented/page/" to "Film Dengan Komentar Terbanyak",
        "$seriesUrl/latest/page/" to "Series Terbaru",
        "$seriesUrl/series/asian/page/" to "Film Asian Terbaru",
        "$mainUrl/latest/page/" to "Film Upload Terbaru",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val res = app.get(request.data + page)
        val baseUrl = getBaseUrl(res.url)
        when {
            request.data.startsWith(mainUrl) -> {
                mainUrl = baseUrl
            }
            request.data.startsWith(seriesUrl) -> {
                seriesUrl = baseUrl
            }
        }
        val document = res.document
        val home = document.select("article.mega-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperLink(str: String, check: String): String {
        return if (check.contains("/series", true) || check.contains("Season", true)) {
            str.replace(mainUrl, seriesUrl)
        } else {
            str
        }
    }

//    private fun changesUrl(url: String): String {
//        val startsWithNoHttp = url.startsWith("//")
//        if (startsWithNoHttp) {
//            return "https:$url"
//        } else {
//            if (url.startsWith('/')) {
//                return seriesUrl + url
//            }
//            return "$seriesUrl/$url"
//        }
//    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h1.grid-title > a")?.ownText()?.trim() ?: return null
        val href = getProperLink(this.selectFirst("a")!!.attr("href"), title)
        val posterUrl = fixUrlNull(this.selectFirst(".grid-poster > a > img")?.attr("src"))
        val type =
            if (this.selectFirst("div.last-episode") == null) TvType.Movie else TvType.TvSeries
        return if (type == TvType.TvSeries) {
            val episode = this.selectFirst("div.last-episode span")?.text()?.filter { it.isDigit() }
                ?.toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
            }
        } else {
            val quality = this.select("div.quality").text().trim()
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
            }
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val req = app.get("$mainUrl/?s=$query")
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        return document.select("div.search-item").map {
            val title = it.selectFirst("h2 > a")!!.text().trim()
            val type = it.selectFirst("p.cat-links a")?.attr("href").toString()
            val href = getProperLink(it.selectFirst("a")!!.attr("href"), type)
            val posterUrl = fixUrl(it.selectFirst("img.img-thumbnail")?.attr("src").toString())
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("li.last > span[itemprop=name]")?.text()?.trim().toString()
        val poster = fixUrl(document.select("img.img-thumbnail").attr("src").toString())
        val tags = document.select("div.content > div:nth-child(5) > h3 > a").map { it.text() }

        val year = Regex("\\d, (\\d+)").find(
            document.select("div.content > div:nth-child(7) > h3").text().trim()
        )?.groupValues?.get(1).toString().toIntOrNull()
        val tvType = if (document.select("div.serial-wrapper")
                .isNotEmpty()
        ) TvType.TvSeries else TvType.Movie
        val description = document.select("div.content > blockquote").text().trim()
        val trailer = document.selectFirst("div.action-player li > a.fancybox")?.attr("href")
        val rating =
            document.selectFirst("div.content > div:nth-child(6) > h3")?.text()?.toRatingInt()
        val actors =
            document.select("div.col-xs-9.content > div:nth-child(3) > h3 > a").map { it.text() }

        val recommendations = document.select("div.row.item-media").map {
            val recName = it.selectFirst("h3")?.text()?.trim().toString()
            val recHref = it.selectFirst(".content-media > a")!!.attr("href")
            val recPosterUrl =
                fixUrl(it.selectFirst(".poster-media > a > img")?.attr("src").toString())
            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
            }
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("div.episode-list > a:matches(\\d+)").map {
                val href = fixUrl(it.attr("href"))
                val episode = it.text().toIntOrNull()
                val season =
                    it.attr("href").substringAfter("season-").substringBefore("-").toIntOrNull()
                Episode(
                    href,
                    "Episode $episode",
                    season,
                    episode,
                )
            }.reversed()
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

//        maybe will need this in future
//        val sources = if (data.contains("-episode-")) {
//            document.select("script").mapNotNull { script ->
//                if (script.data().contains("var data =")) {
//                    val scriptData =
//                        script.toString().substringAfter("var data = '").substringBefore("';")
//                    Jsoup.parse(scriptData).select("li").map {
//                        fixUrl(it.select("a").attr("href"))
//                    }
//                } else {
//                    null
//                }
//            }[0]
//        } else {
//            document.select("ul#loadProviders > li").map {
//                fixUrl(it.select("a").attr("href"))
//            }
//        }

        document.select("ul#loadProviders > li").map {
            fixUrl(it.select("a").attr("href"))
        }.apmap {
            val link = when {
                it.startsWith("https://layarkacaxxi.icu") -> {
                    it.substringBeforeLast("/")
                }
                it.startsWith("https://bananalicious.xyz") -> decode(it.substringAfter("url="))
                else -> {
                    it
                }
            }
            if(link.startsWith(filemoon) || link.startsWith(streamhide)) {
                invokeBackup(link, callback)
            } else {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun invokeBackup(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url).document
        response.select("script[type=text/javascript]").map { script ->
            if (script.data().contains(Regex("eval\\(function\\(p,a,c,k,e,[rd]"))) {
                val unpackedscript = getAndUnpack(script.data())
                val m3u8Regex = Regex("file.\"(.*?m3u8.*?)\"")
                val m3u8 = m3u8Regex.find(unpackedscript)?.destructured?.component1() ?: ""
                if (m3u8.isNotEmpty()) {
                    M3u8Helper.generateM3u8(
                        fixTitle(URI(url).host).substringBefore("."),
                        m3u8,
                        mainUrl
                    ).forEach(callback)
                }
            }
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }
    private fun decode(input: String): String = URLDecoder.decode(input, "utf-8").replace(" ", "%20")

}
