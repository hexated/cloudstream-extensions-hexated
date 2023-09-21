package com.hexated

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.nicehttp.Requests.Companion.await
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

const val defaultTimeOut = 30L
suspend fun request(
    url: String,
    allowRedirects: Boolean = true,
    timeout: Long = defaultTimeOut
): Response {
    val client = OkHttpClient().newBuilder()
        .connectTimeout(timeout, TimeUnit.SECONDS)
        .readTimeout(timeout, TimeUnit.SECONDS)
        .writeTimeout(timeout, TimeUnit.SECONDS)
        .followRedirects(allowRedirects)
        .followSslRedirects(allowRedirects)
        .build()

    val request: Request = Request.Builder()
        .url(url)
        .build()
    return client.newCall(request).await()
}

fun Int.isSuccessful() : Boolean {
    return this in 200..299
}

fun String.fixSourceUrl(): String {
    return this.replace("/manifest.json", "").replace("stremio://", "https://")
}

fun fixRDSourceName(name: String?, title: String?): String {
    return when {
        name?.contains("[RD+]", true) == true -> "[RD+] $title"
        name?.contains("[RD download]", true) == true -> "[RD] $title"
        !name.isNullOrEmpty() && !title.isNullOrEmpty() -> "$name $title"
        else -> title ?: name ?: ""
    }
}

fun getEpisodeSlug(
    season: Int? = null,
    episode: Int? = null,
): Pair<String, String> {
    return if (season == null && episode == null) {
        "" to ""
    } else {
        (if (season!! < 10) "0$season" else "$season") to (if (episode!! < 10) "0$episode" else "$episode")
    }
}

fun isUpcoming(dateString: String?): Boolean {
    return try {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateTime = dateString?.let { format.parse(it)?.time } ?: return false
        unixTimeMS < dateTime
    } catch (t: Throwable) {
        logError(t)
        false
    }

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