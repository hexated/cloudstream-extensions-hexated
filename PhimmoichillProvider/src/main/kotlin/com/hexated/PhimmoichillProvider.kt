package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

class PhimmoichillProvider : MainAPI() {
    override var mainUrl = "https://phimmoichillk.net"
    private var directUrl = mainUrl
    override var name = "Phimmoichill"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/genre/phim-chieu-rap/page-" to "Phim Chiếu Rạp",
        "$mainUrl/list/phim-le/page-" to "Phim Lẻ",
        "$mainUrl/list/phim-bo/page-" to "Phim Bộ",
        "$mainUrl/genre/phim-hoat-hinh/page-" to "Phim Hoạt Hình",
        "$mainUrl/genre/phim-anime/page-" to "Phim Anime",
        "$mainUrl/country/phim-han-quoc/page-" to "Phim Hàn Quốc",
        "$mainUrl/country/phim-trung-quoc/page-" to "Phim Trung Quốc",
        "$mainUrl/country/phim-thai-lan/page-" to "Phim Thái Lan",
        "$mainUrl/genre/phim-sap-chieu/page-" to "Phim Sắp Chiếu",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("li.item").mapNotNull {
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

    private fun decode(input: String): String? = URLDecoder.decode(input, "utf-8")

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst("p,h3")?.text()?.trim().toString()
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val posterUrl = decode(this.selectFirst("img")!!.attr("src").substringAfter("url="))
        val temp = this.select("span.label").text()
        return if (temp.contains(Regex("\\d"))) {
            val episode = Regex("(\\((\\d+))|(\\s(\\d+))").find(temp)?.groupValues?.map { num ->
                num.replace(Regex("\\(|\\s"), "")
            }?.distinct()?.firstOrNull()?.toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
            }
        } else if (temp.contains(Regex("Trailer"))) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            val quality =
                temp.replace(Regex("(-.*)|(\\|.*)|(?i)(VietSub.*)|(?i)(Thuyết.*)"), "").trim()
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/tim-kiem/$query"
        val document = app.get(link).document

        return document.select("ul.list-film li").map {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url)
        directUrl = getBaseUrl(request.url)
        val document = request.document

        val title = document.selectFirst("h1[itemprop=name]")?.text()?.trim().toString()
        val link = document.select("ul.list-button li:last-child a").attr("href")
        val poster = document.selectFirst("div.image img[itemprop=image]")?.attr("src")
        val tags = document.select("ul.entry-meta.block-film li:nth-child(4) a")
            .map { it.text().substringAfter("Phim") }
        val year = document.select("ul.entry-meta.block-film li:nth-child(2) a").text().trim()
            .toIntOrNull()
        val tvType = if (document.select("div.latest-episode").isNotEmpty()
        ) TvType.TvSeries else TvType.Movie
        val description =
            document.select("div#film-content").text().substringAfter("Full HD Vietsub Thuyết Minh")
                .substringBefore("@phimmoi").trim()
        val trailer = document.select("body script")
            .find { it.data().contains("youtube.com") }?.data()?.substringAfterLast("file: \"")
            ?.substringBefore("\",")
        val rating =
            document.select("ul.entry-meta.block-film li:nth-child(7) span").text().toRatingInt()
        val actors = document.select("ul.entry-meta.block-film li:last-child a").map { it.text() }
        val recommendations = document.select("ul#list-film-realted li.item").map {
            it.toSearchResult().apply {
                this.posterUrl =
                    decode(it.selectFirst("img")!!.attr("data-src").substringAfter("url="))
            }
        }

        return if (tvType == TvType.TvSeries) {
            val docEpisodes = app.get(link).document
            val episodes = docEpisodes.select("ul#list_episodes > li").map {
                val href = it.select("a").attr("href")
                val episode =
                    it.select("a").text().replace(Regex("[^0-9]"), "").trim().toIntOrNull()
                val name = "Episode $episode"
                Episode(
                    data = href,
                    name = name,
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
            newMovieLoadResponse(title, url, TvType.Movie, link) {
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

        val key = document.select("div#content script")
            .find { it.data().contains("filmInfo.episodeID =") }?.data()?.let { script ->
                val id = script.substringAfter("parseInt('").substringBefore("'")
                app.post(
                    url = "$directUrl/chillsplayer.php",
                    data = mapOf("qcao" to id),
                    referer = data,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                    )
                ).text.substringAfterLast("iniPlayers(\"")
                    .substringBefore("\"")
            }

        listOf(
            Pair("https://sotrim.topphimmoi.org/raw/$key/index.m3u8", "PMFAST"),
            Pair("https://dash.megacdn.xyz/raw/$key/index.m3u8", "PMHLS"),
            Pair("https://so-trym.phimchill.net/dash/$key/index.m3u8", "PMPRO"),
            Pair("https://dash.megacdn.xyz/dast/$key/index.m3u8", "PMBK")
        ).map { (link, source) ->
            callback.invoke(
                ExtractorLink(
                    source,
                    source,
                    link,
                    referer = "$directUrl/",
                    quality = Qualities.P1080.value,
                    INFER_TYPE,
                )
            )
        }
        return true
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

}
