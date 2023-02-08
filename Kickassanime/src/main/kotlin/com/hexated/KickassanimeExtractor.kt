package com.hexated

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.argamap
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

object KickassanimeExtractor : Kickassanime() {

    suspend fun invokePinkbird(
        name: String,
        url: String? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixUrl = url?.replace(Regex("(player|embed)\\.php"), "pref.php")
        app.get(fixUrl ?: return,
        ).parsedSafe<PinkbirdSources>()?.data?.map { source ->
            val eid = base64Decode(source.eid ?: return@map null)
            callback.invoke(
                ExtractorLink(
                    name,
                    name,
                    "https://pb.kaast1.com/manifest/$eid/master.m3u8",
                    "$kaast/",
                    Qualities.P1080.value,
                    true
                )
            )
        }
    }

    suspend fun invokeAlpha(
        name: String,
        url: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixUrl = url?.replace(Regex("(player|embed)\\.php"), "pref.php")
        val data = app.get(
            fixUrl ?: return,
            referer = kaast
        ).document.selectFirst("script:containsData(Base64.decode)")?.data()
            ?.substringAfter("Base64.decode(\"")?.substringBefore("\")")?.let { base64Decode(it) } ?: return

        if (name == "Dailymotion") {
            val iframe = Jsoup.parse(data).select("iframe").attr("src")
            invokeDailymotion(iframe, subtitleCallback, callback)
        } else {
            val json = data.substringAfter("sources: [").substringBefore("],")
            AppUtils.tryParseJson<List<AlphaSources>>("[$json]")?.map {
                callback.invoke(
                    ExtractorLink(
                        name,
                        name,
                        it.file ?: return@map null,
                        url,
                        getQualityFromName(it.label)
                    )
                )
            }
        }

    }

    suspend fun invokeBeta(
        name: String,
        url: String? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        app.get(
            url ?: return,
            referer = kaast
        ).document.selectFirst("script:containsData(JSON.parse)")?.data()
            ?.substringAfter("JSON.parse('")?.substringBeforeLast("')")
            ?.let { AppUtils.tryParseJson<List<BetaSources>>(it) }?.map {
                callback.invoke(
                    ExtractorLink(
                        name,
                        name,
                        it.file ?: return@map null,
                        getBaseUrl(url),
                        getQualityFromName(it.label)
                    )
                )
            }
    }

    suspend fun invokeMave(
        name: String,
        url: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val fixUrl = url?.replace("/embed/", "/api/source/") ?: return
        val base = getBaseUrl(url)
        val data = app.get(fixUrl, referer = url).parsedSafe<MaveSources>()

        M3u8Helper.generateM3u8(
            if(data?.subtitles.isNullOrEmpty()) "$name [Hardsub]" else "$name [Softsub]",
            fixUrl(data?.hls ?: return, base),
            url
        ).forEach(callback)

        data.subtitles?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    sub.name ?: "",
                    fixUrl(sub.src ?: return@map null, base)
                )
            )
        }

    }

    suspend fun invokeSapphire(
        url: String? = null,
        isDub: Boolean = false,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        var data = app.get("$url&action=config", referer = url).text
        while(true) {
            if(data.startsWith("{") || data == "null") break
            data = data.base64Decode()
        }
        AppUtils.tryParseJson<SapphireSources>(data).let { res ->
            res?.streams?.filter { it.format == "adaptive_hls" }?.reversed()?.map { source ->
                val name = if(isDub) source.audio_lang else source.hardsub_lang.orEmpty().ifEmpty { "raw" }
                M3u8Helper.generateM3u8(
                    "Crunchyroll [$name]",
                    source.url ?: return@map null,
                    "https://static.crunchyroll.com/",
                ).forEach(callback)
            }
            res?.subtitles?.map { sub ->
                subtitleCallback.invoke(
                    SubtitleFile(
                        getLanguage(sub.language ?: return@map null) ?: sub.language,
                        sub.url ?: return@map null
                    )
                )
            }
        }
    }

    suspend fun invokeGogo(
        link: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val iframe = app.get(link)
        val iframeDoc = iframe.document
        argamap({
            iframeDoc.select(".list-server-items > .linkserver")
                .forEach { element ->
                    val status = element.attr("data-status") ?: return@forEach
                    if (status != "1") return@forEach
                    val extractorData = element.attr("data-video") ?: return@forEach
                    loadExtractor(extractorData, iframe.url, subtitleCallback, callback)
                }
        }, {
            val iv = "3134003223491201"
            val secretKey = "37911490979715163134003223491201"
            val secretDecryptKey = "54674138327930866480207815084989"
            GogoExtractor.extractVidstream(
                iframe.url,
                "Gogoanime",
                callback,
                iv,
                secretKey,
                secretDecryptKey,
                isUsingAdaptiveKeys = false,
                isUsingAdaptiveData = true,
                iframeDocument = iframeDoc
            )
        })
    }

    suspend fun invokeDailymotion(
        link: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(link, mainUrl, subtitleCallback) { video ->
            callback.invoke(
                ExtractorLink(
                    video.name,
                    video.name,
                    video.url,
                    video.referer,
                    Qualities.P1080.value,
                    video.isM3u8,
                    video.headers,
                    video.extractorData
                )
            )
        }
    }
}