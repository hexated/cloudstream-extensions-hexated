package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.coroutines.delay

class Loklok : MainAPI() {
    override var name = "Loklok"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val instantLinkLoading = true
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
        private val api = base64Decode("aHR0cHM6Ly9nYS1tb2JpbGUtYXBpLmxva2xvay50dg==")
        private val apiUrl = "$api/${base64Decode("Y21zL2FwcA==")}"
        private const val mainImageUrl = "https://images.weserv.nl"
    }

    private fun encode(input: String): String =
        java.net.URLEncoder.encode(input, "utf-8").replace("+", "%20")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = ArrayList<HomePageList>()
        for (i in 0..8) {
            delay(500)
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

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get(
            "https://loklok.com/search?keyword=$query",
        ).document

        val script = res.select("script").find { it.data().contains("function(a,b,c,d,e") }?.data()
            ?.substringAfter("searchResults:[")?.substringBefore("]}],fetch")

        return res.select("div.search-list div.search-video-card").mapIndexed { num, block ->
            val name = block.selectFirst("h2.title")?.text()
            val data = block.selectFirst("a")?.attr("href")?.split("/")
            val id = data?.last()
            val type = data?.get(2)?.toInt()
            val image = Regex("coverVerticalUrl:\"(\\S+?)\",").findAll(script.toString())
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

        return newTvSeriesLoadResponse(
            res.name ?: return null,
            url,
            if (data.category == 0) TvType.Movie else TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = res.coverVerticalUrl
            this.year = res.year
            this.plot = res.introduction
            this.tags = res.tagNameList
            this.rating = res.score.toRatingInt()
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
            safeApiCall {
                delay(500)
                app.get(
                    "$apiUrl/media/previewInfo?category=${res.category}&contentId=${res.id}&episodeId=${res.epId}&definition=${video.code}",
                    headers = headers
                ).parsedSafe<Video>()?.data.let { link ->
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            this.name,
                            link?.mediaUrl ?: return@let,
                            "",
                            getQualityFromName(video.description),
                            isM3u8 = true,
                            headers = headers
                        )
                    )
                }
            }
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

    data class VideoData(
        @JsonProperty("mediaUrl") val mediaUrl: String? = null,
    )

    data class Video(
        @JsonProperty("data") val data: VideoData? = null,
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
        @JsonProperty("score") val score: String? = null,
        @JsonProperty("episodeVo") val episodeVo: ArrayList<EpisodeVo>? = arrayListOf(),
        @JsonProperty("likeList") val likeList: ArrayList<Media>? = arrayListOf(),
        @JsonProperty("tagNameList") val tagNameList: ArrayList<String>? = arrayListOf(),
    )

    data class Load(
        @JsonProperty("data") val data: MediaDetail? = null,
    )

    data class MediaSearch(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("domainType") val domainType: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("coverVerticalUrl") val coverVerticalUrl: String? = null,
    )

    data class Result(
        @JsonProperty("result") val result: ArrayList<MediaSearch>? = arrayListOf(),
    )

    data class Search(
        @JsonProperty("pageProps") val pageProps: Result? = null,
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

}

