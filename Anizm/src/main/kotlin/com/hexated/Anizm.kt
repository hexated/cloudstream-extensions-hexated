package com.hexated

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element


class Anizm : MainAPI() {
    override var mainUrl = "https://anizm.net"
    override var name = "Anizm"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private const val mainServer = "https://anizmplayer.com"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime-izle?sayfa=" to "Son Eklenen Animeler",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.restrictedWidth div#episodesMiddle").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("-bolum")) {
            "$mainUrl/${uri.substringAfter("$mainUrl/").replace(Regex("-[0-9]+-bolum.*"), "")}"
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = getProperAnimeLink(this.selectFirst("a")!!.attr("href"))
        val title = this.selectFirst("div.title, h5.animeTitle a")?.text() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val episode = this.selectFirst("div.truncateText")?.text()?.let {
            Regex("([0-9]+).\\s?Bölüm").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(episode)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/fullViewSearch?search=$query&skip=0",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).document

        return document.select("div.searchResultItem").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h2.anizm_pageTitle a")!!.text().trim()
        val type =
            if (document.select("div.ui.grid div.four.wide").size == 1) TvType.Movie else TvType.Anime
        val trailer = document.select("div.yt-hd-thumbnail-inner-container iframe").attr("src")
        val episodes = document.select("div.ui.grid div.four.wide").map {
            val name = it.select("div.episodeBlock").text()
            val link = fixUrl(it.selectFirst("a")?.attr("href").toString())
            Episode(link, name)
        }
        return newAnimeLoadResponse(title, url, type) {
            posterUrl = fixUrlNull(document.selectFirst("div.infoPosterImg > img")?.attr("src"))
            this.year = document.select("div.infoSta ul li:first-child").text().trim().toIntOrNull()
            addEpisodes(DubStatus.Subbed, episodes)
            plot = document.select("div.infoDesc").text().trim()
            this.tags = document.select("span.dataValue span.ui.label").map { it.text() }
            addTrailer(trailer)
        }
    }

    private suspend fun invokeLokalSource(
        url: String,
        translator: String,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        app.get(url, referer = "$mainUrl/").document.select("script").find { script ->
            script.data().contains("eval(function(p,a,c,k,e,d)")
        }?.let {
            val key = getAndUnpack(it.data()).substringAfter("FirePlayer(\"").substringBefore("\",")
            val referer = "$mainServer/video/$key"
            val link = "$mainServer/player/index.php?data=$key&do=getVideo"
            Log.i("hexated", link)
            app.post(
                link,
                data = mapOf("hash" to key, "r" to "$mainUrl/"),
                referer = referer,
                headers = mapOf(
                    "Accept" to "*/*",
                    "Origin" to mainServer,
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).parsedSafe<Source>()?.videoSource?.let { m3uLink ->
                M3u8Helper.generateM3u8(
                    "${this.name} ($translator)",
                    m3uLink,
                    referer
                ).forEach(sourceCallback)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("div.episodeTranslators div#fansec").map {
            Pair(it.select("a").attr("translator"), it.select("div.title").text())
        }.apmap { (url, translator) ->
            safeApiCall {
                app.get(
                    url,
                    referer = data,
                    headers = mapOf(
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                ).parsedSafe<Translators>()?.data?.let {
                    Jsoup.parse(it).select("a").apmap { video ->
                        app.get(
                            video.attr("video"),
                            referer = data,
                            headers = mapOf(
                                "Accept" to "application/json, text/javascript, */*; q=0.01",
                                "X-Requested-With" to "XMLHttpRequest"
                            )
                        ).parsedSafe<Videos>()?.player?.let { iframe ->
                            Jsoup.parse(iframe).select("iframe").attr("src").let { link ->
                                when {
                                    link.startsWith(mainServer) -> {
                                        invokeLokalSource(link, translator, callback)
                                    }
                                    else -> {
                                        loadExtractor(
                                            fixUrl(link),
                                            "$mainUrl/",
                                            subtitleCallback,
                                            callback
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    data class Source(
        @JsonProperty("videoSource") val videoSource: String?,
    )

    data class Videos(
        @JsonProperty("player") val player: String?,
    )

    data class Translators(
        @JsonProperty("data") val data: String?,
    )

}