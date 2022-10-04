package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class OnetwothreeTv : MainAPI() {
    override var mainUrl = "http://123tv.live"
    override var name = "123tv"
    override val hasDownloadSupport = false
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Live
    )

    override val mainPage = mainPageOf(
        "1" to "United States (USA)",
        "$mainUrl/top-streams/" to "Top Streams",
        "$mainUrl/latest-streams/" to "Latest Streams",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        val nonPaged =
            (request.name == "Top Streams" || request.name == "Latest Streams") && page <= 1
        if (nonPaged) {
            val res = app.get(request.data).document
            val home = res.select("div.col-md-3.col-sm-6").mapNotNull {
                it.toSearchResult()
            }
            items.add(HomePageList(request.name, home, true))
        }

        if (request.name == "United States (USA)") {
            val res = app.post(
                "$mainUrl/wp-admin/admin-ajax.php", data = mapOf(
                    "action" to "_123tv_load_more_videos_from_category",
                    "cat_id" to request.data,
                    "page_num" to "${page.minus(1)}"
                ), headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).document
            val home = res.select("div.col-md-3.col-sm-6").mapNotNull {
                it.toSearchResult()
            }
            items.add(HomePageList(request.name, home, true))
        }

        return newHomePageResponse(items)

    }

    private fun Element.toSearchResult(): LiveSearchResponse? {
        return LiveSearchResponse(
            this.selectFirst("div.video-title h4")?.text() ?: return null,
            fixUrl(this.selectFirst("a")!!.attr("href")),
            this@OnetwothreeTv.name,
            TvType.Live,
            fixUrlNull(this.selectFirst("img")?.attr("src")),
        )

    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get(
            "$mainUrl/?s=$query"
        ).document.select("div.videos-latest-list.row div.col-md-3.col-sm-6").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        return LiveStreamLoadResponse(
            document.selectFirst("div.video-big-title h1")?.text() ?: return null,
            url,
            this.name,
            document.selectFirst("div.embed-responsive iframe")?.attr("src") ?: url,
            fixUrlNull(document.selectFirst("meta[name=\"twitter:image\"]")?.attr("content")),
            plot = document.select("div.watch-video-description p").text() ?: return null
        )
    }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    private fun cryptojsAESHandler(
        data: AesData,
        pass: String,
        encrypt: Boolean = true
    ): String {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val spec = PBEKeySpec(pass.toCharArray(), data.s.decodeHex(), 999, 256)
        val key = factory.generateSecret(spec)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        return if (!encrypt) {
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.encoded, "AES"),
                IvParameterSpec(data.iv.decodeHex())
            )
            String(cipher.doFinal(base64DecodeArray(data.ct)))
        } else {
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key.encoded, "AES"),
                IvParameterSpec(data.iv.decodeHex())
            )
            base64Encode(cipher.doFinal(data.ct.toByteArray()))

        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains(".m3u8")) {
            app.get(
                data,
                referer = "$mainUrl/"
            ).document.select("script").find { it.data().contains("var player=") }?.data()
                ?.substringAfter("source:'")?.substringBefore("',")?.let { link ->
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = link,
                            referer = "http://azureedge.xyz/",
                            quality = Qualities.Unknown.value,
                            isM3u8 = true,
                            headers = mapOf("Origin" to "http://azureedge.xyz")
                        )
                    )
                }
        } else {
            val script =
                app.get(data).document.select("script").find { it.data().contains("var post_id") }
                    ?.data() ?: throw ErrorLoadingException("No data found")
            val encodeData =
                Regex("\\w{6,10}=\\[(\\S+)];return").find(script)?.groupValues?.getOrNull(1)
                    ?.split(",")?.joinToString("") { it.replace("'", "") }
                    .let { base64Decode("$it") }
            val aesData = tryParseJson<AesData>(encodeData)
                ?: throw ErrorLoadingException("Invalid json responses")
            val pass = Regex("\\[((\\d{2,3},?\\s?){4})];").findAll(script).map { it.groupValues[1] }
                .toList().flatMap { it.split(",") }.map { it.toInt().toChar() }.reversed()
                .joinToString("")
            val decryptData = cryptojsAESHandler(aesData, pass, false)
            val jsonData = Regex("[\"|'](\\?1&json=\\S+)[\"|'];").find(script)?.groupValues?.get(1)
            val m3uLink = "${decryptData.substringBefore(".m3u8")}.m3u8$jsonData"
            app.get(m3uLink, referer = data).let {
                tryParseJson<List<Source>>(it.text)?.map { res ->
                    M3u8Helper.generateM3u8(
                        this.name,
                        res.file,
                        "$mainUrl/",
                        headers = mapOf("Origin" to mainUrl)
                    ).forEach(callback)
                }
            }

        }

        return true

    }

    data class Source(
        @JsonProperty("file") val file: String,
    )

    data class AesData(
        @JsonProperty("ciphertext") val ct: String,
        @JsonProperty("salt") val s: String,
        @JsonProperty("iv") val iv: String,
        @JsonProperty("iterations") val iterations: Int? = null,
    )
}