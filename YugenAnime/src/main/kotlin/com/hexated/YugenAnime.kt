package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

class YugenAnime : MainAPI() {
    override var mainUrl = "https://yugen.to"
    override var name = "YugenAnime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special", true)) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Finished Airing" -> ShowStatus.Completed
                "Currently Airing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/trending/?page=" to "Trending",
        "$mainUrl/latest/?page=" to "Recently Released",
        "$mainUrl/best/?page=" to "Most Popular Series",
        "$mainUrl/new/?page=" to "New to YugenAnime",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        val document = app.get(request.data + page).document
        val home = document.select("div.cards-grid a, ul.ep-grid li.ep-card").mapNotNull {
            it.toSearchResult()
        }
        items.add(HomePageList(request.name, home))
        return newHomePageResponse(items)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.attr("title").ifBlank { this.select("div.ep-origin-name").text() }
            .ifBlank { this.select("span.anime-name").text() } ?: return null
        val href = fixUrl(this.attr("href").ifBlank { this.select("a.ep-details").attr("href") })
        val posterUrl = fixUrlNull(this.selectFirst("img.lozad")?.attr("data-src"))
        val epNum =
            this.select("a.ep-thumbnail").attr("title").substringBefore(":").filter { it.isDigit() }
                .toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(dubExist = true, subExist = true, dubEpisodes = epNum, subEpisodes = epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/?q=$query").document
        return document.select("div.cards-grid a.anime-meta").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.content h1")?.text() ?: return null
        val poster = document.selectFirst("img.cover")?.attr("src")
        val tags = document.getPageContent("Genres").split(",").map { it.trim() }
        val type = getType(document.getPageContent("Format"))
        val year = document.getPageContent("Premiered").filter { it.isDigit() }.toIntOrNull()
        val status = getStatus(document.getPageContent("Status"))
        val description = document.select("p.description").text()

        val malId = document.getExternalId("MyAnimeList")
        val anilistId = document.getExternalId("AniList")

        val trailer = document.selectFirst("iframe.lozad.video")?.attr("src")

        val episodes = app.get("${url}watch").document.select("ul.ep-grid li.ep-card").map { eps ->
            val epsTitle = eps.select("a.ep-title").text()
            val link = fixUrl(eps.select("a.ep-title").attr("href"))
            val episode = epsTitle.substringBefore(":").filter { it.isDigit() }.toIntOrNull()
            Episode(link, name = epsTitle.substringAfter(":").trim(), episode = episode)
        }

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
            addMalId(malId)
            addAniListId(anilistId)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val episode = data.removeSuffix("/").split("/").last()
        val dubData = data.substringBeforeLast("/$episode").let { "$it-dub/$episode" }

        listOf(data, dubData).apmap { url ->
            val doc = app.get(url).document
            val iframe = doc.select("iframe#main-embed").attr("src") ?: return@apmap null
            val id = iframe.removeSuffix("/").split("/").lastOrNull() ?: return@apmap null
            val source = app.post(
                "$mainUrl/api/embed/", data = mapOf(
                    "id" to id,
                    "ac" to "0"
                ), referer = iframe,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<Sources>()?.hls?.distinct()?.firstOrNull() ?: return@apmap null

            val isDub = if (url.contains("-dub")) "dub" else "sub"

            M3u8Helper.generateM3u8(
                "${getSourceType(getBaseUrl(source))} [$isDub]",
                source,
                ""
            ).forEach(callback)
        }

        return true
    }

    private fun Document.getExternalId(str: String): Int? {
        return this.select("div.anime-metadetails > div:contains(External Links) a:contains($str)")
            .attr("href").removeSuffix("/").split("/").lastOrNull()?.toIntOrNull()
    }

    private fun Document.getPageContent(str: String): String {
        return this.select("div.anime-metadetails > div:contains($str) span.description").text()
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private fun getSourceType(url: String): String {
        return when {
            url.contains("vrv", true) -> "Vrv"
            url.contains("gofcdn", true) -> "Gofcdn"
            url.contains("cache", true) -> "Cache"
            else -> this.name
        }
    }

    data class Sources(
        @JsonProperty("hls") val hls: List<String>? = null,
    )

}