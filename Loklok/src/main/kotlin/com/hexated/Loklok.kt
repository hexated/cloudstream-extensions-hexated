package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class Loklok : MainAPI() {
    override var name = "Loklok"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val instantLinkLoading = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
    )
    companion object {
        private const val geoblockError = "Loklok is Geoblock, use vpn or give up"
        private val api = base64DecodeAPI("dg==LnQ=b2s=a2w=bG8=aS4=YXA=ZS0=aWw=b2I=LW0=Z2E=Ly8=czo=dHA=aHQ=")
        private val apiUrl = "$api/${base64Decode("Y21zL2FwcA==")}"
        private val pcApiUrl = base64DecodeAPI("cGM=Yi8=d2U=cy8=Y20=di8=LnQ=b2s=a2w=bG8=aS4=YXA=ZS0=aWw=b2I=LW0=Z2E=Ly8=czo=dHA=aHQ=")
        private val searchApi = base64Decode("aHR0cHM6Ly9sb2tsb2suY29t")
        private const val mainImageUrl = "https://images.weserv.nl"
        private val headers = mutableMapOf(
            "lang" to "en",
            "versioncode" to "999999999",
            "clienttype" to "ios17",
            "deviceid" to getDeviceId(),
        )

        private fun base64DecodeAPI(api: String): String {
            return api.chunked(4).map { base64Decode(it) }.reversed().joinToString("")
        }

    }

    private fun encode(input: String): String =
        java.net.URLEncoder.encode(input, "utf-8").replace("+", "%20")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = ArrayList<HomePageList>()
        for (i in 0..6) {
            app.get("$apiUrl/homePage/getHome?page=$i", headers = headers)
                .parsedSafe<Home>()?.data?.recommendItems.orEmpty().ifEmpty { throw ErrorLoadingException(geoblockError) }
                .filterNot { it.homeSectionType == "BLOCK_GROUP" }
                .filterNot { it.homeSectionType == "BANNER" }
                .mapNotNull { res ->
                    val header = res.homeSectionName ?: return@mapNotNull null
                    val media = res.media?.mapNotNull { media -> media.toSearchResponse() }
                        .orEmpty().ifEmpty { throw ErrorLoadingException(geoblockError) }
                    home.add(HomePageList(header, media))
                }
        }
        return HomePageResponse(home)
    }

    private fun Media.toSearchResponse(): SearchResponse? {

        return newMovieSearchResponse(
            title ?: name ?: return null,
            UrlData(id, category ?: domainType).toJson(),
            TvType.Movie,
        ) {
            this.posterUrl = (imageUrl ?: coverVerticalUrl)?.let {
                "$mainImageUrl/?url=${encode(it)}&w=175&h=246&fit=cover&output=webp"
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val body = mapOf(
            "searchKeyWord" to query,
            "size" to "50",
            "sort" to "",
            "searchType" to "",
        ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

        return app.post(
            "$apiUrl/search/v1/searchWithKeyWord",
            requestBody = body,
            headers = headers
        ).parsedSafe<QuickSearchRes>()?.data?.searchResults?.mapNotNull { media ->
            media.toSearchResponse()
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? = quickSearch(query)

//    override suspend fun search(query: String): List<SearchResponse> {
//        val res = app.get(
//            "$searchApi/search?keyword=$query",
//        ).document
//
//        val script = res.select("script").find { it.data().contains("function(a,b,c,d,e") }?.data()
//            ?.substringAfter("searchResults:[")?.substringBefore("]}],fetch")
//
//        return res.select("div.search-list div.search-video-card").mapIndexed { num, block ->
//            val name = block.selectFirst("h2.title")?.text()
//            val data = block.selectFirst("a")?.attr("href")?.split("/")
//            val id = data?.last()
//            val type = data?.get(2)?.toInt()
//            val image = Regex("coverVerticalUrl:\"(.*?)\",").findAll(script.toString())
//                .map { it.groupValues[1] }.toList().getOrNull(num)?.replace("\\u002F", "/")
//
//
//            newMovieSearchResponse(
//                "$name",
//                UrlData(id, type).toJson(),
//                TvType.Movie,
//            ) {
//                this.posterUrl = image
//            }
//
//        }
//
//    }

    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<UrlData>(url)
        val res = app.get(
            "$apiUrl/movieDrama/get?id=${data.id}&category=${data.category}",
            headers = headers
        ).parsedSafe<Load>()?.data ?: throw ErrorLoadingException(geoblockError)

        val actors = res.starList?.mapNotNull {
            Actor(
                it.localName ?: return@mapNotNull null, it.image
            )
        }

        val episodes = res.episodeVo?.map { eps ->
            val definition = eps.definitionList?.map {
                Definition(
                    it.code,
                    it.description,
                )
            }
            val subtitling = eps.subtitlingList?.map {
                Subtitling(
                    it.languageAbbr,
                    it.language,
                    it.subtitlingUrl
                )
            }
            Episode(
                data = UrlEpisode(
                    data.id.toString(),
                    data.category,
                    eps.id,
                    definition,
                    subtitling
                ).toJson(),
                episode = eps.seriesNo
            )
        } ?: throw ErrorLoadingException("No Episode Found")
        val recommendations = res.likeList?.mapNotNull { rec ->
            rec.toSearchResponse()
        }

        val type = when {
            res.areaList?.firstOrNull()?.id == 44 && res.tagNameList?.contains("Anime") == true -> {
                TvType.Anime
            }
            data.category == 0 -> {
                TvType.Movie
            }
            else -> {
                TvType.TvSeries
            }
        }

        val animeType = if(type == TvType.Anime && data.category == 0) "movie" else "tv"
        val (malId, anilistId) = if (type == TvType.Anime) getTracker(
            res.name,
            animeType,
            res.year
        ) else Tracker()

        return newTvSeriesLoadResponse(
            res.name ?: return null,
            url,
            if(data.category == 0) TvType.Movie else type,
            episodes
        ) {
            this.posterUrl = res.coverVerticalUrl
            this.backgroundPosterUrl = res.coverHorizontalUrl
            this.year = res.year
            this.plot = res.introduction
            this.tags = res.tagNameList
            this.rating = res.score.toRatingInt()
            addActors(actors)
            addMalId(malId)
            addAniListId(anilistId?.toIntOrNull())
            this.recommendations = recommendations
        }

    }

    private fun getLanguage(str: String): String {
        return when (str) {
            "in_ID" -> "Indonesian"
            "pt" -> "Portuguese"
            else -> str.split("_").first().let {
                SubtitleHelper.fromTwoLettersToLanguage(it).toString()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = parseJson<UrlEpisode>(data)

        res.definitionList?.apmap { video ->
            val body =
                """[{"category":${res.category},"contentId":"${res.id}","episodeId":${res.epId},"definition":"${video.code}"}]""".toRequestBody(
                    RequestBodyTypes.JSON.toMediaTypeOrNull()
                )
            val json = app.get(
                "$apiUrl/media/previewInfo?category=${res.category}&contentId=${res.id}&episodeId=${res.epId}&definition=${video.code}",
                headers = headers,
            ).parsedSafe<PreviewResponse>()?.data
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    json?.mediaUrl ?: return@apmap null,
                    "",
                    getQuality(json.currentDefinition ?: ""),
                    isM3u8 = true,
                )
            )
        }

        res.subtitlingList?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    getLanguage(sub.languageAbbr ?: return@map),
                    sub.subtitlingUrl ?: return@map
                )
            )
        }

        return true
    }

    private fun getQuality(quality: String): Int {
        return when (quality) {
            "GROOT_FD" -> Qualities.P360.value
            "GROOT_LD" -> Qualities.P480.value
            "GROOT_SD" -> Qualities.P720.value
            "GROOT_HD" -> Qualities.P1080.value
            else -> Qualities.Unknown.value
        }
    }

    private suspend fun getTracker(title: String?, type: String?, year: Int?): Tracker {
        val res = app.get("https://consumet-instance.vercel.app/meta/anilist/$title")
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

    data class UrlData(
        val id: Any? = null,
        val category: Int? = null,
    )

    data class Subtitling(
        val languageAbbr: String? = null,
        val language: String? = null,
        val subtitlingUrl: String? = null,
    )

    data class Definition(
        val code: String? = null,
        val description: String? = null,
    )

    data class UrlEpisode(
        val id: String? = null,
        val category: Int? = null,
        val epId: Int? = null,
        val definitionList: List<Definition>? = arrayListOf(),
        val subtitlingList: List<Subtitling>? = arrayListOf(),
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
        @JsonProperty("results") val results: java.util.ArrayList<Results>? = arrayListOf(),
    )

    data class QuickSearchData(
        @JsonProperty("searchResults") val searchResults: ArrayList<Media>? = arrayListOf(),
    )

    data class QuickSearchRes(
        @JsonProperty("data") val data: QuickSearchData? = null,
    )

    data class PreviewResponse(
        @JsonProperty("data") val data: PreviewVideos? = null,
    )

    data class PreviewVideos(
        @JsonProperty("mediaUrl") val mediaUrl: String? = null,
        @JsonProperty("currentDefinition") val currentDefinition: String? = null,
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

    data class Region(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )

    data class StarList(
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("localName") val localName: String? = null,
    )

    data class MediaDetail(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("introduction") val introduction: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("category") val category: String? = null,
        @JsonProperty("coverVerticalUrl") val coverVerticalUrl: String? = null,
        @JsonProperty("coverHorizontalUrl") val coverHorizontalUrl: String? = null,
        @JsonProperty("score") val score: String? = null,
        @JsonProperty("starList") val starList: ArrayList<StarList>? = arrayListOf(),
        @JsonProperty("areaList") val areaList: ArrayList<Region>? = arrayListOf(),
        @JsonProperty("episodeVo") val episodeVo: ArrayList<EpisodeVo>? = arrayListOf(),
        @JsonProperty("likeList") val likeList: ArrayList<Media>? = arrayListOf(),
        @JsonProperty("tagNameList") val tagNameList: ArrayList<String>? = arrayListOf(),
    )

    data class Load(
        @JsonProperty("data") val data: MediaDetail? = null,
    )

    data class Media(
        @JsonProperty("id") val id: Any? = null,
        @JsonProperty("category") val category: Int? = null,
        @JsonProperty("domainType") val domainType: Int? = null,
        @JsonProperty("imageUrl") val imageUrl: String? = null,
        @JsonProperty("coverVerticalUrl") val coverVerticalUrl: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("jumpAddress") val jumpAddress: String? = null,
    )

    data class RecommendItems(
        @JsonProperty("homeSectionName") val homeSectionName: String? = null,
        @JsonProperty("homeSectionType") val homeSectionType: String? = null,
        @JsonProperty("recommendContentVOList") val media: ArrayList<Media>? = arrayListOf(),
    )

    data class Data(
        @JsonProperty("recommendItems") val recommendItems: ArrayList<RecommendItems>? = arrayListOf(),
    )

    data class Home(
        @JsonProperty("data") val data: Data? = null,
    )

}

fun getDeviceId(length: Int = 16): String {
    val allowedChars = ('a'..'f') + ('0'..'9')
    return (1..length)
        .map { allowedChars.random() }
        .joinToString("")
}

