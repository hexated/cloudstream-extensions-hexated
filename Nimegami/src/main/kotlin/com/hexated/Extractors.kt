package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
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
            "https://api.mitedrive.com/api/view/$id",
            referer = "$mainUrl/",
            data = mapOf(
                "csrf_token" to "ZXlKcGNDSTZJak0yTGpneExqWTFMakUyTWlJc0ltUmxkbWxqWlNJNklrMXZlbWxzYkdFdk5TNHdJQ2hYYVc1a2IzZHpJRTVVSURFd0xqQTdJRmRwYmpZME95QjROalE3SUhKMk9qRXdNUzR3S1NCSFpXTnJieTh5TURFd01ERXdNU0JHYVhKbFptOTRMekV3TVM0d0lpd2lZbkp2ZDNObGNpSTZJazF2ZW1sc2JHRWlMQ0pqYjI5cmFXVWlPaUlpTENKeVpXWmxjbkpsY2lJNklpSjk=",
                "slug" to id
            )
        ).parsedSafe<Responses>()?.data?.url

        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                video ?: return,
                "$mainUrl/",
                Qualities.Unknown.value,
                INFER_TYPE,
            )
        )

    }

    data class Data(
        @JsonProperty("original_url") val url: String? = null,
    )

    data class Responses(
        @JsonProperty("data") val data: Data? = null,
    )

}