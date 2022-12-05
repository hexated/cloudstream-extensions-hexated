package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.hexated.SoraExtractor.invoKisskh
import com.hexated.SoraExtractor.invoke123Movie
import com.hexated.SoraExtractor.invokeDbgo
import com.hexated.SoraExtractor.invokeFilmxy
import com.hexated.SoraExtractor.invokeFlixhq
import com.hexated.SoraExtractor.invokeHDMovieBox
import com.hexated.SoraExtractor.invokeIdlix
import com.hexated.SoraExtractor.invokeKimcartoon
import com.hexated.SoraExtractor.invokeMovieHab
import com.hexated.SoraExtractor.invokeNoverse
import com.hexated.SoraExtractor.invokeOlgply
import com.hexated.SoraExtractor.invokeSeries9
import com.hexated.SoraExtractor.invokeSoraVIP
import com.hexated.SoraExtractor.invokeTwoEmbed
import com.hexated.SoraExtractor.invokeUniqueStream
import com.hexated.SoraExtractor.invokeVidSrc
import com.hexated.SoraExtractor.invokeXmovies
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.hexated.SoraExtractor.invoZoro
import com.hexated.SoraExtractor.invokeFwatayako
import com.hexated.SoraExtractor.invokeGMovies
import com.hexated.SoraExtractor.invokeLing
import com.hexated.SoraExtractor.invokeUhdmovies
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlin.math.roundToInt

open class SoraStream : TmdbProvider() {
    override var name = "SoraStream"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val instantLinkLoading = true
    override val useMetaLoadResponse = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    /** AUTHOR : Hexated & Sora */
    companion object {
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        private val apiKey = base64DecodeAPI("ZTM=NTg=MjM=MjM=ODc=MzI=OGQ=MmE=Nzk=Nzk=ZjI=NTA=NDY=NDA=MzA=YjA=") // PLEASE DON'T STEAL
        const val tmdb2mal = "https://tmdb2mal.slidemovies.org"
        const val gdbot = "https://gdbot.xyz"

        private val mainAPI = base64DecodeAPI("cHA=LmE=ZWw=cmM=dmU=aC4=dGM=d2E=eHA=Ly8=czo=dHA=aHQ=")
        private var mainServerAPI = base64DecodeAPI("cA==YXA=bC4=Y2U=ZXI=LnY=aWU=b3Y=LW0=cmE=c28=Ly8=czo=dHA=aHQ=")
        const val twoEmbedAPI = "https://www.2embed.to"
        const val vidSrcAPI = "https://v2.vidsrc.me"
        const val dbgoAPI = "https://dbgo.fun"
        const val movie123API = "https://api.123movie.cc"
        const val movieHabAPI = "https://moviehab.com"
        const val databaseGdriveAPI = "https://databasegdriveplayer.co"
        const val hdMovieBoxAPI = "https://hdmoviebox.net"
        const val series9API = "https://series9.la"
        const val idlixAPI = "https://88.210.12.206"
        const val noverseAPI = "https://www.nollyverse.com"
        const val olgplyAPI = "https://olgply.xyz"
        const val uniqueStreamAPI = "https://uniquestream.net"
        const val filmxyAPI = "https://www.filmxy.vip"
        const val kimcartoonAPI = "https://kimcartoon.li"
        const val xMovieAPI = "https://xemovies.net"
        const val consumetFlixhqAPI = "https://api.consumet.org/movies/flixhq"
        const val consumetZoroAPI = "https://api.consumet.org/anime/zoro"
        const val kissKhAPI = "https://kisskh.me"
        const val lingAPI = "https://ling-online.net"
        const val uhdmoviesAPI = "https://uhdmovies.site"
        const val fwatayakoAPI = "https://5100.svetacdn.in"
        const val gMoviesAPI = "https://gdrivemovies.xyz"

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
        "$tmdbAPI/trending/all/day?api_key=$apiKey&region=&page=" to "Trending",
        "$tmdbAPI/movie/popular?api_key=$apiKey&region=&page=" to "Popular Movies",
        "$tmdbAPI/tv/popular?api_key=$apiKey&region=&page=" to "Popular TV Shows",
        "$tmdbAPI/tv/airing_today?api_key=$apiKey&region=&page=" to "Airing Today TV Shows",
        "$tmdbAPI/tv/on_the_air?api_key=$apiKey&region=&page=" to "On The Air TV Shows",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=213&page=" to "Netflix",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=1024&page=" to "Amazon",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=2739&page=" to "Disney+",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=453&page=" to "Hulu",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=2552&page=" to "Apple TV+",
        "$tmdbAPI/movie/top_rated?api_key=$apiKey&region=&page=" to "Top Rated Movies",
        "$tmdbAPI/tv/top_rated?api_key=$apiKey&region=&page=" to "Top Rated TV Shows",
        "$tmdbAPI/movie/upcoming?api_key=$apiKey&region=&page=" to "Upcoming Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=ko&page=" to "Korean Shows",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_keywords=210024|222243&page=" to "Anime",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_keywords=210024|222243&page=" to "Anime Movies",
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
//        checkMainServer()
        val type = if (request.data.contains("/movie")) "movie" else "tv"
        val home = app.get(request.data + page)
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

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        val mainResponse = app.get(
            "$tmdbAPI/search/multi?api_key=$apiKey&language=en-US&query=$query&page=1&include_adult=false",
            referer = "$mainAPI/"
        ).parsedSafe<Results>()?.results?.mapNotNull { media ->
            media.toSearchResponse()
        }
        if (mainResponse?.isNotEmpty() == true) searchResponse.addAll(mainResponse)

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<Data>(url)
        val type = getType(data.type)
        val resUrl = if(type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?api_key=$apiKey&append_to_response=credits,external_ids,videos,recommendations"
        } else {
            "$tmdbAPI/tv/${data.id}?api_key=$apiKey&append_to_response=credits,external_ids,videos,recommendations"
        }
        val res = app.get(resUrl).parsedSafe<MediaDetail>() ?: throw ErrorLoadingException("Invalid Json Response")

        val title = res.title ?: res.name ?: return null
        val poster = getOriImageUrl(res.posterPath)
        val bgPoster = getOriImageUrl(res.backdropPath)
        val orgTitle = res.originalTitle ?: res.originalName ?: return null
        val year = (res.releaseDate ?: res.firstAirDate)?.split("-")?.first()?.toIntOrNull()
        val rating = res.vote_average.toString().toRatingInt()
        val genres = res.genres?.mapNotNull { it.name }
        val show = if (genres?.contains("Animation") == true && res.original_language == "ja") "Anime" else "Series/Movies"

        val actors = res.credits?.cast?.mapNotNull { cast ->
            ActorData(
                Actor(
                    cast.name ?: cast.originalName ?: return@mapNotNull null,
                    getImageUrl(cast.profilePath)
                ),
                roleString = cast.character
            )
        } ?: return null
        val recommendations = res.recommendations?.results?.mapNotNull { media -> media.toSearchResponse() }

        val trailer = res.videos?.results?.map { "https://www.youtube.com/watch?v=${it.key}" }
            ?.randomOrNull()

        return if (type == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()
            val lastSeason = res.seasons?.lastOrNull()?.seasonNumber
            res.seasons?.apmap { season ->
                app.get("$tmdbAPI/${data.type}/${data.id}/season/${season.seasonNumber}?api_key=$apiKey")
                    .parsedSafe<MediaDetailEpisodes>()?.episodes?.map { eps ->
                        episodes.add(Episode(
                            LinkData(
                                data.id,
                                res.external_ids?.imdb_id,
                                data.type,
                                eps.seasonNumber,
                                eps.episodeNumber,
                                title = title,
                                year = season.airDate?.split("-")?.first()?.toIntOrNull(),
                                orgTitle = orgTitle,
                                show = show,
                                airedYear = year,
                                lastSeason = lastSeason
                            ).toJson(),
                            name = eps.name,
                            season = eps.seasonNumber,
                            episode = eps.episodeNumber,
                            posterUrl = getImageUrl(eps.stillPath),
                            rating = eps.voteAverage?.times(10)?.roundToInt(),
                            description = eps.overview
                        ).apply {
                            this.addDate(eps.airDate)
                        })
                    }
            }
            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.tags = genres
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
                    show = show,
                ).toJson(),
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.tags = genres
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
//        val query = if (res.type == "tv") {
//            "$mainServerAPI/tv-shows/${res.id}/season/${res.season}/episode/${res.episode}?_data=routes/tv-shows/\$tvId.season.\$seasonId.episode.\$episodeId"
//        } else {
//            "$mainServerAPI/movies/${res.id}/watch?_data=routes/movies/\$movieId.watch"
//        }
//        val referer = if (res.type == "tv") {
//            "$mainServerAPI/tv-shows/${res.id}/season/${res.season}/episode/${res.episode}"
//        } else {
//            "$mainServerAPI/movies/${res.id}/watch"
//        }

        argamap(
            {
                invokeSoraVIP(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
//            {
//                val json = app.get(
//                    query,
//                    referer = referer,
//                    headers = mapOf("User-Agent" to getRandomUserAgent())
//                ).parsedSafe<LoadLinks>()
//
//                json?.sources?.map { source ->
//                    callback.invoke(
//                        ExtractorLink(
//                            this.name,
//                            this.name,
//                            source.url ?: return@map null,
//                            "$mainServerAPI/",
//                            source.quality?.toIntOrNull() ?: Qualities.Unknown.value,
//                            isM3u8 = source.isM3U8,
//                            headers = mapOf("Origin" to mainServerAPI)
//                        )
//                    )
//                }
//                json?.subtitles?.map { sub ->
//                    subtitleCallback.invoke(
//                        SubtitleFile(
//                            getLanguage(sub.lang.toString()),
//                            sub.url ?: return@map null
//                        )
//                    )
//                }
//            },
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
                if (res.season != null && res.show == "Anime") invoZoro(
                    res.id,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeHDMovieBox(res.title, res.season, res.episode, callback)
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
                invoKisskh(res.title, res.season, res.episode, subtitleCallback, callback)
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
                invokeUhdmovies(
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
                invokeFwatayako(res.imdbId, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeGMovies(res.title, res.year, res.season, res.episode, subtitleCallback, callback)
            },
        )

        return true
    }

    private data class LinkData(
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
        val show: String? = null,
        val airedYear: Int? = null,
        val lastSeason: Int? = null,
    )

    data class Data(
        val id: Int? = null,
        val type: String? = null,
        val aniId: String? = null,
        val malId: Int? = null,
    )

    data class Subtitles(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("language") val language: String? = null,
    )

    data class Sources(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("isM3U8") val isM3U8: Boolean = true,
    )

    data class LoadLinks(
        @JsonProperty("sources") val sources: ArrayList<Sources>? = arrayListOf(),
        @JsonProperty("subtitles") val subtitles: ArrayList<Subtitles>? = arrayListOf(),
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
        @JsonProperty("vote_average") val vote_average: Any? = null,
        @JsonProperty("original_language") val original_language: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("genres") val genres: ArrayList<Genres>? = arrayListOf(),
        @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
        @JsonProperty("videos") val videos: ResultsTrailer? = null,
        @JsonProperty("external_ids") val external_ids: ExternalIds? = null,
        @JsonProperty("credits") val credits: Credits? = null,
        @JsonProperty("recommendations") val recommendations: ResultsRecommendations? = null,
    )

//    data class PageProps(
//        @JsonProperty("id") val id: String? = null,
//        @JsonProperty("imdb") val imdbId: String? = null,
//        @JsonProperty("result") val result: MediaDetail? = null,
//        @JsonProperty("recommandations") val recommandations: ArrayList<Media>? = arrayListOf(),
//        @JsonProperty("cast") val cast: ArrayList<Cast>? = arrayListOf(),
//    )

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
