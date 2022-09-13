package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Kissasian : MainAPI() {
    override var mainUrl = "https://kissasian.pe"
    override var name = "Kissasian"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama)

    companion object {
        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "drama-list/ongoing.html?page=" to "Drama Ongoing",
        "drama-list/completed.html?page=" to "Drama Completed",
        "genre/variety/?page=" to "Variety Show",
        "genre/romance/?page=" to "Romance",
        "genre/action/?page=" to "Action",
        "genre/mystery/?page=" to "Mystery",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}$page").document
        val home = document.select("div.list-drama div.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val title = this.selectFirst("span.title")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.pic img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search.html?keyword=$query").document
        return document.select("div.list-drama div.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.barContentInfo a")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.barContentInfo img")?.attr("src"))
        val tags = document.select("div.barContentInfo p:contains(Genres:) a").map { it.text().removePrefix(",").trim() }

        val year = document.selectFirst("div.barContentInfo p.type.Releasea")?.text()?.trim()?.toIntOrNull()
        val status = getStatus(document.selectFirst("div.barContentInfo p:contains(Status:)")?.ownText()?.trim())
        val description = document.selectFirst("div.barContentInfo p.des")?.nextElementSiblings()?.select("p")?.text()

        val episodes = document.select("ul.listing li").map {
            val name = it.selectFirst("a")?.attr("title")
            val link = fixUrlNull(it.selectFirst("a")?.attr("href"))
            val epNum = Regex("Episode\\s([0-9]+)").find("$name")?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(link) {
                this.name = name
                this.episode = epNum
            }
        }.reversed()

        if (episodes.size == 1) {
            return newMovieLoadResponse(title, url, TvType.Movie, episodes[0].data) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
            }
        } else {
            return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes = episodes) {
                posterUrl = poster
                this.year = year
                showStatus = status
                plot = description
                this.tags = tags
            }
        }
    }

    private suspend fun invokeDembedSource(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url).document
        document.select("ul.list-server-items li").map {
            val iframe = it.attr("data-video").substringBefore("=http")
            loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        document.select("select#selectServer option").apmap {
            safeApiCall {
                val iframe = fixUrl(it.attr("value"))

                when {
                    iframe.startsWith("https://dembed2.com") -> invokeDembedSource(
                        iframe,
                        subtitleCallback,
                        callback
                    )
                    else -> loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }

        return true
    }

}
