package com.hexated

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.argamap
import com.lagradost.cloudstream3.extractors.helper.GogoHelper
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI

object AnichiExtractors : Anichi() {

    suspend fun invokeInternalSources(
        hash: String,
        dubStatus: String,
        episode: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val apiUrl =
            """$apiUrl?variables={"showId":"$hash","translationType":"$dubStatus","episodeString":"$episode"}&extensions={"persistedQuery":{"version":1,"sha256Hash":"$serverHash"}}"""
        val apiResponse = app.get(apiUrl, headers = headers).parsed<LinksQuery>()

        apiResponse.data?.episode?.sourceUrls?.apmap { source ->
            safeApiCall {
                val link = fixSourceUrls(source.sourceUrl ?: return@safeApiCall, source.sourceName)
                    ?: return@safeApiCall
                if (URI(link).isAbsolute || link.startsWith("//")) {
                    val fixedLink = if (link.startsWith("//")) "https:$link" else link
                    val host = link.getHost()

                    when {
                        fixedLink.contains(Regex("(?i)playtaku|gogo")) || source.sourceName == "Vid-mp4" -> {
                            invokeGogo(fixedLink, subtitleCallback, callback)
                        }

                        embedIsBlacklisted(fixedLink) -> {
                            loadExtractor(fixedLink, subtitleCallback, callback)
                        }

                        URI(fixedLink).path.contains(".m3u") -> {
                            getM3u8Qualities(fixedLink, serverUrl, host).forEach(callback)
                        }

                        else -> {
                            callback(
                                ExtractorLink(
                                    name,
                                    host,
                                    fixedLink,
                                    serverUrl,
                                    Qualities.P1080.value,
                                    false
                                )
                            )
                        }
                    }
                } else {
                    val fixedLink = link.fixUrlPath()
                    val links = app.get(fixedLink).parsedSafe<AnichiVideoApiResponse>()?.links
                        ?: emptyList()
                    links.forEach { server ->
                        val host = server.link.getHost()
                        when {
                            source.sourceName?.contains("Default") == true -> {
                                if (server.resolutionStr == "SUB" || server.resolutionStr == "Alt vo_SUB") {
                                    getM3u8Qualities(
                                        server.link,
                                        "https://static.crunchyroll.com/",
                                        host,
                                    ).forEach(callback)
                                }
                            }

                            server.hls != null && server.hls -> {
                                getM3u8Qualities(
                                    server.link,
                                    "$apiEndPoint/player?uri=" + (if (URI(server.link).host.isNotEmpty()) server.link else apiEndPoint + URI(
                                        server.link
                                    ).path),
                                    host
                                ).forEach(callback)
                            }

                            else -> {
                                callback(
                                    ExtractorLink(
                                        host,
                                        host,
                                        server.link,
                                        "$apiEndPoint/player?uri=" + (if (URI(server.link).host.isNotEmpty()) server.link else apiEndPoint + URI(
                                            server.link
                                        ).path),
                                        server.resolutionStr.removeSuffix("p").toIntOrNull()
                                            ?: Qualities.P1080.value,
                                        false,
                                        isDash = server.resolutionStr == "Dash 1"
                                    )
                                )
                                server.subtitles?.map { sub ->
                                    subtitleCallback.invoke(
                                        SubtitleFile(
                                            SubtitleHelper.fromTwoLettersToLanguage(sub.lang ?: "")
                                                ?: sub.lang ?: "",
                                            httpsify(sub.src ?: return@map)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun invokeExternalSources(
        idMal: Int? = null,
        dubStatus: String,
        episode: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val ids = app.get("https://api.malsync.moe/mal/anime/${idMal ?: return}")
            .parsedSafe<MALSyncResponses>()?.sites

        if (dubStatus == "sub") invokeMarin(ids?.marin?.keys?.firstOrNull(), episode, callback)

    }

    private suspend fun invokeMarin(
        id: String? = null,
        episode: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = "$marinHost/anime/${id ?: return}/$episode"
        val cookies = app.get(
            "$marinHost/anime",
            headers = mapOf(
                "Cookie" to "__ddg1_=;__ddg2_=;"
            ),
            referer = "$marinHost/anime",
        ).cookies.let {
            decode(it["XSRF-TOKEN"].toString()) to decode(it["marin_session"].toString())
        }

        val json = app.get(
            url,
            headers = mapOf(
                "Accept" to "text/html, application/xhtml+xml",
                "Cookie" to "__ddg1=;__ddg2_=;XSRF-TOKEN=${cookies.first};marin_session=${cookies.second};",
                "X-XSRF-TOKEN" to cookies.first
            ),
            referer = "$marinHost/anime/$id"
        ).document.selectFirst("div#app")?.attr("data-page")
        tryParseJson<MarinResponses>(json)?.props?.video?.data?.mirror?.map { video ->
            callback.invoke(
                ExtractorLink(
                    "Marin",
                    "Marin",
                    video.code?.file ?: return@map,
                    url,
                    video.code.height ?: Qualities.Unknown.value,
                    headers = mapOf(
                        "Accept" to "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5",
                        "Accept-Language" to "en-US,en;q=0.5",
                        "Cookie" to "__ddg1=;__ddg2_=; XSRF-TOKEN=${cookies.first}; marin_session=${cookies.second};",
                        "Connection" to "keep-alive",
                        "Sec-Fetch-Dest" to "video",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "cross-site",
                    )
                )
            )
        }
    }

    private suspend fun invokeGogo(
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
            GogoHelper.extractVidstream(
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

}