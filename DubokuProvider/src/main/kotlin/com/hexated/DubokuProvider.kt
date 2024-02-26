package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.net.URLDecoder

class DubokuProvider : MainAPI() {
    override var mainUrl = "https://www.duboku.tv"
    private var serverUrl = "https://w.duboku.io"
    override var name = "Duboku"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/vodshow/2--time------" to "连续剧 时间",
        "$mainUrl/vodshow/2--hits------" to "连续剧 人气",
        "$mainUrl/vodshow/13--time------" to "陆剧 时间",
        "$mainUrl/vodshow/13--hits------" to "陆剧 人气",
        "$mainUrl/vodshow/15--time------" to "日韩剧 时间",
        "$mainUrl/vodshow/15--hits------" to "日韩剧 人气",
        "$mainUrl/vodshow/21--time------" to "短剧 时间",
        "$mainUrl/vodshow/21--hits------" to "短剧 人气",
        "$mainUrl/vodshow/16--time------" to "英美剧 时间",
        "$mainUrl/vodshow/16--hits------" to "英美剧 人气",
        "$mainUrl/vodshow/14--time------" to "台泰剧 时间",
        "$mainUrl/vodshow/14--hits------" to "台泰剧 人气",
        "$mainUrl/vodshow/20--time------" to "港剧 时间",
        "$mainUrl/vodshow/20--hits------" to "港剧 人气",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("${request.data}$page---.html").document
        val home = document.select("ul.myui-vodlist.clearfix li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h4.title a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href").toString())
        val posterUrl = fixUrlNull(this.selectFirst("a")?.attr("data-original"))
        val episode = this.selectFirst("span.pic-text.text-right")?.text()?.filter { it.isDigit() }
            ?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            addSub(episode)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/vodsearch/-------------.html?wd=$query&submit=").document

        return document.select("ul#searchList li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.title")?.text()?.trim() ?: return null
        val tvType = if (document.select("ul.myui-content__list li").size == 1
        ) TvType.Movie else TvType.TvSeries
        val actors = document.select("p.data")[2].select("a").map { it.text() }

        val episodes = document.select("ul.myui-content__list li").map {
            val href = fixUrl(it.select("a").attr("href"))
            val name = it.select("a").text().trim()
            Episode(
                data = href,
                name = name,
            )
        }
        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = fixUrlNull(
                document.selectFirst("a.myui-vodlist__thumb.picture img")?.attr("data-original")
            )
            this.year =
                document.select("p.data")[0].select("a").last()?.text()?.trim()?.toIntOrNull()
            this.plot = document.selectFirst("span.sketch.content")?.text()?.trim()
            this.tags = document.select("p.data")[0].select("a").map { it.text() }
            this.rating = document.select("div#rating span.branch").text().toRatingInt()
            addActors(actors)
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val dataJson =
            app.get(data).document.selectFirst("script:containsData(var player_data={)")?.data()
                ?.substringAfter("var player_data={")?.substringBefore("}")
                ?: throw IllegalArgumentException()
        val source = tryParseJson<Sources>("{$dataJson}")
        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                "${decode(base64Decode(source?.url ?: return false))}${getSign(source.from, data)}",
                "$serverUrl/",
                Qualities.Unknown.value,
                INFER_TYPE,
                headers = mapOf(
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Origin" to serverUrl
                ),
            )
        )

        return true
    }

    private suspend fun getSign(server: String? = "vidjs24", ref: String): String {
        return app.get(
            "$serverUrl/static/player/$server.php",
            referer = ref
        ).text.substringAfter("PlayUrl+'").substringBefore("'")
    }

    private fun decode(input: String): String = URLDecoder.decode(input, "utf-8")

    data class Sources(
        @JsonProperty("url") val url: String?,
        @JsonProperty("from") val from: String?,
    )


}