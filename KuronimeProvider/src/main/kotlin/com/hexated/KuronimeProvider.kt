package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI
import java.security.DigestException
import java.security.MessageDigest
import java.util.ArrayList
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class KuronimeProvider : MainAPI() {
    override var mainUrl = "https://45.12.2.26"
    private var animekuUrl = "https://animeku.org"
    override var name = "Kuronime"
    override val hasQuickSearch = true
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        const val KEY = "3&!Z0M,VIZ;dZW=="
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special", true)) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "New Episodes",
        "$mainUrl/popular-anime/page/" to "Popular Anime",
        "$mainUrl/movies/page/" to "Movies",
//        "$mainUrl/genres/donghua/page/" to "Donghua",
//        "$mainUrl/live-action/page/" to "Live Action",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val req = app.get(request.data + page)
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        val home = document.select("article").map {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {
            var title = uri.substringAfter("$mainUrl/")
            title = when {
                (title.contains("-episode")) && !(title.contains("-movie")) -> Regex("nonton-(.+)-episode").find(
                    title
                )?.groupValues?.get(1).toString()

                (title.contains("-movie")) -> Regex("nonton-(.+)-movie").find(title)?.groupValues?.get(
                    1
                ).toString()

                else -> title
            }

            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val href = getProperAnimeLink(fixUrlNull(this.selectFirst("a")?.attr("href")).toString())
        val title = this.select(".bsuxtt, .tt > h4").text().trim()
        val posterUrl = fixUrlNull(
            this.selectFirst("div.view,div.bt")?.nextElementSibling()?.select("img")
                ?.attr("data-src")
        )
        val epNum = this.select(".ep").text().replace(Regex("\\D"), "").trim().toIntOrNull()
        val tvType = getType(this.selectFirst(".bt > span")?.text().toString())
        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }

    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        mainUrl = app.get(mainUrl).url
        return app.post(
            "$mainUrl/wp-admin/admin-ajax.php", data = mapOf(
                "action" to "ajaxy_sf",
                "sf_value" to query,
                "search" to "false"
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<Search>()?.anime?.firstOrNull()?.all?.mapNotNull {
            newAnimeSearchResponse(
                it.postTitle ?: "",
                it.postLink ?: return@mapNotNull null,
                TvType.Anime
            ) {
                this.posterUrl = it.postImage
                addSub(it.postLatest?.toIntOrNull())
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".entry-title")?.text().toString().trim()
        val poster = document.selectFirst("div.l[itemprop=image] > img")?.attr("data-src")
        val tags = document.select(".infodetail > ul > li:nth-child(2) > a").map { it.text() }
        val type =
            document.selectFirst(".infodetail > ul > li:nth-child(7)")?.ownText()?.removePrefix(":")
                ?.lowercase()?.trim() ?: "tv"

        val trailer = document.selectFirst("div.tply iframe")?.attr("data-src")
        val year = Regex("\\d, (\\d*)").find(
            document.select(".infodetail > ul > li:nth-child(5)").text()
        )?.groupValues?.get(1)?.toIntOrNull()
        val status = getStatus(
            document.selectFirst(".infodetail > ul > li:nth-child(3)")!!.ownText()
                .replace(Regex("\\W"), "")
        )
        val description = document.select("span.const > p").text()

        val episodes = document.select("div.bixbox.bxcl > ul > li").mapNotNull {
            val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val name = it.selectFirst("a")?.text() ?: return@mapNotNull null
            val episode =
                Regex("(\\d+[.,]?\\d*)").find(name)?.groupValues?.getOrNull(0)?.toIntOrNull()
            Episode(link, name, episode = episode)
        }.reversed()

        return newAnimeLoadResponse(title, url, getType(type)) {
            engName = title
            posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            addTrailer(trailer)
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        val id = document.selectFirst("div#content script:containsData(is_singular)")?.data()
            ?.substringAfter("\"")?.substringBefore("\";")
            ?: throw ErrorLoadingException("No id found")
        val servers = app.post(
            "$animekuUrl/afi.php", data = mapOf(
                "id" to id
            ), referer = "$mainUrl/"
        ).parsedSafe<Servers>()

        argamap(
            {
                val decrypt = cryptoAES(
                    servers?.src ?: return@argamap,
                    KEY.toByteArray(),
                    false
                )
                val source =
                    tryParseJson<Sources>(decrypt?.toJsonFormat())?.src?.replace("\\", "")
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        source ?: return@argamap,
                        "$animekuUrl/",
                        Qualities.P1080.value,
                        true,
                        headers = mapOf("Origin" to animekuUrl)
                    )
                )
            },
            {
                val decrypt = cryptoAES(
                    servers?.mirror ?: return@argamap,
                    KEY.toByteArray(),
                    false
                )
                tryParseJson<Mirrors>(decrypt)?.embed?.map { embed ->
                    embed.value.apmap {
                        loadFixedExtractor(
                            it.value,
                            embed.key.removePrefix("v"),
                            "$mainUrl/",
                            subtitleCallback,
                            callback
                        )
                    }
                }

            }
        )

        return true
    }

    private fun String.toJsonFormat(): String {
        return if (this.startsWith("\"")) this.substringAfter("\"").substringBeforeLast("\"")
            .replace("\\\"", "\"") else this
    }

    private suspend fun loadFixedExtractor(
        url: String? = null,
        quality: String? = null,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url ?: return, referer, subtitleCallback) { link ->
            callback.invoke(
                ExtractorLink(
                    link.name,
                    link.name,
                    link.url,
                    link.referer,
                    getQualityFromName(quality),
                    link.isM3u8,
                    link.headers,
                    link.extractorData
                )
            )
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    // https://stackoverflow.com/a/41434590/8166854
    private fun generateKeyAndIv(
        password: ByteArray,
        salt: ByteArray,
        hashAlgorithm: String = "MD5",
        keyLength: Int = 32,
        ivLength: Int = 16,
        iterations: Int = 1
    ): List<ByteArray>? {

        val md = MessageDigest.getInstance(hashAlgorithm)
        val digestLength = md.digestLength
        val targetKeySize = keyLength + ivLength
        val requiredLength = (targetKeySize + digestLength - 1) / digestLength * digestLength
        val generatedData = ByteArray(requiredLength)
        var generatedLength = 0

        try {
            md.reset()

            while (generatedLength < targetKeySize) {
                if (generatedLength > 0)
                    md.update(
                        generatedData,
                        generatedLength - digestLength,
                        digestLength
                    )

                md.update(password)
                md.update(salt, 0, 8)
                md.digest(generatedData, generatedLength, digestLength)

                for (i in 1 until iterations) {
                    md.update(generatedData, generatedLength, digestLength)
                    md.digest(generatedData, generatedLength, digestLength)
                }

                generatedLength += digestLength
            }
            return listOf(
                generatedData.copyOfRange(0, keyLength),
                generatedData.copyOfRange(keyLength, targetKeySize)
            )
        } catch (e: DigestException) {
            return null
        }
    }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    private fun cryptoAES(
        data: String,
        pass: ByteArray,
        encrypt: Boolean = true
    ): String? {
        val json = tryParseJson<AesData>(base64Decode(data))
            ?: throw ErrorLoadingException("No Data Found")
        val (key, iv) = generateKeyAndIv(pass, json.s.decodeHex()) ?: return null
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        return if (!encrypt) {
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(base64DecodeArray(json.ct)))
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            base64Encode(cipher.doFinal(json.ct.toByteArray()))

        }
    }

    data class AesData(
        @JsonProperty("ct") val ct: String,
        @JsonProperty("iv") val iv: String,
        @JsonProperty("s") val s: String
    )

    data class Mirrors(
        @JsonProperty("embed") val embed: Map<String, Map<String, String>> = emptyMap(),
    )

    data class Sources(
        @JsonProperty("src") var src: String? = null,
    )

    data class Servers(
        @JsonProperty("src") var src: String? = null,
        @JsonProperty("mirror") var mirror: String? = null,
    )

    data class All(
        @JsonProperty("post_image") var postImage: String? = null,
        @JsonProperty("post_image_html") var postImageHtml: String? = null,
        @JsonProperty("ID") var ID: Int? = null,
        @JsonProperty("post_title") var postTitle: String? = null,
        @JsonProperty("post_genres") var postGenres: String? = null,
        @JsonProperty("post_type") var postType: String? = null,
        @JsonProperty("post_latest") var postLatest: String? = null,
        @JsonProperty("post_sub") var postSub: String? = null,
        @JsonProperty("post_link") var postLink: String? = null
    )

    data class Anime(
        @JsonProperty("all") var all: ArrayList<All> = arrayListOf(),
    )

    data class Search(
        @JsonProperty("anime") var anime: ArrayList<Anime> = arrayListOf()
    )

}
