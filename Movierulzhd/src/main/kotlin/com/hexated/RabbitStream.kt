package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.suspendSafeApiCall
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.coroutines.delay
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayList

object RabbitStream {

    suspend fun extractRabbitStream(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        useSidAuthentication: Boolean,
        /** Used for extractorLink name, input: Source name */
        extractorData: String? = null,
        decryptKey: String? = null,
        nameTransformer: (String) -> String,
    ) = suspendSafeApiCall {
        // https://rapid-cloud.ru/embed-6/dcPOVRE57YOT?z= -> https://rapid-cloud.ru/embed-6
        val mainIframeUrl =
            url.substringBeforeLast("/")
        val mainIframeId = url.substringAfterLast("/")
            .substringBefore("?") // https://rapid-cloud.ru/embed-6/dcPOVRE57YOT?z= -> dcPOVRE57YOT
        var sid: String? = null
        if (useSidAuthentication && extractorData != null) {
            negotiateNewSid(extractorData)?.also { pollingData ->
                app.post(
                    "$extractorData&t=${generateTimeStamp()}&sid=${pollingData.sid}",
                    requestBody = "40".toRequestBody(),
                    timeout = 60
                )
                val text = app.get(
                    "$extractorData&t=${generateTimeStamp()}&sid=${pollingData.sid}",
                    timeout = 60
                ).text.replaceBefore("{", "")

                sid = AppUtils.parseJson<PollingData>(text).sid
                ioSafe { app.get("$extractorData&t=${generateTimeStamp()}&sid=${pollingData.sid}") }
            }
        }
        val getSourcesUrl = "${
            mainIframeUrl.replace(
                "/embed",
                "/ajax/embed"
            )
        }/getSources?id=$mainIframeId${sid?.let { "$&sId=$it" } ?: ""}"
        val response = app.get(
            getSourcesUrl,
            referer = "${Movierulzhd().mainUrl}/",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.5",
                "Connection" to "keep-alive",
                "TE" to "trailers"
            )
        )

        val sourceObject = if (decryptKey != null) {
            val encryptedMap = response.parsedSafe<SourceObjectEncrypted>()
            val sources = encryptedMap?.sources
            if (sources == null || encryptedMap.encrypted == false) {
                response.parsedSafe()
            } else {
                val decrypted =
                    decryptMapped<List<Sources>>(sources, decryptKey)
                SourceObject(
                    sources = decrypted,
                    tracks = encryptedMap.tracks
                )
            }
        } else {
            response.parsedSafe()
        } ?: return@suspendSafeApiCall

        sourceObject.tracks?.forEach { track ->
            track?.toSubtitleFile()?.let { subtitleFile ->
                subtitleCallback.invoke(subtitleFile)
            }
        }

        val list = listOf(
            sourceObject.sources to "source 1",
            sourceObject.sources1 to "source 2",
            sourceObject.sources2 to "source 3",
            sourceObject.sourcesBackup to "source backup"
        )

        list.forEach { subList ->
            subList.first?.forEach { source ->
                source?.toExtractorLink(
                    "Vidcloud",
                    "$twoEmbedAPI/",
                    extractorData,
                )
                    ?.forEach {
                        // Sets Zoro SID used for video loading
//                            (this as? ZoroProvider)?.sid?.set(it.url.hashCode(), sid)
                        callback(it)
                    }
            }
        }
    }

    private suspend fun Sources.toExtractorLink(
        name: String,
        referer: String,
        extractorData: String? = null,
    ): List<ExtractorLink>? {
        return this.file?.let { file ->
            //println("FILE::: $file")
            val isM3u8 = URI(this.file).path.endsWith(".m3u8") || this.type.equals(
                "hls",
                ignoreCase = true
            )
            return if (isM3u8) {
                suspendSafeApiCall {
                    M3u8Helper().m3u8Generation(
                        M3u8Helper.M3u8Stream(
                            this.file,
                            null,
                            mapOf("Referer" to "https://mzzcloud.life/")
                        ), false
                    )
                        .map { stream ->
                            ExtractorLink(
                                name,
                                name,
                                stream.streamUrl,
                                referer,
                                getQualityFromName(stream.quality?.toString()),
                                true,
                                extractorData = extractorData
                            )
                        }
                }.takeIf { !it.isNullOrEmpty() } ?: listOf(
                    // Fallback if m3u8 extractor fails
                    ExtractorLink(
                        name,
                        name,
                        this.file,
                        referer,
                        getQualityFromName(this.label),
                        isM3u8,
                        extractorData = extractorData
                    )
                )
            } else {
                listOf(
                    ExtractorLink(
                        name,
                        name,
                        file,
                        referer,
                        getQualityFromName(this.label),
                        false,
                        extractorData = extractorData
                    )
                )
            }
        }
    }

    private fun Tracks.toSubtitleFile(): SubtitleFile? {
        return this.file?.let {
            SubtitleFile(
                this.label ?: "Unknown",
                it
            )
        }
    }

    /**
     * Generates a session
     * 1 Get request.
     * */
    private suspend fun negotiateNewSid(baseUrl: String): PollingData? {
        // Tries multiple times
        for (i in 1..5) {
            val jsonText =
                app.get("$baseUrl&t=${generateTimeStamp()}").text.replaceBefore(
                    "{",
                    ""
                )
//            println("Negotiated sid $jsonText")
            AppUtils.parseJson<PollingData?>(jsonText)?.let { return it }
            delay(1000L * i)
        }
        return null
    }

    private fun generateTimeStamp(): String {
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
        var code = ""
        var time = APIHolder.unixTimeMS
        while (time > 0) {
            code += chars[(time % (chars.length)).toInt()]
            time /= chars.length
        }
        return code.reversed()
    }

    suspend fun getKey(): String {
        return app.get("https://raw.githubusercontent.com/enimax-anime/key/e4/key.txt")
            .text
    }

    private inline fun <reified T> decryptMapped(input: String, key: String): T? {
        return AppUtils.tryParseJson(decrypt(input, key))
    }

    private fun decrypt(input: String, key: String): String {
        return decryptSourceUrl(
            generateKey(
                base64DecodeArray(input).copyOfRange(8, 16),
                key.toByteArray()
            ), input
        )
    }

    private fun generateKey(salt: ByteArray, secret: ByteArray): ByteArray {
        var key = md5(secret + salt)
        var currentKey = key
        while (currentKey.size < 48) {
            key = md5(key + secret + salt)
            currentKey += key
        }
        return currentKey
    }

    private fun md5(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("MD5").digest(input)
    }

    private fun decryptSourceUrl(decryptionKey: ByteArray, sourceUrl: String): String {
        val cipherData = base64DecodeArray(sourceUrl)
        val encrypted = cipherData.copyOfRange(16, cipherData.size)
        val aesCBC = Cipher.getInstance("AES/CBC/PKCS5Padding")

        Objects.requireNonNull(aesCBC).init(
            Cipher.DECRYPT_MODE, SecretKeySpec(
                decryptionKey.copyOfRange(0, 32),
                "AES"
            ),
            IvParameterSpec(decryptionKey.copyOfRange(32, decryptionKey.size))
        )
        val decryptedData = aesCBC!!.doFinal(encrypted)
        return String(decryptedData, StandardCharsets.UTF_8)
    }

    data class PollingData(
        @JsonProperty("sid") val sid: String? = null,
        @JsonProperty("upgrades") val upgrades: ArrayList<String> = arrayListOf(),
        @JsonProperty("pingInterval") val pingInterval: Int? = null,
        @JsonProperty("pingTimeout") val pingTimeout: Int? = null
    )

    data class Tracks(
        @JsonProperty("file") val file: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("kind") val kind: String?
    )

    data class Sources(
        @JsonProperty("file") val file: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("label") val label: String?
    )

    data class SourceObject(
        @JsonProperty("sources") val sources: List<Sources?>? = null,
        @JsonProperty("sources_1") val sources1: List<Sources?>? = null,
        @JsonProperty("sources_2") val sources2: List<Sources?>? = null,
        @JsonProperty("sourcesBackup") val sourcesBackup: List<Sources?>? = null,
        @JsonProperty("tracks") val tracks: List<Tracks?>? = null
    )

    data class SourceObjectEncrypted(
        @JsonProperty("sources") val sources: String?,
        @JsonProperty("encrypted") val encrypted: Boolean?,
        @JsonProperty("sources_1") val sources1: String?,
        @JsonProperty("sources_2") val sources2: String?,
        @JsonProperty("sourcesBackup") val sourcesBackup: String?,
        @JsonProperty("tracks") val tracks: List<Tracks?>?
    )

}