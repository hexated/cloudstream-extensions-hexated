package com.hexated

import com.lagradost.cloudstream3.mainPageOf

class Animesaga : Movierulzhd() {

    override var mainUrl = "https://www.animesaga.in"
    override var name = "Animesaga"

    override val mainPage = mainPageOf(
        "movies" to "Movies",
        "tvshows" to "TV-Shows",
        "genre/hindi-dub" to "Hindi Dub",
    )


}