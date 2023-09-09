package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.Session
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element
import java.net.URI

class Nekopoi : MainAPI() {
    override var mainUrl = "https://nekopoi.care"
    override var name = "Nekopoi"
    override val hasMainPage = true
    override var lang = "id"
    private val fetch by lazy { Session(app.baseClient) }
    override val supportedTypes = setOf(
        TvType.NSFW,
    )

    companion object {
        val session = Session(Requests().baseClient)
        val mirrorBlackList = arrayOf(
            "MegaupNet",
            "DropApk",
            "Racaty",
            "ZippyShare",
            "VideobinCo",
            "DropApk",
            "SendCm",
            "GoogleDrive",
        )
        const val mirroredHost = "https://www.mirrored.to"

        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

    }

    override val mainPage = mainPageOf(
        "$mainUrl/category/hentai/" to "Hentai",
        "$mainUrl/category/jav/" to "Jav",
        "$mainUrl/category/3d-hentai/" to "3D Hentai",
        "$mainUrl/category/jav-cosplay/" to "Jav Cosplay",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = fetch.get("${request.data}/page/$page").document
        val home = document.select("div.result ul li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("-episode-")) {
            val title = uri.substringAfter("$mainUrl/").substringBefore("-episode-")
                .removePrefix("new-release-").removePrefix("uncensored-")
            "$mainUrl/hentai/$title"
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("h2 a")?.text()?.trim() ?: return null
        val href = getProperAnimeLink(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val epNum = this.selectFirst("i.dot")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        return fetch.get("$mainUrl/search/$query").document.select("div.result ul li")
            .mapNotNull {
                it.toSearchResult()
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetch.get(url).document

        val title = document.selectFirst("span.desc b, div.eroinfo h1")?.text()?.trim() ?: ""
        val poster = fixUrlNull(document.selectFirst("div.imgdesc img, div.thm img")?.attr("src"))
        val table = document.select("div.listinfo ul, div.konten")
        val tags =
            table.select("li:contains(Genres) a").map { it.text() }.takeIf { it.isNotEmpty() }
                ?: table.select("p:contains(Genre)").text().substringAfter(":").split(",")
                    .map { it.trim() }
        val year =
            document.selectFirst("li:contains(Tayang)")?.text()?.substringAfterLast(",")
                ?.filter { it.isDigit() }?.toIntOrNull()
        val status = getStatus(
            document.selectFirst("li:contains(Status)")?.text()?.substringAfter(":")?.trim()
        )
        val duration = document.selectFirst("li:contains(Durasi)")?.text()?.substringAfterLast(":")
            ?.filter { it.isDigit() }?.toIntOrNull()
        val description = document.selectFirst("span.desc p")?.text()

        val episodes = document.select("div.episodelist ul li").mapNotNull {
            val name = it.selectFirst("a")?.text()
            val link = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            Episode(link, name = name)
        }.takeIf { it.isNotEmpty() } ?: listOf(Episode(url, title))

        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            engName = title
            posterUrl = poster
            this.year = year
            this.duration = duration
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val res = fetch.get(data).document

        argamap(
            {
                res.select("div#show-stream iframe").apmap { iframe ->
                    loadExtractor(iframe.attr("src"), "$mainUrl/", subtitleCallback, callback)
                }
            },
            {
                res.select("div.boxdownload div.liner").map { ele ->
                    getIndexQuality(
                        ele.select("div.name").text()
                    ) to ele.selectFirst("a:contains(ouo)")
                        ?.attr("href")
                }.filter { it.first != Qualities.P360.value }.map {
                    val bypassedAds = bypassMirrored(bypassOuo(it.second))
                    bypassedAds.apmap ads@{ adsLink ->
                        loadExtractor(
                            fixEmbed(adsLink) ?: return@ads,
                            "$mainUrl/",
                            subtitleCallback,
                        ) { link ->
                            callback.invoke(
                                ExtractorLink(
                                    link.name,
                                    link.name,
                                    link.url,
                                    link.referer,
                                    if (link.type == ExtractorLinkType.M3U8) link.quality else it.first,
                                    link.type,
                                    link.headers,
                                    link.extractorData
                                )
                            )
                        }
                    }
                }
            }
        )

        return true
    }

    private fun fixEmbed(url: String?): String? {
        if (url == null) return null
        val host = getBaseUrl(url)
        return when {
            url.contains("streamsb", true) -> url.replace("$host/", "$host/e/")
            else -> url
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private suspend fun bypassOuo(url: String?): String? {
        var res = session.get(url ?: return null)
        run lit@{
            (1..2).forEach { _ ->
                if (res.headers["location"] != null) return@lit
                val document = res.document
                val nextUrl = document.select("form").attr("action")
                val data = document.select("form input").mapNotNull {
                    it.attr("name") to it.attr("value")
                }.toMap().toMutableMap()
                val captchaKey =
                    document.select("script[src*=https://www.google.com/recaptcha/api.js?render=]")
                        .attr("src").substringAfter("render=")
                val token = APIHolder.getCaptchaToken(url, captchaKey)
                data["x-token"] = token ?: ""
                res = session.post(
                    nextUrl,
                    data = data,
                    headers = mapOf("content-type" to "application/x-www-form-urlencoded"),
                    allowRedirects = false
                )
            }
        }

        return res.headers["location"]
    }

    private fun NiceResponse.selectMirror(): String? {
        return this.document.selectFirst("script:containsData(#passcheck)")?.data()
            ?.substringAfter("\"GET\", \"")?.substringBefore("\"")
    }

    private suspend fun bypassMirrored(url: String?): List<String?> {
        val request = session.get(url ?: return emptyList())
        delay(2000)
        val mirrorUrl = request.selectMirror() ?: run {
            val nextUrl = request.document.select("div.col-sm.centered.extra-top a").attr("href")
            app.get(nextUrl).selectMirror()
        }
        return session.get(
            fixUrl(
                mirrorUrl ?: return emptyList(),
                mirroredHost
            )
        ).document.select("table.hoverable tbody tr")
            .filter { mirror ->
                !mirrorIsBlackList(mirror.selectFirst("img")?.attr("alt"))
            }.apmap {
                val fileLink = it.selectFirst("a")?.attr("href")
                session.get(
                    fixUrl(
                        fileLink ?: return@apmap null,
                        mirroredHost
                    )
                ).document.selectFirst("div.code_wrap code")?.text()
            }
    }

    private fun mirrorIsBlackList(host: String?): Boolean {
        return mirrorBlackList.any { it.equals(host, true) }
    }

    private fun fixUrl(url: String, domain: String): String {
        if (url.startsWith("http")) {
            return url
        }
        if (url.isEmpty()) {
            return ""
        }

        val startsWithNoHttp = url.startsWith("//")
        if (startsWithNoHttp) {
            return "https:$url"
        } else {
            if (url.startsWith('/')) {
                return domain + url
            }
            return "$domain/$url"
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return when (val quality =
            Regex("""(?i)\[(\d+[pk])]""").find(str ?: "")?.groupValues?.getOrNull(1)?.lowercase()) {
            "2k" -> Qualities.P1440.value
            else -> getQualityFromName(quality)
        }
    }

}