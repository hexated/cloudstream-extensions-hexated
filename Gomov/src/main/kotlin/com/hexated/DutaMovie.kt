package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor

open class DutaMovie : Gomov() {
    override var mainUrl = "https://dutamovie21.live"
    override var name = "DutaMovie"

    override val mainPage = mainPageOf(
        "category/box-office/page/%d/" to "Box Office",
        "category/serial-tv/page/%d/" to "Serial TV",
        "category/animation/page/%d/" to "Animasi",
        "country/korea/page/%d/" to "Serial TV Korea",
        "country/indonesia/page/%d/" to "Serial TV Indonesia",
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        app.get(data).document.select("ul.muvipro-player-tabs li a").apmap {
            val iframe = app.get(fixUrl(it.attr("href"))).document.selectFirst("div.gmr-embed-responsive iframe")
                ?.attr("src")
            loadExtractor(httpsify(iframe ?: return@apmap ), "$mainUrl/", subtitleCallback, callback)
        }

        return true
    }


}