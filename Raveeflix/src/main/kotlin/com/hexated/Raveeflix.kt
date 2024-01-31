package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val res = app.get("$mainUrl/index.json").text.let { AppUtils.tryParseJson<ArrayList<Index>>(it) }
        return res?.filter {
            it.title?.contains(
                query,
                true
            ) == true && !it.section.equals("Categories", true) && !it.section.equals("Tags", true) && it.permalink?.contains("/episode") == false
        }?.mapNotNull {
            newMovieSearchResponse(
                it.title ?: return@mapNotNull null,
                fixUrl(
                    it.permalink?.substringBefore("episode")?.substringBefore("season")
                        ?: return@mapNotNull null,
                ),
                TvType.Movie,
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.text-4xl")?.text() ?: "No Title"
        val poster = document.selectFirst("div.thumbnail_card, div.w-full.thumbnail_card_related")
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
            val section = document.select("div.relative > section.w-full a.min-w-full")
            val hasMultipleSeason = section.any { it.attr("href").contains("/season-") }
            val episodes =
                if (hasMultipleSeason) {
                    section.apmap { ss ->
                        val season = ss.selectFirst("div.text-xl")?.text()?.filter { it.isDigit() }
                            ?.toIntOrNull()
                        app.get(fixUrl(ss.attr("href"))).document.select("div.relative > section.w-full a.min-w-full")
                            .mapNotNull { eps ->
                                val name = eps.selectFirst("div.text-xl")?.text()
                                    ?: return@mapNotNull null
                                val href = fixUrl(eps.attr("href"))
                                val posterUrl = eps.selectFirst("div.thumbnail_card")?.attr("style")
                                    ?.getPoster()
                                Episode(
                                    href,
                                    name,
                                    posterUrl = posterUrl,
                                    season = season
                                )
                            }
                    }.flatten()
                } else {
                    section.mapNotNull { eps ->
                        val name = eps.selectFirst("div.text-xl")?.text() ?: return@mapNotNull null
                        val href = fixUrl(eps.attr("href"))
                        val posterUrl =
                            eps.selectFirst("div.thumbnail_card")?.attr("style")?.getPoster()
                        Episode(
                            href,
                            name,
                            posterUrl = posterUrl,
                        )
                    }
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
            newMovieLoadResponse(title, url, TvType.Movie, url) {
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

    private fun String.getPoster(): String? {
        return fixUrlNull(
            this.substringAfter("(")
                .substringBefore(")"),
        )
    }

    data class Index(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("permalink") val permalink: String? = null,
        @JsonProperty("section") val section: String? = null,
    )
}
