package com.hexated

import com.hexated.TimefourTvExtractor.getLink
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

open class TimefourTv : MainAPI() {
    final override var mainUrl = "https://time4tv.stream"
    override var name = "Time4tv"
    override val hasDownloadSupport = false
    override val hasMainPage = true
    private val time4tvPoster = "$mainUrl/images/logo.png"
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
        "$mainUrl/schedule.php" to "Schedule",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        val nonPaged = request.name != "All Channels" && page <= 1
        if (nonPaged) {
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

        if (nonPaged && request.name == "Schedule") {
            val res = app.get(request.data).document
            val schedule = res.select("div.search_p h2").mapNotNull {
                it.toSearchSchedule()
            }
            items.add(HomePageList(request.name, schedule, true))
        }

        return newHomePageResponse(items)
    }

    private fun Element.toSearchSchedule(): LiveSearchResponse? {
        return LiveSearchResponse(
            this.text() ?: return null,
            this.text(),
            this@TimefourTv.name,
            TvType.Live,
            posterUrl = time4tvPoster
        )
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

    private suspend fun loadSchedule(url: String): LoadResponse {
        val name = url.removePrefix("$mainUrl/")
        val doc = app.get("$mainUrl/schedule.php").document

        val episode =
            doc.selectFirst("div.search_p h2:contains($name)")?.nextElementSibling()?.select("span")
                ?.mapIndexedNotNull { index, ele ->
                    val title = ele.select("a").text()
                    val href = ele.select("a").attr("href")
                    val desc = ele.parent()?.textNodes()?.get(index).toString()
                    Episode(
                        href,
                        title,
                        posterUrl = time4tvPoster,
                        description = desc,
                    )
                } ?: throw ErrorLoadingException("Referest Page")

        return newTvSeriesLoadResponse(name, url, TvType.TvSeries, episode) {
        }
    }

    override suspend fun load(url: String): LoadResponse? {

        val res = app.get(url)
        if (!res.isSuccessful) return loadSchedule(url)

        val document = res.document
        val title = document.selectFirst("div.channelHeading h1")?.text() ?: return null
        val poster =
            fixUrlNull(document.selectFirst("meta[property=\"og:image\"]")?.attr("content"))
        val description = document.selectFirst("div.tvText")?.text() ?: return null
        val episodes = document.selectFirst("div.playit")?.attr("onclick")?.substringAfter("open('")
            ?.substringBefore("',")?.let { link ->
                val doc = app.get(link).document.selectFirst("div.tv_palyer iframe")?.attr("src")
                    ?.let { iframe ->
                        app.get(fixUrl(iframe), referer = link).document
                    }
                if (doc?.select("div.stream_button").isNullOrEmpty()) {
                    doc?.select("iframe")?.mapIndexed { eps, ele ->
                        Episode(
                            fixUrl(ele.attr("src")),
                            "Server ${eps.plus(1)}"
                        )
                    }
                } else {
                    doc?.select("div.stream_button a")?.map {
                        Episode(
                            fixUrl(it.attr("href")),
                            it.text()
                        )
                    }
                }
            } ?: throw ErrorLoadingException("Refresh page")
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val link = when {
            data.contains("/channel") -> app.get(data).document.selectFirst("div.tv_palyer iframe")?.attr("src")
            data.startsWith(mainUrl) -> {
                app.get(data, allowRedirects = false).document.selectFirst("iframe")?.attr("src")
            }
            else -> {
                data
            }
        } ?: throw ErrorLoadingException()
        getLink(fixUrl(link))?.let { m3uLink ->
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    m3uLink,
                    referer = "$mainServer/",
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                )
            )
        }
        return true
    }

}