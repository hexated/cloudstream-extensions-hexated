package com.hexated

import com.lagradost.cloudstream3.*

class Kitanonton : RebahinProvider() {
    override var mainUrl = "http://kitanonton.skin/"
    override var name = "KitaNonton"
    override var mainServer = "https://199.87.210.226"

    override val mainPage = mainPageOf(
        "$mainUrl/genre/populer/page/" to "Populer Movies",
        "$mainUrl/movies/page/" to "New Movies",
        "$mainUrl/genre/westseries/page/" to "West TV Series",
        "$mainUrl/genre/drama-korea/page/" to "Drama Korea",
        "$mainUrl/genre/animation/page/" to "Anime",
        "$mainUrl/genre/series-indonesia/page/" to "Drama Indonesia",
        "$mainUrl/genre/drama-jepang/page/" to "Drama Jepang",
        "$mainUrl/genre/drama-china/page/" to "Drama China",
        "$mainUrl/genre/thailand-series/page/" to "Drama Thailand",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div#featured div.ml-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }
}
