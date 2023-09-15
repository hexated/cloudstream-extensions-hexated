package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.hexated.SoraExtractor.invoke2embed
import com.hexated.SoraExtractor.invokeAnimes
import com.hexated.SoraExtractor.invokeBollyMaza
import com.hexated.SoraExtractor.invokeCryMovies
import com.hexated.SoraExtractor.invokeDbgo
import com.hexated.SoraExtractor.invokeFilmxy
import com.hexated.SoraExtractor.invokeIdlix
import com.hexated.SoraExtractor.invokeKimcartoon
import com.hexated.SoraExtractor.invokeMovieHab
import com.hexated.SoraExtractor.invokeVidSrc
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.hexated.SoraExtractor.invokeDahmerMovies
import com.hexated.SoraExtractor.invokeDoomovies
import com.hexated.SoraExtractor.invokeDramaday
import com.hexated.SoraExtractor.invokeDreamfilm
import com.hexated.SoraExtractor.invokeFDMovies
import com.hexated.SoraExtractor.invokeFlixon
import com.hexated.SoraExtractor.invokeFwatayako
import com.hexated.SoraExtractor.invokeGMovies
import com.hexated.SoraExtractor.invokeGoku
import com.hexated.SoraExtractor.invokeKisskh
import com.hexated.SoraExtractor.invokeLing
import com.hexated.SoraExtractor.invokeM4uhd
import com.hexated.SoraExtractor.invokeMoviesbay
import com.hexated.SoraExtractor.invokeMoviezAdd
import com.hexated.SoraExtractor.invokeNavy
import com.hexated.SoraExtractor.invokeNinetv
import com.hexated.SoraExtractor.invokeNowTv
import com.hexated.SoraExtractor.invokeRStream
import com.hexated.SoraExtractor.invokeRidomovies
import com.hexated.SoraExtractor.invokeShinobiMovies
import com.hexated.SoraExtractor.invokeSmashyStream
import com.hexated.SoraExtractor.invokeDumpStream
import com.hexated.SoraExtractor.invokeEmovies
import com.hexated.SoraExtractor.invokeFourCartoon
import com.hexated.SoraExtractor.invokeHdmovies4u
import com.hexated.SoraExtractor.invokeJump1
import com.hexated.SoraExtractor.invokeMoment
import com.hexated.SoraExtractor.invokeMultimovies
import com.hexated.SoraExtractor.invokeNetflix
import com.hexated.SoraExtractor.invokeNetmovies
import com.hexated.SoraExtractor.invokePobmovies
import com.hexated.SoraExtractor.invokePrimewire
import com.hexated.SoraExtractor.invokeTvMovies
import com.hexated.SoraExtractor.invokeUhdmovies
import com.hexated.SoraExtractor.invokeVegamovies
import com.hexated.SoraExtractor.invokeVidsrcto
import com.hexated.SoraExtractor.invokeWatchOnline
import com.hexated.SoraExtractor.invokeWatchflx
import com.hexated.SoraExtractor.invokeWatchsomuch
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.extractors.VidSrcExtractor
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
        /** TOOLS */
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        const val gdbot = "https://gdtot.pro"
        const val anilistAPI = "https://graphql.anilist.co"
        const val malsyncAPI = "https://api.malsync.moe"
        const val consumetHelper = "https://api.consumet.org/anime/9anime/helper"
        const val jikanAPI = "https://api.jikan.moe/v4"
        const val watchhubApi = "https://watchhub.strem.io"

        private val apiKey =
            base64DecodeAPI("ZTM=NTg=MjM=MjM=ODc=MzI=OGQ=MmE=Nzk=Nzk=ZjI=NTA=NDY=NDA=MzA=YjA=") // PLEASE DON'T STEAL

        /** ALL SOURCES */
        const val twoEmbedAPI = "https://www.2embed.cc"
        const val vidSrcAPI = "https://v2.vidsrc.me"
        const val dbgoAPI = "https://dbgo.fun"
        const val movieHabAPI = "https://moviehab.com"
        const val dreamfilmAPI = "https://dreamfilmsw.net"
        const val idlixAPI = "https://tv.idlixplus.net"
        const val noverseAPI = "https://www.nollyverse.com"
        const val filmxyAPI = "https://www.filmxy.vip"
        const val kimcartoonAPI = "https://kimcartoon.li"
        const val aniwatchAPI = "https://aniwatch.to"
        const val crunchyrollAPI = "https://beta-api.crunchyroll.com"
        const val kissKhAPI = "https://kisskh.co"
        const val lingAPI = "https://ling-online.net"
        const val uhdmoviesAPI = "https://uhdmovies.wiki"
        const val fwatayakoAPI = "https://5100.svetacdn.in"
        const val gMoviesAPI = "https://gdrivemovies.xyz"
        const val fdMoviesAPI = "https://freedrivemovie.lol"
        const val m4uhdAPI = "https://ww2.m4ufree.com"
        const val tvMoviesAPI = "https://www.tvseriesnmovies.com"
        const val moviezAddAPI = "https://ww2.moviezaddiction.click"
        const val bollyMazaAPI = "https://m.bollymaza.click"
        const val moviesbayAPI = "https://moviesbay.live"
        const val rStreamAPI = "https://remotestre.am"
        const val flixonAPI = "https://flixon.lol"
        const val smashyStreamAPI = "https://embed.smashystream.com"
        const val watchSomuchAPI = "https://watchsomuch.tv" // sub only
        const val watchOnlineAPI = "https://watchonline.ag"
        const val nineTvAPI = "https://moviesapi.club"
        const val fmoviesAPI = "https://fmovies.to"
        const val nowTvAPI = "https://myfilestorage.xyz"
        const val gokuAPI = "https://goku.sx"
        const val ridomoviesAPI = "https://ridomovies.pw"
        const val navyAPI = "https://navy-issue-i-239.site"
        const val emoviesAPI = "https://emovies.si"
        const val pobmoviesAPI = "https://pobmovies.cam"
        const val fourCartoonAPI = "https://4cartoon.net"
        const val multimoviesAPI = "https://multi-movies.xyz"
        const val netmoviesAPI = "https://web.netmovies.to"
        const val momentAPI = "https://moment-explanation-i-244.site"
        const val doomoviesAPI = "https://doomovies.net"
        const val primewireAPI = "https://real-primewire.club"
        const val vidsrctoAPI = "https://vidsrc.to"
        const val dramadayAPI = "https://dramaday.me"
        const val animetoshoAPI = "https://animetosho.org"
        const val susflixAPI = "https://susflix.tv"
        const val jump1API = "https://ca.jump1.net"
        const val vegaMoviesAPI = "https://vegamovies.im"
        const val netflixAPI = "https://m.netflixmirror.com"
        const val hdmovies4uAPI = "https://hdmovies4u.name"
        const val watchflxAPI = "https://watchflx.tv"

        // INDEX SITE
        const val dahmerMoviesAPI = "https://edytjedhgmdhm.abfhaqrhbnf.workers.dev"
        const val shinobiMovieAPI = "https://home.shinobicloud.cf/0:"
        val cryMoviesAPI =
            base64DecodeAPI("ZXY=LmQ=cnM=a2U=b3I=Lnc=ZXI=ZGQ=bGE=cy0=b2I=YWM=Lmo=YWw=aW4=LWY=cm4=Ym8=cmU=Ly8=czo=dHA=aHQ=")

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
        "$tmdbAPI/tv/popular?api_key=$apiKey&region=US&with_original_language=en" to "Popular TV Shows",
        "$tmdbAPI/tv/airing_today?api_key=$apiKey&region=US&with_original_language=en" to "Airing Today TV Shows",
//        "$tmdbAPI/tv/on_the_air?api_key=$apiKey&region=US" to "On The Air TV Shows",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=213" to "Netflix",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=1024" to "Amazon",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=2739" to "Disney+",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=453" to "Hulu",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=2552" to "Apple TV+",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=49" to "HBO",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=4330" to "Paramount+",
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
        val append = "alternative_titles,credits,external_ids,keywords,videos,recommendations"
        val resUrl = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?api_key=$apiKey&append_to_response=$append"
        } else {
            "$tmdbAPI/tv/${data.id}?api_key=$apiKey&append_to_response=$append"
        }
        val res = app.get(resUrl).parsedSafe<MediaDetail>()
            ?: throw ErrorLoadingException("Invalid Json Response")

        val title = res.title ?: res.name ?: return null
        val poster = getOriImageUrl(res.posterPath)
        val bgPoster = getOriImageUrl(res.backdropPath)
        val orgTitle = res.originalTitle ?: res.originalName ?: return null
        val releaseDate = res.releaseDate ?: res.firstAirDate
        val year = releaseDate?.split("-")?.first()?.toIntOrNull()
        val rating = res.vote_average.toString().toRatingInt()
        val genres = res.genres?.mapNotNull { it.name }
        val isAnime =
            genres?.contains("Animation") == true && (res.original_language == "zh" || res.original_language == "ja")
        val isAsian = !isAnime && (res.original_language == "zh" || res.original_language == "ko")
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
            val lastSeason = res.last_episode_to_air?.season_number
            val episodes = res.seasons?.mapNotNull { season ->
                app.get("$tmdbAPI/${data.type}/${data.id}/season/${season.seasonNumber}?api_key=$apiKey")
                    .parsedSafe<MediaDetailEpisodes>()?.episodes?.map { eps ->
                        Episode(
                            LinkData(
                                data.id,
                                res.external_ids?.imdb_id,
                                res.external_ids?.tvdb_id,
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
                                jpTitle = res.alternative_titles?.results?.find { it.iso_3166_1 == "JP" }?.title,
                                date = season.airDate,
                                airedDate = res.releaseDate ?: res.firstAirDate,
                                isAsian = isAsian,
                            ).toJson(),
                            name = eps.name + if (isUpcoming(eps.airDate)) " - [UPCOMING]" else "",
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
                addTMDbId(data.id.toString())
                addImdbId(res.external_ids?.imdb_id)
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LinkData(
                    data.id,
                    res.external_ids?.imdb_id,
                    res.external_ids?.tvdb_id,
                    data.type,
                    title = title,
                    year = year,
                    orgTitle = orgTitle,
                    isAnime = isAnime,
                    jpTitle = res.alternative_titles?.results?.find { it.iso_3166_1 == "JP" }?.title,
                    airedDate = res.releaseDate ?: res.firstAirDate,
                    isAsian = isAsian,
                ).toJson(),
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.comingSoon = isUpcoming(releaseDate)
                this.year = year
                this.plot = res.overview
                this.duration = res.runtime
                this.tags = if (isAnime) keywords else genres
                this.rating = rating
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
                addTMDbId(data.id.toString())
                addImdbId(res.external_ids?.imdb_id)
            }
        }
    }

    override suspend fun extractorVerifierJob(extractorData: String?) {
        if (extractorData == null) return
        VidSrcExtractor.validatePass(extractorData)
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
                invokeDumpStream(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeGoku(
                    res.title,
                    res.year,
                    res.season,
                    res.lastSeason,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeVidSrc(res.id, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeDbgo(res.imdbId, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeMovieHab(res.imdbId, res.season, res.episode, subtitleCallback, callback)
            },
            {
                if (res.isAnime) invokeAnimes(
                    res.title,
                    res.epsTitle,
                    res.date,
                    res.airedDate,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeDreamfilm(
                    res.title,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
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
//            {
//                invokeNoverse(res.title, res.season, res.episode, callback)
//            },
            {
                if (!res.isAnime) invokeFilmxy(
                    res.imdbId,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeKimcartoon(
                    res.title,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeVidsrcto(
                    res.imdbId,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeKisskh(
                    res.title,
                    res.season,
                    res.episode,
                    res.isAnime,
                    res.lastSeason,
                    subtitleCallback,
                    callback
                )
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
                if (!res.isAnime) invokeM4uhd(
                    res.title,
                    res.airedYear ?: res.year,
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
                invokeSmashyStream(
                    res.imdbId,
                    res.season,
                    res.episode,
                    res.isAnime,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeWatchsomuch(
                    res.imdbId,
                    res.season,
                    res.episode,
                    subtitleCallback
                )
            },
            {
                if (!res.isAnime) invokeNinetv(
                    res.id,
                    res.season,
                    res.episode,
                    subtitleCallback,
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
            {
                invokePrimewire(res.title, res.year, res.season, res.episode, callback)
            },
//            {
//                if (!res.isAnime) invokeGdbotMovies(
//                    res.title,
//                    res.year,
//                    res.season,
//                    res.episode,
//                    callback
//                )
//            },
            {
                if (!res.isAnime) invokeShinobiMovies(
                    shinobiMovieAPI,
                    "ShinobiMovies",
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    callback
                )
            },
            {
                invokeWatchOnline(
                    res.imdbId,
                    res.id,
                    res.title,
                    res.airedYear ?: res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime && res.season == null) invokeCryMovies(
                    res.imdbId,
                    res.title,
                    res.year,
                    res.episode,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeNowTv(res.id, res.season, res.episode, callback)
            },
            {
                if (res.season == null) invokeRidomovies(res.title, res.year, callback)
            },
            {
                invokeNavy(res.imdbId, res.season, res.episode, callback)
            },
            {
                invokeMoment(res.imdbId, res.season, res.episode, callback)
            },
            {
                if (!res.isAnime) invokeEmovies(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeVegamovies(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime && res.season == null) invokePobmovies(
                    res.title,
                    res.year,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeFourCartoon(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    callback
                )
            },
            {
                invokeMultimovies(res.title, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeNetmovies(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime && res.season == null) invokeDoomovies(
                    res.title,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (res.isAsian) invokeDramaday(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime) invoke2embed(res.imdbId, res.season, res.episode, callback)
            },
//            {
//                invokeSusflix(res.id,res.season,res.episode,subtitleCallback,callback)
//            },
            {
                if (!res.isAnime) invokeJump1(
                    res.id,
                    res.tvdbId,
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeNetflix(
                    res.imdbId,
                    res.season,
                    res.episode,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeHdmovies4u(
                    res.title,
                    res.imdbId,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeWatchflx(res.id, res.season, res.episode, callback)
            }
        )

        return true
    }

    data class LinkData(
        val id: Int? = null,
        val imdbId: String? = null,
        val tvdbId: Int? = null,
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
        val jpTitle: String? = null,
        val date: String? = null,
        val airedDate: String? = null,
        val isAsian: Boolean = false,
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

    data class AltTitles(
        @JsonProperty("iso_3166_1") val iso_3166_1: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("type") val type: String? = null,
    )

    data class ResultsAltTitles(
        @JsonProperty("results") val results: ArrayList<AltTitles>? = arrayListOf(),
    )

    data class ExternalIds(
        @JsonProperty("imdb_id") val imdb_id: String? = null,
        @JsonProperty("tvdb_id") val tvdb_id: Int? = null,
    )

    data class Credits(
        @JsonProperty("cast") val cast: ArrayList<Cast>? = arrayListOf(),
    )

    data class ResultsRecommendations(
        @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class LastEpisodeToAir(
        @JsonProperty("episode_number") val episode_number: Int? = null,
        @JsonProperty("season_number") val season_number: Int? = null,
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
        @JsonProperty("last_episode_to_air") val last_episode_to_air: LastEpisodeToAir? = null,
        @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
        @JsonProperty("videos") val videos: ResultsTrailer? = null,
        @JsonProperty("external_ids") val external_ids: ExternalIds? = null,
        @JsonProperty("credits") val credits: Credits? = null,
        @JsonProperty("recommendations") val recommendations: ResultsRecommendations? = null,
        @JsonProperty("alternative_titles") val alternative_titles: ResultsAltTitles? = null,
    )

    data class MovieHabData(
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("token") val token: String? = null,
    )

    data class MovieHabRes(
        @JsonProperty("data") val data: MovieHabData? = null,
    )

}
