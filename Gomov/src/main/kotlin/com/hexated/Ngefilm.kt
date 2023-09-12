package com.hexated

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class Ngefilm : DutaMovie() {
    override var mainUrl = "https://ngefilm21.lat"
    override var name = "Ngefilm"

    override val mainPage = mainPageOf(
        "/page/%d/?s&search=advanced&post_type=movie&index&orderby&genre&movieyear&country&quality=" to "Movies Terbaru",
        "/page/%d/?s=&search=advanced&post_type=tv&index=&orderby=&genre=&movieyear=&country=&quality=" to "Series Terbaru",
        "/page/%d/?s=&search=advanced&post_type=tv&index=&orderby=&genre=drakor&movieyear=&country=&quality=" to "Series Korea",
        "/page/%d/?s=&search=advanced&post_type=tv&index=&orderby=&genre=&movieyear=&country=indonesia&quality=" to "Series Indonesia",
    )

}