package com.hexated

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.requestCreator
import java.net.URI
import java.util.ArrayList

object SoraExtractor : SoraStream() {

    suspend fun invokeLocalSources(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(
            url,
            headers = mapOf("User-Agent" to RandomUserAgent.getRandomUserAgent())
        ).document
        val script = doc.select("script").find { it.data().contains("\"sources\":[") }?.data()
        val sourcesData = script?.substringAfter("\"sources\":[")?.substringBefore("],")
        val subData = script?.substringAfter("\"subtitles\":[")?.substringBefore("],")

        AppUtils.tryParseJson<List<Sources>>("[$sourcesData]")?.map { source ->
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    source.url ?: return@map null,
                    "$mainServerAPI/",
                    source.quality?.toIntOrNull() ?: Qualities.Unknown.value,
                    isM3u8 = source.isM3U8,
                    headers = mapOf("Origin" to mainServerAPI)
                )
            )
        }

        AppUtils.tryParseJson<List<Subtitles>>("[$subData]")?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    sub.lang.toString(),
                    sub.url ?: return@map null
                )
            )
        }

    }

    suspend fun invokeTwoEmbed(
        id: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = if (season == null) {
            "$twoEmbedAPI/embed/tmdb/movie?id=$id"
        } else {
            "$twoEmbedAPI/embed/tmdb/tv?id=$id&s=$season&e=$episode"
        }
        val document = app.get(url).document
        val captchaKey =
            document.select("script[src*=https://www.google.com/recaptcha/api.js?render=]")
                .attr("src").substringAfter("render=")

        document.select(".dropdown-menu a[data-id]").map { it.attr("data-id") }.apmap { serverID ->
            val token = APIHolder.getCaptchaToken(url, captchaKey)
            app.get(
                "$twoEmbedAPI/ajax/embed/play?id=$serverID&_token=$token",
                referer = url
            ).parsedSafe<EmbedJson>()?.let { source ->
                Log.i("hexated", "${source.link}")
                loadExtractor(
                    source.link ?: return@let null,
                    twoEmbedAPI,
                    subtitleCallback,
                    callback
                )
            }
        }
    }

    suspend fun invokeVidSrc(
        id: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = if (season == null) {
            "$vidSrcAPI/embed/$id"
        } else {
            "$vidSrcAPI/embed/$id/${season}-${episode}"
        }

        loadExtractor(url, null, subtitleCallback, callback)
    }

    suspend fun invokeOlgply(
        id: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val url =
            "https://olgply.xyz/${id}${season?.let { "/$it" } ?: ""}${episode?.let { "/$it" } ?: ""}"
        loadLinksWithWebView(url, callback)
    }

    suspend fun invokeDbgo(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        var iframeDbgo: String? = null
        val script = if (season == null) {
            val doc = app.get("$dbgoAPI/imdb.php?id=$id").document
            iframeDbgo = doc.select("div.myvideo iframe").attr("src")
            app.get(iframeDbgo, referer = "$dbgoAPI/").document.select("script")
                .find { it.data().contains("CDNplayerConfig =") }?.data()
        } else {
            val doc = app.get("$dbgoAPI/tv-imdb.php?id=$id&s=$season").document
            iframeDbgo = doc.select("div.myvideo iframe").attr("src")
            val token = app.get(
                iframeDbgo,
                referer = "$dbgoAPI/"
            ).document.selectFirst("select#translator-name option")?.attr("data-token")
            app.get("https://voidboost.net/serial/$token/iframe?s=$season&e=$episode&h=dbgo.fun").document.select(
                "script"
            )
                .find { it.data().contains("CDNplayerConfig =") }?.data()
        }

        val source =
            Regex("['|\"]file['|\"]:\\s['|\"](#\\S+?)['|\"]").find(script.toString())?.groupValues?.get(
                1
            )
        val subtitle =
            Regex("['|\"]subtitle['|\"]:\\s['|\"](\\S+?)['|\"]").find(script.toString())?.groupValues?.get(
                1
            )

        decryptStreamUrl(source.toString()).split(",").map { links ->
            val quality =
                Regex("\\[([0-9]*p.*?)]").find(links)?.groupValues?.getOrNull(1).toString().trim()
            links.replace("[$quality]", "").split("or").map { it.trim() }
                .map { link ->
                    val name = if (link.contains(".m3u8")) "Dbgo (Main)" else "Dbgo (Backup)"
                    callback.invoke(
                        ExtractorLink(
                            name,
                            name,
                            link,
                            "${getBaseUrl(iframeDbgo)}/",
                            getQuality(quality),
                            isM3u8 = link.contains(".m3u8"),
                            headers = mapOf(
                                "Origin" to getBaseUrl(iframeDbgo)
                            )
                        )
                    )
                }
        }

        subtitle?.split(",")?.map { sub ->
            val language =
                Regex("\\[(.*)]").find(sub)?.groupValues?.getOrNull(1)
                    .toString()
            val link = sub.replace("[$language]", "").trim()
            subtitleCallback.invoke(
                SubtitleFile(
                    language,
                    link
                )
            )
        }

    }

}

private fun getQuality(str: String): Int {
    return when (str) {
        "360p" -> Qualities.P240.value
        "480p" -> Qualities.P360.value
        "720p" -> Qualities.P480.value
        "1080p" -> Qualities.P720.value
        "1080p Ultra" -> Qualities.P1080.value
        else -> getQualityFromName(str)
    }
}

private fun getBaseUrl(url: String): String {
    return URI(url).let {
        "${it.scheme}://${it.host}"
    }
}

private fun decryptStreamUrl(data: String): String {

    fun getTrash(arr: List<String>, item: Int): List<String> {
        val trash = ArrayList<List<String>>()
        for (i in 1..item) {
            trash.add(arr)
        }
        return trash.reduce { acc, list ->
            val temp = ArrayList<String>()
            acc.forEach { ac ->
                list.forEach { li ->
                    temp.add(ac.plus(li))
                }
            }
            return@reduce temp
        }
    }

    val trashList = listOf("@", "#", "!", "^", "$")
    val trashSet = getTrash(trashList, 2) + getTrash(trashList, 3)
    var trashString = data.replace("#2", "").split("//_//").joinToString("")

    trashSet.forEach {
        val temp = base64Encode(it.toByteArray())
        trashString = trashString.replace(temp, "")
    }

    return base64Decode(trashString)

}

suspend fun loadLinksWithWebView(
    url: String,
    callback: (ExtractorLink) -> Unit
) {
    val foundVideo = WebViewResolver(
        Regex("""\.m3u8|i7njdjvszykaieynzsogaysdgb0hm8u1mzubmush4maopa4wde\.com""")
    ).resolveUsingWebView(
        requestCreator(
            "GET", url, referer = "https://olgply.com/"
        )
    ).first ?: return

    callback.invoke(
        ExtractorLink(
            "Olgply",
            "Olgply",
            foundVideo.url.toString(),
            "",
            Qualities.Unknown.value,
            true
        )
    )
}