package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Aniworld : MainAPI() {
    override var mainUrl = "https://aniworld.to"
    override var name = "Aniworld"
    override val hasMainPage = true
    override var lang = "de"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("Anime Ova") -> TvType.OVA
                t.contains("Anime Movie") -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String): ShowStatus {
            return when {
                t.contains("/complete", true) -> ShowStatus.Completed
                t.contains("/running", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(mainUrl).document
        val item = arrayListOf<HomePageList>()
        document.select("div.carousel").map { ele ->
            val header = ele.selectFirst("h2")?.text() ?: return@map
            val home = ele.select("div.coverListItem").mapNotNull {
                it.toSearchResult()
            }
            if (home.isNotEmpty()) item.add(HomePageList(header, home))
        }
        return HomePageResponse(item)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = app.post(
            "$mainUrl/ajax/search",
            data = mapOf("keyword" to query),
            referer = "$mainUrl/search",
            headers = mapOf(
                "x-requested-with" to "XMLHttpRequest"
            )
        )
        return tryParseJson<List<AnimeSearch>>(json.text)?.filter {
            !it.link.contains("episode-") && it.link.contains(
                "/stream"
            )
        }?.map {
            newAnimeSearchResponse(
                it.title?.replace(Regex("</?em>"), "") ?: "",
                fixUrl(it.link),
                TvType.Anime
            ) {
            }
        } ?: throw ErrorLoadingException()

    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.series-title span")?.text() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.seriesCoverBox img")?.attr("data-src"))
        val tags = document.select("div.genres li a").map { it.text() }
        val year = document.selectFirst("span[itemprop=startDate] a")?.text()?.toIntOrNull()
        val description = document.select("p.seri_des").text()
        val actor =
            document.select("li:contains(Schauspieler:) ul li a").map { it.select("span").text() }

        val episodes = document.select("div#stream > ul:nth-child(4) li").mapNotNull { eps ->
            Episode(
                fixUrl(eps.selectFirst("a")?.attr("href") ?: return@mapNotNull null),
                episode = eps.selectFirst("a")?.text()?.toIntOrNull()
            )
        }

        return newAnimeLoadResponse(
            title,
            url,
            TvType.Anime
        ) {
            engName = title
            posterUrl = poster
            this.year = year
            addEpisodes(
                DubStatus.Subbed,
                episodes
            )
            addActors(actor)
            plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("div.hosterSiteVideo ul li").map {
            Triple(
                it.attr("data-lang-key"),
                it.attr("data-link-target"),
                it.select("h4").text()
            )
        }.filter {
            it.third == "VOE"
        }.apmap {
            val redirectUrl = app.get(fixUrl(it.second)).url
            loadExtractor(redirectUrl, data, subtitleCallback) { link ->
                val name = "${link.name} [${it.first.getLanguage()}]"
                callback.invoke(
                    ExtractorLink(
                        name,
                        name,
                        link.url,
                        link.referer,
                        link.quality,
                        link.isM3u8,
                        link.headers,
                        link.extractorData
                    )
                )
            }
        }

        return true
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val title = this.selectFirst("h3")?.text() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private fun String.getLanguage() : String {
        return when(this) {
            "1" -> "Deutsch"
            "2" -> "Untertitel Deutsch"
            "3" -> "Untertitel Englisch"
            else -> {
                ""
            }
        }
    }

    private data class AnimeSearch(
        @JsonProperty("link") val link: String,
        @JsonProperty("title") val title: String? = null,
    )

}

class Urochsunloath : Voe() {
    override var mainUrl = "https://urochsunloath.com"
}