package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.Hxfile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

class NontonAnimeIDProvider : MainAPI() {
    override var mainUrl = "https://nontonanimeid.mom"
    override var name = "NontonAnimeID"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
            TvType.Anime,
            TvType.AnimeMovie,
            TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("TV",true) -> TvType.Anime
                t.contains("Movie",true) -> TvType.AnimeMovie
                else -> TvType.OVA
            }
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
            "" to "Latest Update",
            "ongoing-list/" to " Ongoing List",
            "popular-series/" to "Popular Series",
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}").document
        val home = document.select(".animeseries").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val title = this.selectFirst(".title")?.text() ?: ""
        val posterUrl = fixUrlNull(this.selectFirst("img")?.getImageAttr())

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(dubExist = false, subExist = true)
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = app.get(link).document

        return document.select(".result > ul > li").mapNotNull {
            val title = it.selectFirst("h2")!!.text().trim()
            val poster = it.selectFirst("img")?.getImageAttr()
            val tvType = getType(
                    it.selectFirst(".boxinfores > span.typeseries")!!.text().toString()
            )
            val href = fixUrl(it.selectFirst("a")!!.attr("href"))

            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = poster
                addDubStatus(dubExist = false, subExist = true)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixUrl = if (url.contains("/anime/")) {
            url
        } else {
            app.get(url).document.selectFirst("div.nvs.nvsc a")?.attr("href")
        }

        val req = app.get(fixUrl ?: return null)
        mainUrl = getBaseUrl(req.url)
        val document = req.document

        val title = document.selectFirst("h1.entry-title.cs")!!.text().removeSurrounding("Nonton Anime", "Sub Indo").trim()
        val poster = document.selectFirst(".poster > img")?.getImageAttr()
        val tags = document.select(".tagline > a").map { it.text() }

        val year = Regex("\\d, (\\d*)").find(
                document.select(".bottomtitle > span:nth-child(5)").text()
        )?.groupValues?.get(1)?.toIntOrNull()
        val status = getStatus(
                document.select("span.statusseries").text().trim()
        )
        val type = getType(document.select("span.typeseries").text().trim().lowercase())
        val rating = document.select("span.nilaiseries").text().trim().toIntOrNull()
        val description = document.select(".entry-content.seriesdesc > p").text().trim()
        val trailer = document.selectFirst("a.trailerbutton")?.attr("href")

        val episodes = if (document.select("button.buttfilter").isNotEmpty()) {
            val id = document.select("input[name=series_id]").attr("value")
            val numEp =
                    document.selectFirst(".latestepisode > a")?.text()?.replace(Regex("\\D"), "")
                            .toString()
            Jsoup.parse(
                    app.post(
                            url = "$mainUrl/wp-admin/admin-ajax.php",
                            data = mapOf(
                                    "misha_number_of_results" to numEp,
                                    "misha_order_by" to "date-DESC",
                                    "action" to "mishafilter",
                                    "series_id" to id
                            )
                    ).parsed<EpResponse>().content
            ).select("li").map {
                val episode = Regex("Episode\\s?(\\d+)").find(
                        it.selectFirst("a")?.text().toString()
                )?.groupValues?.getOrNull(0) ?: it.selectFirst("a")?.text()
                val link = fixUrl(it.selectFirst("a")!!.attr("href"))
                Episode(link, episode = episode?.toIntOrNull())
            }.reversed()
        } else {
            document.select("ul.misha_posts_wrap2 > li").map {
                val episode = Regex("Episode\\s?(\\d+)").find(
                        it.selectFirst("a")?.text().toString()
                )?.groupValues?.getOrNull(0) ?: it.selectFirst("a")?.text()
                val link = it.select("a").attr("href")
                Episode(link, episode = episode?.toIntOrNull())
            }.reversed()
        }

        val recommendations = document.select(".result > li").mapNotNull {
            val epHref = it.selectFirst("a")!!.attr("href")
            val epTitle = it.selectFirst("h3")!!.text()
            val epPoster = it.selectFirst(".top > img")?.getImageAttr()
            newAnimeSearchResponse(epTitle, epHref, TvType.Anime) {
                this.posterUrl = epPoster
                addDubStatus(dubExist = false, subExist = true)
            }
        }

        val tracker = APIHolder.getTracker(listOf(title),TrackerType.getTypes(type),year,true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            this.rating = rating
            plot = description
            addTrailer(trailer)
            this.tags = tags
            this.recommendations = recommendations
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }

    }

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        document.select(".container1 > ul > li:not(.boxtab)").apmap {
            val dataPost = it.attr("data-post")
            val dataNume = it.attr("data-nume")
            val dataType = it.attr("data-type")

            val iframe = app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                            "action" to "player_ajax",
                            "post" to dataPost,
                            "nume" to dataNume,
                            "type" to dataType,
                            "nonce" to "8c5a2e3cb2"
                    ),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).document.selectFirst("iframe")?.attr("src")

            loadExtractor(iframe ?: return@apmap , "$mainUrl/", subtitleCallback, callback)
        }

        return true
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private fun Element.getImageAttr(): String? {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private data class EpResponse(
            @JsonProperty("posts") val posts: String?,
            @JsonProperty("max_page") val max_page: Int?,
            @JsonProperty("found_posts") val found_posts: Int?,
            @JsonProperty("content") val content: String
    )

}
