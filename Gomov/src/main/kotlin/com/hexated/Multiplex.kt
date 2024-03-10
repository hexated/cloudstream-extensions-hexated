package com.hexated

import com.lagradost.cloudstream3.mainPageOf

class Multiplex : Gomov() {


    override var mainUrl = "https://imb-bmi.org/"


    override var name = "Multiplex"
    override val mainPage = mainPageOf(
        "country/usa/page/%d/" to "Movie",
        "west-series/page/%d/" to "West Series",
        "nonton-drama-korea/page/%d/" to "Drama Korea",
    )

}
