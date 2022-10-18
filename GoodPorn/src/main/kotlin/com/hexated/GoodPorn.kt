package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.*

class GoodPorn : MainAPI() {
    override var mainUrl = "https://goodporn.to"
    override var name = "GoodPorn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/?mode=async&function=get_block&block_id=list_videos_most_recent_videos&sort_by=post_date&from=" to "New Videos",
        "$mainUrl/?mode=async&function=get_block&block_id=list_videos_most_recent_videos&sort_by=video_viewed&from=" to "Most Viewed Videos",
        "$mainUrl/?mode=async&function=get_block&block_id=list_videos_most_recent_videos&sort_by=rating&from=" to "Top Rated Videos ",
        "$mainUrl/?mode=async&function=get_block&block_id=list_videos_most_recent_videos&sort_by=most_commented&from=" to "Most Commented Videos",
        "$mainUrl/?mode=async&function=get_block&block_id=list_videos_most_recent_videos&sort_by=duration&from=" to "Longest Videos",
        "$mainUrl/channels/brazzers/?mode=async&function=get_block&block_id=list_videos_common_videos_list&sort_by=post_date&from=" to "Brazzers",
        "$mainUrl/channels/digitalplayground/?mode=async&function=get_block&block_id=list_videos_common_videos_list&sort_by=post_date&from=" to "Digital Playground",
        "$mainUrl/channels/realitykings/?mode=async&function=get_block&block_id=list_videos_common_videos_list&sort_by=post_date&from=" to "Realitykings",
        "$mainUrl/channels/babes-network/?mode=async&function=get_block&block_id=list_videos_common_videos_list&sort_by=post_date&from=" to "Babes Network",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home =
            document.select("div#list_videos_most_recent_videos_items div.item, div#list_videos_common_videos_list_items div.item")
                .mapNotNull {
                    it.toSearchResult()
                }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("strong.title")?.text() ?: return null
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val posterUrl = fixUrlNull(this.select("div.img > img").attr("data-original"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..15) {
            val document =
                app.get(
                    "$mainUrl/search/nikki-benz/?mode=async&function=get_block&block_id=list_videos_videos_list_search_result&q=$query&category_ids=&sort_by=&from_videos=$i&from_albums=$i",
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).document
            val results =
                document.select("div#list_videos_videos_list_search_result_items div.item")
                    .mapNotNull {
                        it.toSearchResult()
                    }
            searchResponse.addAll(results)
            if (results.isEmpty()) break
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("div.headline > h1")?.text()?.trim().toString()
        val poster =
            fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content").toString())
        val tags = document.select("div.info div:nth-child(5) > a").map { it.text() }
        val description = document.select("div.info div:nth-child(2)").text().trim()
        val actors = document.select("div.info div:nth-child(6) > a").map { it.text() }
        val recommendations =
            document.select("div#list_videos_related_videos_items div.item").mapNotNull {
                it.toSearchResult()
            }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            addActors(actors)
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("div.info div:last-child a").map { res ->
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    res.attr("href")
                        .replace(Regex("\\?download\\S+.mp4&"), "?") + "&rnd=${Date().time}",
                    referer = data,
                    quality = Regex("([0-9]+p),").find(res.text())?.groupValues?.get(1)
                        .let { getQualityFromName(it) },
                    headers = mapOf("Range" to "bytes=0-"),
                )
            )
        }

        return true
    }

}