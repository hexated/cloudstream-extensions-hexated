package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

class Raveeflix : MainAPI() {
    override var mainUrl = "https://raveeflix.my.id"
    override var name = "Raveeflix"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes =
        setOf(
            TvType.Movie,
            TvType.TvSeries,
            TvType.AsianDrama,
        )

    override val mainPage =
        mainPageOf(
            "categories/trending" to "Trending",
            "movies" to "Movies",
            "tv" to "Tv-Shows",
            "drakor" to "Drakor",
            "categories/anime" to "Anime",
        )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val pages = if (page > 1) "page/$page/" else ""
        val document = app.get("$mainUrl/${request.data}/$pages").document
        val home = document.select("section.w-full a.min-w-full").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            HomePageList(
                request.name, home, true
            )
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.text-xl")?.text() ?: return null
        val href = fixUrl(this.attr("href"))
        val posterUrl = this.selectFirst("div.thumbnail_card")?.attr("style")?.getPoster()

        return newMovieSearchResponse(title, Media(href, posterUrl).toJson(), TvType.Movie, false) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val res =
            app.get("$mainUrl/index.json").text.let { AppUtils.tryParseJson<ArrayList<Index>>(it) }
        return res?.filter {
            it.title?.contains(
                query,
                true
            ) == true && !it.section.equals("Categories", true) && !it.section.equals(
                "Tags",
                true
            ) && it.permalink?.contains("/episode") == false
        }?.mapNotNull {
            newMovieSearchResponse(
                it.title ?: return@mapNotNull null,
                Media(
                    fixUrl(
                        it.permalink?.substringBefore("episode")?.substringBefore("season")
                            ?: return@mapNotNull null
                    )
                ).toJson(),
                TvType.Movie,
                false,
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val media = parseJson<Media>(url)
        val document = app.get(media.url).document
        val title = document.selectFirst("h1.text-4xl")?.text() ?: "No Title"
        val poster = media.poster ?: document.selectFirst("div.thumbnail_card, div.w-full.thumbnail_card_related")
            ?.attr("style")?.getPoster()
        val type =
            if (document.select("mux-player").isNullOrEmpty()) TvType.TvSeries else TvType.Movie
        val tags =
            if (type == TvType.TvSeries) {
                document.selectFirst("div.movie-details > p:nth-child(1)")
                    ?.ownText()?.split(",")
                    ?.map { it.trim() }
            } else {
                document.select("span.mr-2")
                    .map { it.text() }.distinct()
            }

        val year =
            document.selectFirst("div.movie-details > p:nth-child(2), div.max-w-prose.mb-20 > ul > li:nth-child(2) span")
                ?.ownText()?.substringAfter(",")?.toIntOrNull()
        val description =
            document.selectFirst("div.lead.text-neutral-500, span#storyline")
                ?.text()?.trim()
        val rating =
            document.selectFirst("span#rating")?.text()
                ?.toRatingInt()
        val actors =
            document.select("span#cast").text().split(", ")
                .map { it.trim() }

        val recommendations =
            document.select("section.w-full a.min-w-full").mapNotNull { it.toSearchResult() }

        return if (type == TvType.TvSeries) {
            val sectionSelector = "div.relative > section.w-full a.min-w-full"
            val section = document.select(sectionSelector)
            val hasMultipleSeason = section.any { it.attr("href").contains("/season-") }
            val episodes = if (hasMultipleSeason) {
                section.apmap { ss ->
                    fetchEpisodesFromPages(
                        ss.attr("href"),
                        5,
                        sectionSelector,
                        true,
                        ss.selectFirst("div.text-xl")?.text()?.filter { it.isDigit() }
                            ?.toIntOrNull()
                    )
                }.toMutableList().flatten()
            } else {
                fetchEpisodesFromPages(media.url, 5, sectionSelector, false)
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
                this.posterUrl = poster
                this.year = year
                this.seasonNames
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, media.url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {

        val video = app.get(data).document.select("mux-player").attr("src")

        callback.invoke(
            ExtractorLink(
                name,
                name,
                video,
                "",
                Qualities.Unknown.value,
            )
        )

        return true
    }

    private suspend fun fetchEpisodesFromPages(
        baseUrl: String,
        maxPages: Int,
        sectionSelector: String,
        hasMultipleSeasons: Boolean,
        season: Int? = null
    ): MutableList<Episode> {
        val epsData = mutableListOf<Episode>()
        for (index in 1..maxPages) {
            val pageUrl = if (index == 1) baseUrl else "${baseUrl.removeSuffix("/")}/page/$index/"
            val episodeVo = app.get(fixUrl(pageUrl)).document.select(sectionSelector)
                .getEpisodes(if (hasMultipleSeasons) season else null)
            if (episodeVo.isEmpty()) break
            epsData.addAll(episodeVo)
        }
        return epsData
    }

    private fun Elements.getEpisodes(season: Int? = 1): List<Episode> {
        return this.mapNotNull { eps ->
            val name = eps.selectFirst("div.text-xl")?.text() ?: return@mapNotNull null
            val href = fixUrl(eps.attr("href"))
            val posterUrl =
                eps.selectFirst("div.thumbnail_card")?.attr("style")?.getPoster()
            Episode(
                href,
                name,
                posterUrl = posterUrl,
                season = season
            )
        }
    }

    private fun String.getPoster(): String? {
        return fixUrlNull(
            this.substringAfter("(")
                .substringBefore(")"),
        )
    }

    data class Media(val url: String, val poster: String? = null)

    data class Index(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("permalink") val permalink: String? = null,
        @JsonProperty("section") val section: String? = null,
    )
}
