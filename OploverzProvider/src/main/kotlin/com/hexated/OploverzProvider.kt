package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class OploverzProvider : MainAPI() {
    override var mainUrl = "https://oploverz.win"
    override var name = "Oploverz"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        const val acefile = "https://acefile.co"
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Finished Airing" -> ShowStatus.Completed
                "Completed" -> ShowStatus.Completed
                "Currently Airing" -> ShowStatus.Ongoing
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

    }

    override val mainPage = mainPageOf(
        "update" to "Latest Update",
        "latest" to "Latest Added",
        "popular" to "Popular Anime",
        "rating" to "Top Rated",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/anime-list/page/$page/?title&order=${request.data}&status&type").document
        val home = document.select("div.relat > article").mapNotNull {
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
                (title.contains("-episode")) && !(title.contains("-movie")) -> Regex("(.+)-episode").find(
                    title
                )?.groupValues?.get(1).toString()
                (title.contains("-movie")) -> Regex("(.+)-movie").find(title)?.groupValues?.get(
                    1
                ).toString()
                else -> title
            }
            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("div.title")?.text()?.trim() ?: return null
        val href = getProperAnimeLink(this.selectFirst("a")!!.attr("href"))
        val posterUrl = this.select("img[itemprop=image]").attr("src").toString()
        val type = getType(this.select("div.type").text().trim())
        val epNum =
            this.selectFirst("span.episode")?.ownText()?.replace(Regex("\\D"), "")?.trim()
                ?.toIntOrNull()
        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val anime = mutableListOf<SearchResponse>()
        (1..2).forEach { page ->
            val link = "$mainUrl/page/$page/?s=$query"
            val document = app.get(link).document
            val media = document.select(".site-main.relat > article").mapNotNull {
                val title = it.selectFirst("div.title > h2")!!.ownText().trim()
                val href = it.selectFirst("a")!!.attr("href")
                val posterUrl = it.selectFirst("img")!!.attr("src").toString()
                val type = getType(it.select("div.type").text().trim())
                newAnimeSearchResponse(title, href, type) {
                    this.posterUrl = posterUrl
                }
            }
            if(media.isNotEmpty()) anime.addAll(media)
        }
        return anime
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()
            ?.replace("Subtitle Indonesia", "")?.trim() ?: ""
        val type = getType(document.selectFirst("div.alternati span.type")?.text() ?: "")
        val year = document.selectFirst("div.alternati a")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val episodes = document.select("div.lstepsiode.listeps ul li").mapNotNull {
            val header = it.selectFirst("a") ?: return@mapNotNull null
            val episode = header.text().trim().toIntOrNull()
            val link = fixUrl(header.attr("href"))
            Episode(link, episode = episode)
        }.reversed()

        val tracker = APIHolder.getTracker(listOf(title),TrackerType.getTypes(type),year,true)

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: document.selectFirst("div.thumb > img")?.attr("src")
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus =
                getStatus(
                    document.selectFirst("div.alternati span:nth-child(2)")?.text()?.trim()
                )
            plot = document.selectFirst("div.entry-content > p")?.text()?.trim()
            this.tags =
                document.select("div.genre-info a").map { it.text() }
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

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
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).document.select("iframe").attr("src")

                    loadExtractor(fixedIframe(iframe), "$mainUrl/", subtitleCallback, callback)

                }
            },
            {
                document.select("div#download tr").map { el ->
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
        return when(this) {
            "MP4HD" -> Qualities.P720.value
            "FULLHD" -> Qualities.P1080.value
            else -> Regex("(\\d{3,4})p").find(this)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
        }
    }

    private fun fixedIframe(url: String): String {
        val id = Regex("""(?:/f/|/file/)(\w+)""").find(url)?.groupValues?.getOrNull(1)
        return when {
            url.startsWith(acefile) -> "${acefile}/player/$id"
            else -> fixUrl(url)
        }
    }

}
