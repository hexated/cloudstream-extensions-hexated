package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

class Hdfilmcehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.de"
    override var name = "hdfilmcehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/tavsiye-filmler-izle2/page/" to "Tavsiye Filmler Kategorisi",
        "$mainUrl/yabancidiziizle-1/page/" to "Son Eklenen Yabancı Diziler",
        "$mainUrl/imdb-7-puan-uzeri-filmler/page/" to "Imdb 7+ Filmler",
        "$mainUrl/en-cok-yorumlananlar/page/" to "En Çok Yorumlananlar",
        "$mainUrl/en-cok-begenilen-filmleri-izle/page/" to "En Çok Beğenilenler",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.card-body div.row div.col-6.col-sm-3.poster-container")
            .mapNotNull {
                it.toSearchResult()
            }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.title")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }

    }

    private fun Media.toSearchResponse(): SearchResponse? {
        return newMovieSearchResponse(
            title ?: return null,
            "$mainUrl/$slugPrefix$slug",
            TvType.TvSeries,
        ) {
            this.posterUrl = "$mainUrl/uploads/poster/$poster"
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        return app.post(
            "$mainUrl/search/",
            data = mapOf("query" to query),
            referer = "$mainUrl/",
            headers = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).parsedSafe<Result>()?.result?.mapNotNull { media ->
            media.toSearchResponse()
        } ?: throw ErrorLoadingException("Invalid Json reponse")
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.card-header > h1, div.card-header > h2")?.text()
            ?.removeSuffix("Filminin Bilgileri")?.trim()
            ?: return null
        val poster = fixUrlNull(document.select("img.img-fluid").lastOrNull()?.attr("src"))
        val tags = document.select("div.mb-0.lh-lg div:nth-child(5) a").map { it.text() }
        val year =
            document.selectFirst("div.mb-0.lh-lg div:nth-child(4) a")?.text()?.trim()?.toIntOrNull()
        val tvType = if (document.select("nav#seasonsTabs").isNullOrEmpty()
        ) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.text-white > p")?.text()?.trim()
        val rating = document.selectFirst("div.rating-votes div.rate span")?.text()?.toRatingInt()
        val actors = document.select("div.mb-0.lh-lg div:last-child a.chip").map {
            Actor(it.text(), it.select("img").attr("src"))
        }
        val recommendations =
            document.select("div.swiper-wrapper div.poster.poster-pop").mapNotNull {
                val recName = it.selectFirst("h2.title")?.text() ?: return@mapNotNull null
                val recHref =
                    fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src"))
                newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                    this.posterUrl = recPosterUrl
                }
            }

        return if (tvType == TvType.TvSeries) {
            val trailer =
                document.selectFirst("button.btn.btn-fragman.btn-danger")?.attr("data-trailer")
                    ?.let {
                        "https://www.youtube.com/embed/$it"
                    }
            val episodes = document.select("div#seasonsTabs-tabContent div.card-list-item").map {
                val href = it.select("a").attr("href")
                val name = it.select("h3").text().trim()
                val episode = it.select("h3").text().let { num ->
                    Regex("Sezon\\s?([0-9]+).").find(num)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
                val season = it.parents()[1].attr("id").substringAfter("-").toIntOrNull()
                Episode(
                    href,
                    name,
                    season,
                    episode,
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
            val trailer =
                document.selectFirst("nav.nav.card-nav.nav-slider a[data-bs-toggle=\"modal\"]")
                    ?.attr("data-trailer")?.let {
                        "https://www.youtube.com/embed/$it"
                    }
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

    private suspend fun invokeLocalSource(
        source: String,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val script = app.get(
            url,
            referer = "${mainUrl}/"
        ).document.select("script")
            .find { it.data().contains("sources:") }?.data() ?: return
        val videoData = getAndUnpack(script).substringAfter("file_link=\"").substringBefore("\";")
        val subData = script.substringAfter("tracks: [").substringBefore("]")

        callback.invoke(
            ExtractorLink(
                source,
                source,
                base64Decode(videoData),
                "$mainUrl/",
                Qualities.Unknown.value,
                true
            )
        )

        tryParseJson<List<SubSource>>("[${subData}]")
            ?.filter { it.kind == "captions" }?.map {
                subtitleCallback.invoke(
                    SubtitleFile(
                        it.label.toString(),
                        fixUrl(it.file.toString())
                    )
                )
            }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("nav.nav.card-nav.nav-slider a.nav-link").map {
            Pair(it.attr("href"), it.text())
        }.apmap { (url, source) ->
            safeApiCall {
                app.get(url).document.select("div.card-video > iframe").attr("data-src")
                    .let { url ->
                        if (url.startsWith(mainUrl)) {
                            invokeLocalSource(source, url, subtitleCallback, callback)
                        } else {
                            loadExtractor(url, "$mainUrl/", subtitleCallback) { link ->
                                callback.invoke(
                                    ExtractorLink(
                                        source,
                                        source,
                                        link.url,
                                        link.referer,
                                        link.quality,
                                        link.type,
                                        link.headers,
                                        link.extractorData
                                    )
                                )
                            }
                        }
                    }
            }
        }
        return true
    }

    private data class SubSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null,
    )

    data class Result(
        @JsonProperty("result") val result: ArrayList<Media>? = arrayListOf(),
    )

    data class Media(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("slug_prefix") val slugPrefix: String? = null,
    )
}
