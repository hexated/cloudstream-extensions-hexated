package com.yacientv

import android.util.Base64
//import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.requestCreator

class YacienTV : MainAPI() {
    override var lang = "ar"

    override var name = "Yacien TV"
    override val usesWebView = false
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes =
        setOf(
            TvType.Live
        )
    private val yacienTVAPI = "http://ver3.yacinelive.com/api"
    private val mainkey = "c!xZj+N9&G@Ev@vw"

    private fun decrypt(enc: String, headerstr: String): String {
        val key = mainkey + headerstr
        val decodedBytes = Base64.decode(enc, Base64.DEFAULT)
        val encString = decodedBytes.toString(charset("UTF-8"))
        var result = ""
        for (i in encString.indices) {
            result += (encString[i].code xor key[i % key.length].code).toChar()
        }
        return result
    }

    override val mainPage = mainPageOf(
        "$yacienTVAPI/categories/4/channels" to "beIN SPORTS (1080P)",
        "$yacienTVAPI/categories/5/channels" to "beIN SPORTS (720P)",
        "$yacienTVAPI/categories/6/channels" to "beIN SPORTS (360P)",
        "$yacienTVAPI/categories/7/channels" to "beIN SPORTS (244P)",
        "$yacienTVAPI/categories/8/channels" to "beIN ENTERTAINMENT",
        "$yacienTVAPI/categories/86/channels" to "SSC SPORTS",
        //"$yacienTVAPI/categories/9/channels" to "ARABIC CHANNELS",
        "$yacienTVAPI/categories/10/channels" to "OSN CHANNELS",
        "$yacienTVAPI/categories/11/channels" to "MBC CHANNELS",
        "$yacienTVAPI/categories/12/channels" to "FRANCE CHANNELS",
        "$yacienTVAPI/categories/88/channels" to "TURKISH CHANNELS",
        "$yacienTVAPI/categories/13/channels" to "KIDS CHANNELS",
        "$yacienTVAPI/categories/87/channels" to "WEYYAK",
        "$yacienTVAPI/categories/94/channels" to "SHAHID VIP",
    )

    data class Channel(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("logo") val logo: String? = null,
    )

    data class Results(
        @JsonProperty("data") val results: ArrayList<Channel>? = arrayListOf(),
    )

    data class ChannelResults(
        @JsonProperty("data") val links: ArrayList<Links>? = arrayListOf(),
    )

    data class Links(
        @JsonProperty("name") val name: String,
        @JsonProperty("url") val url: String,
        @JsonProperty("url_type") val urltype: String,
        @JsonProperty("user_agent") val useragent: String,
        @JsonProperty("referer") val referer: String,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        if (page <= 1) {

            val decodedbody = getDecoded(request.data)

            val list = parseJson<Results>(decodedbody).results?.mapNotNull { element ->
                element.toSearchResponse()
            } ?: throw ErrorLoadingException("Invalid Json reponse")
            if (list.isNotEmpty()) items.add(HomePageList(request.name, list, true))
        }
        return newHomePageResponse(items)
    }

    data class Data(
        val id: String? = null,
        val name: String,
        val posterUrl: String? = null,
    )

    data class LinksData(
        val id: String,
        val name: String,
        val posterUrl: String? = null,
    )

    private fun Channel.toSearchResponse(type: String? = null): SearchResponse? {
        return LiveSearchResponse(
            name ?: return null,
            Data(id = id, name = name, posterUrl = logo).toJson(),
            this@YacienTV.name,
            TvType.Live,
            logo,
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LinksData>(url)
        return LiveStreamLoadResponse(
            name = data.name,
            url = data.id,
            dataUrl = data.id,
            apiName = name,
            posterUrl = data.posterUrl,
            type = TvType.Live,
            plot = "${data.name} live stream."
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val decodedbody = getDecoded("$yacienTVAPI/channel/$data")

        parseJson<ChannelResults>(decodedbody).links?.map { element ->
            callback.invoke(
                ExtractorLink(
                    source = element.name,
                    name = element.name,
                    url = element.url,
                    referer = "",
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                )
            )
        } ?: throw ErrorLoadingException("Invalid Json response")
        return true
    }
    private fun getDecoded(url: String): String {
        val client = app.baseClient.newBuilder()
            .build()

        val request = requestCreator(
            method = "GET",
            url = url,
            headers = mapOf(
                "user-agent" to "okhttp/3.12.8",
                "Accept" to "application/json",
                "Cache-Control" to "no-cache",
            ),
        )
        val req = client.newCall(request).execute()

        return decrypt(req.body.string(), req.headers["t"].toString())
    }

}