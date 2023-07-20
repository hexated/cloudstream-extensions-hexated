package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.SpeedoStream
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getAndUnpack

class Streamoupload : SpeedoStream() {
    override val mainUrl = "https://streamoupload.xyz"
    override val name = "Streamoupload"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val script = getAndUnpack(app.get(url, referer = referer).text)
        val data = script.substringAfter("sources:[")
            .substringBefore("],").replace("file", "\"file\"").trim()
        AppUtils.tryParseJson<File>(data)?.let {
            M3u8Helper.generateM3u8(
                name,
                it.file,
                "$mainUrl/",
            ).forEach(callback)
        }
    }

    private data class File(
        @JsonProperty("file") val file: String,
    )
}