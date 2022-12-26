package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.hexated.GogoExtractor.extractVidstream
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import java.net.URI


class Animixplay : MainAPI() {
    override var mainUrl = "https://animixplay.red"
    override var name = "Animixplay"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Finished Airing" -> ShowStatus.Completed
                "Currently Airing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/api/search" to "Sub",
        "$mainUrl/api/search" to "Dub",
        "$mainUrl/a/XsWgdGCnKJfNvDFAM28EV" to "All",
        "$mainUrl/api/search" to "Movie",
    )

    private var newPagination: String? = null
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        val paged = page.toString()
        val pagination = if (request.name == "Movie") {
            paged.replace(paged, "99999999")
        } else {
            paged.replace(paged, "3020-05-06 00:00:00")
        }

        if (page <= 1) {
            val headers = when (request.name) {
                "Sub" -> mapOf("seasonal" to pagination)
                "Dub" -> mapOf("seasonaldub" to pagination)
                "All" -> mapOf("recent" to pagination)
                "Movie" -> mapOf("movie" to pagination)
                else -> mapOf()
            }
            val res = app.post(
                request.data,
                data = headers,
                referer = mainUrl,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<Result>()
            newPagination = res?.last.toString()
            val home = res?.result?.mapNotNull {
                it.toSearchResponse()
            } ?: throw ErrorLoadingException("No media found")
            items.add(
                HomePageList(
                    name = request.name,
                    list = home,
                )
            )
        } else {
            val headers = when (request.name) {
                "Sub" -> mapOf("seasonal" to "$newPagination")
                "Dub" -> mapOf("seasonaldub" to "$newPagination")
                "All" -> mapOf("recent" to "$newPagination")
                "Movie" -> mapOf("movie" to "$newPagination")
                else -> mapOf()
            }
            val res = app.post(
                request.data,
                data = headers,
                referer = mainUrl,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<Result>()
            newPagination = res?.last.toString()
            val home = res?.result?.mapNotNull {
                it.toSearchResponse()
            } ?: throw ErrorLoadingException("No media found")
            items.add(
                HomePageList(
                    name = request.name,
                    list = home,
                )
            )
        }

        return newHomePageResponse(items)
    }

    private fun Anime.toSearchResponse(): AnimeSearchResponse? {
        return newAnimeSearchResponse(
            title ?: return null,
            fixUrl(url ?: return null),
            TvType.TvSeries,
        ) {
            this.posterUrl = img ?: picture
            addDubStatus(
                isDub = title.contains("Dub"),
                episodes = Regex("EP\\s([0-9]+)/").find(
                    infotext ?: ""
                )?.groupValues?.getOrNull(1)
                    ?.toIntOrNull()
            )
        }
    }


    override suspend fun search(query: String): List<SearchResponse>? {
        return app.post(
            url = "https://v1.ij7p9towl8uj4qafsopjtrjk.workers.dev",
            referer = mainUrl,
            data = mapOf(
                "q2" to query,
                "origin" to "1",
                "root" to "animixplay.to",
                "d" to "gogoanime.tel"
            )
        ).parsedSafe<FullSearch>()?.result?.let {
            Jsoup.parse(it).select("div").map { elem ->

                val href = fixUrl(elem.select("a").attr("href"))
                val title = elem.select("a").attr("title")
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = elem.select("img").attr("src")
                    addDubStatus(isDub = title.contains("Dub"))
                }
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return app.post(
            "https://cdn.animixplay.to/api/search",
            data = mapOf("qfast" to query, "root" to URI(mainUrl).host)
        ).parsedSafe<Search>()?.result?.let {
            Jsoup.parse(it).select("a").map { elem ->
                val href = elem.attr("href")
                val title = elem.select("p.name").text()
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = elem.select("img").attr("src")
                    addDubStatus(isDub = title.contains("Dub"))
                }
            }
        }
    }

    private suspend fun loadMissingAnime(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("span.animetitle")?.text()
        val image = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val genres = doc.selectFirst("span#genredata")?.text()?.split(",")?.map { it.trim() }

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        val dataEps = doc.select("div#epslistplace")
            .text().trim()
        Regex("\"([0-9]+)\":\"(\\S+?)\"").findAll(dataEps).toList()
            .map { it.groupValues[1] to it.groupValues[2] }.map { (ep, link) ->
                val episode = Episode(fixUrl(link), episode = ep.toInt() + 1)
                if (url.contains("-dub")) {
                    dubEpisodes.add(episode)
                } else {
                    subEpisodes.add(episode)
                }
            }

        return newAnimeLoadResponse(
            title ?: return null,
            url,
            TvType.Anime
        ) {
            this.posterUrl = image
            this.tags = genres
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    override suspend fun load(url: String): LoadResponse? {

        val (fixUrl, malId) = if (url.contains("/anime/")) {
            listOf(url, Regex("anime/([0-9]+)/?").find(url)?.groupValues?.get(1))
        } else {
            val malId = app.get(url).text.substringAfterLast("malid = '").substringBefore("';")
            listOf("$mainUrl/anime/$malId", malId)
        }

        val anilistId = app.post(
            "https://graphql.anilist.co/", data = mapOf(
                "query" to "{Media(idMal:$malId,type:ANIME){id}}",
            )
        ).parsedSafe<DataAni>()?.data?.media?.id

        val res = app.get("$mainUrl/assets/mal/$malId.json").parsedSafe<AnimeDetail>()
            ?: return loadMissingAnime(url)

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        app.post("$mainUrl/api/search", data = mapOf("recomended" to "$malId"))
            .parsedSafe<Data>()?.data?.map { server ->
                server.items?.apmap { data ->
                    val jsonData =
                        app.get(
                            fixUrl(
                                data.url ?: return@apmap null
                            )
                        ).document.select("div#epslistplace")
                            .text().trim()
                    val episodeData = when (server.type) {
                        "AL" -> Regex("\"([0-9]+)\":\\[(.*?)]").findAll(jsonData).toList()
                            .map { it.groupValues[1] to it.groupValues[2] }.map { (ep, link) ->
                                Episode(link, episode = ep.toInt() + 1)
                            }
                        "RUSH" -> Regex("\"([0-9]+)\":\\[(.*?)]").findAll(jsonData).toList()
                            .map { it.groupValues[1] to it.groupValues[2] }.map { (ep, link) ->
                                val linkData =
                                    Regex("\"vid\":\"(\\S+?)\"").findAll(link)
                                        .map { it.groupValues[1] }
                                        .toList().joinToString("")
                                Episode(linkData, episode = ep.toInt() + 1)
                            }
                        else -> {
                            Regex("\"([0-9]+)\":\"(\\S+?)\"").findAll(jsonData).toList()
                                .map { it.groupValues[1] to it.groupValues[2] }.map { (ep, link) ->
                                    Episode(fixUrl(link), episode = ep.toInt() + 1)
                                }
                        }
                    }
                    episodeData.map {
                        if (data.url.contains("-dub")) {
                            dubEpisodes.add(it)
                        } else {
                            subEpisodes.add(it)
                        }
                    }
                }
            }

        val recommendations = app.get("$mainUrl/assets/similar/$malId.json")
            .parsedSafe<RecResult>()?.recommendations?.mapNotNull { rec ->
                newAnimeSearchResponse(
                    rec.title ?: return@mapNotNull null,
                    "$mainUrl/anime/${rec.malId}/",
                    TvType.Anime
                ) {
                    this.posterUrl = rec.imageUrl
                    addDubStatus(dubExist = false, subExist = true)
                }
            }

        return newAnimeLoadResponse(
            res.title ?: return null,
            url,
            TvType.Anime
        ) {
            engName = res.title
            posterUrl = res.imageUrl
            this.year = res.aired?.from?.split("-")?.firstOrNull()?.toIntOrNull()
            showStatus = getStatus(res.status)
            plot = res.synopsis
            this.tags = res.genres?.mapNotNull { it.name }
            this.recommendations = recommendations
            addMalId(malId?.toIntOrNull())
            addAniListId(anilistId?.toIntOrNull())
            addTrailer(res.trailerUrl)
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, groupEpisodes(subEpisodes))
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, groupEpisodes(dubEpisodes))
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.contains("\"")) {
            invokeGogo(data, subtitleCallback, callback)
        } else {
            data.split("http").apmap {
                val link = it.replace("\"", "").let { url -> "http$url".trim() }
                if (link.startsWith("https://gogohd.net")) {
                    invokeGogo(link, subtitleCallback, callback)
                } else {
                    loadExtractor(link, "$mainUrl/", subtitleCallback) { links ->
                        val name =
                            if (link.startsWith("https://streamsb.net")) "StreamNet" else links.name
                        callback.invoke(
                            ExtractorLink(
                                name,
                                name,
                                links.url,
                                links.referer,
                                links.quality,
                                links.isM3u8,
                                links.headers,
                                links.extractorData
                            )
                        )
                    }
                }
            }
        }
        return true
    }

    private fun groupEpisodes(episodes: List<Episode>): List<Episode> {
        return episodes.groupBy { it.episode }.map { eps ->
            val epsNum = eps.key
            val epsLink = eps.value.joinToString("") { it.data }.replace("\",\"", "")
            Episode(epsLink, episode = epsNum)
        }
    }

    private suspend fun invokeGogo(
        link: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val iframe = app.get(link)
        val iframeDoc = iframe.document
        argamap({
            iframeDoc.select(".list-server-items > .linkserver")
                .forEach { element ->
                    val status = element.attr("data-status") ?: return@forEach
                    if (status != "1") return@forEach
                    val extractorData = element.attr("data-video") ?: return@forEach
                    loadExtractor(extractorData, iframe.url, subtitleCallback, callback)
                }
        }, {
            val iv = "3134003223491201"
            val secretKey = "37911490979715163134003223491201"
            val secretDecryptKey = "54674138327930866480207815084989"
            extractVidstream(
                iframe.url,
                this.name,
                callback,
                iv,
                secretKey,
                secretDecryptKey,
                isUsingAdaptiveKeys = false,
                isUsingAdaptiveData = true,
                iframeDocument = iframeDoc
            )
        })
    }

    private data class IdAni(
        @JsonProperty("id") val id: String? = null,
    )

    private data class MediaAni(
        @JsonProperty("Media") val media: IdAni? = null,
    )

    private data class DataAni(
        @JsonProperty("data") val data: MediaAni? = null,
    )

    private data class Items(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("title") val title: String? = null,
    )

    private data class Episodes(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("items") val items: ArrayList<Items>? = arrayListOf(),
    )

    private data class Data(
        @JsonProperty("data") val data: ArrayList<Episodes>? = arrayListOf(),
    )

    private data class Aired(
        @JsonProperty("from") val from: String? = null,
    )

    private data class Genres(
        @JsonProperty("name") val name: String? = null,
    )

    private data class RecResult(
        @JsonProperty("recommendations") val recommendations: ArrayList<Recommendations>? = arrayListOf(),
    )

    private data class Recommendations(
        @JsonProperty("mal_id") val malId: String? = null,
        @JsonProperty("image_url") val imageUrl: String? = null,
        @JsonProperty("title") val title: String? = null,
    )

    private data class AnimeDetail(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("image_url") val imageUrl: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("aired") val aired: Aired? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("synopsis") val synopsis: String? = null,
        @JsonProperty("trailer_url") val trailerUrl: String? = null,
        @JsonProperty("genres") val genres: ArrayList<Genres>? = arrayListOf(),
    )

    private data class Search(
        @JsonProperty("result") val result: String? = null,
    )

    private data class Result(
        @JsonProperty("result") val result: ArrayList<Anime> = arrayListOf(),
        @JsonProperty("last") val last: Any? = null,
    )

    private data class Anime(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("img") val img: String? = null,
        @JsonProperty("picture") val picture: String? = null,
        @JsonProperty("infotext") val infotext: String? = null,
    )

    private data class FullSearch(
        @JsonProperty("result") val result: String? = null,
    )

}