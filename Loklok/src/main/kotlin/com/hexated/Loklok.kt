package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
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

    private val headers = mapOf(
        "lang" to "en",
        "versioncode" to "11",
        "clienttype" to "ios_jike_default"
    )

    // no license found
    // thanks to https://github.com/napthedev/filmhot for providing API
    companion object {
        private val api = base64DecodeAPI("dg==LnQ=b2s=a2w=bG8=aS4=YXA=ZS0=aWw=b2I=LW0=Z2E=Ly8=czo=dHA=aHQ=")
        private val apiUrl = "$api/${base64Decode("Y21zL2FwcA==")}"
        private val searchApi = base64Decode("aHR0cHM6Ly9sb2tsb2suY29t")
        private const val jikanAPI = "https://api.jikan.moe/v4"
        private const val mainImageUrl = "https://images.weserv.nl"

        private fun base64DecodeAPI(api: String): String {
            return api.chunked(4).map { base64Decode(it) }.reversed().joinToString("")
        }

    }

    private fun encode(input: String): String =
        java.net.URLEncoder.encode(input, "utf-8").replace("+", "%20")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = ArrayList<HomePageList>()
        for (i in 0..6) {
//            delay(500)
            app.get("$apiUrl/homePage/getHome?page=$i", headers = headers)
                .parsedSafe<Home>()?.data?.recommendItems
                ?.filterNot { it.homeSectionType == "BLOCK_GROUP" }
                ?.filterNot { it.homeSectionType == "BANNER" }
                ?.mapNotNull { res ->
                    val header = res.homeSectionName ?: return@mapNotNull null
                    val media = res.media?.mapNotNull { media -> media.toSearchResponse() }
                        ?: throw ErrorLoadingException("Invalid Json Reponse")
                    home.add(HomePageList(header, media))
                }
        }
        return HomePageResponse(home)
    }

    private fun Media.toSearchResponse(): SearchResponse? {

        return newMovieSearchResponse(
            title ?: name ?: return null,
            UrlData(id, category).toJson(),
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

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get(
            "$searchApi/search?keyword=$query",
        ).document

        val script = res.select("script").find { it.data().contains("function(a,b,c,d,e") }?.data()
            ?.substringAfter("searchResults:[")?.substringBefore("]}],fetch")

        return res.select("div.search-list div.search-video-card").mapIndexed { num, block ->
            val name = block.selectFirst("h2.title")?.text()
            val data = block.selectFirst("a")?.attr("href")?.split("/")
            val id = data?.last()
            val type = data?.get(2)?.toInt()
            val image = Regex("coverVerticalUrl:\"(.*?)\",").findAll(script.toString())
                .map { it.groupValues[1] }.toList().getOrNull(num)?.replace("\\u002F", "/")


            newMovieSearchResponse(
                "$name",
                UrlData(id, type).toJson(),
                TvType.Movie,
            ) {
                this.posterUrl = image
            }

        }

    }

    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<UrlData>(url)
        val res = app.get(
            "$apiUrl/movieDrama/get?id=${data.id}&category=${data.category}",
            headers = headers
        ).parsedSafe<Load>()?.data ?: throw ErrorLoadingException("Invalid Json Reponse")

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
            data.category == 0 -> {
                TvType.Movie
            }
            data.category != 0 && res.tagNameList?.contains("Anime") == true -> {
                TvType.Anime
            }
            else -> {
                TvType.TvSeries
            }
        }

        val animeType = if(type == TvType.Anime && data.category == 0) "movie" else "tv"

        val malId = if(type == TvType.Anime) {
            app.get("${jikanAPI}/anime?q=${res.name}&start_date=${res.year}&type=$animeType&order_by=start_date&limit=1")
                .parsedSafe<JikanResponse>()?.data?.firstOrNull()?.mal_id
        } else {
            null
        }
        val anilistId = if(malId != null) {
            app.post(
                "https://graphql.anilist.co/", data = mapOf(
                    "query" to "{Media(idMal:$malId,type:ANIME){id}}",
                )
            ).parsedSafe<DataAni>()?.data?.media?.id
        } else {
            null
        }

        return newTvSeriesLoadResponse(
            res.name ?: return null,
            url,
            type,
            episodes
        ) {
            this.posterUrl = res.coverVerticalUrl
            this.backgroundPosterUrl = res.coverHorizontalUrl
            this.year = res.year
            this.plot = res.introduction
            this.tags = res.tagNameList
            this.rating = res.score.toRatingInt()
            addMalId(malId?.toIntOrNull())
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
            val body = """[{"category":${res.category},"contentId":"${res.id}","episodeId":${res.epId},"definition":"${video.code}"}]""".toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
            val response = app.post(
                "$apiUrl/media/bathGetplayInfo",
                requestBody = body,
                headers = headers,
            ).text
            val json = tryParseJson<PreviewResponse>(response)?.data?.firstOrNull()
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

    data class QuickSearchData(
        @JsonProperty("searchResults") val searchResults: ArrayList<Media>? = arrayListOf(),
    )

    data class QuickSearchRes(
        @JsonProperty("data") val data: QuickSearchData? = null,
    )

    data class PreviewResponse(
        @JsonProperty("data") val data: ArrayList<PreviewVideos>? = arrayListOf(),
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

    data class MediaDetail(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("introduction") val introduction: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("category") val category: String? = null,
        @JsonProperty("coverVerticalUrl") val coverVerticalUrl: String? = null,
        @JsonProperty("coverHorizontalUrl") val coverHorizontalUrl: String? = null,
        @JsonProperty("score") val score: String? = null,
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

    data class DataMal(
        @JsonProperty("mal_id") val mal_id: String? = null,
    )

    data class JikanResponse(
        @JsonProperty("data") val data: ArrayList<DataMal>? = arrayListOf(),
    )

    private data class IdAni(
        @JsonProperty("id") val id: String? = null,
    )

    private data class MediaAni(
        @JsonProperty("Media") val media: IdAni? = null,
    )

    private data class DataAni(
        @JsonProperty("data") val data: MediaAni? = null,
    )

}

