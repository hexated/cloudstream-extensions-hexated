package com.hexated

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class MoflixLink : MoflixClick() {
    override val name = "MoflixLink"
    override val mainUrl = "https://moflix-stream.link"
}

class MoflixFans : MoflixClick() {
    override val name = "MoflixFans"
    override val mainUrl = "https://moflix-stream.fans"
}

class Highstream : MoflixClick() {
    override val name = "Highstream"
    override val mainUrl = "https://highstream.tv"
}

open class MoflixClick : ExtractorApi() {
    override val name = "MoflixClick"
    override val mainUrl = "https://moflix-stream.click"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer)
        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            getAndUnpack(response.text)
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        }
        val m3u8 = Regex("file:\\s*\"(.*?m3u8.*?)\"").find(script ?: return)?.groupValues?.getOrNull(1)
        callback.invoke(
            ExtractorLink(
                name,
                name,
                m3u8 ?: return,
                "$mainUrl/",
                Qualities.Unknown.value,
                INFER_TYPE
            )
        )
    }

}