package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.hexated.RandomUserAgent.getRandomUserAgent
import com.hexated.SoraExtractor.invoke123Movie
import com.hexated.SoraExtractor.invokeDatabaseGdrive
import com.hexated.SoraExtractor.invokeDbgo
import com.hexated.SoraExtractor.invokeGogo
import com.hexated.SoraExtractor.invokeHDMovieBox
import com.hexated.SoraExtractor.invokeIdlix
import com.hexated.SoraExtractor.invokeLocalSources
import com.hexated.SoraExtractor.invokeMovieHab
import com.hexated.SoraExtractor.invokeNoverse
import com.hexated.SoraExtractor.invokeOlgply
import com.hexated.SoraExtractor.invokeSeries9
import com.hexated.SoraExtractor.invokeSoraVIP
import com.hexated.SoraExtractor.invokeTwoEmbed
import com.hexated.SoraExtractor.invokeUniqueStream
import com.hexated.SoraExtractor.invokeVidSrc
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlin.math.roundToInt

open class SoraStream : TmdbProvider() {
    override var name = "SoraStream"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val instantLinkLoading = true
    override val useMetaLoadResponse = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    /** AUTHOR : Hexated & Sora */
    companion object {
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        private const val apiKey = "b030404650f279792a8d3287232358e3" // PLEASE DON'T STEAL
        val mainAPI = base64DecodeAPI("cHA=LmE=ZWw=cmM=dmU=aC4=dGM=d2E=eHA=Ly8=czo=dHA=aHQ=")
        val mainServerAPI =
            base64DecodeAPI("cA==YXA=bC4=Y2U=ZXI=LnY=aWU=b3Y=LW0=cmE=c28=Ly8=czo=dHA=aHQ=")
        const val twoEmbedAPI = "https://www.2embed.to"
        const val vidSrcAPI = "https://v2.vidsrc.me"
        const val dbgoAPI = "https://dbgo.fun"
        const val movie123API = "https://api.123movie.cc"
        const val movieHabAPI = "https://moviehab.com"
        const val databaseGdriveAPI = "https://databasegdriveplayer.co"
        const val hdMovieBoxAPI = "https://hdmoviebox.net"
        const val series9API = "https://series9.la"
        const val idlixAPI = "https://109.234.36.69"
        const val noverseAPI = "https://www.nollyverse.com"
        const val olgplyAPI = "https://olgply.xyz"
        const val uniqueStreamAPI = "https://uniquestream.net"

        fun getType(t: String?): TvType {
            return when (t) {
                "movie" -> TvType.Movie
                else -> TvType.TvSeries
            }
        }

        fun getActorRole(t: String?): ActorRole {
            return when (t) {
                "Acting" -> ActorRole.Main
                else -> ActorRole.Background
            }
        }


        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Returning Series" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

        private fun base64DecodeAPI(api: String): String {
            return api.chunked(4).map { base64Decode(it) }.reversed().joinToString("")
        }
    }

    override val mainPage = mainPageOf(
        "$tmdbAPI/tv/airing_today?api_key=$apiKey&region=&page=" to "Airing Today TV Shows",
        "$tmdbAPI/movie/popular?api_key=$apiKey&region=&page=" to "Popular Movies",
        "$tmdbAPI/tv/popular?api_key=$apiKey&region=&page=" to "Popular TV Shows",
        "$tmdbAPI/tv/on_the_air?api_key=$apiKey&region=&page=" to "On The Air TV Shows",
        "$tmdbAPI/movie/top_rated?api_key=$apiKey&region=&page=" to "Top Rated Movies",
        "$tmdbAPI/tv/top_rated?api_key=$apiKey&region=&page=" to "Top Rated TV Shows",
        "$tmdbAPI/movie/upcoming?api_key=$apiKey&region=&page=" to "Upcoming Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_keywords=210024|222243&page=" to "Anime",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_keywords=210024|222243&page=" to "Anime Movies",
    )

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w500/$link" else link
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
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

    private fun Anime.toSearchResponse(): AnimeSearchResponse? {
        return newAnimeSearchResponse(
            title?.romaji ?: title?.english ?: title?.native ?: title?.userPreferred ?: return null,
            Data(aniId = id, malId = malId).toJson(),
            TvType.Anime
        ) {
            this.posterUrl = image
            addSub(totalEpisodes)
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
        if(mainResponse?.isNotEmpty() == true) searchResponse.addAll(mainResponse)

        val animeResponse =
            app.get("$mainServerAPI/search/anime/$query?_data=routes/search/anime/\$animeKeyword")
                .parsedSafe<SearchAnime>()?.searchResults?.results?.mapNotNull { anime -> anime.toSearchResponse() }
        if(animeResponse?.isNotEmpty() == true) searchResponse.addAll(animeResponse)

        return searchResponse
    }

    private suspend fun loadAnime(aniId: String? = null, malId: Int? = null): LoadResponse? {

        val res = app.get("$mainServerAPI/anime/$aniId/overview?_data=routes/anime/\$animeId")
            .parsedSafe<DetailAnimeResult>()?.detail ?: throw ErrorLoadingException()

        val episodes = res.episodes?.map { eps ->
            Episode(
                LinkData(aniId = aniId, animeId = eps.id).toJson(),
                name = eps.title,
                episode = eps.number
            )
        }

        return newAnimeLoadResponse(
            res.title?.romaji ?: res.title?.english ?: res.title?.native ?: res.title?.userPreferred
            ?: return null,
            "",
            TvType.Anime
        ) {
            posterUrl = res.image
            this.year = res.releaseDate
            plot = res.description
            this.tags = res.genres
            this.recommendations = res.recommendations?.mapNotNull { it.toSearchResponse() }
            addMalId(malId)
            addAniListId(aniId?.toIntOrNull())
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<Data>(url)

        if (data.aniId?.isNotEmpty() == true) {
            return loadAnime(data.aniId, data.malId)
        }

        val buildId =
            app.get("$mainAPI/").text.substringAfterLast("\"buildId\":\"").substringBefore("\",")
        val responses =
            app.get("$mainAPI/_next/data/$buildId/${data.type}/${data.id}.json?id=${data.id}")
                .parsedSafe<Detail>()?.pageProps
                ?: throw ErrorLoadingException("Invalid Json Response")
        val res = responses.result ?: return null
        val title = res.title ?: res.name ?: res.originalTitle ?: res.originalName ?: return null
        val year = (res.releaseDate ?: res.firstAirDate)?.split("-")?.first()?.toIntOrNull()
        val type = getType(data.type)
        val actors = responses.cast?.mapNotNull { cast ->
            ActorData(
                Actor(
                    cast.name ?: cast.originalName ?: return@mapNotNull null,
                    getImageUrl(cast.profilePath)
                ),
                getActorRole(cast.knownForDepartment)
            )
        } ?: return null
        val recommendations =
            responses.recommandations?.mapNotNull { media -> media.toSearchResponse() }

        val trailer =
            responses.result.videos?.results?.map { "https://www.youtube.com/watch?v=${it.key}" }
                ?.randomOrNull()

        return if (type == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()
            res.seasons?.apmap { season ->
                app.get("$tmdbAPI/${data.type}/${data.id}/season/${season.seasonNumber}?api_key=$apiKey")
                    .parsedSafe<MediaDetailEpisodes>()?.episodes?.map { eps ->
                        episodes.add(Episode(
                            LinkData(
                                data.id,
                                responses.imdbId,
                                data.type,
                                eps.seasonNumber,
                                eps.episodeNumber,
                                title = title,
                                year = year,
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
                this.posterUrl = getImageUrl(res.posterPath)
                this.year = year
                this.plot = res.overview
                this.tags = res.genres?.mapNotNull { it.name }
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
                    responses.imdbId,
                    data.type,
                    title = title,
                    year = year,
                ).toJson(),
            ) {
                this.posterUrl = getImageUrl(res.posterPath)
                this.year = year
                this.plot = res.overview
                this.tags = res.genres?.mapNotNull { it.name }
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
        val query = if (res.type == "tv") {
            "$mainServerAPI/tv-shows/${res.id}/season/${res.season}/episode/${res.episode}?_data=routes/tv-shows/\$tvId.season.\$seasonId.episode.\$episodeId"
        } else {
            "$mainServerAPI/movies/${res.id}/watch?_data=routes/movies/\$movieId.watch"
        }
        val referer = if (res.type == "tv") {
            "$mainServerAPI/tv-shows/${res.id}/season/${res.season}/episode/${res.episode}"
        } else {
            "$mainServerAPI/movies/${res.id}/watch"
        }

        argamap(
            {
                invokeSoraVIP(res.id, res.season, res.episode, subtitleCallback, callback)
            },
            {
                val json = app.get(
                    query,
                    referer = referer,
                    headers = mapOf("User-Agent" to getRandomUserAgent())
                ).parsedSafe<LoadLinks>()

                if (json?.sources.isNullOrEmpty()) {
                    invokeLocalSources(referer, subtitleCallback, callback)
                } else {
                    json?.sources?.map { source ->
                        callback.invoke(
                            ExtractorLink(
                                this.name,
                                this.name,
                                source.url ?: return@map null,
                                "$mainServerAPI/",
                                source.quality?.toIntOrNull() ?: Qualities.Unknown.value,
                                isM3u8 = source.isM3U8,
                                headers = mapOf("Origin" to mainServerAPI)
                            )
                        )
                    }
                    json?.subtitles?.map { sub ->
                        subtitleCallback.invoke(
                            SubtitleFile(
                                sub.lang.toString(),
                                sub.url ?: return@map null
                            )
                        )
                    }
                }
            },
            {
                invokeTwoEmbed(res.id, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeVidSrc(res.id, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeOlgply(res.id, res.season, res.episode, callback)
            },
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
                if (res.aniId?.isNotEmpty() == true) invokeGogo(res.aniId, res.animeId, callback)
            },
            {
                invokeHDMovieBox(res.title, res.season, res.episode, callback)
            },
            {
                invokeSeries9(res.title, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeIdlix(res.title, res.year, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeNoverse(res.title, res.season, res.episode, callback)
            },
            {
                invokeUniqueStream(res.title, res.year, res.season, res.episode, subtitleCallback, callback)
            }
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

    data class MediaDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("genres") val genres: ArrayList<Genres>? = arrayListOf(),
        @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
        @JsonProperty("videos") val videos: ResultsTrailer? = null,

        )

    data class PageProps(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("imdb") val imdbId: String? = null,
        @JsonProperty("result") val result: MediaDetail? = null,
        @JsonProperty("recommandations") val recommandations: ArrayList<Media>? = arrayListOf(),
        @JsonProperty("cast") val cast: ArrayList<Cast>? = arrayListOf(),
    )

    data class Detail(
        @JsonProperty("pageProps") val pageProps: PageProps? = null,
    )

    data class EmbedJson(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("sources") val sources: List<String?> = arrayListOf(),
        @JsonProperty("tracks") val tracks: List<String>? = null,
    )

    data class TitleAnime(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("native") val native: String? = null,
        @JsonProperty("userPreferred") val userPreferred: String? = null,
    )

    data class Anime(
        @JsonProperty("title") val title: TitleAnime? = null,
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("malId") val malId: Int? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
    )

    data class SearchResults(
        @JsonProperty("results") val results: ArrayList<Anime>? = null,
    )

    data class SearchAnime(
        @JsonProperty("searchResults") val searchResults: SearchResults? = null,
    )

    data class TrailerAnime(
        @JsonProperty("id") val id: String? = null,
    )

    data class EpisodesAnime(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("number") val number: Int? = null,
    )

    data class DetailAnime(
        @JsonProperty("title") val title: TitleAnime? = null,
        @JsonProperty("id") val aniId: String? = null,
        @JsonProperty("malId") val malId: Int? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("releaseDate") val releaseDate: Int? = null,
        @JsonProperty("rating") val rating: Int? = null,
        @JsonProperty("duration") val duration: Int? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("recommendations") val recommendations: ArrayList<Anime>? = arrayListOf(),
        @JsonProperty("episodes") val episodes: ArrayList<EpisodesAnime>? = arrayListOf(),
        @JsonProperty("genres") val genres: ArrayList<String>? = arrayListOf(),
        @JsonProperty("trailer") val trailer: TrailerAnime? = null,
    )

    data class DetailAnimeResult(
        @JsonProperty("detail") val detail: DetailAnime? = null,
    )

    data class SeasonsVip(
        @JsonProperty("air_date") val air_date: String? = null,
        @JsonProperty("season_number") val season_number: Int? = null,
    )

    data class DetailVip(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_title") val original_title: String? = null,
        @JsonProperty("original_name") val original_name: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("seasons") val seasons: ArrayList<SeasonsVip>? = arrayListOf(),
    )

    data class DetailVipResult(
        @JsonProperty("detail") val detail: DetailVip? = null,
    )

    data class Providers(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("provider") val provider: String? = null,
    )

    data class ProvidersResult(
        @JsonProperty("provider") val provider: ArrayList<Providers>? = arrayListOf(),
    )

    data class MovieHabData(
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("token") val token: String? = null,
    )

    data class MovieHabRes(
        @JsonProperty("data") val data: MovieHabData? = null,
    )

}
