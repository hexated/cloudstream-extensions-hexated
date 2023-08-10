package com.hexated

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class KissasianMx : Kissasian() {
    override var mainUrl = "https://kissasian.mx"
    override var name = "KissasianMx"
    override val contentInfoClass = "barContent"
    override val mainPage = mainPageOf(
        "Status/Ongoing?page=" to "Drama Ongoing",
        "Status/Completed?page=" to "Drama Completed",
        "Status/Completed?page=" to "Drama Completed",
        "Genre/Romance?page=" to "Drama Romance",
        "Genre/Reality-TV?page=" to "Reality-TV",
        "Genre/Mystery?page=" to "Drama Mystery",
        "Genre/Movie?page=" to "Movie",
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            "$mainUrl/Search/SearchSuggest", data = mapOf(
                "type" to "Drama",
                "keyword" to query,
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).document
        return document.select("a").mapNotNull {
            val href = fixUrl(it.attr("href"))
            val title = it.text()
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama)
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
            val server = it.attr("value")
            val iframe = app.get(fixUrl(server ?: return@apmap)).document.selectFirst("div#centerDivVideo iframe")?.attr("src")
            loadExtractor(iframe ?: return@apmap, "$mainUrl/", subtitleCallback, callback)
        }

        return true
    }
}