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

        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                video ?: return,
                "",
                Qualities.Unknown.value,
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