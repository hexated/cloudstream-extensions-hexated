package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty

data class FDMovieIFrame(
    val link: String,
    val quality: String,
    val size: String,
    val type: String,
)

data class BaymoviesConfig(
    val country: String,
    val downloadTime: String,
    val workers: List<String>
)

data class AniIds(
    var id: Int? = null,
    var idMal: Int? = null
)

data class AniMedia(
    @JsonProperty("id") var id: Int? = null,
    @JsonProperty("idMal") var idMal: Int? = null
)

data class AniPage(
    @JsonProperty("media") var media: java.util.ArrayList<AniMedia> = arrayListOf()
)

data class AniData(
    @JsonProperty("Page") var Page: AniPage? = AniPage()
)

data class AniSearch(
    @JsonProperty("data") var data: AniData? = AniData()
)

data class Tmdb2Anilist(
    @JsonProperty("tmdb_id") val tmdb_id: String? = null,
    @JsonProperty("anilist_id") val anilist_id: String? = null,
    @JsonProperty("mal_id") val mal_id: String? = null,
)

data class Movie123Media(
    @JsonProperty("url") val url: String? = null,
)

data class Movie123Data(
    @JsonProperty("t") val t: String? = null,
    @JsonProperty("s") val s: String? = null,
)

data class Movie123Search(
    @JsonProperty("data") val data: ArrayList<Movie123Data>? = arrayListOf(),
)

data class GomoviesSources(
    @JsonProperty("src") val src: String,
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: Int? = null,
    @JsonProperty("max") val max: String,
    @JsonProperty("size") val size: String,
)

data class UHDBackupUrl(
    @JsonProperty("url") val url: String? = null,
)

data class MoviesbayValues(
    @JsonProperty("values") val values: List<List<String>>? = arrayListOf(),
)

data class HdMovieBoxTracks(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("file") val file: String? = null,
)

data class HdMovieBoxSource(
    @JsonProperty("videoUrl") val videoUrl: String? = null,
    @JsonProperty("videoServer") val videoServer: String? = null,
    @JsonProperty("videoDisk") val videoDisk: Any? = null,
    @JsonProperty("tracks") val tracks: ArrayList<HdMovieBoxTracks>? = arrayListOf(),
)

data class HdMovieBoxIframe(
    @JsonProperty("api_iframe") val apiIframe: String? = null,
)

data class ResponseHash(
    @JsonProperty("embed_url") val embed_url: String,
    @JsonProperty("type") val type: String?,
)

data class KisskhSources(
    @JsonProperty("Video") val video: String?,
    @JsonProperty("ThirdParty") val thirdParty: String?,
)

data class KisskhSubtitle(
    @JsonProperty("src") val src: String?,
    @JsonProperty("label") val label: String?,
)

data class KisskhEpisodes(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("number") val number: Int?,
)

data class KisskhDetail(
    @JsonProperty("episodes") val episodes: ArrayList<KisskhEpisodes>? = arrayListOf(),
)

data class KisskhResults(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("title") val title: String?,
)

data class EpisodesFwatayako(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("download") val download: HashMap<String, String>? = hashMapOf(),
)

data class SeasonFwatayako(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("folder") val folder: ArrayList<EpisodesFwatayako>? = arrayListOf(),
)

data class SourcesFwatayako(
    @JsonProperty("movie") val sourcesMovie: String? = null,
    @JsonProperty("tv") val sourcesTv: ArrayList<SeasonFwatayako>? = arrayListOf(),
    @JsonProperty("movie_dl") val movie_dl: HashMap<String, String>? = hashMapOf(),
    @JsonProperty("tv_dl") val tv_dl: ArrayList<SeasonFwatayako>? = arrayListOf(),
)

data class DriveBotLink(
    @JsonProperty("url") val url: String? = null,
)

data class DirectDl(
    @JsonProperty("download_url") val download_url: String? = null,
)

data class Safelink(
    @JsonProperty("safelink") val safelink: String? = null,
)

data class FDAds(
    @JsonProperty("linkr") val linkr: String? = null,
)

data class Smashy1Tracks(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null,
)

data class Smashy1Source(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("tracks") val tracks: ArrayList<Smashy1Tracks>? = arrayListOf(),
)

data class WatchsomuchTorrents(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("movieId") val movieId: Int? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
)

data class WatchsomuchMovies(
    @JsonProperty("torrents") val torrents: ArrayList<WatchsomuchTorrents>? = arrayListOf(),
)

data class WatchsomuchResponses(
    @JsonProperty("movie") val movie: WatchsomuchMovies? = null,
)

data class WatchsomuchSubtitles(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("label") val label: String? = null,
)

data class WatchsomuchSubResponses(
    @JsonProperty("subtitles") val subtitles: ArrayList<WatchsomuchSubtitles>? = arrayListOf(),
)

data class IndexMedia(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("driveId") val driveId: String? = null,
    @JsonProperty("mimeType") val mimeType: String? = null,
    @JsonProperty("size") val size: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("modifiedTime") val modifiedTime: String? = null,
)

data class IndexData(
    @JsonProperty("files") val files: ArrayList<IndexMedia>? = arrayListOf(),
)

data class IndexSearch(
    @JsonProperty("data") val data: IndexData? = null,
)

data class TgarMedia(
    @JsonProperty("_id") val _id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("size") val size: Double? = null,
    @JsonProperty("file_unique_id") val file_unique_id: String? = null,
    @JsonProperty("mime_type") val mime_type: String? = null,
)

data class TgarData(
    @JsonProperty("documents") val documents: ArrayList<TgarMedia>? = arrayListOf(),
)

data class SorastreamResponse(
    @JsonProperty("data") val data: SorastreamVideos? = null,
)

data class SorastreamVideos(
    @JsonProperty("mediaUrl") val mediaUrl: String? = null,
    @JsonProperty("currentDefinition") val currentDefinition: String? = null,
)

data class BiliBiliEpisodes(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("sourceId") val sourceId: String? = null,
    @JsonProperty("sourceEpisodeId") val sourceEpisodeId: String? = null,
    @JsonProperty("sourceMediaId") val sourceMediaId: String? = null,
    @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
)

data class BiliBiliDetails(
    @JsonProperty("episodes") val episodes: ArrayList<BiliBiliEpisodes>? = arrayListOf(),
)

data class BiliBiliSubtitles(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("language") val language: String? = null,
)

data class BiliBiliSources(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("type") val type: String? = null,
)

data class BiliBiliSourcesResponse(
    @JsonProperty("sources") val sources: ArrayList<BiliBiliSources>? = arrayListOf(),
    @JsonProperty("subtitles") val subtitles: ArrayList<BiliBiliSubtitles>? = arrayListOf(),
)

data class WatchOnlineItems(
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("tmdb_id") val tmdb_id: Int? = null,
    @JsonProperty("imdb_id") val imdb_id: String? = null,
)

data class WatchOnlineSearch(
    @JsonProperty("items") val items: ArrayList<WatchOnlineItems>? = arrayListOf(),
)

data class WatchOnlineResponse(
    @JsonProperty("streams") val streams: HashMap<String, String>? = null,
    @JsonProperty("subtitles") val subtitles: Any? = null,
)

data class PutlockerEpisodes(
    @JsonProperty("html") val html: String? = null,
)

data class PutlockerEmbed(
    @JsonProperty("src") val src: String? = null,
)

data class PutlockerSources(
    @JsonProperty("file") val file: String,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("type") val type: String? = null,
)

data class PutlockerResponses(
    @JsonProperty("sources") val sources: ArrayList<PutlockerSources>? = arrayListOf(),
    @JsonProperty("backupLink") val backupLink: String? = null,
)

data class ShivamhwSources(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("stream_link") val stream_link: String? = null,
    @JsonProperty("process_link") val process_link: String? = null,
    @JsonProperty("name") val name: String,
    @JsonProperty("size") val size: String,
)

data class CryMoviesProxyHeaders(
    @JsonProperty("request") val request: Map<String, String>?,
)

data class CryMoviesBehaviorHints(
    @JsonProperty("proxyHeaders") val proxyHeaders: CryMoviesProxyHeaders?,
)

data class CryMoviesStream(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("behaviorHints") val behaviorHints: CryMoviesBehaviorHints? = null,
)

data class CryMoviesResponse(
    @JsonProperty("streams") val streams: List<CryMoviesStream>? = null,
)

data class DudetvSources(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("title") val title: String? = null,
)

data class FmoviesResponses(
    @JsonProperty("result") val result: FmoviesResult? = null,
)

data class FmoviesResult(
    @JsonProperty("html") val html: String? = null,
    @JsonProperty("result") val result: String? = null,
    @JsonProperty("url") val url: String? = null,
)

data class FmoviesSubtitles(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("file") val file: String? = null,
)

data class VizcloudSources(
    @JsonProperty("file") val file: String? = null,
)

data class VizcloudMedia(
    @JsonProperty("sources") val sources: ArrayList<VizcloudSources>? = arrayListOf(),
)

data class VizcloudData(
    @JsonProperty("media") val media: VizcloudMedia? = null,
)

data class VizcloudResponses(
    @JsonProperty("data") val data: VizcloudData? = null,
)

data class AnilistExternalLinks(
    @JsonProperty("id") var id: Int? = null,
    @JsonProperty("site") var site: String? = null,
    @JsonProperty("url") var url: String? = null,
    @JsonProperty("type") var type: String? = null,
)

data class AnilistMedia(
    @JsonProperty("externalLinks") var externalLinks: ArrayList<AnilistExternalLinks> = arrayListOf()
)

data class AnilistData(
    @JsonProperty("Media") var Media: AnilistMedia? = AnilistMedia()
)

data class AnilistResponses(
    @JsonProperty("data") var data: AnilistData? = AnilistData()
)

data class CrunchyrollToken(
    @JsonProperty("access_token") val accessToken: String? = null,
    @JsonProperty("expires_in") val expiresIn: Int? = null,
    @JsonProperty("token_type") val tokenType: String? = null,
    @JsonProperty("scope") val scope: String? = null,
    @JsonProperty("country") val country: String? = null
)

data class CrunchyrollVersions(
    @JsonProperty("audio_locale") val audio_locale: String? = null,
    @JsonProperty("guid") val guid: String? = null,
)

data class CrunchyrollData(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("slug_title") val slug_title: String? = null,
    @JsonProperty("season_number") val season_number: Int? = null,
    @JsonProperty("episode_number") val episode_number: Int? = null,
    @JsonProperty("versions") val versions: ArrayList<CrunchyrollVersions>? = null,
    @JsonProperty("streams_link") val streams_link: String? = null,
    @JsonProperty("adaptive_hls") val adaptive_hls: HashMap<String, HashMap<String, String>>? = hashMapOf(),
    @JsonProperty("vo_adaptive_hls") val vo_adaptive_hls: HashMap<String, HashMap<String, String>>? = hashMapOf(),
)

data class CrunchyrollResponses(
    @JsonProperty("data") val data: ArrayList<CrunchyrollData>? = arrayListOf(),
)

data class CrunchyrollMeta(
    @JsonProperty("subtitles") val subtitles: HashMap<String, HashMap<String, String>>? = hashMapOf(),
)

data class CrunchyrollSourcesResponses(
    @JsonProperty("data") val data: ArrayList<CrunchyrollData>? = arrayListOf(),
    @JsonProperty("meta") val meta: CrunchyrollMeta? = null,
)

data class MALSyncSites(
    @JsonProperty("Zoro") val zoro: HashMap<String?, HashMap<String, String?>>? = hashMapOf(),
)

data class MALSyncResponses(
    @JsonProperty("Sites") val sites: MALSyncSites? = null,
)

data class AniwatchResponses(
    @JsonProperty("html") val html: String? = null,
    @JsonProperty("link") val link: String? = null,
)

data class MalSyncRes(
    @JsonProperty("Sites") val Sites: Map<String, Map<String, Map<String, String>>>? = null,
)

data class GokuData(
    @JsonProperty("link") val link: String? = null,
)

data class GokuServer(
    @JsonProperty("data") val data: GokuData? = GokuData(),
)

data class NavyEpisodeFolder(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("file") val file: String? = null,
)

data class NavySeasonFolder(
    @JsonProperty("episode") val episode: String? = null,
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("folder") val folder: ArrayList<NavyEpisodeFolder>? = arrayListOf(),
)

data class NavyServer(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("folder") val folder: ArrayList<NavySeasonFolder>? = arrayListOf(),
)

data class NavyPlaylist(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("key") val key: String? = null,
    @JsonProperty("href") val href: String? = null,
)

data class DumpMedia(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("domainType") val domainType: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("releaseTime") val releaseTime: String? = null,
)

data class DumpQuickSearchData(
    @JsonProperty("searchResults") val searchResults: ArrayList<DumpMedia>? = arrayListOf(),
)

data class SubtitlingList(
    @JsonProperty("languageAbbr") val languageAbbr: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("subtitlingUrl") val subtitlingUrl: String? = null,
)

data class DefinitionList(
    @JsonProperty("code") val code: String? = null,
    @JsonProperty("description") val description: String? = null,
)

data class EpisodeVo(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("seriesNo") val seriesNo: Int? = null,
    @JsonProperty("definitionList") val definitionList: ArrayList<DefinitionList>? = arrayListOf(),
    @JsonProperty("subtitlingList") val subtitlingList: ArrayList<SubtitlingList>? = arrayListOf(),
)

data class DumpMediaDetail(
    @JsonProperty("episodeVo") val episodeVo: ArrayList<EpisodeVo>? = arrayListOf(),
)

data class EMovieServer(
    @JsonProperty("value") val value: String? = null,
)

data class EMovieSources(
    @JsonProperty("file") val file: String? = null,
)

data class EMovieTraks(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null,
)

data class FourCartoonSources(
    @JsonProperty("videoSource") val videoSource: String? = null,
)
