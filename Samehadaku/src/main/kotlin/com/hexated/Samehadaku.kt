package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.XStreamCdn
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Samehadaku : MainAPI() {
    override var mainUrl = "https://samehadaku.win"
    override var name = "Samehadaku"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private const val jikanAPI = "https://api.jikan.moe/v4"

        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special", true)) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Episode Terbaru",
        "$mainUrl/" to "HomePage",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = mutableListOf<HomePageList>()

        if (request.name != "Episode Terbaru" && page <= 1) {
            val doc = app.get(request.data).document
            doc.select("div.widget_senction").forEach { block ->
                val header = block.selectFirst("div.widget-title h3")?.ownText() ?: return@forEach
                val home = block.select("div.animepost").mapNotNull {
                    it.toSearchResult()
                }
                if (home.isNotEmpty()) items.add(HomePageList(header, home))
            }
        }

        if (request.name == "Episode Terbaru") {
            val home =
                app.get(request.data + page).document.selectFirst("div.post-show")?.select("ul li")
                    ?.mapNotNull {
                        it.toSearchResult()
                    } ?: throw ErrorLoadingException("No Media Found")
            items.add(HomePageList(request.name, home, true))
        }

        return newHomePageResponse(items)

    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("div.title, h2.entry-title a, div.lftinfo h2")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.select("img").attr("src"))
        val epNum = this.selectFirst("div.dtla author")?.text()?.toIntOrNull()
        return newAnimeSearchResponse(title, href ?: return null, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("main#main div.animepost").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixUrl = if (url.contains("/anime/")) {
            url
        } else {
            app.get(url).document.selectFirst("div.nvs.nvsc a")?.attr("href")
        }

        val document = app.get(fixUrl ?: return null).document
        val title = document.selectFirst("h1.entry-title")?.text()?.removeSurrounding("Nonton", "Subtitle Indonesia")?.trim() ?: return null
        val poster = document.selectFirst("div.thumb > img")?.attr("src")
        val tags = document.select("div.genre-info > a").map { it.text() }

        val year = Regex("\\d,\\s([0-9]*)").find(
            document.selectFirst("div.spe > span:contains(Released)")?.ownText() ?: return null
        )?.groupValues?.get(1)?.toIntOrNull()
        val status = getStatus(document.selectFirst("div.spe > span:contains(Status)")?.ownText() ?: return null)
        val type = document.selectFirst("div.spe > span:contains(Type)")?.ownText()?.trim()?.lowercase() ?: "tv"
        val rating = document.selectFirst("span.ratingValue")?.text()?.trim()?.toRatingInt()
        val description = document.select("div.desc p").text().trim()
        val trailer = document.selectFirst("div.trailer-anime iframe")?.attr("src")

        val malId = app.get("${jikanAPI}/anime?q=$title&start_date=${year}&type=$type&limit=1")
            .parsedSafe<JikanResponse>()?.data?.firstOrNull()?.mal_id
        val anilistId = app.post(
            "https://graphql.anilist.co/", data = mapOf(
                "query" to "{Media(idMal:$malId,type:ANIME){id}}",
            )
        ).parsedSafe<DataAni>()?.data?.media?.id

        val episodes = document.select("div.lstepsiode.listeps ul li").mapNotNull {
            val header = it.selectFirst("span.lchx > a") ?: return@mapNotNull null
            val episode = Regex("Episode\\s?([0-9]+)").find(header.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
            val link = fixUrl(header.attr("href"))
            Episode(link, episode = episode)
        }.reversed()

        val recommendations = document.select("aside#sidebar ul li").mapNotNull {
            it.toSearchResult()
        }

        return newAnimeLoadResponse(title, url, getType(type)) {
            engName = title
            posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            this.rating = rating
            plot = description
            addMalId(malId?.toIntOrNull())
            addAniListId(anilistId?.toIntOrNull())
            addTrailer(trailer)
            this.tags = tags
            this.recommendations = recommendations
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        val sources = ArrayList<String>()

        document.select("div#server ul li div").apmap {
            val dataPost = it.attr("data-post")
            val dataNume = it.attr("data-nume")
            val dataType = it.attr("data-type")

            val iframe = app.post(
                url = "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "player_ajax",
                    "post" to dataPost,
                    "nume" to dataNume,
                    "type" to dataType
                ),
                referer = data,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).document.select("iframe").attr("src")

            sources.add(fixUrl(iframe))
        }

        sources.apmap {
            loadExtractor(it, "$mainUrl/", subtitleCallback, callback)
        }
        return true
    }

    data class Data(
        @JsonProperty("mal_id") val mal_id: String? = null,
    )

    data class JikanResponse(
        @JsonProperty("data") val data: ArrayList<Data>? = arrayListOf(),
    )

    private data class IdAni(
        @JsonProperty("id") val id: String? = null,
    )

    private data class MediaAni(
        @JsonProperty("Media") val media: IdAni? = null,
    )

    private data class DataAni(
        @JsonProperty("data") val data: MediaAni? = null,
    )

}

class Suzihaza: XStreamCdn() {
    override val name: String = "Suzihaza"
    override val mainUrl: String = "https://suzihaza.com"
}