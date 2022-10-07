package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

class Dizikorea : MainAPI() {
    override var mainUrl = "https://dizikorea.com"
    override var name = "Dizikorea"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler/page/" to "Kore Dizileri",
        "$mainUrl/kore-filmleri-izle/page/" to "Son Eklenen Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("ul li.segment-poster").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val title = this.selectFirst("h2.truncate")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        return app.post("$mainUrl/ajax.php?qr=$query")
            .parsedSafe<Search>()?.data?.result?.mapNotNull { item ->
                newTvSeriesSearchResponse(
                    item.s_name ?: return@mapNotNull null,
                    item.s_link ?: return@mapNotNull null,
                    TvType.AsianDrama
                ) {
                    this.posterUrl = fixUrlNull(item.s_image)
                }
            }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.page-title")?.ownText()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("a.ui.image img")?.attr("data-src"))
        val tags = document.select("div.nano-content div:contains(Tür:) a").map { it.text() }

        val year = document.selectFirst("table.ui.unstackable tr td:contains(Yapım Yılı) a")?.text()
            ?.trim()
            ?.toIntOrNull()
        val description = document.selectFirst("p#tv-series-desc")?.text()?.trim()
        val rating =
            document.selectFirst("table.ui.unstackable tr td:contains(IMDb Puanı) .color-imdb")
                ?.text()?.trim()
                .toRatingInt()
        val actors = document.select("div.global-box div.item").map {
            Actor(
                it.select("h5.truncate").text().trim(),
                fixUrlNull(it.selectFirst("img")?.attr("src"))
            )
        }
        val type = if (document.select("div.all-seriespart")
                .isNullOrEmpty()
        ) TvType.Movie else TvType.TvSeries
        val trailer = document.selectFirst("a.prettyPhoto")?.attr("href")

        return when (type) {
            TvType.Movie -> {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = description
                    this.tags = tags
                    this.rating = rating
                    addActors(actors)
                    addTrailer(trailer)
                }
            }
            else -> {
                val episodes = document.select("div.all-seriespart div.el-item").map { ep ->
                    Episode(
                        fixUrl(ep.selectFirst("a")!!.attr("href")),
                        episode = ep.attr("data-epnumber").toIntOrNull(),
                        season = ep.selectFirst("span.season-name")?.text()?.filter { it.isDigit() }
                            ?.toIntOrNull()
                    )
                }
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = description
                    this.tags = tags
                    this.rating = rating
                    addActors(actors)
                    addTrailer(trailer)
                }
            }
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private suspend fun invokeLokalSource(
        url: String,
        source: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val script = app.get(url, referer = "$mainUrl/").document.select("script")
            .find { it.data().contains("sources:") }?.data()?.substringAfter("sources: [")
            ?.substringBefore("],")?.replace(Regex("\"?file\"?"), "\"file\"")

        AppUtils.tryParseJson<Source>(script)?.file?.let { link ->
            if (link.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    source,
                    fixUrl(link),
                    getBaseUrl(url)
                ).forEach(callback)
            } else {
                callback.invoke(
                    ExtractorLink(
                        source,
                        source,
                        fixUrl(link),
                        "$mainUrl/",
                        Qualities.Unknown.value,
                    )
                )
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
        val sources: MutableList<String> = mutableListOf()

        val mainServer = document.select("div#singlePlay iframe").attr("src")
        sources.add(mainServer)

        document.select("ul.linkler li").apmap {
            val server =
                app.get(it.select("a").attr("href")).document.select("div#singlePlay iframe")
                    .attr("src")
            if (sources.isNotEmpty()) sources.add(fixUrl(server))
        }

        sources.distinct().apmap { link ->
            when {
                link.startsWith("https://playerkorea") -> invokeLokalSource(link, this.name, callback)
                link.startsWith("https://vidmoly") -> invokeLokalSource(link, "Vidmoly", callback)
                else -> loadExtractor(link, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return true
    }

    private data class SearchItem(
        @JsonProperty("s_link") val s_link: String? = null,
        @JsonProperty("s_image") val s_image: String? = null,
        @JsonProperty("s_name") val s_name: String? = null,
    )

    private data class Result(
        @JsonProperty("result") val result: ArrayList<SearchItem> = arrayListOf(),
    )

    private data class Search(
        @JsonProperty("data") val data: Result? = null,
    )

    private data class Source(
        @JsonProperty("file") val file: String? = null,
    )

}