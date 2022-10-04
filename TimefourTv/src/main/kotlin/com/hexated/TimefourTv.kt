package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import java.net.URI

class TimefourTv : MainAPI() {
    override var mainUrl = "https://time4tv.stream"
    override var name = "Time4tv"
    override val hasDownloadSupport = false
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Live
    )

    override val mainPage = mainPageOf(
        "$mainUrl/tv-channels" to "All Channels",
        "$mainUrl/usa-channels" to "USA Channels",
        "$mainUrl/uk-channels" to "UK Channels",
        "$mainUrl/sports-channels" to "Sport Channels",
        "$mainUrl/live-sports-streams" to "Live Sport Channels",
        "$mainUrl/news-channels" to "News Channels",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        val nonPaged = request.name != "All Channels" && page <= 1
        if(nonPaged) {
            val res = app.get("${request.data}.php").document
            val home = res.select("div.tab-content ul li").mapNotNull {
                it.toSearchResult()
            }
            items.add(HomePageList(request.name, home, true))
        }
        if (request.name == "All Channels") {
            val res = if (page == 1) {
                app.get("${request.data}.php").document
            } else {
                app.get("${request.data}${page.minus(1)}.php").document
            }
            val home = res.select("div.tab-content ul li").mapNotNull {
                it.toSearchResult()
            }
            items.add(HomePageList(request.name, home, true))
        }

        return newHomePageResponse(items)
    }

    private fun Element.toSearchResult(): LiveSearchResponse? {
        return LiveSearchResponse(
            this.selectFirst("div.channelName")?.text() ?: return null,
            fixUrl(this.selectFirst("a")!!.attr("href")),
            this@TimefourTv.name,
            TvType.Live,
            fixUrlNull(this.selectFirst("img")?.attr("src")),
        )

    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("div.channelHeading h1")?.text() ?: return null
        val poster =
            fixUrlNull(document.selectFirst("meta[property=\"og:image\"]")?.attr("content"))
        val description = document.selectFirst("div.tvText")?.text() ?: return null
        val episodes = document.selectFirst("div.playit")?.attr("onclick")?.substringAfter("open('")
            ?.substringBefore("',")?.let { link ->
                val doc = app.get(link).document.selectFirst("div.tv_palyer iframe")?.attr("src")
                    ?.let { iframe ->
                        app.get(fixUrl(iframe), allowRedirects = false).document
                    }
                doc?.select("div.stream_button a")?.map {
                    Episode(
                        fixUrl(it.attr("href")),
                        it.text()
                    )
                }
            } ?: throw ErrorLoadingException("Refresh page")
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private var mainServer: String? = null
    private suspend fun getLink(url: String): String? {
        val (channel, iframe) = if (url.contains("width=")) {
            val doc = app.get(url, referer = "$mainUrl/").document
            val tempIframe = doc.selectFirst("iframe")?.attr("src") ?: return null
            val doctwo = app.get(fixUrl(tempIframe), referer = url).text
            listOf(
                tempIframe.split("?").last().removePrefix("id=").replace(".php", ""),
                doctwo.substringAfterLast("<iframe  src=\"").substringBefore("'+")
            )
        } else {
            val doc = app.get(url, referer = "$mainUrl/").text
            listOf(
                url.split("?").last().removePrefix("id=").replace(".php", ""),
                doc.substringAfterLast("<iframe  src=\"").substringBefore("'+")
            )
        }

        val linkFirst = "$iframe$channel.php"
        val refFirst = getBaseUrl(url)
        val docSecond = app.get(fixUrl(linkFirst), referer = refFirst).document
        val iframeSecond = docSecond.select("iframe:last-child, iframe#thatframe").attr("src")

        val refSecond = getBaseUrl(linkFirst)
        val docThird = app.get(fixUrl(iframeSecond), referer = "$refSecond/")
        mainServer = getBaseUrl(iframeSecond)

        return Regex("""source:['|"](\S+.m3u8)['|"],""").find(docThird.text)?.groupValues?.getOrNull(
            1
        ) ?: run {
            val scriptData =
                docThird.document.selectFirst("div#player")?.nextElementSibling()?.data()
                    ?.substringAfterLast("return(")?.substringBefore(".join")
            scriptData?.removeSurrounding("[", "]")?.replace("\"", "")?.split(",")
                ?.joinToString("")
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data, allowRedirects = false).document
        val link = document.selectFirst("iframe")?.attr("src")
            ?: throw ErrorLoadingException()
        getLink(fixUrl(link))?.let { m3uLink ->
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    m3uLink,
                    referer = "$mainServer/",
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        }
        return true
    }

}