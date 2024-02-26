package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.hexated.KickassanimeExtractor.invokeAlpha
import com.hexated.KickassanimeExtractor.invokeBeta
import com.hexated.KickassanimeExtractor.invokeDailymotion
import com.hexated.KickassanimeExtractor.invokeGogo
import com.hexated.KickassanimeExtractor.invokeMave
import com.hexated.KickassanimeExtractor.invokePinkbird
import com.hexated.KickassanimeExtractor.invokeSapphire
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

open class Kickassanime : MainAPI() {
    final override var mainUrl = "https://www2.kickassanime.ro"
    override var name = "Kickassanime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true

    override val supportedSyncNames = setOf(
        SyncIdName.MyAnimeList,
        SyncIdName.Anilist
    )

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        const val kaast = "https://kaast1.com"
        private const val consumetAnilist = "https://consumet-instance.vercel.app/meta/anilist"
        private const val consumetMal = "https://consumet-instance.vercel.app/meta/mal"
        fun getType(t: String): TvType {
            return when {
                t.contains("Ova", true) -> TvType.OVA
                t.contains("Movie", true) -> TvType.AnimeMovie
                else -> TvType.Anime
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
        "$mainUrl/api/get_anime_list/all/" to "All",
        "$mainUrl/api/get_anime_list/sub/" to "Sub",
        "$mainUrl/api/get_anime_list/dub/" to "Dub",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val home = app.get(request.data + page).parsedSafe<Responses>()?.data?.mapNotNull { media ->
            media.toSearchResponse()
        } ?: throw ErrorLoadingException()
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return when {
            uri.contains("/episode") -> fixUrl(uri.substringBeforeLast("/"))
            else -> fixUrl(uri)
        }
    }

    private fun Animes.toSearchResponse(): AnimeSearchResponse? {
        val href = getProperAnimeLink(this.slug ?: return null)
        val title = this.name ?: return null
        val posterUrl = getImageUrl(this.poster)
        val episode = this.episode?.toIntOrNull()
        val isDub = this.name.contains("(Dub)")
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(isDub, episode)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?q=$query").document
        val data = document.selectFirst("script:containsData(appData)")?.data()
            ?.substringAfter("\"animes\":[")?.substringBefore("],")
        return tryParseJson<List<Animes>>("[$data]")?.mapNotNull { media -> media.toSearchResponse() }
            ?: throw ErrorLoadingException()
    }

    override suspend fun getLoadUrl(name: SyncIdName, id: String): String {
        val syncId = id.split("/").last()
        val url = if (name == SyncIdName.Anilist) {
            "$consumetAnilist/info/$syncId"
        } else {
            "$consumetMal/info/$syncId"
        }

        val res = app.get(url).parsedSafe<SyncInfo>()?.title

        val romanjiUrl = "$mainUrl/anime/${res?.romaji?.createSlug()}"
        val englishUrl = "$mainUrl/anime/${res?.english?.createSlug()}"

        return if (app.get(romanjiUrl).url != "$mainUrl/") romanjiUrl else englishUrl
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val res = document.selectFirst("script:containsData(appData)")?.data()
            ?.substringAfter("\"anime\":{")?.substringBefore("},\"wkl\"")?.let {
                tryParseJson<DetailAnime>("{$it}")
            } ?: throw ErrorLoadingException()

        val title = res.name ?: return null
        val trackerTitle = res.en_title.orEmpty().ifEmpty { res.name }.getTrackerTitle()
        val poster = getImageUrl(res.image)
        val tags = res.genres?.map { it.name ?: return null }
        val year = res.startdate?.substringBefore("-")?.toIntOrNull()
        val status = getStatus(res.status ?: return null)
        val description = res.description

        val episodes = res.episodes?.mapNotNull { eps ->
            Episode(fixUrl(eps.slug ?: return@mapNotNull null), episode = eps.num?.toIntOrNull())
        }?.reversed() ?: emptyList()

        val type = res.type?.substringBefore(",")?.trim()?.let {
            when (it) {
                "TV Series" -> "tv"
                "Ova" -> "ova"
                "ONA" -> "ona"
                "Movie" -> "movie"
                else -> "tv"
            }
        } ?: if (episodes.size == 1) "movie" else "tv"

        val (malId, anilistId, image, cover) = getTracker(
            trackerTitle,
            title.getTrackerTitle(),
            type,
            year
        )

        return newAnimeLoadResponse(title, url, getType(type)) {
            engName = title
            posterUrl = image ?: poster
            backgroundPosterUrl = cover ?: image ?: poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
            addMalId(malId)
            addAniListId(anilistId?.toIntOrNull())
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        val sources = document.selectFirst("script:containsData(appData)")?.data()?.let {
            tryParseJson<Resources>("{${Regex("(\"episode\":.*),\"wkl").find(it)?.groupValues?.get(1)}}")
        }?.let { server ->
            listOf(
                server.episode?.link1.orEmpty().ifEmpty { server.episode?.link4 },
                server.ext_servers?.find { it.name == "Vidstreaming" }?.link
            )
        }?.filterNotNull()
        val isDub = data.contains("-dub-")
        sources?.flatMap {
            httpsify(it).fixIframe()
        }?.apmap { (name, iframe) ->
            val sourceName = fixTitle(name ?: this.name)
            val link = httpsify(iframe ?: return@apmap null)
            when {
                link.startsWith("https://www.dailymotion.com") -> {
                    invokeDailymotion(link, subtitleCallback, callback)
                }
                name?.contains(Regex("(?i)(KICKASSANIMEV2|ORIGINAL-QUALITY-V2|BETA-SERVER|DAILYMOTION)")) == true -> {
                    invokeAlpha(sourceName, link, subtitleCallback, callback)
                }
                name?.contains(Regex("(?i)(BETAPLAYER)")) == true -> {
                    invokeBeta(sourceName, link, callback)
                }
                name?.contains(Regex("(?i)(MAVERICKKI)")) == true -> {
                    invokeMave(sourceName, link, subtitleCallback, callback)
                }
                name?.contains(Regex("(?i)(gogo)")) == true -> {
                    invokeGogo(link, subtitleCallback, callback)
                }
                name?.contains(Regex("(?i)(SAPPHIRE-DUCK)")) == true -> {
                    invokeSapphire(link, isDub, subtitleCallback, callback)
                }
                name?.contains(Regex("(?i)(PINK-BIRD)")) == true -> {
                    invokePinkbird(sourceName, link, callback)
                }
                else -> return@apmap null
            }
        }

        return true
    }

    private suspend fun getTracker(
        title: String?,
        romajiTitle: String?,
        type: String?,
        year: Int?
    ): Tracker {
        val res = searchAnime(title).orEmpty().ifEmpty { searchAnime(romajiTitle) }
        val media = res?.find { media ->
            (media.title?.english.equals(title, true) || media.title?.romaji.equals(
                title,
                true
            )) || (media.type.equals(type, true) && media.releaseDate == year)
        }
        return Tracker(media?.malId, media?.aniId, media?.image, media?.cover)
    }

    private suspend fun searchAnime(title: String?): ArrayList<Results>? {
        return app.get("$consumetAnilist/$title")
            .parsedSafe<AniSearch>()?.results
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

    data class Genres(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
    )

    data class Episodes(
        @JsonProperty("epnum") val epnum: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("createddate") val createddate: String? = null,
        @JsonProperty("num") val num: String? = null,
    )

    data class DetailAnime(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("en_title") val en_title: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("startdate") val startdate: String? = null,
        @JsonProperty("broadcast_day") val broadcast_day: String? = null,
        @JsonProperty("broadcast_time") val broadcast_time: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("episodes") val episodes: ArrayList<Episodes>? = null,
        @JsonProperty("genres") val genres: ArrayList<Genres>? = null,
    )

    data class Animes(
        @JsonProperty("episode") val episode: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("episode_date") val episode_date: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster") val poster: String? = null,
    )

    data class Responses(
        @JsonProperty("data") val data: ArrayList<Animes>? = arrayListOf(),
    )

    data class Iframe(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("src") val src: String? = null,
    )

    data class ExtServers(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("link") val link: String? = null,
    )

    data class Eps(
        @JsonProperty("link1") val link1: String? = null,
        @JsonProperty("link4") val link4: String? = null,
    )

    data class Resources(
        @JsonProperty("episode") val episode: Eps? = null,
        @JsonProperty("ext_servers") val ext_servers: ArrayList<ExtServers>? = arrayListOf(),
    )

    data class BetaSources(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
    )

    data class AlphaSources(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
    )

    data class MaveSubtitles(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("src") val src: String? = null,
    )

    data class MaveSources(
        @JsonProperty("hls") val hls: String? = null,
        @JsonProperty("subtitles") val subtitles: ArrayList<MaveSubtitles>? = arrayListOf(),
    )

    data class SapphireSubtitles(
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("url") val url: String? = null,
    )

    data class SapphireStreams(
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("audio_lang") val audio_lang: String? = null,
        @JsonProperty("hardsub_lang") val hardsub_lang: String? = null,
        @JsonProperty("url") val url: String? = null,
    )

    data class SapphireSources(
        @JsonProperty("streams") val streams: ArrayList<SapphireStreams>? = arrayListOf(),
        @JsonProperty("subtitles") val subtitles: ArrayList<SapphireSubtitles>? = arrayListOf(),
    )

    data class PinkbirdSources(
        @JsonProperty("data") val data: ArrayList<PinkbirdData>? = arrayListOf(),
    )

    data class PinkbirdData(
        @JsonProperty("eid") val eid: String? = null,
        @JsonProperty("lh") val lh: String? = null,
    )

    data class SyncTitle(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null,
    )

    data class SyncInfo(
        @JsonProperty("title") val title: SyncTitle? = null,
    )

}
