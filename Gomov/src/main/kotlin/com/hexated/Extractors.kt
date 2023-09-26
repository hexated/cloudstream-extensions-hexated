package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

class Doods : DoodLaExtractor() {
    override var name = "Doods"
    override var mainUrl = "https://doods.pro"
}

class Dutamovie21 : StreamSB() {
    override var name = "Dutamovie21"
    override var mainUrl = "https://dutamovie21.xyz"
}

class FilelionsTo : Filesim() {
    override val name = "Filelions"
    override var mainUrl = "https://filelions.to"
}

class Lylxan : Filesim() {
    override val name = "Lylxan"
    override var mainUrl = "https://lylxan.com"
}

class Embedwish : Filesim() {
    override val name = "Embedwish"
    override var mainUrl = "https://embedwish.com"
}

class Likessb : StreamSB() {
    override var name = "Likessb"
    override var mainUrl = "https://likessb.com"
}

class DbGdriveplayer : Gdriveplayer() {
    override var mainUrl = "https://database.gdriveplayer.us"
}

class NineTv {

    companion object {
        private const val key = "B#8G4o2\$WWFz"
        suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            val mainUrl = getBaseUrl(url)
            val master = Regex("MasterJS\\s*=\\s*'([^']+)").find(
                app.get(
                    url,
                    referer = referer
                ).text
            )?.groupValues?.get(1)
            val decrypt = AesHelper.cryptoAESHandler(master ?: return, key.toByteArray(), false)
                ?.replace("\\", "") ?: throw ErrorLoadingException("failed to decrypt")

            val name = url.getHost()
            val source = Regex(""""?file"?:\s*"([^"]+)""").find(decrypt)?.groupValues?.get(1)
            val tracks = Regex("""tracks:\s*\[(.+)]""").find(decrypt)?.groupValues?.get(1)

            M3u8Helper.generateM3u8(
                name,
                source ?: return,
                "$mainUrl/",
                headers = mapOf(
                    "Accept" to "*/*",
                    "Connection" to "keep-alive",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site",
                    "Origin" to mainUrl,
                )
            ).forEach(callback)

            AppUtils.tryParseJson<List<Tracks>>("[$tracks]")
                ?.filter { it.kind == "captions" }?.map { track ->
                    subtitleCallback.invoke(
                        SubtitleFile(
                            track.label ?: "",
                            track.file ?: return@map null
                        )
                    )
                }
        }
    }

    data class Tracks(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null,
    )
}