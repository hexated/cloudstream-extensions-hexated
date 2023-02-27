package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

open class Vicloud : ExtractorApi() {
    override val name: String = "Vicloud"
    override val mainUrl: String = "https://vicloud.sbs"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("\"apiQuery\":\"(.*?)\"").find(app.get(url).text)?.groupValues?.getOrNull(1)
        app.get(
            "$mainUrl/api/?$id=&_=${System.currentTimeMillis()}",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = url
        ).parsedSafe<Responses>()?.sources?.map { source ->
            callback.invoke(
                ExtractorLink(
                    name,
                    name,
                    source.file ?: return@map null,
                    url,
                    getQualityFromName(source.label),
                )
            )
        }

    }

    private data class Sources(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
    )

    private data class Responses(
        @JsonProperty("sources") val sources: List<Sources>? = arrayListOf(),
    )

}

open class Uservideo : ExtractorApi() {
    override val name: String = "Uservideo"
    override val mainUrl: String = "https://uservideo.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val script = app.get(url).document.selectFirst("script:containsData(hosts =)")?.data()
        val host = script?.substringAfter("hosts = [\"")?.substringBefore("\"];")
        val servers = script?.substringAfter("servers = \"")?.substringBefore("\";")

        val sources = app.get("$host/s/$servers").text.substringAfter("\"sources\":[").substringBefore("],").let {
            AppUtils.tryParseJson<List<Sources>>("[$it]")
        }
        val quality = Regex("(\\d{3,4})[Pp]").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

        sources?.map { source ->
            callback.invoke(
                ExtractorLink(
                    name,
                    name,
                    source.src ?: return@map null,
                    url,
                    quality ?: Qualities.Unknown.value,
                )
            )
        }

    }

    data class Sources(
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("label") val label: String? = null,
    )

}