package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object LocalServer {
    private const val KEY = "4VqE3#N7zt&HEP^a"

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

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
        val encData = AppUtils.tryParseJson<AESData>(base64Decode(master ?: return))
        val decrypt = cryptoAESHandler(encData ?: return, KEY, false)

        val source = Regex(""""?file"?:\s*"([^"]+)""").find(decrypt)?.groupValues?.get(1)

        // required
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
                Ngefilm().name,
                Ngefilm().name,
                source ?: return,
                "$mainUrl/",
                Qualities.P1080.value,
                headers = headers,
                isM3u8 = true
            )
        )

    }

    private fun cryptoAESHandler(
        data: AESData,
        pass: String,
        encrypt: Boolean = true
    ): String {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val spec = PBEKeySpec(
            pass.toCharArray(),
            data.salt?.hexToByteArray(),
            data.iterations?.toIntOrNull() ?: 1,
            256
        )
        val key = factory.generateSecret(spec)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        return if (!encrypt) {
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.encoded, "AES"),
                IvParameterSpec(data.iv?.hexToByteArray())
            )
            String(cipher.doFinal(base64DecodeArray(data.ciphertext.toString())))
        } else {
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key.encoded, "AES"),
                IvParameterSpec(data.iv?.hexToByteArray())
            )
            base64Encode(cipher.doFinal(data.ciphertext?.toByteArray()))
        }
    }

    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2)
            .map { it.toInt(16).toByte() }

            .toByteArray()
    }

    data class AESData(
        @JsonProperty("ciphertext") val ciphertext: String? = null,
        @JsonProperty("iv") val iv: String? = null,
        @JsonProperty("salt") val salt: String? = null,
        @JsonProperty("iterations") val iterations: String? = null,
    )

}