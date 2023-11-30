package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.XStreamCdn
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

open class DramaidProvider : MainAPI() {
    override var mainUrl = "https://dramaid.skin"
    override var name = "DramaId"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.AsianDrama)

    companion object {
        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

        fun getType(t: String?): TvType {
            return when {
                t?.contains("Movie", true) == true -> TvType.Movie
                t?.contains("Anime", true) == true -> TvType.Anime
                else -> TvType.AsianDrama
            }
        }
    }

    override val mainPage = mainPageOf(
        "&status=&type=&order=update" to "Drama Terbaru",
        "&order=latest" to "Baru Ditambahkan",
        "&status=&type=&order=popular" to "Drama Popular",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/series/?page=$page${request.data}").document
        val home = document.select("article[itemscope=itemscope]").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperDramaLink(uri: String): String {
        return if (uri.contains("-episode-")) {
            "$mainUrl/series/" + Regex("$mainUrl/(.+)-ep.+").find(uri)?.groupValues?.get(1)
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = getProperDramaLink(this.selectFirst("a.tip")!!.attr("href"))
        val title = this.selectFirst("h2[itemprop=headline]")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(this.select("img:last-child").attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article[itemscope=itemscope]").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = fixUrlNull(document.select("div.thumb img:last-child").attr("src"))
        val tags = document.select(".genxed > a").map { it.text() }
        val type = document.selectFirst(".info-content .spe span:contains(Tipe:)")?.ownText()
        val year = Regex("\\d, ([0-9]*)").find(
            document.selectFirst(".info-content > .spe > span > time")!!.text().trim()
        )?.groupValues?.get(1).toString().toIntOrNull()
        val status = getStatus(
            document.select(".info-content > .spe > span:nth-child(1)")
                .text().trim().replace("Status: ", "")
        )
        val description = document.select(".entry-content > p").text().trim()

        val episodes = document.select(".eplister > ul > li").mapNotNull {
            val name = it.selectFirst("a > .epl-title")?.text()
            val link = fixUrl(it.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            Episode(
                link,
                name,
            )
        }.reversed()

        val recommendations =
            document.select(".listupd > article[itemscope=itemscope]").mapNotNull { rec ->
                rec.toSearchResult()
            }

        return newTvSeriesLoadResponse(
            title,
            url,
            getType(type),
            episodes = episodes
        ) {
            posterUrl = poster
            this.year = year
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
        }

    }

    private data class Sources(
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("default") val default: Boolean?
    )

    private data class Tracks(
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String,
        @JsonProperty("kind") val type: String,
        @JsonProperty("default") val default: Boolean?
    )

    private suspend fun invokeDriveSource(
        url: String,
        name: String,
        subCallback: (SubtitleFile) -> Unit,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        val server = app.get(url).document.selectFirst(".picasa")?.nextElementSibling()?.data()

        val source = "[${server!!.substringAfter("sources: [").substringBefore("],")}]".trimIndent()
        val trackers = server.substringAfter("tracks:[").substringBefore("],")
            .replace("//language", "")
            .replace("file", "\"file\"")
            .replace("label", "\"label\"")
            .replace("kind", "\"kind\"").trimIndent()

        tryParseJson<List<Sources>>(source)?.map {
            sourceCallback(
                ExtractorLink(
                    name,
                    "Drive",
                    fixUrl(it.file),
                    referer = "https://motonews.club/",
                    quality = getQualityFromName(it.label)
                )
            )
        }

        tryParseJson<Tracks>(trackers)?.let {
            subCallback.invoke(
                SubtitleFile(
                    if (it.label.contains("Indonesia")) "${it.label}n" else it.label,
                    it.file
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
        val document = app.get(data).document
        val sources = document.select(".mobius > .mirror > option").mapNotNull {
            fixUrl(Jsoup.parse(base64Decode(it.attr("value"))).select("iframe").attr("src"))
        }

        sources.map {
            it.replace("https://ndrama.xyz", "https://www.fembed.com")
        }.apmap {
            when {
                it.contains("motonews") -> invokeDriveSource(
                    it,
                    this.name,
                    subtitleCallback,
                    callback
                )

                else -> loadExtractor(it, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return true
    }

}

