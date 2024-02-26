package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import okhttp3.FormBody
import org.jsoup.nodes.Element

class Hentaiheaven : MainAPI() {
    override var mainUrl = "https://hentaihaven.xxx"
    override var name = "Hentaiheaven"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.NSFW)

    override val mainPage = mainPageOf(
        "?m_orderby=new-manga" to "New",
        "?m_orderby=views" to "Most Views",
        "?m_orderby=rating" to "Rating",
        "?m_orderby=alphabet" to "A-Z",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/page/$page/${request.data}").document
        val home =
            document.select("div.page-listing-item div.col-6.col-md-zarat.badge-pos-1").mapNotNull {
                it.toSearchResult()
            }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val title =
            this.selectFirst("h3 a, h5 a")?.text()?.trim() ?: this.selectFirst("a")?.attr("title")
            ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val episode = this.selectFirst("span.chapter.font-meta a")?.text()?.filter { it.isDigit() }
            ?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(episode)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query&post_type=wp-manga"
        val document = app.get(link).document

        return document.select("div.c-tabs-item > div.c-tabs-item__content").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.post-title h1")?.text()?.trim() ?: return null
        val poster = document.select("div.summary_image img").attr("src")
        val tags = document.select("div.genres-content > a").map { it.text() }

        val description = document.select("div.description-summary p").text().trim()
        val trailer = document.selectFirst("a.trailerbutton")?.attr("href")

        val episodes = document.select("div.listing-chapters_wrap ul li").mapNotNull {
            val name = it.selectFirst("a")?.text() ?: return@mapNotNull null
            val image = fixUrlNull(it.selectFirst("a img")?.attr("src"))
            val link = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            Episode(link, name, posterUrl = image)
        }.reversed()

        val recommendations =
            document.select("div.row div.col-6.col-md-zarat").mapNotNull {
                it.toSearchResult()
            }

        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            engName = title
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
            plot = description
            this.tags = tags
            this.recommendations = recommendations
            addTrailer(trailer)
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document
        val meta = doc.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")?.substringAfter("/hh/")?.substringBefore("/") ?: return false
        doc.select("div.player_logic_item iframe").attr("src").let { iframe ->
            val document = app.get(iframe, referer = data).text
            val en = Regex("var\\sen\\s=\\s'(\\S+)';").find(document)?.groupValues?.getOrNull(1)
            val iv = Regex("var\\siv\\s=\\s'(\\S+)';").find(document)?.groupValues?.getOrNull(1)

            val body = FormBody.Builder()
                .addEncoded("action", "zarat_get_data_player_ajax")
                .addEncoded("a", "$en")
                .addEncoded("b", "$iv")
                .build()

            app.post(
                "$mainUrl/wp-content/plugins/player-logic/api.php",
//                data = mapOf(
//                    "action" to "zarat_get_data_player_ajax",
//                    "a" to "$en",
//                    "b" to "$iv"
//                ),
                requestBody = body,
//                headers = mapOf("Sec-Fetch-Mode" to "cors")
            ).parsedSafe<Response>()?.data?.sources?.map { res ->
//                M3u8Helper.generateM3u8(
//                    this.name,
//                    res.src ?: return@map null,
//                    referer = "$mainUrl/",
//                    headers = mapOf(
//                        "Origin" to mainUrl,
//                    )
//                ).forEach(callback)
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        res.src?.replace("/hh//", "/hh/$meta/") ?: return@map null,
                        referer = "",
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
            }
        }

        return true
    }

    data class Response(
        @JsonProperty("data") val data: Data? = null,
    )

    data class Data(
        @JsonProperty("sources") val sources: ArrayList<Sources>? = arrayListOf(),
    )

    data class Sources(
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("label") val label: String? = null,
    )


}