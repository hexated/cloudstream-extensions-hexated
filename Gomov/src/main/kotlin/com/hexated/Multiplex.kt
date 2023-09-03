package com.hexated

import com.lagradost.cloudstream3.mainPageOf

class Multiplex : DutaMovie() {
    override var mainUrl = "http://5.104.81.46"
    override var name = "Multiplex"

    override val mainPage = mainPageOf(
        "country/usa/page/%d/" to "Movie",
        "west-series/page/%d/" to "West Series",
        "nonton-drama-korea/page/%d/" to "Drama Korea",
    )

}