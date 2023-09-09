package com.yacientv

import android.util.Base64
import android.util.Log
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
    private val mainKey = "YyF4WmorTjkmR0BFdkB2dw=="

    private fun decrypt(enc: String, headerStr: String): String {
        val key = Base64.decode(mainKey, Base64.DEFAULT).toString(charset("UTF-8")) + headerStr
        val decodedBytes = Base64.decode(enc, Base64.DEFAULT)
        val encString = decodedBytes.toString(charset("UTF-8"))
        var result = ""
        for (i in encString.indices) {
            result += (encString[i].code xor key[i % key.length].code).toChar()
        }
        return result
    }

    override val mainPage = mainPageOf(
        "$yacienTVAPI/events" to "Live Events",
        "$yacienTVAPI/categories/4/channels" to "beIN SPORTS (1080P)",
        "$yacienTVAPI/categories/5/channels" to "beIN SPORTS (720P)",
        "$yacienTVAPI/categories/6/channels" to "beIN SPORTS (360P)",
        "$yacienTVAPI/categories/7/channels" to "beIN SPORTS (244P)",
        "$yacienTVAPI/categories/8/channels" to "beIN ENTERTAINMENT",
        "$yacienTVAPI/categories/86/channels" to "SSC SPORTS",
        "$yacienTVAPI/categories/9" to "ARABIC CHANNELS",
        "$yacienTVAPI/categories/10/channels" to "OSN CHANNELS",
        "$yacienTVAPI/categories/11/channels" to "MBC CHANNELS",
        "$yacienTVAPI/categories/12/channels" to "FRANCE CHANNELS",
        "$yacienTVAPI/categories/88/channels" to "TURKISH CHANNELS",
        "$yacienTVAPI/categories/13/channels" to "KIDS CHANNELS",
        "$yacienTVAPI/categories/87/channels" to "WEYYAK",
        "$yacienTVAPI/categories/94/channels" to "SHAHID VIP",
    )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        if (page <= 1) {
            //Log.d("King", "request:$request")
            val decodedBody = getDecoded(request.data)
            Log.d("King", "decodedBody:$decodedBody")

            if (request.name == "Live Events") {
                val parsedData = parseJson<EventsResults>(decodedBody).results
                Log.d("King", "parsedEventData:$parsedData")
                val list = parsedData?.mapNotNull { element ->
                    element.toSearchResponse(request)
                } ?: throw ErrorLoadingException("Invalid Json response")
                if (list.isNotEmpty()) items.add(HomePageList(request.name, list, true))

            } else {
                val parsedData = parseJson<Results>(decodedBody).results
                val list = parsedData?.mapNotNull { element ->
                    element.toSearchResponse(request)
                } ?: throw ErrorLoadingException("Invalid Json response")
                if (list.isNotEmpty()) items.add(HomePageList(request.name, list, true))
            }
        }
        return newHomePageResponse(items)
    }
    private fun Channel.toSearchResponse(request: MainPageRequest, type: String? = null): SearchResponse? {
        return LiveSearchResponse(
            name ?: return null,
            Data(id = id, name = name, posterUrl = logo, category = request.name).toJson(),
            this@YacienTV.name,
            TvType.Live,
            logo,
        )
    }
    private fun Event.toSearchResponse(request: MainPageRequest, type: String? = null): SearchResponse? {
        Log.d("King", "SearchResp${request}")

        return LiveSearchResponse(
            name = "${team_1["name"].toString()} vs ${team_2["name"].toString()}" ,
            LinksData(
                id = id,
                start_time = start_time,
                end_time = end_time,
                champions = champions,
                channel = channel,
                team_1 = mapOf(
                    "name" to team_1["name"].toString(),
                    "logo" to team_1["logo"].toString()
                ),
                team_2 = mapOf(
                    "name" to team_2["name"].toString(),
                    "logo" to team_2["logo"].toString()
                ),
                category = request.name,
                name = "${team_1["name"].toString()} vs ${team_2["name"].toString()}",
                commentary = commentary,
            ).toJson(),
            this@YacienTV.name,
            type = TvType.Live,
        )
    }
    override suspend fun load(url: String): LoadResponse {
        Log.d("King", "Load:$url")

        val data = parseJson<LinksData>(url)

        if (data.category == "ARABIC CHANNELS") {
            val decodedBody = getDecoded("$yacienTVAPI/categories/${data.id}/channels")
            //Log.d("King", "ArabicDecodedBody:decodedBody")

            val channels = parseJson<Results>(decodedBody).results?.map { element ->
                Episode(
                    name = element.name,
                    posterUrl = element.logo,
                    data = element.id.toString(),
                )
            } ?: throw ErrorLoadingException("Invalid Json response")

            return newTvSeriesLoadResponse(
                name = data.name.toString(),
                url = Data(
                    id = data.id,
                    name = data.name,
                    posterUrl = data.posterUrl,
                    category = data.category,
                ).toJson(),
                type = TvType.TvSeries,
                episodes = channels,
            ) {
                this.posterUrl = data.posterUrl
                this.plot = "${data.name} livestreams of ${data.category} category."
            }
        }
        var plotStr = ""

        if (data.category == "Live Events") {
            plotStr = "Teams: ${data.name}" +
                    "<br>Time: ${data.start_time}" +
                    "<br>Commentary: ${data.commentary}" +
                    "<br>Channel: ${data.channel}"
        } else {
            plotStr = "${data.name} channel livestream of ${data.category} category."
        }

        return LiveStreamLoadResponse(
            name = data.name.toString(),
            url = Data(id = data.id, name = data.name, posterUrl = data.posterUrl, category = data.category.toString()).toJson(),
            dataUrl = LinksData(id = data.id, name = data.name, posterUrl = data.posterUrl, category = data.category).toJson(),
            apiName = name,
            posterUrl = data.posterUrl,
            type = TvType.Live,
            plot = plotStr,
        )
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        Log.d("King", "loadLinks:$data")
        val linksData = parseJson<LinksData>(data)

        var decodedBody = ""
        if (linksData.category == "Live Events") {
            decodedBody = getDecoded("$yacienTVAPI/event/${linksData.id}")
        } else {
            decodedBody = getDecoded("$yacienTVAPI/channel/${linksData.id}")
        }

        parseJson<ChannelResults>(decodedBody).links?.map { element ->
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
    data class Team(
        @JsonProperty("name") val name: String,
        @JsonProperty("logo") val logo: String,
    )
    data class Event(
        @JsonProperty("id") val id: String,
        @JsonProperty("start_time") val start_time: String,
        @JsonProperty("end_time") val end_time: String,
        @JsonProperty("champions") val champions: String,
        @JsonProperty("commentary") val commentary: String,
        @JsonProperty("channel") val channel: String,
        @JsonProperty("team_1") val team_1: Map<String, String> = mapOf(),
        @JsonProperty("team_2") val team_2: Map<String, String> = mapOf(),
    )
    data class EventsResults(
        @JsonProperty("data") val results: ArrayList<Event>? = arrayListOf(),
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
    data class Data(
        val id: String? = null,
        val name: String? = null,
        val posterUrl: String? = null,
        val category: String,
    )
    data class LinksData(
        val id: String? = null,
        val name: String? = null,
        val posterUrl: String? = null,
        val category: String? = null,
        val start_time: String? = null,
        val end_time: String? = null,
        val champions: String? = null,
        val channel: String? = null,
        val commentary: String? = null,
        val team_1: Map<String, String>? = null,
        val team_2: Map<String, String>? = null,
    )
}