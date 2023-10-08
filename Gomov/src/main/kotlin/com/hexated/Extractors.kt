package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

open class Uplayer : ExtractorApi() {
    override val name = "Uplayer"
    override val mainUrl = "https://uplayer.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url,referer=referer).text
        val m3u8 = Regex("file:\\s*\"(.*?m3u8.*?)\"").find(res)?.groupValues?.getOrNull(1)
        M3u8Helper.generateM3u8(
            name,
            m3u8 ?: return,
            mainUrl
        ).forEach(callback)
    }

}

open class Kotakajaib : ExtractorApi() {
    override val name = "Kotakajaib"
    override val mainUrl = "https://kotakajaib.me"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        app.get(url,referer=referer).document.select("ul#dropdown-server li a").apmap {
            loadExtractor(base64Decode(it.attr("data-frame")), "$mainUrl/", subtitleCallback, callback)
        }
    }

}

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

class FilelionsOn : Filesim() {
    override val name = "Filelions"
    override var mainUrl = "https://filelions.online"
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

object NineTv {

        suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            val mainUrl = getBaseUrl(url)
            val res = app.get(url, referer = referer)
            val master = Regex("JScript\\s*=\\s*'([^']+)").find(res.text)?.groupValues?.get(1)
            val key = res.document.getKeys() ?: throw ErrorLoadingException("can't generate key")
            val decrypt = AesHelper.cryptoAESHandler(master ?: return, key.toByteArray(), false)
                ?.replace("\\", "")
                ?: throw ErrorLoadingException("failed to decrypt")

            val source = Regex(""""?file"?:\s*"([^"]+)""").find(decrypt)?.groupValues?.get(1)
            val tracks = Regex("""tracks:\s*\[(.+)]""").find(decrypt)?.groupValues?.get(1)
            val name = url.getHost()

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

    private fun Document.getKeys(): String? {
        val script = (this.selectFirst("script:containsData(eval\\()")?.data()
            ?.replace("eval(", "var result=")?.removeSuffix(");") + ";").trimIndent()
        val run = script.runJS("result")
        return """,\s*'([^']+)""".toRegex().find(run)?.groupValues?.getOrNull(1)
    }

    fun String.runJS(variable: String): String {
        val rhino = Context.enter()
        rhino.optimizationLevel = -1
        val scope: Scriptable = rhino.initSafeStandardObjects()
        val result: String
        try {
            rhino.evaluateString(scope, this, "JavaScript", 1, null)
            result = Context.toString(scope.get(variable, scope))
        } finally {
            Context.exit()
        }
        return result
    }

    data class Tracks(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null,
    )
}