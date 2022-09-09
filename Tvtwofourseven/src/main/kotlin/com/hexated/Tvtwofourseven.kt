package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import java.net.URI

class Tvtwofourseven : MainAPI() {
    override var mainUrl = "http://tv247.us"
    override var name = "Tv247"
    override val hasDownloadSupport = false
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Live
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = listOf(
            Pair("$mainUrl/top-channels", "Top Channels"),
            Pair("$mainUrl/all-channels", "All Channels")
        ).apmap { (url,name) ->
            val home =
                app.get(url).document.select("div.grid-items div.item").mapNotNull { item ->
                    item.toSearchResult()
                }
            HomePageList(name, home, true)
        }.filter { it.list.isNotEmpty() }
        return HomePageResponse(home)
    }

    private fun Element.toSearchResult(): LiveSearchResponse? {
        return LiveSearchResponse(
            this.selectFirst("div.layer-content a")?.text() ?: return null,
            fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null,
            this@Tvtwofourseven.name,
            TvType.Live,
            fixUrlNull(this.select("img").attr("src")),
        )

    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.post(
            "$mainUrl/wp-admin/admin-ajax.php", data = mapOf(
                "action" to "ajaxsearchlite_search",
                "aslp" to query,
                "asid" to "1",
                "options" to "qtranslate_lang=0&set_intitle=None&set_incontent=None&set_inposts=None"
            ),
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).document.select("div.item").mapNotNull {
            LiveSearchResponse(
                it.selectFirst("a")?.text() ?: return@mapNotNull null,
                fixUrl(it.selectFirst("a")!!.attr("href")),
                this@Tvtwofourseven.name,
                TvType.Live,
                fixUrlNull(
                    it.select("div.asl_image").attr("style").substringAfter("url(\"")
                        .substringBefore("\");")
                )
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val data =
            document.select("script").find { it.data().contains("var channelName =") }?.data()
        val baseUrl = data?.substringAfter("baseUrl = \"")?.substringBefore("\";")
        val channel = data?.substringAfter("var channelName = \"")?.substringBefore("\";")
        return LiveStreamLoadResponse(
            document.selectFirst("title")?.text()?.split("-")?.first()?.trim() ?: return null,
            url,
            this.name,
            "$baseUrl$channel.m3u8",
            fixUrlNull(document.selectFirst("img.aligncenter.jetpack-lazy-image")?.attr("src")),
            plot = document.select("address").text()
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        if (URI(data).host == "cdn.espnfree.xyz") {
            M3u8Helper.generateM3u8(
                this.name,
                data,
                "$mainUrl/",
                headers = mapOf("Origin" to mainUrl, "X-Cache" to "HIT"),
            ).forEach(callback)
        } else {
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = data,
                    referer = "$mainUrl/",
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                    headers = mapOf("Origin" to mainUrl)
                )
            )
        }

        return true

    }
}