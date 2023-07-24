package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

open class Mitedrive : ExtractorApi() {
    override val name = "Mitedrive"
    override val mainUrl = "https://mitedrive.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringAfterLast("/")
        val video = app.post(
            "$mainUrl/api/generate",
            referer = "$mainUrl/",
            data = mapOf(
                "short_url" to id
            )
        ).parsedSafe<Responses>()?.data?.url

        val headers = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl,
        )

        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                video ?: return,
                "$mainUrl/",
                Qualities.Unknown.value,
                headers = headers
            )
        )

    }

    data class Data(
        @JsonProperty("url") val url: String? = null,
    )

    data class Responses(
        @JsonProperty("data") val data: Data? = null,
    )

}