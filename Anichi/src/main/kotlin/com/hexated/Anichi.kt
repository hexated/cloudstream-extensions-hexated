package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.getTracker
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.helper.GogoHelper
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI

class Anichi : MainAPI() {
    override var name = "Anichi"
    override val instantLinkLoading = true
    override val hasQuickSearch = false
    override val hasMainPage = true

    private fun getStatus(t: String): ShowStatus {
        return when (t) {
            "Finished" -> ShowStatus.Completed
            "Releasing" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    private fun getType(t: String?): TvType {
        return when {
            t.equals("OVA", true) || t.equals("Special") -> TvType.OVA
            t.equals("Movie", true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }
    }

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val popularTitle = "Popular"
    private val animeRecentTitle = "Latest Anime"
    private val donghuaRecentTitle = "Latest Donghua"
    private val movieTitle = "Movie"

    override val mainPage = mainPageOf(
        """$apiUrl?variables={"search":{"sortBy":"Latest_Update","allowAdult":${settingsForProvider.enableAdult},"allowUnknown":false},"limit":26,"page":%d,"translationType":"sub","countryOrigin":"JP"}&extensions={"persistedQuery":{"version":1,"sha256Hash":"$mainHash"}}""" to animeRecentTitle,
        """$apiUrl?variables={"search":{"sortBy":"Latest_Update","allowAdult":${settingsForProvider.enableAdult},"allowUnknown":false},"limit":26,"page":%d,"translationType":"sub","countryOrigin":"CN"}&extensions={"persistedQuery":{"version":1,"sha256Hash":"$mainHash"}}""" to donghuaRecentTitle,
        """$apiUrl?variables={"type":"anime","size":30,"dateRange":1,"page":%d,"allowAdult":${settingsForProvider.enableAdult},"allowUnknown":false}&extensions={"persistedQuery":{"version":1,"sha256Hash":"$popularHash"}}""" to popularTitle,
        """$apiUrl?variables={"search":{"slug":"movie-anime","format":"anime","tagType":"upcoming","name":"Trending Movies"}}&extensions={"persistedQuery":{"version":1,"sha256Hash":"$slugHash"}}""" to movieTitle
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val url = request.data.format(page)
        val res = app.get(url, headers = headers).parsedSafe<AnichiQuery>()?.data
        val query = res?.shows ?: res?.queryPopular ?: res?.queryListForTag
        val card = if(request.name == popularTitle) query?.recommendations?.map { it.anyCard } else query?.edges
        val home = card?.filter {
            // filtering in case there is an anime with 0 episodes available on the site.
            !(it?.availableEpisodes?.raw == 0 && it.availableEpisodes.sub == 0 && it.availableEpisodes.dub == 0)
        }?.mapNotNull { media ->
            media?.toSearchResponse()
        } ?: emptyList()
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
            ),
            hasNext = request.name != movieTitle
        )
    }

    private fun Edges.toSearchResponse(): AnimeSearchResponse? {

        return newAnimeSearchResponse(
            name ?: englishName ?: nativeName ?: "",
            Id ?: return null,
            fix = false
        ) {
            this.posterUrl = thumbnail
            this.year = airedStart?.year
            this.otherName = englishName
            addDub(availableEpisodes?.dub)
            addSub(availableEpisodes?.sub)
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {

        val link =
            """$apiUrl?variables={"search":{"allowAdult":false,"allowUnknown":false,"query":"$query"},"limit":26,"page":1,"translationType":"sub","countryOrigin":"ALL"}&extensions={"persistedQuery":{"version":1,"sha256Hash":"$mainHash"}}"""
        val res = app.get(
            link,
            headers = headers
        ).text.takeUnless { it.contains("PERSISTED_QUERY_NOT_FOUND") }
        // Retries
            ?: app.get(
                link,
                headers = headers
            ).text.takeUnless { it.contains("PERSISTED_QUERY_NOT_FOUND") }
            ?: return emptyList()

        val response = parseJson<AnichiQuery>(res)

        val results = response.data?.shows?.edges?.filter {
            // filtering in case there is an anime with 0 episodes available on the site.
            !(it.availableEpisodes?.raw == 0 && it.availableEpisodes.sub == 0 && it.availableEpisodes.dub == 0)
        }

        return results?.map {
            newAnimeSearchResponse(it.name ?: "", "${it.Id}", fix = false) {
                this.posterUrl = it.thumbnail
                this.year = it.airedStart?.year
                this.otherName = it.englishName
                addDub(it.availableEpisodes?.dub)
                addSub(it.availableEpisodes?.sub)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {

        val id = url.substringAfterLast("/")
        // lazy to format
        val body = """
        {
            "query": "                        query(\n                      ${'$'}_id: String!\n                    ) {\n                      show(\n                        _id: ${'$'}_id\n                      ) {\n                          _id\n                          name\n                          description\n                          thumbnail\n                          thumbnails\n                          lastEpisodeInfo\n                          lastEpisodeDate       \n                          type\n                          genres\n                          score\n                          status\n                          season\n                          altNames  \n                          averageScore\n                          rating\n                          episodeCount\n                          episodeDuration\n                          broadcastInterval\n                          banner\n                          airedEnd\n                          airedStart \n                          studios\n                          characters\n                          availableEpisodesDetail\n                          availableEpisodes\n                          prevideos\n                          nameOnlyString\n                          relatedShows\n                          relatedMangas\n                          musics\n                          isAdult\n                          \n                          tags\n                          countryOfOrigin\n\n                          pageStatus{\n                            _id\n                            notes\n                            pageId\n                            showId\n                            \n                              # ranks:[Object]\n    views\n    likesCount\n    commentCount\n    dislikesCount\n    reviewCount\n    userScoreCount\n    userScoreTotalValue\n    userScoreAverValue\n    viewers{\n        firstViewers{\n          viewCount\n          lastWatchedDate\n        user{\n          _id\n          displayName\n          picture\n          # description\n          hideMe\n          # createdAt\n          # badges\n          brief\n        }\n      \n      }\n      recViewers{\n        viewCount\n          lastWatchedDate\n        user{\n          _id\n          displayName\n          picture\n          # description\n          hideMe\n          # createdAt\n          # badges\n          brief\n        }\n      \n      }\n      }\n\n                        }\n                      }\n                    }",
            "extensions": "{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"$detailHash\"}}",
            "variables": "{\"_id\":\"$id\"}"
        }
    """.trimIndent().trim().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

        val res = app.post(apiUrl, requestBody = body, headers = headers)
        val showData = res.parsedSafe<Detail>()?.data?.show ?: return null

        val title = showData.name
        val description = showData.description
        val poster = showData.thumbnail
        val type = getType(showData.type ?: "")

        val episodes = showData.availableEpisodes.let {
            if (it == null) return@let Pair(null, null)
            if (showData.Id == null) return@let Pair(null, null)

            Pair(if (it.sub != 0) ((1..it.sub).map { epNum ->
                Episode(
                    AnichiLoadData(showData.Id, "sub", epNum).toJson(), episode = epNum
                )
            }) else null, if (it.dub != 0) ((1..it.dub).map { epNum ->
                Episode(
                    AnichiLoadData(showData.Id, "dub", epNum).toJson(), episode = epNum
                )
            }) else null)
        }

        val characters = showData.characters?.map {
            val role = when (it.role) {
                "Main" -> ActorRole.Main
                "Supporting" -> ActorRole.Supporting
                "Background" -> ActorRole.Background
                else -> null
            }
            val name = it.name?.full ?: it.name?.native ?: ""
            val image = it.image?.large ?: it.image?.medium
            Pair(Actor(name, image), role)
        }

        val names = showData.altNames?.plus(title)?.filterNotNull() ?: emptyList()
        val trackers = getTracker(names, TrackerType.getTypes(type), showData.airedStart?.year)

        return newAnimeLoadResponse(title ?: "", url, TvType.Anime) {
            engName = showData.altNames?.firstOrNull()
            posterUrl = trackers?.image ?: poster
            backgroundPosterUrl = trackers?.cover ?: showData.banner
            rating = showData.averageScore?.times(100)
            tags = showData.genres
            year = showData.airedStart?.year
            duration = showData.episodeDuration?.div(60_000)
            addTrailer(showData.prevideos.filter { it.isNotBlank() }
                .map { "https://www.youtube.com/watch?v=$it" })

            addEpisodes(DubStatus.Subbed, episodes.first)
            addEpisodes(DubStatus.Dubbed, episodes.second)
            addActors(characters)
            //this.recommendations = recommendations

            showStatus = getStatus(showData.status.toString())
            addMalId(trackers?.malId)
            addAniListId(trackers?.aniId?.toIntOrNull())
            plot = description?.replace(Regex("""<(.*?)>"""), "")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val loadData = parseJson<AnichiLoadData>(data)

        val apiUrl =
            """$apiUrl?variables={"showId":"${loadData.hash}","translationType":"${loadData.dubStatus}","episodeString":"${loadData.episode}"}&extensions={"persistedQuery":{"version":1,"sha256Hash":"$serverHash"}}"""
        val apiResponse = app.get(apiUrl, headers = headers).parsed<LinksQuery>()

        apiResponse.data?.episode?.sourceUrls?.apmap { source ->
            safeApiCall {
                val link = fixSourceUrls(source.sourceUrl ?: return@safeApiCall, source.sourceName) ?: return@safeApiCall
                if (URI(link).isAbsolute || link.startsWith("//")) {
                    val fixedLink = if (link.startsWith("//")) "https:$link" else link
                    val host = link.getHost()

                    when {
                        fixedLink.contains(Regex("(?i)playtaku|gogo")) || source.sourceName == "Vid-mp4" -> {
                            invokeGogo(fixedLink, subtitleCallback, callback)
                        }
                        embedIsBlacklisted(fixedLink) -> {
                            loadExtractor(fixedLink, subtitleCallback, callback)
                        }
                        URI(fixedLink).path.contains(".m3u") -> {
                            getM3u8Qualities(fixedLink, serverUrl, host).forEach(callback)
                        }
                        else -> {
                            callback(
                                ExtractorLink(
                                    name,
                                    host,
                                    fixedLink,
                                    serverUrl,
                                    Qualities.P1080.value,
                                    false
                                )
                            )
                        }
                    }
                } else {
                    val fixedLink = link.fixUrlPath()
                    val links = app.get(fixedLink).parsedSafe<AnichiVideoApiResponse>()?.links
                        ?: emptyList()
                    links.forEach { server ->
                        val host = server.link.getHost()
                        when {
                            source.sourceName == "Default" -> {
                                if (server.resolutionStr == "SUB" || server.resolutionStr == "Alt vo_SUB") {
                                    getM3u8Qualities(
                                        server.link,
                                        "https://static.crunchyroll.com/",
                                        host,
                                    ).forEach(callback)
                                }
                            }
                            server.hls != null && server.hls -> {
                                getM3u8Qualities(
                                    server.link,
                                    "$apiEndPoint/player?uri=" + (if (URI(server.link).host.isNotEmpty()) server.link else apiEndPoint + URI(
                                        server.link
                                    ).path),
                                    host
                                ).forEach(callback)
                            }
                            else -> {
                                callback(
                                    ExtractorLink(
                                        host,
                                        host,
                                        server.link,
                                        "$apiEndPoint/player?uri=" + (if (URI(server.link).host.isNotEmpty()) server.link else apiEndPoint + URI(
                                            server.link
                                        ).path),
                                        server.resolutionStr.removeSuffix("p").toIntOrNull()
                                            ?: Qualities.P1080.value,
                                        false,
                                        isDash = server.resolutionStr == "Dash 1"
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    private val embedBlackList = listOf(
        "https://mp4upload.com/",
        "https://streamsb.net/",
        "https://dood.to/",
        "https://videobin.co/",
        "https://ok.ru",
        "https://streamlare.com",
        "streaming.php",
    )

    private fun embedIsBlacklisted(url: String): Boolean {
        embedBlackList.forEach {
            if (it.javaClass.name == "kotlin.text.Regex") {
                if ((it as Regex).matches(url)) {
                    return true
                }
            } else {
                if (url.contains(it)) {
                    return true
                }
            }
        }
        return false
    }

    private suspend fun getM3u8Qualities(
        m3u8Link: String,
        referer: String,
        qualityName: String,
    ): List<ExtractorLink> {
        return M3u8Helper.generateM3u8(
            this.name,
            m3u8Link,
            referer,
            name = qualityName
        )
    }

    private suspend fun invokeGogo(
        link: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val iframe = app.get(link)
        val iframeDoc = iframe.document
        argamap({
            iframeDoc.select(".list-server-items > .linkserver")
                .forEach { element ->
                    val status = element.attr("data-status") ?: return@forEach
                    if (status != "1") return@forEach
                    val extractorData = element.attr("data-video") ?: return@forEach
                    loadExtractor(extractorData, iframe.url, subtitleCallback, callback)
                }
        }, {
            val iv = "3134003223491201"
            val secretKey = "37911490979715163134003223491201"
            val secretDecryptKey = "54674138327930866480207815084989"
            GogoHelper.extractVidstream(
                iframe.url,
                "Gogoanime",
                callback,
                iv,
                secretKey,
                secretDecryptKey,
                isUsingAdaptiveKeys = false,
                isUsingAdaptiveData = true,
                iframeDocument = iframeDoc
            )
        })
    }

    private fun String.getHost(): String {
        return fixTitle(URI(this).host.substringBeforeLast(".").substringAfterLast("."))
    }

    private fun String.fixUrlPath() : String {
        return if(this.contains(".json?")) apiEndPoint + this else apiEndPoint + URI(this).path + ".json?" + URI(this).query
    }

    private fun fixSourceUrls(url: String, source: String?) : String? {
        return if(source == "Ak" || url.contains("/player/vitemb")) {
            tryParseJson<AkIframe>(base64Decode(url.substringAfter("=")))?.idUrl
        } else {
            url.replace(" ", "%20")
        }
    }

    companion object {
        private const val apiUrl = BuildConfig.ANICHI_API
        private const val serverUrl = BuildConfig.ANICHI_SERVER
        private const val apiEndPoint = BuildConfig.ANICHI_ENDPOINT

        private const val mainHash = "e42a4466d984b2c0a2cecae5dd13aa68867f634b16ee0f17b380047d14482406"
        private const val popularHash = "31a117653812a2547fd981632e8c99fa8bf8a75c4ef1a77a1567ef1741a7ab9c"
        private const val slugHash = "bf603205eb2533ca21d0324a11f623854d62ed838a27e1b3fcfb712ab98b03f4"
        private const val detailHash = "bb263f91e5bdd048c1c978f324613aeccdfe2cbc694a419466a31edb58c0cc0b"
        private const val serverHash = "5e7e17cdd0166af5a2d8f43133d9ce3ce9253d1fdb5160a0cfd515564f98d061"

        private val headers = mapOf(
            "app-version" to "android_c-247",
            "from-app" to BuildConfig.ANICHI_APP,
            "platformstr" to "android_c",
        )
    }

    data class AnichiLoadData(
        val hash: String,
        val dubStatus: String,
        val episode: Int
    )

    data class AkIframe(
        @JsonProperty("idUrl") val idUrl: String? = null,
    )

    data class Stream(
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("audio_lang") val audio_lang: String? = null,
        @JsonProperty("hardsub_lang") val hardsub_lang: String? = null,
        @JsonProperty("url") val url: String? = null,
    )

    data class PortData(
        @JsonProperty("streams") val streams: ArrayList<Stream>? = arrayListOf(),
    )

    data class Subtitles(
        @JsonProperty("lang") val lang: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("src") val src: String?,
    )

    data class Links(
        @JsonProperty("link") val link: String,
        @JsonProperty("hls") val hls: Boolean?,
        @JsonProperty("resolutionStr") val resolutionStr: String,
        @JsonProperty("src") val src: String?,
        @JsonProperty("portData") val portData: PortData? = null,
        @JsonProperty("subtitles") val subtitles: ArrayList<Subtitles>? = arrayListOf(),
    )

    data class AnichiVideoApiResponse(
        @JsonProperty("links") val links: List<Links>
    )

    data class Data(
        @JsonProperty("shows") val shows: Shows? = null,
        @JsonProperty("queryListForTag") val queryListForTag: Shows? = null,
        @JsonProperty("queryPopular") val queryPopular: Shows? = null,
    )

    data class Shows(
        @JsonProperty("edges") val edges: List<Edges>? = arrayListOf(),
        @JsonProperty("recommendations") val recommendations: List<EdgesCard>? = arrayListOf(),
    )

    data class EdgesCard(
        @JsonProperty("anyCard") val anyCard: Edges? = null,
    )

    data class CharacterImage(
        @JsonProperty("large") val large: String?,
        @JsonProperty("medium") val medium: String?
    )

    data class CharacterName(
        @JsonProperty("full") val full: String?,
        @JsonProperty("native") val native: String?
    )

    data class Characters(
        @JsonProperty("image") val image: CharacterImage?,
        @JsonProperty("role") val role: String?,
        @JsonProperty("name") val name: CharacterName?,
    )

    data class Edges(
        @JsonProperty("_id") val Id: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("englishName") val englishName: String?,
        @JsonProperty("nativeName") val nativeName: String?,
        @JsonProperty("thumbnail") val thumbnail: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("season") val season: Season?,
        @JsonProperty("score") val score: Double?,
        @JsonProperty("airedStart") val airedStart: AiredStart?,
        @JsonProperty("availableEpisodes") val availableEpisodes: AvailableEpisodes?,
        @JsonProperty("availableEpisodesDetail") val availableEpisodesDetail: AvailableEpisodesDetail?,
        @JsonProperty("studios") val studios: List<String>?,
        @JsonProperty("genres") val genres: List<String>?,
        @JsonProperty("averageScore") val averageScore: Int?,
        @JsonProperty("characters") val characters: List<Characters>?,
        @JsonProperty("altNames") val altNames: List<String>?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("status") val status: String?,
        @JsonProperty("banner") val banner: String?,
        @JsonProperty("episodeDuration") val episodeDuration: Int?,
        @JsonProperty("prevideos") val prevideos: List<String> = emptyList(),
    )

    data class AvailableEpisodes(
        @JsonProperty("sub") val sub: Int,
        @JsonProperty("dub") val dub: Int,
        @JsonProperty("raw") val raw: Int
    )

    data class AiredStart(
        @JsonProperty("year") val year: Int,
        @JsonProperty("month") val month: Int,
        @JsonProperty("date") val date: Int
    )

    data class Season(
        @JsonProperty("quarter") val quarter: String,
        @JsonProperty("year") val year: Int
    )

    data class AnichiQuery(
        @JsonProperty("data") val data: Data? = null
    )

    data class Detail(
        @JsonProperty("data") val data: DetailShow
    )

    data class DetailShow(
        @JsonProperty("show") val show: Edges
    )

    data class AvailableEpisodesDetail(
        @JsonProperty("sub") val sub: List<String>,
        @JsonProperty("dub") val dub: List<String>,
        @JsonProperty("raw") val raw: List<String>
    )

    data class LinksQuery(
        @JsonProperty("data") val data: LinkData? = LinkData()
    )

    data class LinkData(
        @JsonProperty("episode") val episode: Episode? = Episode()
    )

    data class SourceUrls(
        @JsonProperty("sourceUrl") val sourceUrl: String? = null,
        @JsonProperty("priority") val priority: Int? = null,
        @JsonProperty("sourceName") val sourceName: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("className") val className: String? = null,
        @JsonProperty("streamerId") val streamerId: String? = null
    )

    data class Episode(
        @JsonProperty("sourceUrls") val sourceUrls: ArrayList<SourceUrls> = arrayListOf(),
    )

    data class Sub(
        @JsonProperty("hour") val hour: Int? = null,
        @JsonProperty("minute") val minute: Int? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("month") val month: Int? = null,
        @JsonProperty("date") val date: Int? = null
    )

    data class LastEpisodeDate(
        @JsonProperty("dub") val dub: Sub? = Sub(),
        @JsonProperty("sub") val sub: Sub? = Sub(),
        @JsonProperty("raw") val raw: Sub? = Sub()
    )

    data class AnyCard(
        @JsonProperty("_id") val Id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("englishName") val englishName: String? = null,
        @JsonProperty("nativeName") val nativeName: String? = null,
        @JsonProperty("availableEpisodes") val availableEpisodes: AvailableEpisodes? = null,
        @JsonProperty("score") val score: Double? = null,
        @JsonProperty("lastEpisodeDate") val lastEpisodeDate: LastEpisodeDate? = LastEpisodeDate(),
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("lastChapterDate") val lastChapterDate: String? = null,
        @JsonProperty("availableChapters") val availableChapters: String? = null,
        @JsonProperty("__typename") val _typename: String? = null
    )

    data class PageStatus(
        @JsonProperty("_id") val Id: String? = null,
        @JsonProperty("views") val views: String? = null,
        @JsonProperty("showId") val showId: String? = null,
        @JsonProperty("rangeViews") val rangeViews: String? = null,
        @JsonProperty("isManga") val isManga: Boolean? = null,
        @JsonProperty("__typename") val _typename: String? = null
    )


    data class Recommendations(
        @JsonProperty("anyCard") val anyCard: AnyCard? = null,
        @JsonProperty("pageStatus") val pageStatus: PageStatus? = PageStatus(),
        @JsonProperty("__typename") val _typename: String? = null
    )

    data class QueryPopular(
        @JsonProperty("total") val total: Int? = null,
        @JsonProperty("recommendations") val recommendations: ArrayList<Recommendations> = arrayListOf(),
        @JsonProperty("__typename") val _typename: String? = null
    )

    data class DataPopular(
        @JsonProperty("queryPopular") val queryPopular: QueryPopular? = QueryPopular()
    )


}