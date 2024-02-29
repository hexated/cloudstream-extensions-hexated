package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor

class DutaMovie : Gomov() {

    override var mainUrl = "https://viral.dutamovie21.tech"
override var name = "DutaMovie"
    override val mainPage = mainPageOf(
        "category/box-office/page/%d/" to "Box Office",
        "category/serial-tv/page/%d/" to "Serial TV",
        "category/animation/page/%d/" to "Animasi",
        "country/korea/page/%d/" to "Serial TV Korea",
        "country/indonesia/page/%d/" to "Serial TV Indonesia",
    )

}
