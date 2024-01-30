package com.anon

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.Session
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.nicehttp.requestCreator
import org.jsoup.Jsoup
import java.util.regex.Pattern
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.RequestBody.Companion.toRequestBody
import com.lagradost.cloudstream3.extractors.helper.AesHelper.cryptoAESHandler

class AnimeDekhoProvider : MainAPI() {
    override var mainUrl = "https://animedekho.com"
    override var name = "Anime Dekho"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "/series/" to "Series",
        "/movie/" to "Movies",
        "/category/anime/" to "Anime",
        "/category/cartoon/" to "Cartoon",
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val link = "$mainUrl${request.data}"
        val document = app.get(link).document
        val home = document.select("article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        //val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val href = this.selectFirst("a.lnk-blk")?.attr("href") ?: "null"
        var title = this.selectFirst("header h2")?.text() ?: "null"
        val posterUrl = this.selectFirst("div figure img")?.attr("src") ?: "null"

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<AnimeSearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        //return document.select("div.post-filter-image").mapNotNull {
        return document.select("ul[data-results] li article").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        
        val document = app.get(url).document

        var title = document?.selectFirst("h1.entry-title")?.text()?.trim()  ?: (document?.selectFirst("h3")?.text()?.trim() ?: "Null title")
        val poster  = document?.selectFirst("div.post-thumbnail figure img")?.attr("src") ?: "null"
        val plot  = document?.selectFirst("div.entry-content p")?.text()?.trim() ?: "null"
        val year  = document?.selectFirst("span.year")?.text()?.trim()?.toInt() ?: 1990
        var episodes = mutableListOf<Episode>()

        val items = document.select("ul.seasons-lst li").mapNotNull {   
            val name = it?.selectFirst("h3.title")?.text() ?: "null"
            val tempstring = it?.selectFirst("a")?.attr("href") ?: "null"

            episodes.add( Episode(tempstring, name) )
            }
        
        if(items.size==0){
            val vidtoonoid = document?.selectFirst("iframe")?.attr("src") ?: "NULL"
            val vidlink = app.get(vidtoonoid)?.document?.selectFirst("div iframe")?.attr("src") ?: "null"
            return newMovieLoadResponse(title, vidlink, TvType.Movie, vidlink) {
                this.posterUrl = poster.toString()
                this.plot = plot
                this.year = year
                //this.recommendations = recommendations
            }
        }

        else{
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster.toString()
                this.plot = plot
                this.year = year
                //this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var vidlink = ""

        if(data.contains("https://vidxstream.xyz")){
            vidlink = data
        }
        else{

            val page1 = app.get(data).document

            val name = page1?.selectFirst("body")?.attr("class") ?: "null"

            var term = Regex("""\bterm-(\d+)\b""").find(name)?.value!!?.replace("term-","")

            vidlink = app.get("https://animedekho.com/?trembed=0&trid="+term)
                                .document?.selectFirst("iframe")?.attr("src") ?: "null"
        }

        //Log.d("TAGNAME", "vidlink $vidlink") //https://vidxstream.xyz/v/H0Rh3ixVLJKk/
        val body = app.get(vidlink).text
        val master = Regex("""JScript[\w+]?\s*=\s*'([^']+)""").find(body)!!.groupValues?.get(1)
        val decrypt = cryptoAESHandler(master ?: return false, "4MmH9EsZrq0WEekn".toByteArray(), false)?.replace("\\", "") ?: "ERROR"
        val vidfinal = Regex("""file:\s*\"(https:[^\"]+)\"""").find(decrypt)!!.groupValues?.get(1)

        val headers = mapOf(
            "accept" to "*/*",
            "accept-language" to "en-US,en;q=0.5",
            "Origin" to "https://vidxstream.xyz",
            "Accept-Encoding" to "gzip, deflate, br",
            "Connection" to "keep-alive",
            //"Referer" to "https://vidxstream.xyz/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/116.0",
                )
        
        callback.invoke(ExtractorLink
            (
            source = "Toon",
            name = "Toon",
            url = vidfinal!!,
            referer = "https://vidxstream.xyz/",
            quality = Qualities.Unknown.value,
            isM3u8 = true,
            headers = headers,
            )
        )
        return true
    }
}