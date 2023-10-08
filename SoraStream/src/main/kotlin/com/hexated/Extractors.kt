package com.hexated

import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.GMPlayer
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.Voe
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.getCaptchaToken
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.Pixeldrain
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.math.BigInteger
import java.security.MessageDigest

open class Playm4u : ExtractorApi() {
    override val name = "Playm4u"
    override val mainUrl = "https://play9str.playm4u.xyz"
    override val requiresReferer = true
    private val password = "plhq@@@22"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document
        val script = document.selectFirst("script:containsData(idfile =)")?.data() ?: return
        val passScript = document.selectFirst("script:containsData(domain_ref =)")?.data() ?: return

        val pass = passScript.substringAfter("CryptoJS.MD5('").substringBefore("')")
        val amount = passScript.substringAfter(".toString()), ").substringBefore("));").toInt()

        val idFile = "idfile".findIn(script)
        val idUser = "idUser".findIn(script)
        val domainApi = "DOMAIN_API".findIn(script)
        val nameKeyV3 = "NameKeyV3".findIn(script)
        val dataEnc = caesarShift(
            mahoa(
                "Win32|$idUser|$idFile|$referer",
                md5(pass)
            ), amount
        ).toHex()

        val captchaKey =
            document.select("script[src*=https://www.google.com/recaptcha/api.js?render=]")
                .attr("src").substringAfter("render=")
        val token = getCaptchaToken(
            url,
            captchaKey,
            referer = referer
        )

        val source = app.post(
            domainApi, data = mapOf(
                "namekey" to nameKeyV3,
                "token" to "$token",
                "referrer" to "$referer",
                "data" to "$dataEnc|${md5(dataEnc + password)}",
            ), referer = "$mainUrl/"
        ).parsedSafe<Source>()

        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                source?.data ?: return,
                "$mainUrl/",
                Qualities.P1080.value,
                INFER_TYPE
            )
        )

        subtitleCallback.invoke(
            SubtitleFile(
                source.sub?.substringBefore("|")?.toLanguage() ?: return,
                source.sub.substringAfter("|"),
            )
        )

    }

    private fun caesarShift(str: String, amount: Int): String {
        var output = ""
        val adjustedAmount = if (amount < 0) amount + 26 else amount
        for (element in str) {
            var c = element
            if (c.isLetter()) {
                val code = c.code
                c = when (code) {
                    in 65..90 -> ((code - 65 + adjustedAmount) % 26 + 65).toChar()
                    in 97..122 -> ((code - 97 + adjustedAmount) % 26 + 97).toChar()
                    else -> c
                }
            }
            output += c
        }
        return output
    }

    private fun mahoa(input: String, key: String): String {
        val a = CryptoJS.encrypt(key, input)
        return a.replace("U2FsdGVkX1", "")
            .replace("/", "|a")
            .replace("+", "|b")
            .replace("=", "|c")
            .replace("|", "-z")
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return BigInteger(1, md.digest(input.toByteArray())).toString(16).padStart(32, '0')
    }

    private fun String.toHex(): String {
        return this.toByteArray().joinToString("") { "%02x".format(it) }
    }

    private fun String.findIn(data: String): String {
        return "$this\\s*=\\s*[\"'](\\S+)[\"'];".toRegex().find(data)?.groupValues?.get(1) ?: ""
    }

    private fun String.toLanguage() : String {
        return if(this == "EN") "English" else this
    }

    data class Source(
        @JsonProperty("data") val data: String? = null,
        @JsonProperty("sub") val sub: String? = null,
    )

}

open class M4ufree : ExtractorApi() {
    override val name = "M4ufree"
    override val mainUrl = "https://play.playm4u.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = session.get(url, referer = referer).document
        val script = document.selectFirst("script:containsData(idfile =)")?.data() ?: return

        val idFile = "idfile".findIn(script)
        val idUser = "idUser".findIn(script)

        val video = session.post(
            "https://api-plhq.playm4u.xyz/apidatard/$idUser/$idFile",
            data = mapOf("referrer" to "$referer"),
            headers = mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
            )
        ).text.let { AppUtils.tryParseJson<Source>(it) }?.data

        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                video ?: return,
                referer ?: "",
                Qualities.P720.value,
                INFER_TYPE
            )
        )

    }

    private fun String.findIn(data: String): String? {
        return "$this\\s*=\\s*[\"'](\\S+)[\"'];".toRegex().find(data)?.groupValues?.get(1)
    }

    data class Source(
        @JsonProperty("data") val data: String? = null,
    )

}

open class VCloud : ExtractorApi() {
    override val name: String = "V-Cloud"
    override val mainUrl: String = "https://v-cloud.bio"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url)
        val doc = res.document
        val changedLink = doc.selectFirst("script:containsData(url =)")?.data()?.let {
            """url\s*=\s*['"](.*)['"];""".toRegex().find(it)?.groupValues?.get(1)
                ?.substringAfter("r=")
        } ?: doc.selectFirst("div.div.vd.d-none a")?.attr("href")
        val header = doc.selectFirst("div.card-header")?.text()
        app.get(
            base64Decode(changedLink ?: return), cookies = res.cookies, headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            )
        ).document.select("p.text-success ~ a").apmap {
            val link = it.attr("href")
            if (link.contains("workers.dev")) {
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        link,
                        "",
                        getIndexQuality(header),
                        INFER_TYPE
                    )
                )
            } else {
                val direct = if(link.contains("gofile.io")) app.get(link).url else link
                loadExtractor(direct, referer, subtitleCallback, callback)
            }
        }

    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

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

    private fun String.runJS(variable: String): String {
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

open class Streamruby : ExtractorApi() {
    override val name = "Streamruby"
    override val mainUrl = "https://streamruby.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = "/e/(\\w+)".toRegex().find(url)?.groupValues?.get(1) ?: return
        val response = app.post("$mainUrl/dl", data = mapOf(
            "op" to "embed",
            "file_code" to id,
            "auto" to "1",
            "referer" to "",
        ), referer = referer)
        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            getAndUnpack(response.text)
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        }
        val m3u8 =
            Regex("file:\\s*\"(.*?m3u8.*?)\"").find(script ?: return)?.groupValues?.getOrNull(1)
        M3u8Helper.generateM3u8(
            name,
            m3u8 ?: return,
            mainUrl
        ).forEach(callback)
    }

}

class Streamwish : Filesim() {
    override val name = "Streamwish"
    override var mainUrl = "https://streamwish.to"
}

class FilelionsTo : Filesim() {
    override val name = "Filelions"
    override var mainUrl = "https://filelions.to"
}

class Hubcloud : VCloud() {
    override val name = "Hubcloud"
    override val mainUrl = "https://hubcloud.in"
}

class Pixeldra : Pixeldrain() {
    override val mainUrl = "https://pixeldra.in"
}

class TravelR : GMPlayer() {
    override val name = "TravelR"
    override val mainUrl = "https://travel-russia.xyz"
}

class Mwish : Filesim() {
    override val name = "Mwish"
    override var mainUrl = "https://mwish.pro"
}

class Animefever : Filesim() {
    override val name = "Animefever"
    override var mainUrl = "https://animefever.fun"
}

class Multimovies : Filesim() {
    override val name = "Multimovies"
    override var mainUrl = "https://multimovies.cloud"
}

class MultimoviesSB : StreamSB() {
    override var name = "Multimovies"
    override var mainUrl = "https://multimovies.website"
}

class Yipsu : Voe() {
    override val name = "Yipsu"
    override var mainUrl = "https://yip.su"
}