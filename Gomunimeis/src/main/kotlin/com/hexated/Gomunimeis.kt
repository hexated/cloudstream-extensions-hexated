package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import java.util.ArrayList


class Gomunimeis : MainAPI() {
    override var mainUrl = "https://anoboy.life"
    override var name = "Gomunime.is"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private const val mainImageUrl = "https://upload.anoboy.live"

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
        "&limit=12&action=load_movie_last_update&status=Ongoing" to "Episode Baru",
        "&limit=15&action=load_movie_last_update&status=Completed" to "Completed",
        "&limit=15&action=load_movie_last_update&type=Live Action" to "Live Action",
        "&limit=15&action=load_movie_trending" to "Trending"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val home = app.get(
            "$mainUrl/my-ajax?page=$page${request.data}",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        )
            .parsedSafe<Responses>()?.data
            ?.mapNotNull { media ->
                media.toSearchResponse()
            } ?: throw ErrorLoadingException("Invalid Json reponse")
        return newHomePageResponse(request.name, home)
    }

    private fun Anime.toSearchResponse(): SearchResponse? {

        return newAnimeSearchResponse(
            postTitle ?: return null,
            "$mainUrl/anime/$postName",
            TvType.TvSeries,
        ) {
            this.posterUrl = "$mainImageUrl/$image"
            addSub(totalEpisode?.toIntOrNull())
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get(
            "$mainUrl/my-ajax?page=1&limit=10&action=load_search_movie&keyword=$query",
            referer = "$mainUrl/search/?keyword=$query",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<Responses>()?.data
            ?.mapNotNull { media ->
                media.toSearchResponse()
            } ?: throw ErrorLoadingException("Invalid Json reponse")
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".entry-title")?.text().toString()
        val poster = document.selectFirst(".thumbposter > img")?.attr("src")
        val tags = document.select(".genxed > a").map { it.text() }
        val type = document.selectFirst("div.info-content .spe span:last-child")?.ownText()?.lowercase() ?: "tv"

        val year = Regex("\\d, ([0-9]*)").find(
            document.selectFirst("div.info-content .spe span.split")?.ownText().toString()
        )?.groupValues?.get(1)?.toIntOrNull()
        val status = getStatus(document.selectFirst(".spe > span")!!.ownText())
        val description = document.select("div[itemprop = description] > p").text()
        val (malId, anilistId, image, cover) = getTracker(title, type, year)
        val episodes = document.select(".eplister > ul > li").map {
            val episode = Regex("Episode\\s?([0-9]+)").find(
                it.select(".epl-title").text()
            )?.groupValues?.getOrNull(0)
            val link = it.select("a").attr("href")
            Episode(link, episode = episode?.toIntOrNull())
        }.reversed()

        return newAnimeLoadResponse(title, url, getType(type)) {
            engName = title
            posterUrl = image ?: poster
            backgroundPosterUrl = cover ?: image ?: poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            addMalId(malId)
            addAniListId(anilistId?.toIntOrNull())
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

        document.select("div.player-container iframe").attr("src").substringAfter("html#")
            .let { id ->
                app.get("https://gomunimes.com/stream?id=$id")
                    .parsedSafe<Sources>()?.server?.streamsb?.link?.let { link ->
                        loadExtractor(link, "https://vidgomunime.xyz/", subtitleCallback, callback)
                    }
            }

        return true
    }

    private suspend fun getTracker(title: String?, type: String?, year: Int?): Tracker {
        val res = app.get("https://api.consumet.org/meta/anilist/$title")
            .parsedSafe<AniSearch>()?.results?.find { media ->
                (media.title?.english.equals(title, true) || media.title?.romaji.equals(
                    title,
                    true
                )) || (media.type.equals(type, true) && media.releaseDate == year)
            }
        return Tracker(res?.malId, res?.aniId, res?.image, res?.cover)
    }

    data class Tracker(
        val malId: Int? = null,
        val aniId: String? = null,
        val image: String? = null,
        val cover: String? = null,
    )

    data class Title(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null,
    )

    data class Results(
        @JsonProperty("id") val aniId: String? = null,
        @JsonProperty("malId") val malId: Int? = null,
        @JsonProperty("title") val title: Title? = null,
        @JsonProperty("releaseDate") val releaseDate: Int? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("cover") val cover: String? = null,
    )

    data class AniSearch(
        @JsonProperty("results") val results: ArrayList<Results>? = arrayListOf(),
    )

    data class Streamsb(
        @JsonProperty("link") val link: String?,
    )

    data class Server(
        @JsonProperty("streamsb") val streamsb: Streamsb?,
    )

    data class Sources(
        @JsonProperty("server") val server: Server?,
    )

    data class Responses(
        @JsonProperty("data") val data: ArrayList<Anime>? = arrayListOf(),
    )

    data class Anime(
        @JsonProperty("post_title") val postTitle: String?,
        @JsonProperty("post_name") val postName: String?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("total_episode") val totalEpisode: String?,
        @JsonProperty("salt") val salt: String?,
    )

}