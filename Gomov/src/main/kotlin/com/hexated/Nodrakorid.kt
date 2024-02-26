package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.net.URI

class Nodrakorid : Gomov() {
    override var mainUrl = "https://no1.nodrakor.store"
    override var name = "Nodrakorid"

    override val mainPage = mainPageOf(
        "genre/movie/page/%d/" to "Film Terbaru",
        "genre/korean-movie/page/%d/" to "Film Korea",
        "genre/drama/page/%d/" to "Drama Korea",
        "genre/c-drama/c-drama-c-drama/page/%d/" to "Drama China",
        "genre/thai-drama/page/%d/" to "Drama Thailand",
    )

    override suspend fun load(url: String): LoadResponse {
        return super.load(url).apply {
            when (this) {
                is TvSeriesLoadResponse -> {
                    val doc = app.get(url).document
                    this.comingSoon = false
                    this.episodes = when {
                        doc.select("div.vid-episodes a, div.gmr-listseries a").isNotEmpty() -> this.episodes
                        doc.select("div#download").isEmpty() -> {
                            doc.select("div.entry-content p:contains(Episode)").distinctBy {
                                it.text()
                            }.mapNotNull { eps ->
                                val endSibling = eps.nextElementSiblings().select("p:contains(Episode)").firstOrNull() ?: eps.nextElementSiblings().select("div.content-moviedata").firstOrNull()
                                val siblings = eps.nextElementSiblingsUntil(endSibling).map { ele ->
                                    ele.ownText().filter { it.isDigit() }.toIntOrNull() to ele.select("a")
                                        .map { it.attr("href") to it.text() }
                                }.filter { it.first != null }
                                Episode(siblings.toJson(), episode = eps.text().toEpisode())
                            }
                        }
                        else -> {
                            doc.select("div#download h3.title-download").mapNotNull { eps ->
                                val siblings = eps.nextElementSibling()?.select("li")?.map { ele ->
                                    ele.text().filter { it.isDigit() }.toIntOrNull() to ele.select("a").map {
                                        it.attr("href") to it.text().split(" ").first()
                                    }
                                }?.filter { it.first != null }
                                Episode(siblings?.toJson() ?: return@mapNotNull null, episode = eps.text().toEpisode())
                            }
                        }
                    }
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return if (data.startsWith("[")) {
            tryParseJson<ArrayList<LinkData>>(data)?.filter { it.first != 360 }?.map {
                it.second.apmap { link ->
                    loadFixedExtractor(
                        fixEmbed(link.first, link.second),
                        it.first,
                        "$mainUrl/",
                        subtitleCallback,
                        callback
                    )
                }
            }
            true
        } else {
            super.loadLinks(data, isCasting, subtitleCallback, callback)
        }
    }

    private fun fixEmbed(url: String, server: String): String {
        return when {
            server.contains("streamsb", true) -> {
                val host = getBaseUrl(url)
                url.replace("$host/", "$host/e/")
            }

            server.contains("hxfile", true) -> {
                val host = getBaseUrl(url)
                val id = url.substringAfterLast("/")
                "$host/embed-$id.html"
            }

            else -> url
        }
    }

    private fun String.toEpisode() : Int? {
        return Regex("(?i)Episode\\s?([0-9]+)").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: Int?,
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
                    if(link.type == ExtractorLinkType.M3U8) link.quality else quality ?: Qualities.Unknown.value,
                    link.type,
                    link.headers,
                    link.extractorData
                )
            )
        }
    }

    private fun Element.nextElementSiblingsUntil(untilElement: Element?): List<Element> {
        val siblings = mutableListOf<Element>()
        var currentElement = this.nextElementSibling()

        while (currentElement != null && currentElement != untilElement) {
            siblings.add(currentElement)
            currentElement = currentElement.nextElementSibling()
        }

        return siblings
    }

    data class LinkData(
        @JsonProperty("first") var first: Int? = null,
        @JsonProperty("second") var second: ArrayList<Second> = arrayListOf()
    )

    data class Second(
        @JsonProperty("first") var first: String,
        @JsonProperty("second") var second: String
    )
}
