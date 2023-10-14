package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty

data class FDMovieIFrame(
    val link: String,
    val quality: String,
    val size: String,
    val type: String,
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

data class GpressSources(
    @JsonProperty("src") val src: String,
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: Int? = null,
    @JsonProperty("max") val max: String,
)

data class UHDBackupUrl(
    @JsonProperty("url") val url: String? = null,
)

data class ResponseHash(
    @JsonProperty("embed_url") val embed_url: String,
    @JsonProperty("key") val key: String? = null,
    @JsonProperty("type") val type: String? = null,
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

data class Jump1Episodes(
    @JsonProperty("id") val id: Any? = null,
    @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
    @JsonProperty("videoId") val videoId: String? = null,
)

data class Jump1Season(
    @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
    @JsonProperty("id") val id: String? = null,
)

data class Jump1Movies(
    @JsonProperty("movies") val movies: ArrayList<Jump1Episodes>? = arrayListOf(),
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

data class ZShowEmbed(
    @JsonProperty("m") val meta: String? = null,
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

data class JikanExternal(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("url") val url: String? = null,
)

data class JikanData(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("external") val external: ArrayList<JikanExternal>? = arrayListOf(),
)

data class JikanResponse(
    @JsonProperty("data") val data: JikanData? = null,
)

data class WatchOnlineResults(
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: String? = null,
)


data class WatchOnlineSearch(
    @JsonProperty("result") val result: ArrayList<WatchOnlineResults>? = arrayListOf(),
)

data class WatchOnlineSubtitles(
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("file") val file: Any? = null,
)

data class WatchOnlineResponse(
    @JsonProperty("streams") val streams: HashMap<String, String>? = null,
    @JsonProperty("subtitles") val subtitles: ArrayList<WatchOnlineSubtitles>? = arrayListOf(),
)

data class FmoviesSubtitles(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("file") val file: String? = null,
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

data class BlackvidSubtitles(
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("url") val url: String? = null,
)

data class BlackvidSource(
    @JsonProperty("quality") var quality: String? = null,
    @JsonProperty("url") var url: String? = null,
)

data class BlackvidSources(
    @JsonProperty("label") var label: String? = null,
    @JsonProperty("sources") var sources: ArrayList<BlackvidSource> = arrayListOf()
)

data class BlackvidResponses(
    @JsonProperty("sources") var sources: ArrayList<BlackvidSources> = arrayListOf(),
    @JsonProperty("subtitles") var subtitles: ArrayList<BlackvidSubtitles> = arrayListOf()
)

data class ShowflixResultsMovies(
    @JsonProperty("movieName") val movieName: String? = null,
    @JsonProperty("streamwish") val streamwish: String? = null,
    @JsonProperty("filelions") val filelions: String? = null,
    @JsonProperty("streamruby") val streamruby: String? = null,
)

data class ShowflixResultsSeries(
    @JsonProperty("seriesName") val seriesName: String? = null,
    @JsonProperty("streamwish") val streamwish: HashMap<String, List<String>>? = hashMapOf(),
    @JsonProperty("filelions") val filelions: HashMap<String, List<String>>? = hashMapOf(),
    @JsonProperty("streamruby") val streamruby: HashMap<String, List<String>>? = hashMapOf(),
)

data class ShowflixSearchMovies(
    @JsonProperty("results") val resultsMovies: ArrayList<ShowflixResultsMovies>? = arrayListOf(),
)

data class ShowflixSearchSeries(
    @JsonProperty("results") val resultsSeries: ArrayList<ShowflixResultsSeries>? = arrayListOf(),
)

data class SFMoviesSeriess(
    @JsonProperty("title") var title: String? = null,
    @JsonProperty("svideos") var svideos: String? = null,
)

data class SFMoviesAttributes(
    @JsonProperty("title") var title: String? = null,
    @JsonProperty("video") var video: String? = null,
    @JsonProperty("releaseDate") var releaseDate: String? = null,
    @JsonProperty("seriess") var seriess: ArrayList<ArrayList<SFMoviesSeriess>>? = arrayListOf(),
    @JsonProperty("contentId") var contentId: String? = null,
)

data class SFMoviesData(
    @JsonProperty("id") var id: Int? = null,
    @JsonProperty("attributes") var attributes: SFMoviesAttributes? = SFMoviesAttributes()
)

data class SFMoviesSearch(
    @JsonProperty("data") var data: ArrayList<SFMoviesData>? = arrayListOf(),
)

data class VaticSrtfiles(
    @JsonProperty("caption") var caption: String? = null,
    @JsonProperty("url") var url: String? = null,
)

data class VaticQualities(
    @JsonProperty("path") var path: String? = null,
    @JsonProperty("quality") var quality: String? = null,
)

data class VaticSources(
    @JsonProperty("Qualities") var qualities: ArrayList<VaticQualities> = arrayListOf(),
    @JsonProperty("Srtfiles") var srtfiles: ArrayList<VaticSrtfiles> = arrayListOf(),
)