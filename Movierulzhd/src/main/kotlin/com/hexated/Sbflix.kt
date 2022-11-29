package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class Sbrulz : Sbflix() {
    override val name = "Sbrulz"
    override var mainUrl = "https://sbrulz.xyz"
}

open class Sbflix : ExtractorApi() {
    override val mainUrl = "https://sbflix.xyz"
    override val name = "Sbflix"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val regexID =
            Regex("(embed-[a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+|/e/[a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)")
        val id = regexID.findAll(url).map {
            it.value.replace(Regex("(embed-|/e/)"), "")
        }.first()
        val master = "$mainUrl/sources48/" + bytesToHex("||$id||||streamsb".toByteArray()) + "/"
        val headers = mapOf(
            "watchsb" to "sbstream",
        )
        val urltext = app.get(
            master.lowercase(),
            headers = headers,
            referer = url,
        ).text
        val mapped = urltext.let { AppUtils.parseJson<Main>(it) }
        callback.invoke(
            ExtractorLink(
                name,
                name,
                mapped.streamData.file,
                url,
                Qualities.Unknown.value,
                isM3u8 = true,
                headers = headers
            )
        )
    }

    private val hexArray = "0123456789ABCDEF".toCharArray()

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF

            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    data class Subs(
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String,
    )

    data class StreamData(
        @JsonProperty("file") val file: String,
        @JsonProperty("cdn_img") val cdnImg: String,
        @JsonProperty("hash") val hash: String,
        @JsonProperty("subs") val subs: List<Subs>?,
        @JsonProperty("length") val length: String,
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("backup") val backup: String,
    )

    data class Main(
        @JsonProperty("stream_data") val streamData: StreamData,
        @JsonProperty("status_code") val statusCode: Int,
    )

}