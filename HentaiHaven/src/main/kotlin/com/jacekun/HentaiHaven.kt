package com.jacekun

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.select.Elements

class HentaiHaven : MainAPI() {
    private val globalTvType = TvType.NSFW
    override var name = "Hentai Haven"
    override var mainUrl = "https://hentaihaven.xxx"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasDownloadSupport = false
    override val hasMainPage= true
    override val hasQuickSearch = false

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(mainUrl).document
        val all = ArrayList<HomePageList>()

        doc.getElementsByTag("body").select("div.c-tabs-item")
            .select("div.vraven_home_slider").forEach { it2 ->
                // Fetch row title
                val title = it2?.select("div.home_slider_header")?.text() ?: "Unnamed Row"
                // Fetch list of items and map
                it2.select("div.page-content-listing div.item.vraven_item.badge-pos-1").let { inner ->

                    all.add(
                        HomePageList(
                            name = title,
                            list = inner.getResults(this.name),
                            isHorizontalImages = false
                        )
                    )
                }
            }
        return HomePageResponse(all)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "${mainUrl}/?s=${query}&post_type=wp-manga"
        return app.get(searchUrl).document
            .select("div.c-tabs-item div.row.c-tabs-item__content")
            .getResults(this.name)
    }

    override suspend fun load(url: String): LoadResponse {
        //TODO: Load polishing
        val doc = app.get(url).document
        //Log.i(this.name, "Result => (url) ${url}")
        val poster = doc.select("meta[property=og:image]")
            .firstOrNull()?.attr("content")
        val title = doc.select("meta[name=title]")
            .firstOrNull()?.attr("content")
            ?.toString() ?: ""
        val descript = doc.select("div.description-summary").text()

        val body = doc.getElementsByTag("body")
        val episodes = body.select("div.page-content-listing.single-page")
            .first()?.select("li")

        val year = episodes?.last()
            ?.selectFirst("span.chapter-release-date")
            ?.text()?.trim()?.takeLast(4)?.toIntOrNull()

        val episodeList = episodes?.mapNotNull {
            val innerA = it?.selectFirst("a") ?: return@mapNotNull null
            val eplink = innerA.attr("href") ?: return@mapNotNull null
            val epCount = innerA.text().trim().filter { a -> a.isDigit() }.toIntOrNull()
            val imageEl = innerA.selectFirst("img")
            val epPoster = imageEl?.attr("src") ?: imageEl?.attr("data-src")
            Episode(
                name = innerA.text(),
                data = eplink,
                posterUrl = epPoster,
                episode = epCount,
            )
        } ?: listOf()

        //Log.i(this.name, "Result => (id) ${id}")
        return AnimeLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            type = globalTvType,
            posterUrl = poster,
            year = year,
            plot = descript,
            episodes = mutableMapOf(
                Pair(DubStatus.Subbed, episodeList.reversed())
            )
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        try {
            Log.i(name, "Loading iframe")
            val requestLink = "${mainUrl}/wp-content/plugins/player-logic/api.php"
            val action = "zarat_get_data_player_ajax"
            val reA = Regex("(?<=var en =)(.*?)(?=';)", setOf(RegexOption.DOT_MATCHES_ALL))
            val reB = Regex("(?<=var iv =)(.*?)(?=';)", setOf(RegexOption.DOT_MATCHES_ALL))

            app.get(data).document.selectFirst("div.player_logic_item iframe")
                ?.attr("src")?.let { epLink ->

                    Log.i(name, "Loading ep link => $epLink")
                    val scrAppGet = app.get(epLink, referer = data)
                    val scrDoc = scrAppGet.document.getElementsByTag("script").toString()
                    //Log.i(name, "Loading scrDoc => (${scrAppGet.code}) $scrDoc")
                    if (scrDoc.isNotBlank()) {
                        //en
                        val a = reA.find(scrDoc)?.groupValues?.getOrNull(1)
                            ?.trim()?.removePrefix("'") ?: ""
                        //iv
                        val b = reB.find(scrDoc)?.groupValues?.getOrNull(1)
                            ?.trim()?.removePrefix("'") ?: ""

                        Log.i(name, "a => $a")
                        Log.i(name, "b => $b")

                        val doc = app.post(
                            url = requestLink,
                            headers = mapOf(
//                              Pair("mode", "cors"),
//                              Pair("Content-Type", "multipart/form-data"),
//                              Pair("Origin", mainUrl),
//                              Pair("Host", mainUrl.split("//").last()),
                                Pair("User-Agent", USER_AGENT),
                                Pair("Sec-Fetch-Mode", "cors")
                            ),
                            data = mapOf(
                                Pair("action", action),
                                Pair("a", a),
                                Pair("b", b)
                            )
                        )
                        Log.i(name, "Response (${doc.code}) => ${doc.text}")
                        //AppUtils.tryParseJson<ResponseJson?>(doc.text)
                        doc.parsedSafe<ResponseJson>()?.data?.sources?.map { m3src ->
                            val m3srcFile = m3src.src ?: return@map null
                            val label = m3src.label ?: ""
                            Log.i(name, "M3u8 link: $m3srcFile")
                            callback.invoke(
                                ExtractorLink(
                                    name = "$name m3u8",
                                    source = "$name m3u8",
                                    url = m3srcFile,
                                    referer = "$mainUrl/",
                                    quality = getQualityFromName(label),
                                    isM3u8 = true
                                )
                            )
                        }
                    }
                }
        } catch (e: Exception) {
            Log.i(name, "Error => $e")
            logError(e)
            return false
        }
        return true
    }

    private fun Elements?.getResults(apiName: String): List<AnimeSearchResponse> {
        return this?.mapNotNull {
            val innerDiv = it.select("div").firstOrNull()
            val firstA = innerDiv?.selectFirst("a")
            val link = fixUrlNull(firstA?.attr("href")) ?: return@mapNotNull null
            val name = firstA?.attr("title") ?: "<No Title>"
            val year = innerDiv?.selectFirst("span.c-new-tag")?.selectFirst("a")
                ?.attr("title")?.takeLast(4)?.toIntOrNull()

            val imageDiv = firstA?.selectFirst("img")
            var image = imageDiv?.attr("src")
            if (image.isNullOrBlank()) {
                image = imageDiv?.attr("data-src")
            }

            val latestEp = innerDiv?.selectFirst("div.list-chapter")
                ?.selectFirst("div.chapter-item")
                ?.selectFirst("a")
                ?.text()
                ?.filter { a -> a.isDigit() }
                ?.toIntOrNull() ?: 0
            val dubStatus = mutableMapOf(
                Pair(DubStatus.Subbed, latestEp)
            )

            AnimeSearchResponse(
                name = name,
                url = link,
                apiName = apiName,
                type = globalTvType,
                posterUrl = image,
                year = year,
                episodes = dubStatus
            )
        } ?: listOf()
    }

    private data class ResponseJson(
        @JsonProperty("data") val data: ResponseData?
    )
    private data class ResponseData(
        @JsonProperty("sources") val sources: List<ResponseSources>? = listOf()
    )
    private data class ResponseSources(
        @JsonProperty("src") val src: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("label") val label: String?
    )
}