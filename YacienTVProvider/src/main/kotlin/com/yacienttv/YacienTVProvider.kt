package com.yacientv

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element
import android.util.Log
import android.util.Base64
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.Qualities

class YacienTV : MainAPI() {
    override var lang = "ar"

    override var name = "YacienTV"
    override val usesWebView = false
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes =
        setOf(
            TvType.Live
        )
    private val yacienTVAPI = "http://ver3.yacinelive.com/api"
    private val mainkey = "c!xZj+N9&G@Ev@vw"

    fun decrypt(enc: String, headerstr: String): String {
        var key = mainkey + headerstr
        val decodedBytes = Base64.decode(enc, Base64.DEFAULT)
        val encString = String(decodedBytes)
        var result = ""
        for (i in encString.indices) {
            result += (encString[i].toInt() xor key[i % key.length].toInt()).toChar()
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
            val req = app.get(request.data)
            val response = req.document
            val theader = req.headers.get("t").toString()
            val responsebody = decrypt(response.select("body").text(), theader)

            val list = parseJson<Results>(responsebody)?.results?.mapNotNull { element ->
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

    fun Channel.toSearchResponse(type: String? = null): SearchResponse? {
        //Log.d("King", "Channel.toSearchResponse")
        val hr = LiveSearchResponse(
            name ?: return null,
            Data(id = id, name = name, posterUrl = logo).toJson(),
            this@YacienTV.name,
            TvType.Live,
            logo,
        )
        //Log.d("King", hr.toString())
        return hr
    }

    override suspend fun load(url: String): LoadResponse {
        //Log.d("King", "Load:" + url)
        val data = parseJson<LinksData>(url)
        Log.d("King", "Load:" + data)
        val loadret =  LiveStreamLoadResponse(
            name = data.name,
            url = data.id,
            dataUrl = data.id,
            apiName = this.name,
            posterUrl = data.posterUrl,
            type = TvType.Live,
            plot = "${data.name} live stream."
        )
        Log.d("King", loadret.toString())
        return loadret
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("King", "loadlinks:"+ data)

        val chaurl = "$yacienTVAPI/channel/$data"
        val req = app.get(chaurl)
        val response = req.document
        var theader = req.headers.get("t").toString()
        val responsebody = decrypt(response.select("body").text(), theader)

        val channel = parseJson<ChannelResults>(responsebody)?.links?.mapNotNull { element ->
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
        } ?: throw ErrorLoadingException("Invalid Json reponse")
        return true
    }
}