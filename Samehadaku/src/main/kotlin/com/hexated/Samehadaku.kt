package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Samehadaku : MainAPI() {
    override var mainUrl = "https://samehadaku.guru"
    override var name = "Samehadaku"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
            TvType.Anime,
            TvType.AnimeMovie,
            TvType.OVA
    )
    private val interceptor by lazy { CloudflareKiller() }
    companion object {
        const val acefile = "https://acefile.co"

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
            "$mainUrl/page/" to "Episode Terbaru",
            "$mainUrl/" to "HomePage",
    )

    override suspend fun getMainPage(
            page: Int,
            request: MainPageRequest
    ): HomePageResponse {
        val items = mutableListOf<HomePageList>()

        if (request.name != "Episode Terbaru" && page <= 1) {
            val doc = app.get(request.data, interceptor = interceptor).document
            doc.select("div.widget_senction:not(:contains(Baca Komik))").forEach { block ->
                val header = block.selectFirst("div.widget-title h3")?.ownText() ?: return@forEach
                val home = block.select("div.animepost").mapNotNull {
                    it.toSearchResult()
                }
                if (home.isNotEmpty()) items.add(HomePageList(header, home))
            }
        }

        if (request.name == "Episode Terbaru") {
            val home = app.get(request.data + page, interceptor = interceptor).document.selectFirst("div.post-show")?.select("ul li")
                    ?.mapNotNull {
                        it.toSearchResult()
                    } ?: throw ErrorLoadingException("No Media Found")
            items.add(HomePageList(request.name, home, true))
        }

        return newHomePageResponse(items)

    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("div.title, h2.entry-title a, div.lftinfo h2")?.text()?.trim()
                ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.select("img").attr("src"))
        val epNum = this.selectFirst("div.dtla author")?.text()?.toIntOrNull()
        return newAnimeSearchResponse(title, href ?: return null, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
            posterHeaders = interceptor.getCookieHeaders(mainUrl).toMap()
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", interceptor = interceptor).document
        return document.select("main#main div.animepost").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixUrl = if (url.contains("/anime/")) {
            url
        } else {
            app.get(url, interceptor = interceptor).document.selectFirst("div.nvs.nvsc a")?.attr("href")
        }

        val document = app.get(fixUrl ?: return null, interceptor = interceptor).document
        val title = document.selectFirst("h1.entry-title")?.text()?.removeBloat() ?: return null
        val poster = document.selectFirst("div.thumb > img")?.attr("src")
        val tags = document.select("div.genre-info > a").map { it.text() }
        val year = document.selectFirst("div.spe > span:contains(Rilis)")?.ownText()?.let {
            Regex("\\d,\\s(\\d*)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        val status = getStatus(
                document.selectFirst("div.spe > span:contains(Status)")?.ownText() ?: return null
        )
        val type =
                getType(document.selectFirst("div.spe > span:contains(Type)")?.ownText()?.trim()?.lowercase()
                        ?: "tv")
        val rating = document.selectFirst("span.ratingValue")?.text()?.trim()?.toRatingInt()
        val description = document.select("div.desc p").text().trim()
        val trailer = document.selectFirst("div.trailer-anime iframe")?.attr("src")

        val episodes = document.select("div.lstepsiode.listeps ul li").mapNotNull {
            val header = it.selectFirst("span.lchx > a") ?: return@mapNotNull null
            val episode = Regex("Episode\\s?(\\d+)").find(header.text())?.groupValues?.getOrNull(1)
                    ?.toIntOrNull()
            val link = fixUrl(header.attr("href"))
            Episode(link, episode = episode)
        }.reversed()

        val recommendations = document.select("aside#sidebar ul li").mapNotNull {
            it.toSearchResult()
        }

        val tracker = APIHolder.getTracker(listOf(title),TrackerType.getTypes(type),year,true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            this.rating = rating
            plot = description
            addTrailer(trailer)
            this.tags = tags
            this.recommendations = recommendations
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
            posterHeaders = interceptor.getCookieHeaders(mainUrl).toMap()
        }

    }

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data, interceptor = interceptor).document

        argamap(
                {
                    document.select("div#server ul li div").apmap {
                        val dataPost = it.attr("data-post")
                        val dataNume = it.attr("data-nume")
                        val dataType = it.attr("data-type")

                        val iframe = app.post(
                                url = "$mainUrl/wp-admin/admin-ajax.php",
                                data = mapOf(
                                        "action" to "player_ajax",
                                        "post" to dataPost,
                                        "nume" to dataNume,
                                        "type" to dataType
                                ),
                                referer = data,
                                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                                interceptor = interceptor
                        ).document.select("iframe").attr("src")

                        loadFixedExtractor(fixedIframe(iframe), it.text(), "$mainUrl/", subtitleCallback, callback)

                    }
                },
                {
                    document.select("div#downloadb li").map { el ->
                        el.select("a").apmap {
                            loadFixedExtractor(fixedIframe(it.attr("href")), el.select("strong").text(), "$mainUrl/", subtitleCallback, callback)
                        }
                    }
                }
        )

        return true
    }

    private suspend fun loadFixedExtractor(
            url: String,
            name: String,
            referer: String? = null,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            callback.invoke(
                    ExtractorLink(
                            link.name,
                            link.name,
                            link.url,
                            link.referer,
                            name.fixQuality(),
                            link.type,
                            link.headers,
                            link.extractorData
                    )
            )
        }
    }

    private fun String.fixQuality() : Int {
        return when(this.uppercase()) {
            "4K" -> Qualities.P2160.value
            "FULLHD" -> Qualities.P1080.value
            "MP4HD" -> Qualities.P720.value
            else -> this.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
        }
    }

    private fun fixedIframe(url: String): String {
        val id = Regex("""(?:/f/|/file/)(\w+)""").find(url)?.groupValues?.getOrNull(1)
        return when {
            url.startsWith(acefile) -> "${acefile}/player/$id"
            else -> fixUrl(url)
        }
    }

    private fun String.removeBloat(): String {
        return this.replace(Regex("(Nonton)|(Anime)|(Subtitle\\sIndonesia)"), "").trim()
    }

}
