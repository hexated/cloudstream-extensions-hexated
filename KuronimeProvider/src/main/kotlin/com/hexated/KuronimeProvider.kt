package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.ArrayList

class KuronimeProvider : MainAPI() {
    override var mainUrl = "https://45.12.2.25"
    override var name = "Kuronime"
    override val hasQuickSearch = true
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special", true)) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "New Episodes",
        "$mainUrl/popular-anime/page/" to "Popular Anime",
        "$mainUrl/movies/page/" to "Movies",
//        "$mainUrl/genres/donghua/page/" to "Donghua",
//        "$mainUrl/live-action/page/" to "Live Action",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("article").map {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {
            var title = uri.substringAfter("$mainUrl/")
            title = when {
                (title.contains("-episode")) && !(title.contains("-movie")) -> Regex("nonton-(.+)-episode").find(
                    title
                )?.groupValues?.get(1).toString()
                (title.contains("-movie")) -> Regex("nonton-(.+)-movie").find(title)?.groupValues?.get(
                    1
                ).toString()
                else -> title
            }

            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val href = getProperAnimeLink(fixUrlNull(this.selectFirst("a")?.attr("href")).toString())
        val title = this.select(".bsuxtt, .tt > h4").text().trim()
        val posterUrl = fixUrlNull(
            this.selectFirst("div.view,div.bt")?.nextElementSibling()?.select("img")
                ?.attr("data-src")
        )
        val epNum = this.select(".ep").text().replace(Regex("\\D"), "").trim().toIntOrNull()
        val tvType = getType(this.selectFirst(".bt > span")?.text().toString())
        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }

    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        return app.post(
            "$mainUrl/wp-admin/admin-ajax.php", data = mapOf(
                "action" to "ajaxy_sf",
                "sf_value" to query,
                "search" to "false"
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<Search>()?.anime?.firstOrNull()?.all?.mapNotNull {
            newAnimeSearchResponse(it.postTitle ?: "", it.postLink ?: return@mapNotNull null, TvType.Anime) {
                this.posterUrl = it.postImage
                addSub(it.postLatest?.toIntOrNull())
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".entry-title")?.text().toString().trim()
        val poster = document.selectFirst("div.l[itemprop=image] > img")?.attr("data-src")
        val tags = document.select(".infodetail > ul > li:nth-child(2) > a").map { it.text() }
        val type =
            document.selectFirst(".infodetail > ul > li:nth-child(7)")?.ownText()?.removePrefix(":")
                ?.lowercase()?.trim() ?: "tv"

        val trailer = document.selectFirst("div.tply iframe")?.attr("data-src")
        val year = Regex("\\d, (\\d*)").find(
            document.select(".infodetail > ul > li:nth-child(5)").text()
        )?.groupValues?.get(1)?.toIntOrNull()
        val status = getStatus(
            document.selectFirst(".infodetail > ul > li:nth-child(3)")!!.ownText()
                .replace(Regex("\\W"), "")
        )
        val description = document.select("span.const > p").text()

        val episodes = document.select("div.bixbox.bxcl > ul > li").mapNotNull {
            val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val name = it.selectFirst("a")?.text() ?: return@mapNotNull null
            val episode =
                Regex("(\\d+[.,]?\\d*)").find(name)?.groupValues?.getOrNull(0)?.toIntOrNull()
            Episode(link, name, episode = episode)
        }.reversed()

        return newAnimeLoadResponse(title, url, getType(type)) {
            engName = title
            posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            addTrailer(trailer)
            this.tags = tags
        }
    }

    private suspend fun invokeKuroSource(
        url: String,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = "${mainUrl}/").document

        doc.select("script").map { script ->
            if (script.data().contains("function jalankan_jwp() {")) {
                val data = script.data()
                val doma = data.substringAfter("var doma = \"").substringBefore("\";")
                val token = data.substringAfter("var token = \"").substringBefore("\";")
                val pat = data.substringAfter("var pat = \"").substringBefore("\";")
                val link = "$doma$token$pat/index.m3u8"
                val quality =
                    Regex("\\d{3,4}p").find(doc.select("title").text())?.groupValues?.get(0)

                sourceCallback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        link,
                        referer = "https://animeku.org/",
                        quality = getQualityFromName(quality),
                        headers = mapOf("Origin" to "https://animeku.org"),
                        isM3u8 = true
                    )
                )
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
        val sources = document.select(".mobius > .mirror > option").mapNotNull {
            fixUrl(Jsoup.parse(base64Decode(it.attr("value"))).select("iframe").attr("data-src"))
        }

        sources.apmap {
            safeApiCall {
                when {
                    it.startsWith("https://animeku.org") -> invokeKuroSource(it, callback)
                    else -> loadExtractor(it, mainUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    data class All(
        @JsonProperty("post_image") var postImage: String? = null,
        @JsonProperty("post_image_html") var postImageHtml: String? = null,
        @JsonProperty("ID") var ID: Int? = null,
        @JsonProperty("post_title") var postTitle: String? = null,
        @JsonProperty("post_genres") var postGenres: String? = null,
        @JsonProperty("post_type") var postType: String? = null,
        @JsonProperty("post_latest") var postLatest: String? = null,
        @JsonProperty("post_sub") var postSub: String? = null,
        @JsonProperty("post_link") var postLink: String? = null
    )

    data class Anime(
        @JsonProperty("all") var all: ArrayList<All> = arrayListOf(),
    )

    data class Search(
        @JsonProperty("anime") var anime: ArrayList<Anime> = arrayListOf()
    )

}
