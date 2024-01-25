package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.normalSafeApiCall
import com.lagradost.cloudstream3.utils.*
import java.net.URL

object Extractors : Superstream() {

    suspend fun invokeInternalSource(
        id: Int? = null,
        type: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        fun LinkList.toExtractorLink(): ExtractorLink? {
            if (this.path.isNullOrBlank()) return null
            return ExtractorLink(
                "Internal",
                "Internal [${this.size}]",
                this.path.replace("\\/", ""),
                "",
                getQualityFromName(this.quality),
            )
        }

        // No childmode when getting links
        // New api does not return video links :(
        val query = if (type == ResponseTypes.Movies.value) {
            """{"childmode":"0","uid":"","app_version":"11.5","appid":"$appId","module":"Movie_downloadurl_v3","channel":"Website","mid":"$id","lang":"","expired_date":"${getExpiryDate()}","platform":"android","oss":"1","group":""}"""
        } else {
            """{"childmode":"0","app_version":"11.5","module":"TV_downloadurl_v3","channel":"Website","episode":"$episode","expired_date":"${getExpiryDate()}","platform":"android","tid":"$id","oss":"1","uid":"","appid":"$appId","season":"$season","lang":"en","group":""}"""
        }

        val linkData = queryApiParsed<LinkDataProp>(query, false)
        linkData.data?.list?.forEach {
            callback.invoke(it.toExtractorLink() ?: return@forEach)
        }

        // Should really run this query for every link :(
        val fid = linkData.data?.list?.firstOrNull { it.fid != null }?.fid

        val subtitleQuery = if (type == ResponseTypes.Movies.value) {
            """{"childmode":"0","fid":"$fid","uid":"","app_version":"11.5","appid":"$appId","module":"Movie_srt_list_v2","channel":"Website","mid":"$id","lang":"en","expired_date":"${getExpiryDate()}","platform":"android"}"""
        } else {
            """{"childmode":"0","fid":"$fid","app_version":"11.5","module":"TV_srt_list_v2","channel":"Website","episode":"$episode","expired_date":"${getExpiryDate()}","platform":"android","tid":"$id","uid":"","appid":"$appId","season":"$season","lang":"en"}"""
        }

        val subtitles = queryApiParsed<SubtitleDataProp>(subtitleQuery).data
        subtitles?.list?.forEach { subs ->
            val sub = subs.subtitles.maxByOrNull { it.support_total ?: 0 }
            subtitleCallback.invoke(
                SubtitleFile(
                    sub?.language ?: sub?.lang ?: return@forEach,
                    sub?.filePath ?: return@forEach
                )
            )
        }
    }

    suspend fun invokeExternalSource(
        mediaId: Int? = null,
        type: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
        val shareKey = app.get("$fourthAPI/index/share_link?id=${mediaId}&type=$type")
            .parsedSafe<ExternalResponse>()?.data?.link?.substringAfterLast("/") ?: return

        val headers = mapOf("Accept-Language" to "en")
        val shareRes = app.get("$thirdAPI/file/file_share_list?share_key=$shareKey", headers = headers)
            .parsedSafe<ExternalResponse>()?.data ?: return

        val fids = if (season == null) {
            shareRes.file_list
        } else {
            val parentId = shareRes.file_list?.find { it.file_name.equals("season $season", true) }?.fid
            app.get("$thirdAPI/file/file_share_list?share_key=$shareKey&parent_id=$parentId&page=1", headers = headers)
                .parsedSafe<ExternalResponse>()?.data?.file_list?.filter {
                    it.file_name?.contains("s${seasonSlug}e${episodeSlug}", true) == true
                }
        } ?: return

        fids.apmapIndexed { index, fileList ->
            val player = app.get("$thirdAPI/file/player?fid=${fileList.fid}&share_key=$shareKey").text
            val sources = "sources\\s*=\\s*(.*);".toRegex().find(player)?.groupValues?.get(1)
            val qualities = "quality_list\\s*=\\s*(.*);".toRegex().find(player)?.groupValues?.get(1)
            listOf(sources, qualities).forEach {
                AppUtils.tryParseJson<ArrayList<ExternalSources>>(it)?.forEach org@{ source ->
                    val format = if (source.type == "video/mp4") ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                    val label = if (format == ExtractorLinkType.M3U8) "Hls" else "Mp4"
                    if(!(source.label == "AUTO" || format == ExtractorLinkType.VIDEO)) return@org
                    callback.invoke(
                        ExtractorLink(
                            "External",
                            "External $label [Server ${index + 1}]",
                            (source.m3u8_url ?: source.file)?.replace("\\/", "/") ?: return@org,
                            "",
                            getIndexQuality(if (format == ExtractorLinkType.M3U8) fileList.file_name else source.label),
                            type = format,
                        )
                    )
                }
            }
        }
    }

    suspend fun invokeWatchsomuch(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val id = imdbId?.removePrefix("tt")
        val epsId = app.post(
            "$watchSomuchAPI/Watch/ajMovieTorrents.aspx",
            data = mapOf(
                "index" to "0",
                "mid" to "$id",
                "wsk" to "30fb68aa-1c71-4b8c-b5d4-4ca9222cfb45",
                "lid" to "",
                "liu" to ""
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<WatchsomuchResponses>()?.movie?.torrents?.let { eps ->
            if (season == null) {
                eps.firstOrNull()?.id
            } else {
                eps.find { it.episode == episode && it.season == season }?.id
            }
        } ?: return

        val (seasonSlug, episodeSlug) = getEpisodeSlug(
            season,
            episode
        )

        val subUrl = if (season == null) {
            "$watchSomuchAPI/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part="
        } else {
            "$watchSomuchAPI/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part=S${seasonSlug}E${episodeSlug}"
        }

        app.get(subUrl)
            .parsedSafe<WatchsomuchSubResponses>()?.subtitles
            ?.map { sub ->
                subtitleCallback.invoke(
                    SubtitleFile(
                        sub.label ?: "",
                        fixUrl(sub.url ?: return@map null, watchSomuchAPI)
                    )
                )
            }


    }

    suspend fun invokeOpenSubs(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val slug = if(season == null) {
            "movie/$imdbId"
        } else {
            "series/$imdbId:$season:$episode"
        }
        app.get("${openSubAPI}/subtitles/$slug.json", timeout = 120L).parsedSafe<OsResult>()?.subtitles?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    SubtitleHelper.fromThreeLettersToLanguage(sub.lang ?: "") ?: sub.lang
                    ?: return@map,
                    sub.url ?: return@map
                )
            )
        }
    }

    suspend fun invokeVidsrcto(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val url = if (season == null) {
            "$vidsrctoAPI/embed/movie/$imdbId"
        } else {
            "$vidsrctoAPI/embed/tv/$imdbId/$season/$episode"
        }

        val mediaId = app.get(url).document.selectFirst("ul.episodes li a")?.attr("data-id") ?: return
        val subtitles = app.get("$vidsrctoAPI/ajax/embed/episode/$mediaId/subtitles").text
        AppUtils.tryParseJson<List<VidsrcSubtitles>>(subtitles)?.map {
            subtitleCallback.invoke(
                SubtitleFile(
                    it.label ?: "",
                    it.file ?: return@map
                )
            )
        }

    }

    private fun fixUrl(url: String, domain: String): String {
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

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun getEpisodeSlug(
        season: Int? = null,
        episode: Int? = null,
    ): Pair<String, String> {
        return if (season == null && episode == null) {
            "" to ""
        } else {
            (if (season!! < 10) "0$season" else "$season") to (if (episode!! < 10) "0$episode" else "$episode")
        }
    }

}