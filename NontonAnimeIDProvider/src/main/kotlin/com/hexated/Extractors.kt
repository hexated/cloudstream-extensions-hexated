package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Hxfile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

open class Gdplayer : ExtractorApi() {
    override val name = "Gdplayer"
    override val mainUrl = "https://gdplayer.to"
    override val requiresReferer = true

    override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer).document
        val script = res.selectFirst("script:containsData(player = \"\")")?.data()
        val kaken = script?.substringAfter("kaken = \"")?.substringBefore("\"")

        val json = app.get(
                "$mainUrl/api/?${kaken ?: return}=&_=${APIHolder.unixTimeMS}",
                headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest"
                )
        ).parsedSafe<Response>()

        json?.sources?.map {
            callback.invoke(
                    ExtractorLink(
                            this.name,
                            this.name,
                            it.file ?: return@map,
                            "",
                            getQuality(json.title)
                    )
            )
        }
    }

    private fun getQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Qualities.Unknown.value
    }

    data class Response(
            @JsonProperty("title") val title: String? = null,
            @JsonProperty("sources") val sources: ArrayList<Sources>? = null,
    ) {
        data class Sources(
                @JsonProperty("file") val file: String? = null,
                @JsonProperty("type") val type: String? = null,
        )
    }

}

class Nontonanimeid : Hxfile() {
    override val name = "Nontonanimeid"
    override val mainUrl = "https://nontonanimeid.com"
    override val requiresReferer = true
}

class EmbedKotakAnimeid : Hxfile() {
    override val name = "EmbedKotakAnimeid"
    override val mainUrl = "https://embed2.kotakanimeid.com"
    override val requiresReferer = true
}

class KotakAnimeidCom : Hxfile() {
    override val name = "KotakAnimeid"
    override val mainUrl = "https://kotakanimeid.com"
    override val requiresReferer = true
}