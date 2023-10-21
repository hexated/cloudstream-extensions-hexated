package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import android.util.Log

class FullHDFilmizlesene : MainAPI() {
    override var mainUrl            = "https://www.fullhdfilmizlesene.pw"
    override var name               = "FullHDFilmizlesene"
    override val hasMainPage        = true
    override var lang               = "tr"
    override val hasQuickSearch     = true
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Movie)

    override val mainPage           =
        mainPageOf(
            "$mainUrl/en-cok-izlenen-filmler-izle-hd/" to "En Çok izlenen Filmler",
            "$mainUrl/filmizle/imdb-puani-yuksek-filmler-izle-1/" to "IMDB Puanı Yüksek Filmler",
            "$mainUrl/filmizle/bilim-kurgu-filmleri-izle-1/" to "Bilim Kurgu Filmleri",
            "$mainUrl/filmizle/komedi-filmleri-izle-2/" to "Komedi Filmleri",
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home     = document.select("li.film").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("span.film-title")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/arama/$query").document
        return document.select("li.film").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title           = document.selectFirst("div[class=izle-titles]")?.text()?.trim() ?: return null
        val poster          = fixUrlNull(document.selectFirst("div img")?.attr("data-src"))
        val year            = document.selectFirst("div.dd a.category")?.text()?.split(" ")?.get(0)?.trim()?.toIntOrNull()
        val description     = document.selectFirst("div.ozet-ic > p")?.text()?.trim()
        val tags            = document.select("a[rel='category tag']").map { it.text() }
        val rating          = document.selectFirst("div.puanx-puan")?.text()?.trim()?.split(".")?.get(0)?.toRatingInt()
        val duration        = document.selectFirst("span.sure")?.text()?.split(" ")?.get(0)?.trim()?.toRatingInt()
        val recommendations = document.select("div.izle-alt-content:nth-of-type(3) ul li").mapNotNull {
            val recName      = it.selectFirst("h2.film-title")?.text() ?: return@mapNotNull null
            val recHref      = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("src"))
            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
            }
        }
        val actors = document.select("div.film-info ul li:nth-child(2) a > span").map {
            Actor(it.text())
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.rating          = rating
            this.duration        = duration
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
        ): Boolean {

            val document = app.get(data).document
            val iframe   = document.selectFirst("div#plx iframe")?.attr("src") ?: return false

            val rapid          = app.get(iframe, referer = "$mainUrl/").text
            val pattern        = """file": "(.*)",""".toRegex()
            val matchResult    = pattern.find(rapid)
            val extractedValue = matchResult?.groups?.get(1)?.value ?: return false

            // val encoded = extractedValue.toByteArray(Charsets.UTF_8)
            // val decoded = String(encoded, Charsets.UTF_8)

            val bytes   = extractedValue.split("""\\x""").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
            val decoded = String(bytes, Charsets.UTF_8)

            loadExtractor(decoded, "$mainUrl/", subtitleCallback, callback)

            return true
    }
}
