package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Paradisehill : MainAPI() {
    override var mainUrl = "https://en.paradisehill.cc"
    override var name = "Paradisehill"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override var sequentialMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/all/?sort=created_at&page=" to "New Porn Movies",
        "$mainUrl/popular/?filter=all&sort=by_likes&page=" to "Popular Porn Movies",
        "$mainUrl/studio/89/?sort=created_at&page=" to "Brazzers",
        "$mainUrl/studio/29/?sort=created_at&page=" to "Digital Playground",
        "$mainUrl/studio/16/?sort=created_at&page=" to "Evil Angel",
        "$mainUrl/studio/6/?sort=created_at&page=" to "Bang Bros Productions",
        "$mainUrl/studio/78/?sort=created_at&page=" to "Jules Jordan Video",
        "$mainUrl/studio/64/?sort=created_at&page=" to "Reality Kings",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page, verify = false).document
        val home =
            document.select("div.content div.item")
                .mapNotNull {
                    it.toSearchResult()
                }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("span[itemprop=name]")?.text() ?: return null
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..10) {
            val document = app.get("$mainUrl/search/?pattern=$query&what=1&page=$i").document
            val results = document.select("div.content div.item")
                .mapNotNull {
                    it.toSearchResult()
                }
            searchResponse.addAll(results)
            if(results.isEmpty()) break
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.title-inside")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("img[itemprop=thumbnailUrl]")?.attr("src"))
        val tags = document.select("div.opisanie span[itemprop=genre] a").map { it.text() }
        val year = document.select("div.opisanie span[itemprop=releasedEvent]").text()
            .let { Regex("[0-9]{4}").find(it)?.groupValues?.getOrNull(0)?.toIntOrNull() }
        val description = document.select("div.opisanie span[itemprop=description]").text().trim()
        val actors = document.select("div.opisanie p:contains(Actors:) a").map { it.text() }

        val dataEps =
            document.select("script").find { it.data().contains("var videoList =") }?.data()
                ?.substringAfter("videoList = [")?.substringBefore("];")?.let { data ->
                    Regex("\"src\":\"(\\S*?.mp4)\",").findAll(data).map { it.groupValues[1] }
                        .toList()
                }
        val episodes = dataEps?.mapIndexed { index, link ->
            Episode(link, episode = index + 1)
        } ?: throw ErrorLoadingException("No Episode Found")

        val recommendations =
            document.select("div.content div.item")
                .mapNotNull {
                    it.toSearchResult()
                }

        return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            addActors(actors)
            this.year = year
            this.recommendations = recommendations
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                data.replace("\\", ""),
                referer = mainUrl,
                quality = Qualities.Unknown.value,
//                headers = mapOf("Range" to "bytes=0-"),
            )
        )
        return true
    }

}