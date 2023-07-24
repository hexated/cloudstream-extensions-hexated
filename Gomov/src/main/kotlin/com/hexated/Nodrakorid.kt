package com.hexated

import com.lagradost.cloudstream3.mainPageOf

class Nodrakorid : DutaMovie() {
    override var mainUrl = "https://no-drak-or.xyz"
    override var name = "Nodrakorid"

    override val mainPage = mainPageOf(
        "genre/movie/page/%d/" to "Film Terbaru",
        "genre/korean-movie/page/%d/" to "Film Korea",
        "genre/drama/page/%d/" to "Drama Korea",
        "genre/c-drama/c-drama-c-drama/page/%d/" to "Drama China",
        "genre/thai-drama/page/%d/" to "Drama Thailand",
    )
}