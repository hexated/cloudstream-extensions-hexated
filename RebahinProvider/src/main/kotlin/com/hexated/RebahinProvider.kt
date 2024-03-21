package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.net.URI

open class RebahinProvider : MainAPI() {
    override var mainUrl = "http://rebahin.skin/"
    private var directUrl: String? = null
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    open var mainServer = "http://rebahin.skin/"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urls = listOf(
            Pair("Featured", "xtab1"),
            Pair("Film Terbaru", "xtab2"),
            Pair("Romance", "xtab3"),
            Pair("Drama", "xtab4"),
            Pair("Action", "xtab5"),
            Pair("Scifi", "xtab6"),
            Pair("Tv Series Terbaru", "stab1"),
            Pair("Anime Series", "stab2"),
            Pair("Drakor Series", "stab3"),
            Pair("West Series", "stab4"),
            Pair("China Series", "stab5"),
            Pair("Japan Series", "stab6"),
        )

        val items = ArrayList<HomePageList>()

        for ((header, tab) in urls) {
            try {
                val home =
                    app.get("$mainUrl/wp-content/themes/indoxxi/ajax-top-$tab.php").document.select(
                        "div.ml-item"
                    ).mapNotNull {
                        it.toSearchResult()
                    }
                items.add(HomePageList(header, home))
            } catch (e: Exception) {
                logError(e)
            }
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("span.mli-info > h2")?.text() ?: return null
        val href = this.selectFirst("a")!!.attr("href")
        val type =
            if (this.select("span.mli-quality").isNotEmpty()) TvType.Movie else TvType.TvSeries
        return if (type == TvType.Movie) {
            val posterUrl = fixUrlNull(this.select("img").attr("src"))
            val quality = getQualityFromString(this.select("span.mli-quality").text().trim())
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            val posterUrl =
                fixUrlNull(
                    this.select("img").attr("src")
                        .ifEmpty { this.select("img").attr("data-original") })
            val episode =
                this.select("div.mli-eps > span").text().replace(Regex("[^0-9]"), "").toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = app.get(link).document

        return document.select("div.ml-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val req = app.get(url)
        directUrl = getBaseUrl(req.url)
        val document = req.document
        val title = document.selectFirst("h3[itemprop=name]")!!.ownText().trim()
        val poster = document.select(".mvic-desc > div.thumb.mvic-thumb").attr("style")
            .substringAfter("url(").substringBeforeLast(")")
        val tags = document.select("span[itemprop=genre]").map { it.text() }

        val year = Regex("([0-9]{4}?)-").find(
            document.selectFirst(".mvici-right > p:nth-child(3)")!!.ownText().trim()
        )?.groupValues?.get(1).toString().toIntOrNull()
        val tvType = if (url.contains("/series/")) TvType.TvSeries else TvType.Movie
        val description = document.select("span[itemprop=reviewBody] > p").text().trim()
        val trailer = fixUrlNull(document.selectFirst("div.modal-body-trailer iframe")?.attr("src"))
        val rating = document.selectFirst("span[itemprop=ratingValue]")?.text()?.toRatingInt()
        val duration = document.selectFirst(".mvici-right > p:nth-child(1)")!!
            .ownText().replace(Regex("[^0-9]"), "").toIntOrNull()
        val actors = document.select("span[itemprop=actor] > a").map { it.select("span").text() }

        val baseLink = fixUrl(document.select("div#mv-info > a").attr("href").toString())

        return if (tvType == TvType.TvSeries) {
            val episodes = app.get(baseLink).document.select("div#list-eps > a").map {
                Pair(it.text(), it.attr("data-iframe"))
            }.groupBy { it.first }.map { eps ->
                Episode(
                    data = eps.value.map { fixUrl(base64Decode(it.second)) }.toString(),
                    name = eps.key,
                    episode = eps.key.filter { it.isDigit() }.toIntOrNull()
                )

            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val links =
                app.get(baseLink).document.select("div#server-list div.server-wrapper div[id*=episode]")
                    .map {
                        fixUrl(base64Decode(it.attr("data-iframe")))
                    }.toString()
            newMovieLoadResponse(title, url, TvType.Movie, links) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                addActors(actors)
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

        data.removeSurrounding("[", "]").split(",").map { it.trim() }.apmap { link ->
            safeApiCall {
                when {
                    link.startsWith(mainServer) -> invokeLokalSource(
                        link,
                        subtitleCallback,
                        callback
                    )
                    else -> {
                        loadExtractor(link, "$directUrl/", subtitleCallback, callback)
                    }
                }
            }
        }

        return true
    }

    private suspend fun invokeLokalSource(
        url: String,
        subCallback: (SubtitleFile) -> Unit,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(
            url,
            allowRedirects = false,
            referer = directUrl,
            headers = mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        ).document

        document.select("script").find { it.data().contains("config =") }?.data()?.let { script ->
            Regex("\"file\":\\s?\"(.+.m3u8)\"").find(script)?.groupValues?.getOrNull(1)
                ?.let { link ->
                    M3u8Helper.generateM3u8(
                        name,
                        link,
                        referer = "$mainServer/",
                        headers = mapOf("Accept" to "*/*", "Origin" to mainServer)
                    ).forEach(sourceCallback)
                }

            val subData =
                Regex("\"?tracks\"?:\\s\\n?\\[(.*)],").find(script)?.groupValues?.getOrNull(1)
                    ?: Regex("\"?tracks\"?:\\s\\n?\\[\\s*(?s:(.+)],\\n\\s*\"sources)").find(script)?.groupValues?.getOrNull(
                        1
                    )
            tryParseJson<List<Tracks>>("[$subData]")?.map {
                subCallback.invoke(
                    SubtitleFile(
                        getLanguage(it.label ?: return@map null),
                        if (it.file?.contains(".srt") == true) it.file else return@map null
                    )
                )

            }
        }
    }

    private fun getLanguage(str: String): String {
        return when {
            str.contains("indonesia", true) || str.contains("bahasa", true) -> "Indonesian"
            else -> str
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private data class Tracks(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )

}

