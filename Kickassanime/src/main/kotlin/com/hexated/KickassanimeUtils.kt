package com.hexated

import android.util.Base64
import com.hexated.KickassanimeExtractor.mainUrl
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.SubtitleHelper
import java.net.URI
import java.net.URLDecoder

suspend fun String.fixIframe(): List<Pair<String?, String?>> {
    return when {
        this.startsWith("${Kickassanime.kaast}/dust/") -> {
            val document = app.get(this).document
            document.selectFirst("script:containsData(sources =)")?.data()
                ?.substringAfter("sources = [")?.substringBefore("];")?.let {
                    AppUtils.tryParseJson<List<Kickassanime.Iframe>>("[$it]")?.map { source ->
                        source.name to source.src
                    }
                } ?: emptyList()
        }
        this.startsWith("${Kickassanime.kaast}/axplayer/") -> {
            val source = decode(
                this.substringAfter("&data=").substringBefore("&vref=")
            )
            listOf("gogo" to source)
        }
        else -> {
            emptyList()
        }
    }
}

fun String.base64Decode(): String {
    return Base64.decode(this, Base64.DEFAULT).toString(Charsets.UTF_8)
}

fun decode(input: String): String =
    URLDecoder.decode(input, "utf-8").replace(" ", "%20")

fun String.createSlug(): String {
    return this.replace(Regex("[^\\w ]+"), "").replace(" ", "-").lowercase()
}

fun String.getTrackerTitle(): String {
    val blacklist = arrayOf(
        "Dub",
        "Uncensored",
        "TV",
        "JPN DUB",
        "Uncensored"
    ).joinToString("|") { "\\($it\\)" }
    return this.replace(Regex(blacklist), "").trim()
}

fun getImageUrl(link: String?): String? {
    if (link == null) return null
    return if (link.startsWith(mainUrl)) link else "$mainUrl/uploads/$link"
}

fun getBaseUrl(url: String): String {
    return URI(url).let {
        "${it.scheme}://${it.host}"
    }
}


fun getLanguage(language: String?): String? {
    return SubtitleHelper.fromTwoLettersToLanguage(language ?: return null)
        ?: SubtitleHelper.fromTwoLettersToLanguage(language.substringBefore("-"))
}

fun fixUrl(url: String, domain: String): String {
    if (url.startsWith("http")) {
        return url
    }
    if (url.isEmpty()) {
        return ""
    }

    val startsWithNoHttp = url.startsWith("//")
    if (startsWithNoHttp) {
        return "https:$url"
    } else {
        if (url.startsWith('/')) {
            return domain + url
        }
        return "$domain/$url"
    }
}