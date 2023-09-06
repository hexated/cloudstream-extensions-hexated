package com.elostoratv

import android.icu.util.Calendar
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.requestCreator

class ElOstoraTV : MainAPI() {
    override var lang = "ar"

    override var name = "El Ostora TV"
    override val usesWebView = false
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes =
        setOf(
            TvType.Live
        )
    private val APIurl = "https://z420572.radwan.shop/api/v4_8.php"

    //override val mainPage = generateHomePage()
    override val mainPage = generateServersHomePage()
    private fun generateServersHomePage() : List<MainPageData> {
        val homepage =  mutableListOf<MainPageData>()
        val data = mapOf(
            "main" to "1",
            "id" to "",
        )
        val decodedbody = getDecoded(data)
        //Log.d("King", "decodedbody:$decodedbody")

        parseJson<Categories>(decodedbody).results?.map { element ->
            homepage.add(mainPage(name = element.category_name, url = element.cid))
        } ?: throw ErrorLoadingException("Invalid Json response")
        //Log.d("King", "homepage:$homepage")
        return homepage
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        if (page <= 1) {
            //Log.d("King", "getMainPage:$request")
            val data = mapOf(
                "main_id" to request.data,
                "id" to "",
                "sub_id" to "0"
            )
            val decodedbody = getDecoded(data)
            //Log.d("King", "getMaindecodedbody:$decodedbody")
            val list = parseJson<Categories>(decodedbody).results?.map { element ->
                element.toSearchResponse(request)
            } ?: throw ErrorLoadingException("Invalid Json response")
            if (list.isNotEmpty()) items.add(HomePageList(request.name, list, false))
        }
        //Log.d("King", "items:$items")
        return newHomePageResponse(items)
    }
    private fun Category.toSearchResponse(request: MainPageRequest): SearchResponse {
        return LiveSearchResponse(
            name = this.category_name,
            url = Data(id = cid, channel_title = this.category_name, channel_thumbnail = this.image, category = request.name, channel_url = cid).toJson(),
            this@ElOstoraTV.name,
            TvType.Live,
            this.image,
        )
    }
    override suspend fun load(url: String): LoadResponse {
        Log.d("King", "Load:$url")
        val data = parseJson<Data>(url)

        val Postdata = mapOf(
            "id" to "",
            "cat_id" to data.id
        )
        val decodedbody = getDecoded(Postdata)
        Log.d("King", "decodedbody:$decodedbody")
        var channels = parseJson<Results>(decodedbody).results?.map { element ->
            Episode(
                name = element.channel_title,
                posterUrl = element.channel_thumbnail,
                data = Data(
                    id = element.id,
                    channel_title = element.channel_title,
                    channel_thumbnail = element.channel_thumbnail,
                    category = data.category,
                    channel_url = element.channel_url,
                ).toJson(),
            )
        } ?: throw ErrorLoadingException("Invalid Json response")
        if (data.category == "المسلسلات العربية" || data.category == "مسلسلات رمضان 2023" || data.category == "مسرحيات")
        {
            channels = channels.reversed()
        }
        return newTvSeriesLoadResponse(
            name = data.channel_title,
            url = Data(
                id = data.id,
                channel_title = data.channel_title,
                channel_thumbnail = data.channel_thumbnail,
                category = data.category,
                channel_url = data.channel_url,
            ).toJson(),
            type = TvType.TvSeries,
            episodes = channels,
        ) {
            this.posterUrl = data.channel_thumbnail
            this.plot = "${data.channel_title} - ${data.category}."
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("King", "loadLinks:$data")
        val data = parseJson<Data>(data)

        var fullUrl = fixUrl(data.channel_url)
        var key: String = ""
        var kid: String = ""
        val encrypted : Boolean = fullUrl.contains("###")

        Log.d("King", "fullUrl:$fullUrl")
        if (encrypted)
        {
            Log.d("King", "encrypted")
            val suffix = fullUrl.split("###")[1].split(":")
            key = suffix[0]
            kid = suffix[1]
            fullUrl = fullUrl.split("###")[0]
            Log.d("King", "fullUrl:$fullUrl")
            Log.d("King", "key:$key")
            Log.d("King", "kid:$kid")
            DrmExtractorLink(
                source = data.channel_title,
                name = data.channel_title,
                url = fullUrl,
                referer = "",
                quality = Qualities.Unknown.value,
                key = key,
                kid = kid,
                kty = "oct",
                type = INFER_TYPE,
            )
            return true
        }
        callback.invoke(
            ExtractorLink(
                source = data.channel_title,
                name = data.channel_title,
                url = fullUrl,
                referer = "",
                quality = Qualities.Unknown.value,
                type = INFER_TYPE,
            )
        )
        return true
    }

    private fun fixUrl(url: String): String {
        return url.substring(url.lastIndexOf("http"))
    }
    private fun getDecoded(payload: Map<String, String>): String {

        val t = Calendar.getInstance().timeInMillis.toString()

        val client = app.baseClient.newBuilder()
            .build()

        val request = requestCreator(
            method = "POST",
            url = APIurl,
            headers = mapOf(
                "user-agent" to "Mozilla/5.0 (Linux; U; Android 10; en; YAL-L41 Api/HUAWEIYAL-L41) AppleWebKit/534.30 (KHTML, like Gecko) Version/5.0 Mobile Safari/534.30",
                "Time" to t,
            ),
            data = payload,
        )
        val req = client.newCall(request).execute()
        val decryptedBody = decrypt(req.body.string(), t.toCharArray())
        //Log.d("King", "decryptedBody:" + decryptedBody)
        return decryptedBody
    }

    private fun decrypt(str: String, key: CharArray): String {
        val sb = java.lang.StringBuilder()
        for (i in str.indices) {
            val charAt = str[i]
            val cArr: CharArray = key
            sb.append((charAt.code xor cArr[i % cArr.size].code).toChar())
        }
        return sb.toString()
    }

    data class Channel(
        @JsonProperty("id") val id: String,
        @JsonProperty("channel_title") val channel_title: String,
        @JsonProperty("channel_thumbnail") val channel_thumbnail: String,
        @JsonProperty("channel_url") val channel_url: String,
        @JsonProperty("download_url") val download_url: String,
        @JsonProperty("agent") val agent: String,
        @JsonProperty("video_player") val video_player: String,
    )
    data class Results(
        @JsonProperty("data") val results: ArrayList<Channel>? = arrayListOf(),
    )

    data class Categories(
        @JsonProperty("data") val results: ArrayList<Category>? = arrayListOf(),
    )
    data class Category(
        @JsonProperty("cid") val cid: String,
        @JsonProperty("cat") val cat: String,
        @JsonProperty("category_name") val category_name: String,
        @JsonProperty("image") val image: String,
        @JsonProperty("adp") val adp: String,
    )
    data class Servers(
        @JsonProperty("data") val results: ArrayList<Category>? = arrayListOf(),
    )
    data class Server(
        @JsonProperty("cid") val cid: String,
        @JsonProperty("cat") val cat: String,
        @JsonProperty("category_name") val category_name: String,
        @JsonProperty("image") val image: String,
        @JsonProperty("adp") val adp: String,
    )
    data class Data(
        val id: String,
        val channel_title: String,
        val channel_thumbnail: String,
        val category: String,
        val channel_url: String,
    )
}