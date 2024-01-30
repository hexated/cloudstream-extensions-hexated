package com.hexated

import com.lagradost.cloudstream3.mainPageOf

class Cineclix : Moflix() {
    override var name = "Cineclix"
    override var mainUrl = "https://cineclix.in"
    override val mainPage = mainPageOf(
        "77/created_at:desc" to "Neuerscheinungen Filme",
        "82/created_at:desc" to "Neuerscheinungen Serien",
        "77/popularity:desc" to "Filme",
        "82/popularity:desc" to "Serien",
    )
}