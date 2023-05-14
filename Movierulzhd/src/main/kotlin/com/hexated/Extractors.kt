package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.hexated.RabbitStream.extractRabbitStream
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlin.random.Random

const val twoEmbedAPI = "https://www.2embed.to"

class Sbrulz : Sbflix() {
    override val name = "Sbrulz"
    override var mainUrl = "https://sbrulz.xyz"
}

open class Sbflix : ExtractorApi() {
    override val mainUrl = "https://sbflix.xyz"
    override val name = "Sbflix"
    override val requiresReferer = false
    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val regexID =
            Regex("(embed-[a-zA-Z\\d]{0,8}[a-zA-Z\\d_-]+|/e/[a-zA-Z\\d]{0,8}[a-zA-Z\\d_-]+)")
        val id = regexID.findAll(url).map {
            it.value.replace(Regex("(embed-|/e/)"), "")
        }.first()
        val master = "$mainUrl/375664356a494546326c4b797c7c6e756577776778623171737/${encodeId(id)}"
        val headers = mapOf(
            "watchsb" to "sbstream",
        )
        val mapped = app.get(
            master.lowercase(),
            headers = headers,
            referer = url,
        ).parsedSafe<Main>()
        callback.invoke(
            ExtractorLink(
                name,
                name,
                mapped?.streamData?.file ?: return,
                url,
                Qualities.P720.value,
                isM3u8 = true,
                headers = headers
            )
        )

        mapped.streamData.subs?.map {sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    sub.label.toString(),
                    sub.file ?: return@map null,
                )
            )
        }
    }

    private fun encodeId(id: String): String {
        val code = "${createHashTable()}||$id||${createHashTable()}||streamsb"
        return code.toCharArray().joinToString("") { char ->
            char.code.toString(16)
        }
    }

    private fun createHashTable(): String {
        return buildString {
            repeat(12) {
                append(alphabet[Random.nextInt(alphabet.length)])
            }
        }
    }

    data class Subs (
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
    )

    data class StreamData (
        @JsonProperty("file") val file: String,
        @JsonProperty("cdn_img") val cdnImg: String,
        @JsonProperty("hash") val hash: String,
        @JsonProperty("subs") val subs: ArrayList<Subs>? = arrayListOf(),
        @JsonProperty("length") val length: String,
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("backup") val backup: String,
    )

    data class Main (
        @JsonProperty("stream_data") val streamData: StreamData,
        @JsonProperty("status_code") val statusCode: Int,
    )

}

suspend fun invokeTwoEmbed(
    url: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val document = app.get(url ?: return).document
    val captchaKey =
        document.select("script[src*=https://www.google.com/recaptcha/api.js?render=]")
            .attr("src").substringAfter("render=")

    document.select(".dropdown-menu a[data-id]").map { it.attr("data-id") }.apmap { serverID ->
        val token = APIHolder.getCaptchaToken(url, captchaKey)
        app.get(
            "${twoEmbedAPI}/ajax/embed/play?id=$serverID&_token=$token", referer = url
        ).parsedSafe<EmbedJson>()?.let { source ->
            val link = source.link ?: return@let
            if (link.contains("rabbitstream")) {
                extractRabbitStream(
                    link,
                    subtitleCallback,
                    callback,
                    false,
                    decryptKey = RabbitStream.getKey()
                ) { it }
            } else {
                loadExtractor(
                    link, twoEmbedAPI, subtitleCallback, callback
                )
            }
        }
    }
}

data class EmbedJson(
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("link") val link: String? = null,
    @JsonProperty("sources") val sources: List<String?> = arrayListOf(),
    @JsonProperty("tracks") val tracks: List<String>? = null,
)
