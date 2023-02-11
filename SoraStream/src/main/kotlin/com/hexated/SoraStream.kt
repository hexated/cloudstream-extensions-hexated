package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.hexated.SoraExtractor.invoke123Movie
import com.hexated.SoraExtractor.invokeAnimes
import com.hexated.SoraExtractor.invokeBlackmovies
import com.hexated.SoraExtractor.invokeBollyMaza
import com.hexated.SoraExtractor.invokeCodexmovies
import com.hexated.SoraExtractor.invokeDbgo
import com.hexated.SoraExtractor.invokeFilmxy
import com.hexated.SoraExtractor.invokeFlixhq
import com.hexated.SoraExtractor.invokeHDMovieBox
import com.hexated.SoraExtractor.invokeIdlix
import com.hexated.SoraExtractor.invokeKimcartoon
import com.hexated.SoraExtractor.invokeMovieHab
import com.hexated.SoraExtractor.invokeNoverse
import com.hexated.SoraExtractor.invokeSeries9
import com.hexated.SoraExtractor.invokeTwoEmbed
import com.hexated.SoraExtractor.invokeUniqueStream
import com.hexated.SoraExtractor.invokeVidSrc
import com.hexated.SoraExtractor.invokeXmovies
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.hexated.SoraExtractor.invokeCrunchyroll
import com.hexated.SoraExtractor.invokeDahmerMovies
import com.hexated.SoraExtractor.invokeEdithxmovies
import com.hexated.SoraExtractor.invokeFDMovies
import com.hexated.SoraExtractor.invokeFlixon
import com.hexated.SoraExtractor.invokeFwatayako
import com.hexated.SoraExtractor.invokeGMovies
import com.hexated.SoraExtractor.invokeJsmovies
import com.hexated.SoraExtractor.invokeKisskh
import com.hexated.SoraExtractor.invokeLing
import com.hexated.SoraExtractor.invokeM4uhd
import com.hexated.SoraExtractor.invokeMovie123Net
import com.hexated.SoraExtractor.invokeMoviesbay
import com.hexated.SoraExtractor.invokeMoviezAdd
import com.hexated.SoraExtractor.invokePapaonMovies1
import com.hexated.SoraExtractor.invokePapaonMovies2
import com.hexated.SoraExtractor.invokeRStream
import com.hexated.SoraExtractor.invokeRinzrymovies
import com.hexated.SoraExtractor.invokeSmashyStream
import com.hexated.SoraExtractor.invokeSoraStream
import com.hexated.SoraExtractor.invokeTvMovies
import com.hexated.SoraExtractor.invokeUhdmovies
import com.hexated.SoraExtractor.invokeWatchsomuch
import com.hexated.SoraExtractor.invokeXtrememovies
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlin.math.roundToInt

open class SoraStream : TmdbProvider() {
    override var name = "SoraStream"
    override val hasMainPage = true
    override val instantLinkLoading = true
    override val useMetaLoadResponse = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    /** AUTHOR : Hexated & Sora */
    companion object {
        // TOOLS
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        const val tmdb2mal = "https://tmdb2mal.slidemovies.org"
        const val jikanAPI = "https://api.jikan.moe/v4"
        const val gdbot = "https://gdbot.xyz"
        const val consumetAnilistAPI = "https://api.consumet.org/meta/anilist"

        private val apiKey = base64DecodeAPI("ZTM=NTg=MjM=MjM=ODc=MzI=OGQ=MmE=Nzk=Nzk=ZjI=NTA=NDY=NDA=MzA=YjA=") // PLEASE DON'T STEAL

        // ALL SOURCES
        const val twoEmbedAPI = "https://www.2embed.to"
        const val vidSrcAPI = "https://v2.vidsrc.me"
        const val dbgoAPI = "https://dbgo.fun"
        const val movie123API = "https://api.123movie.cc"
        const val movieHabAPI = "https://moviehab.com"
        const val databaseGdriveAPI = "https://databasegdriveplayer.co"
        const val hdMovieBoxAPI = "https://hdmoviebox.net"
        const val series9API = "https://series9.la"
        const val idlixAPI = "https://idlixian.com"
        const val noverseAPI = "https://www.nollyverse.com"
        const val olgplyAPI = "https://olgply.xyz" // dead
        const val uniqueStreamAPI = "https://uniquestreaming.net"
        const val filmxyAPI = "https://www.filmxy.vip"
        const val kimcartoonAPI = "https://kimcartoon.li"
        const val xMovieAPI = "https://xemovies.to"
        const val consumetFlixhqAPI = "https://api.consumet.org/movies/flixhq"
        const val consumetZoroAPI = "https://api.consumet.org/anime/zoro"
        const val consumetCrunchyrollAPI = "https://api.consumet.org/anime/crunchyroll"
        const val kissKhAPI = "https://kisskh.me"
        const val lingAPI = "https://ling-online.net"
        const val uhdmoviesAPI = "https://uhdmovies.world"
        const val fwatayakoAPI = "https://5100.svetacdn.in"
        const val gMoviesAPI = "https://gdrivemovies.xyz"
        const val fdMoviesAPI = "https://freedrivemovie.lol"
        const val m4uhdAPI = "https://m4uhd.tv"
        const val tvMoviesAPI = "https://www.tvseriesnmovies.com"
        const val moviezAddAPI = "https://45.143.223.244"
        const val bollyMazaAPI = "https://b.bloginguru.info"
        const val moviesbayAPI = "https://moviesbay.live"
        const val rStreamAPI = "https://fsa.remotestre.am"
        const val flixonAPI = "https://flixon.ru"
        const val animeKaizokuAPI = "https://animekaizoku.com"
        const val movie123NetAPI = "https://ww7.0123movie.net"
        const val smashyStreamAPI = "https://embed.smashystream.com"
        const val watchSomuchAPI = "https://watchsomuch.tv" // sub only
        const val baymoviesAPI = "https://opengatewayindex.pages.dev" // dead
        const val chillmovies0API = "https://chill.aicirou.workers.dev/0:" // dead
        const val chillmovies1API = "https://chill.aicirou.workers.dev/1:" // dead
        const val gamMoviesAPI = "https://drive.gamick.workers.dev/0:" // dead
        const val jsMoviesAPI = "https://jsupload.jnsbot.workers.dev/0:"
        const val blackMoviesAPI = "https://dl.blacklistedbois.workers.dev/0:"
        const val rinzryMoviesAPI = "https://rinzry.stream/0:"
        const val codexMoviesAPI = "https://packs.codexcloudx.tech/0:"
        const val edithxMoviesAPI = "https://index.edithx.ga/0:"
        const val xtremeMoviesAPI = "https://kartik19.xtrememirror0.workers.dev/0:"
        const val papaonMovies1API = "https://m.papaonwork.workers.dev/0:"
        const val papaonMovies2API = "https://m.papaonwork.workers.dev/1:"
        const val dahmerMoviesAPI = "https://edytjedhgmdhm.abfhaqrhbnf.workers.dev"

        fun getType(t: String?): TvType {
            return when (t) {
                "movie" -> TvType.Movie
                else -> TvType.TvSeries
            }
        }

        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Returning Series" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

        fun base64DecodeAPI(api: String): String {
            return api.chunked(4).map { base64Decode(it) }.reversed().joinToString("")
        }

    }

    override val mainPage = mainPageOf(
        "$tmdbAPI/trending/all/day?api_key=$apiKey&region=US" to "Trending",
        "$tmdbAPI/movie/popular?api_key=$apiKey&region=US" to "Popular Movies",
        "$tmdbAPI/tv/popular?api_key=$apiKey&region=US" to "Popular TV Shows",
        "$tmdbAPI/tv/airing_today?api_key=$apiKey&region=US" to "Airing Today TV Shows",
//        "$tmdbAPI/tv/on_the_air?api_key=$apiKey&region=US" to "On The Air TV Shows",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=213" to "Netflix",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=1024" to "Amazon",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=2739" to "Disney+",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=453" to "Hulu",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=2552" to "Apple TV+",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=49" to "HBO",
        "$tmdbAPI/movie/top_rated?api_key=$apiKey&region=US" to "Top Rated Movies",
        "$tmdbAPI/tv/top_rated?api_key=$apiKey&region=US" to "Top Rated TV Shows",
        "$tmdbAPI/movie/upcoming?api_key=$apiKey&region=US" to "Upcoming Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=ko" to "Korean Shows",
        "$tmdbAPI/tv/airing_today?api_key=$apiKey&with_keywords=210024|222243&sort_by=primary_release_date.desc" to "Airing Today Anime",
        "$tmdbAPI/tv/on_the_air?api_key=$apiKey&with_keywords=210024|222243&sort_by=primary_release_date.desc" to "Ongoing Anime",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_keywords=210024|222243" to "Anime",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_keywords=210024|222243" to "Anime Movies",
    )

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w500/$link" else link
    }

    private fun getOriImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original/$link" else link
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val adultQuery =
            if (settingsForProvider.enableAdult) "" else "&without_keywords=190370|13059|226161|195669"
        val type = if (request.data.contains("/movie")) "movie" else "tv"
        val home = app.get("${request.data}$adultQuery&page=$page")
            .parsedSafe<Results>()?.results
            ?.mapNotNull { media ->
                media.toSearchResponse(type)
            } ?: throw ErrorLoadingException("Invalid Json reponse")
        return newHomePageResponse(request.name, home)
    }

    private fun Media.toSearchResponse(type: String? = null): SearchResponse? {
        return newMovieSearchResponse(
            title ?: name ?: originalTitle ?: return null,
            Data(id = id, type = mediaType ?: type).toJson(),
            TvType.Movie,
        ) {
            this.posterUrl = getImageUrl(posterPath)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        return app.get(
            "$tmdbAPI/search/multi?api_key=$apiKey&language=en-US&query=$query&page=1&include_adult=${settingsForProvider.enableAdult}"
        ).parsedSafe<Results>()?.results?.mapNotNull { media ->
            media.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<Data>(url)
        val type = getType(data.type)
        val resUrl = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?api_key=$apiKey&append_to_response=keywords,credits,external_ids,videos,recommendations"
        } else {
            "$tmdbAPI/tv/${data.id}?api_key=$apiKey&append_to_response=keywords,credits,external_ids,videos,recommendations"
        }
        val res = app.get(resUrl).parsedSafe<MediaDetail>()
            ?: throw ErrorLoadingException("Invalid Json Response")

        val title = res.title ?: res.name ?: return null
        val poster = getOriImageUrl(res.posterPath)
        val bgPoster = getOriImageUrl(res.backdropPath)
        val orgTitle = res.originalTitle ?: res.originalName ?: return null
        val year = (res.releaseDate ?: res.firstAirDate)?.split("-")?.first()?.toIntOrNull()
        val rating = res.vote_average.toString().toRatingInt()
        val genres = res.genres?.mapNotNull { it.name }
        val isAnime =
            genres?.contains("Animation") == true && (res.original_language == "zh" || res.original_language == "ja")
        val keywords = res.keywords?.results?.mapNotNull { it.name }.orEmpty()
            .ifEmpty { res.keywords?.keywords?.mapNotNull { it.name } }

        val actors = res.credits?.cast?.mapNotNull { cast ->
            ActorData(
                Actor(
                    cast.name ?: cast.originalName ?: return@mapNotNull null,
                    getImageUrl(cast.profilePath)
                ),
                roleString = cast.character
            )
        } ?: return null
        val recommendations =
            res.recommendations?.results?.mapNotNull { media -> media.toSearchResponse() }

        val trailer = res.videos?.results?.map { "https://www.youtube.com/watch?v=${it.key}" }
            ?.randomOrNull()

        return if (type == TvType.TvSeries) {
            val lastSeason = res.seasons?.lastOrNull()?.seasonNumber
            val episodes = res.seasons?.mapNotNull { season ->
                app.get("$tmdbAPI/${data.type}/${data.id}/season/${season.seasonNumber}?api_key=$apiKey")
                    .parsedSafe<MediaDetailEpisodes>()?.episodes?.map { eps ->
                        Episode(
                            LinkData(
                                data.id,
                                res.external_ids?.imdb_id,
                                data.type,
                                eps.seasonNumber,
                                eps.episodeNumber,
                                title = title,
                                year = season.airDate?.split("-")?.first()?.toIntOrNull(),
                                orgTitle = orgTitle,
                                isAnime = isAnime,
                                airedYear = year,
                                lastSeason = lastSeason,
                                epsTitle = eps.name,
                            ).toJson(),
                            name = eps.name,
                            season = eps.seasonNumber,
                            episode = eps.episodeNumber,
                            posterUrl = getImageUrl(eps.stillPath),
                            rating = eps.voteAverage?.times(10)?.roundToInt(),
                            description = eps.overview
                        ).apply {
                            this.addDate(eps.airDate)
                        }
                    }
            }?.flatten() ?: listOf()
            newTvSeriesLoadResponse(
                title,
                url,
                if (isAnime) TvType.Anime else TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.tags = if (isAnime) keywords else genres
                this.rating = rating
                this.showStatus = getStatus(res.status)
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LinkData(
                    data.id,
                    res.external_ids?.imdb_id,
                    data.type,
                    title = title,
                    year = year,
                    orgTitle = orgTitle,
                    isAnime = isAnime,
                ).toJson(),
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.duration = res.runtime
                this.tags = if (isAnime) keywords else genres
                this.rating = rating
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val res = parseJson<LinkData>(data)

        argamap(
            {
                invokeSoraStream(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback,
                )
            },
            {
                invokeTwoEmbed(res.id, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeVidSrc(res.id, res.season, res.episode, subtitleCallback, callback)
            },
//            {
//                invokeOlgply(res.id, res.season, res.episode, callback)
//            },
            {
                invokeDbgo(res.imdbId, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invoke123Movie(
                    res.id,
                    res.imdbId,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeMovieHab(res.imdbId, res.season, res.episode, subtitleCallback, callback)
            },
//            {
//                invokeDatabaseGdrive(
//                    res.imdbId,
//                    res.season,
//                    res.episode,
//                    subtitleCallback,
//                    callback
//                )
//            },
            {
                if (res.isAnime) invokeAnimes(
                    res.id,
                    res.title,
                    res.epsTitle,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (res.season != null && res.isAnime) invokeCrunchyroll(
                    res.title,
                    res.epsTitle,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeHDMovieBox(
                    res.title,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeSeries9(res.title, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeIdlix(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeNoverse(res.title, res.season, res.episode, callback)
            },
            {
                invokeUniqueStream(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeFilmxy(res.imdbId, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeKimcartoon(res.title, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeXmovies(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeFlixhq(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeKisskh(res.title, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeLing(
                    res.title,
                    res.airedYear ?: res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeUhdmovies(
                    res.title,
                    res.year,
                    res.season,
                    res.lastSeason,
                    res.episode,
                    res.epsTitle,
                    callback
                )
            },
            {
                invokeFwatayako(res.imdbId, res.season, res.episode, callback)
            },
            {
                if (!res.isAnime) invokeGMovies(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeFDMovies(
                    res.title,
                    res.season,
                    res.episode,
                    callback
                )
            },
            {
                invokeM4uhd(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeTvMovies(res.title, res.season, res.episode, callback)
            },
            {
                if (res.season == null) invokeMoviesbay(
                    res.title,
                    res.year,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeMoviezAdd(
                    moviezAddAPI,
                    "MoviezAdd",
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeBollyMaza(
                    bollyMazaAPI,
                    "BollyMaza",
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    callback
                )
            },
            {
                invokeRStream(res.id, res.season, res.episode, callback)
            },
            {
                invokeFlixon(res.id, res.imdbId, res.season, res.episode, callback)
            },
            {
                invokeMovie123Net(res.title, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeSmashyStream(res.imdbId, res.season, res.episode, callback)
            },
//            {
//                if (!res.isAnime) invokeBaymovies(
//                    res.title,
//                    res.year,
//                    res.season,
//                    res.episode,
//                    callback
//                )
//            },
//            {
//                invokeChillmovies0(
//                    chillmovies0API,
//                    "Chillmovies0",
//                    res.title,
//                    res.year,
//                    res.season,
//                    res.episode,
//                    callback
//                )
//            },
//            {
//                invokeChillmovies1(
//                    chillmovies1API,
//                    "Chillmovies1",
//                    res.title,
//                    res.year,
//                    res.season,
//                    res.episode,
//                    callback
//                )
//            },
//            {
//                if (!res.isAnime) invokeGammovies(
//                    gamMoviesAPI,
//                    "GamMovies",
//                    res.title,
//                    res.year,
//                    res.season,
//                    res.episode,
//                    callback
//                )
//            },
            {
                invokeWatchsomuch(
                    res.imdbId,
                    res.season,
                    res.episode,
                    subtitleCallback
                )
            },
            {
                if (!res.isAnime) invokeBlackmovies(
                    blackMoviesAPI,
                    "BlackMovies",
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeJsmovies(
                    jsMoviesAPI,
                    "JSMovies",
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    callback
                )
            },
            {
                invokeRinzrymovies(
                    rinzryMoviesAPI,
                    "RinzryMovies",
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    callback,
                )
            },
            {
                if (!res.isAnime) invokeCodexmovies(
                    codexMoviesAPI,
                    "CodexMovies",
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    callback,
                    "Basic Y29kZXg6Y29kZXhjbG91ZA=="
                )
            },
            {
                if (!res.isAnime) invokeEdithxmovies(
                    edithxMoviesAPI,
                    "EdithxMovies",
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    callback,
                    "Basic ZWRpdGg6amFydmlz"
                )
            },
            {
                if (!res.isAnime) invokeXtrememovies(
                    xtremeMoviesAPI,
                    "XtremeMovies",
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    callback
                )
            },
            {
                if (!res.isAnime) invokePapaonMovies1(
                    papaonMovies1API,
                    "PapaonMovies[1]",
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    callback
                )
            },
            {
                if (!res.isAnime) invokePapaonMovies2(
                    papaonMovies2API,
                    "PapaonMovies[2]",
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    callback
                )
            },
            {
                invokeDahmerMovies(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    callback
                )
            },
        )

        return true
    }

    data class LinkData(
        val id: Int? = null,
        val imdbId: String? = null,
        val type: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val aniId: String? = null,
        val animeId: String? = null,
        val title: String? = null,
        val year: Int? = null,
        val orgTitle: String? = null,
        val isAnime: Boolean = false,
        val airedYear: Int? = null,
        val lastSeason: Int? = null,
        val epsTitle: String? = null,
    )

    data class Data(
        val id: Int? = null,
        val type: String? = null,
        val aniId: String? = null,
        val malId: Int? = null,
    )

    data class Results(
        @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class Media(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
    )

    data class Genres(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )

    data class Keywords(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )

    data class KeywordResults(
        @JsonProperty("results") val results: ArrayList<Keywords>? = arrayListOf(),
        @JsonProperty("keywords") val keywords: ArrayList<Keywords>? = arrayListOf(),
    )

    data class Seasons(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("air_date") val airDate: String? = null,
    )

    data class Cast(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("known_for_department") val knownForDepartment: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class Episodes(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("air_date") val airDate: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    data class MediaDetailEpisodes(
        @JsonProperty("episodes") val episodes: ArrayList<Episodes>? = arrayListOf(),
    )

    data class Trailers(
        @JsonProperty("key") val key: String? = null,
    )

    data class ResultsTrailer(
        @JsonProperty("results") val results: ArrayList<Trailers>? = arrayListOf(),
    )

    data class ExternalIds(
        @JsonProperty("imdb_id") val imdb_id: String? = null,
        @JsonProperty("tvdb_id") val tvdb_id: String? = null,
    )

    data class Credits(
        @JsonProperty("cast") val cast: ArrayList<Cast>? = arrayListOf(),
    )

    data class ResultsRecommendations(
        @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class MediaDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("vote_average") val vote_average: Any? = null,
        @JsonProperty("original_language") val original_language: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("genres") val genres: ArrayList<Genres>? = arrayListOf(),
        @JsonProperty("keywords") val keywords: KeywordResults? = null,
        @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
        @JsonProperty("videos") val videos: ResultsTrailer? = null,
        @JsonProperty("external_ids") val external_ids: ExternalIds? = null,
        @JsonProperty("credits") val credits: Credits? = null,
        @JsonProperty("recommendations") val recommendations: ResultsRecommendations? = null,
    )

    data class EmbedJson(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("sources") val sources: List<String?> = arrayListOf(),
        @JsonProperty("tracks") val tracks: List<String>? = null,
    )

    data class MovieHabData(
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("token") val token: String? = null,
    )

    data class MovieHabRes(
        @JsonProperty("data") val data: MovieHabData? = null,
    )

}
