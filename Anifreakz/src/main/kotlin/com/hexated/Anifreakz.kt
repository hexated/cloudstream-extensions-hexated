package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import org.jsoup.nodes.Element
import java.net.URLDecoder

class Anifreakz : MainAPI() {
    override var mainUrl = "https://anifreakz.com"
    override var name = "Anifreakz"
    override val hasMainPage = true
    override var lang = "de"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/series?filter=null&page=" to "Anime Neuste",
        "$mainUrl/series?filter={\"sorting\":\"popular\"}&page=" to "Anime Angesagt",
        "$mainUrl/series?filter={\"sorting\":\"released\"}&page=" to "Anime Veröffentlicht",
        "$mainUrl/kalender" to "Anime Kalender",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        val nonPaged = request.name == "Anime Kalender" && page <= 1

        if (!nonPaged) {
            val document = app.get(request.data + page).document
            val home = document.select("div.app-section div.col").mapNotNull {
                it.toSearchResult()
            }
            items.add(HomePageList(request.name, home))
        }

        if (nonPaged) {
            val document = app.get(request.data).document
            document.select("div.app-content div.app-section").forEach { block ->
                val header = block.selectFirst("div.app-heading div.text")?.ownText() ?: ""
                val home = block.select("div.col").mapNotNull {
                    it.toSearchResult()
                }
                items.add(HomePageList(header, home))
            }
        }

        return newHomePageResponse(items)

    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val title = this.selectFirst("a.list-title")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.media-cover")?.attr("data-src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/$query").document
        return document.select("div.app-section div.col").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun decode(input: String): String? = URLDecoder.decode(input, "utf-8")

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title =
            document.selectFirst("div.pl-md-4 h1, div.caption-content h1")?.text() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.media.media-cover")?.attr("data-src"))
        val tags = document.select("div.categories a").map { it.text() }
        val rating = document.selectFirst("div.featured-box div:contains(IMDB) div.text")?.text()
            .toRatingInt()
        val year =
            document.selectFirst("div.featured-box div:contains(Veröffentlicht) div.text")?.text()
                ?.trim()?.toIntOrNull()
        val type = if (document.select("div.episodes.tab-content")
                .isNullOrEmpty()
        ) TvType.Movie else TvType.TvSeries
        val description =
            document.select("div.detail-attr div.text-content, div.caption div:contains(Übersicht) div.text")
                .text().trim()
        val trailer = document.selectFirst("button:contains(Trailer)")?.attr("data-remote")
            ?.substringAfter("?trailer=")?.let {
                decode(it)
            }

        return if (type == TvType.Movie) {
            newMovieLoadResponse(title, url, type, url) {
                posterUrl = poster
                this.year = year
                this.rating = rating
                plot = description
                this.tags = tags
                addTrailer(trailer)
            }
        } else {
            val subEpisodes = mutableListOf<Episode>()
            document.select("div.episodes.tab-content div[id*=season-]").forEach { season ->
                val seasonNum = season.attr("id").substringAfterLast("-").toIntOrNull()
                season.select("a").map { eps ->
                    val href = eps.attr("href")
                    val name = eps.select("div.name").text()
                    val episode =
                        eps.select("div.episode").text().substringBefore(".").toIntOrNull()
                    val episodes = Episode(
                        href,
                        name,
                        seasonNum,
                        episode
                    )
                    subEpisodes.add(episodes)
                }
            }
            newAnimeLoadResponse(title, url, type) {
                posterUrl = poster
                this.year = year
                addEpisodes(DubStatus.Subbed, subEpisodes)
                this.rating = rating
                plot = description
                this.tags = tags
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

        val servers = mutableListOf<Pair<String, String>>()

        document.select("div[aria-labelledby=videoSource] button").map {
            servers.add(it.attr("data-embed") to it.text())
        }

        document.select("div.nav-player-select a").map {
            servers.add(it.attr("data-embed") to it.select("span").text())
        }

        servers.distinctBy { it.first }.apmap { (id, source) ->
            val iframe = app.post(
                "$mainUrl/ajax/embed",
                data = mapOf("id" to id, "captcha" to ""),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                referer = data
            ).document.select("iframe").attr("src")
            val doc = app.get(iframe, referer = "$mainUrl/").document
            val link =
                unpackJs(doc)?.substringAfter("file:\"")?.substringBefore("\"") ?: return@apmap null
            callback.invoke(
                ExtractorLink(
                    source,
                    source,
                    link,
                    "https://filemoon.sx/",
                    Qualities.Unknown.value,
                    isM3u8 = link.contains(".m3u8")
                )
            )
        }

        return true
    }

    private fun unpackJs(script: Element): String? {
        return script.select("script").find { it.data().contains("eval(function(p,a,c,k,e,d)") }
            ?.data()?.let { getAndUnpack(it) }
    }

}