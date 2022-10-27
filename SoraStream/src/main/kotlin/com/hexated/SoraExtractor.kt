package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.requestCreator
import java.net.URI

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

        tryParseJson<List<Sources>>("[$sourcesData]")?.map { source ->
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

        tryParseJson<List<Subtitles>>("[$subData]")?.map { sub ->
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

        loadExtractor(url, null, subtitleCallback) { link ->
            callback.invoke(
                ExtractorLink(
                    link.name,
                    link.name,
                    link.url,
                    link.referer,
                    Qualities.P1080.value,
                    link.isM3u8,
                    link.headers,
                    link.extractorData
                )
            )
        }
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

    suspend fun invoke123Movie(
        tmdbId: Int? = null,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = if (season == null) {
            "$movie123API/imdb.php?imdb=$imdbId&server=vcu"
        } else {
            "$movie123API/tmdb_api.php?se=$season&ep=$episode&tmdb=$tmdbId&server_name=vcu"
        }
        val iframe = app.get(url).document.selectFirst("iframe")?.attr("src")

        val doc = app.get(
            "$iframe",
            referer = url,
            headers = mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        ).document

        doc.select("ul.list-server-items li.linkserver").mapNotNull { server ->
            server.attr("data-video").let {
                Regex("(.*?)((\\?cap)|(\\?sub)|(#cap)|(#sub))").find(it)?.groupValues?.get(1)
            }
        }.apmap { link ->
            loadExtractor(
                link,
                "https://123moviesjr.cc/",
                subtitleCallback,
                callback
            )
        }
    }

    suspend fun invokeMovieHab(
        id: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = if (season == null) {
            "$movieHabAPI/embed/movie?tmdb=$id"
        } else {
            "$movieHabAPI/embed/series?tmdb=$id&sea=$season&epi=$episode"
        }

        val doc = app.get(url, referer = "$movieHabAPI/").document
        val movieId = doc.select("div#embed-player").attr("data-movie-id")

        doc.select("div.dropdown-menu a").apmap {
            val dataId = it.attr("data-id")
            app.get(
                "$movieHabAPI/ajax/get_stream_link?id=$dataId&movie=$movieId&is_init=true&captcha=&ref=",
                referer = url,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<MovieHabRes>()?.data?.let { res ->
                loadExtractor(
                    res.link ?: return@let null,
                    movieHabAPI,
                    subtitleCallback,
                    callback
                )
            }
        }
    }

    suspend fun invokeDatabaseGdrive(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = if (season == null) {
            "$databaseGdriveAPI/player.php?imdb=$imdbId"
        } else {
            "$databaseGdriveAPI/player.php?type=series&imdb=$imdbId&season=$season&episode=$episode"
        }
        loadExtractor(url, databaseGdriveAPI, subtitleCallback, callback)
    }

    suspend fun invokeSoraVIP(
        id: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val providerId = if (season == null) {
            val url = "$mainServerAPI/movies/$id/?_data=routes/movies/\$movieId"
            val data = app.get(url).parsedSafe<DetailVipResult>()?.detail
            app.get(
                "$mainServerAPI/api/provider?title=${data?.title ?: data?.name}&type=movie&origTitle=${data?.original_title ?: data?.original_name}&year=${
                    (data?.release_date ?: data?.first_air_date)?.substringBefore("-")
                }&_data=routes/api/provider"
            )
                .parsedSafe<ProvidersResult>()?.provider?.first { it.provider == "Loklok" }?.id

        } else {
            val url = "$mainServerAPI/tv-shows/$id/?_data=routes/tv-shows/\$tvId"
            val data = app.get(url).parsedSafe<DetailVipResult>()?.detail
            app.get(
                "$mainServerAPI/api/provider?title=${data?.title ?: data?.name}&type=tv&origTitle=${data?.original_title ?: data?.original_name}&year=${
                    (data?.release_date ?: data?.first_air_date)?.substringBefore("-")
                }&season=$season&_data=routes/api/provider"
            )
                .parsedSafe<ProvidersResult>()?.provider?.first { it.provider == "Loklok" }?.id

        }

        val query = if (season == null) {
            "$mainServerAPI/movies/$id/watch?provider=Loklok&id=$providerId&_data=routes/movies/\$movieId.watch"
        } else {
            "$mainServerAPI/tv-shows/$id/season/$season/episode/${episode?.minus(1)}?provider=Loklok&id=$providerId&_data=routes/tv-shows/\$tvId.season.\$seasonId.episode.\$episodeId"
        }

        val json = app.get(
            query,
            headers = mapOf("User-Agent" to RandomUserAgent.getRandomUserAgent())
        ).parsedSafe<LoadLinks>()

        json?.sources?.map { source ->
            callback.invoke(
                ExtractorLink(
                    "${this.name} (VIP)",
                    "${this.name} (VIP)",
                    source.url ?: return@map null,
                    "$mainServerAPI/",
                    source.quality?.toIntOrNull() ?: Qualities.Unknown.value,
                    isM3u8 = source.isM3U8,
                    headers = mapOf("Origin" to mainServerAPI)
                )
            )
        }

        json?.subtitles?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    sub.lang.toString(),
                    sub.url ?: return@map null
                )
            )
        }
    }

    suspend fun invokeGogo(
        aniId: String? = null,
        animeId: String? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val res =
            app.get("$mainServerAPI/anime/$aniId/episode/$animeId?_data=routes/anime/\$animeId.episode.\$episodeId")
                .parsedSafe<LoadLinks>()

        res?.sources?.map { source ->
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    source.url ?: return@map null,
                    "$mainServerAPI/",
                    getQualityFromName(source.quality),
                    isM3u8 = source.isM3U8,
                    headers = mapOf("Origin" to mainServerAPI)
                )
            )
        }

    }

    suspend fun invokeHDMovieBox(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title?.replace(":", "")?.replace(" ", "-")?.lowercase()
        val url = "$hdMovieBoxAPI/watch/$fixTitle"
        val ref = if (season == null) {
            "$hdMovieBoxAPI/watch/$fixTitle"
        } else {
            "$hdMovieBoxAPI/watch/$fixTitle/season-$season/episode-$episode"
        }

        val doc = app.get(url).document
        val id = if (season == null) {
            doc.selectFirst("div.player div#not-loaded")?.attr("data-whatwehave")
        } else {
            doc.select("div.season-list-column div[data-season=$season] div.list div.item")[episode?.minus(
                1
            ) ?: 0].selectFirst("div.ui.checkbox")?.attr("data-episode")
        }

        val iframeUrl = app.post(
            "$hdMovieBoxAPI/ajax/service", data = mapOf(
                "e_id" to "$id",
                "v_lang" to "en",
                "type" to "get_whatwehave",
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<HdMovieBoxIframe>()?.apiIframe ?: return

        val iframe = app.get(iframeUrl, referer = "$hdMovieBoxAPI/").document.selectFirst("iframe")
            ?.attr("src")

        val script = app.get(
            iframe ?: return,
            referer = "$hdMovieBoxAPI/"
        ).document.selectFirst("script:containsData(var vhash =)")?.data()
            ?.substringAfter("vhash, {")?.substringBefore("}, false")

        tryParseJson<HdMovieBoxSource>("{$script}").let { source ->
            val link = getBaseUrl(iframe) + source?.videoUrl?.replace(
                "\\",
                ""
            ) + "?s=${source?.videoServer}&d=${base64Encode(source?.videoDisk?.toByteArray() ?: return)}"
            callback.invoke(
                ExtractorLink(
                    "HDMovieBox",
                    "HDMovieBox",
                    link,
                    iframe,
                    Qualities.P1080.value,
                    isM3u8 = true,
                )
            )
        }
    }

    suspend fun invokeSeries9(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title?.replace(":", "")?.replace(" ", "-")?.lowercase()
        val url = if (season == null) {
            "$series9API/film/$fixTitle/watching.html"
        } else {
            "$series9API/film/$fixTitle-season-$season/watching.html"
        }

        val res = app.get(url).document
        val sources : ArrayList<String?> = arrayListOf()

        if (season == null) {
            val xstreamcdn = res.selectFirst("div#list-eps div#server-29 a")?.attr("player-data")?.let {
                Regex("(.*?)((\\?cap)|(\\?sub)|(#cap)|(#sub))").find(it)?.groupValues?.get(1)
            }
            val streamsb = res.selectFirst("div#list-eps div#server-13 a")?.attr("player-data")
            val doodstream = res.selectFirst("div#list-eps div#server-14 a")?.attr("player-data")
            sources.addAll(listOf(xstreamcdn, streamsb, doodstream))
        } else {
            val xstreamcdn = res.selectFirst("div#list-eps div#server-29 a[episode-data=$episode]")?.attr("player-data")?.let {
                Regex("(.*?)((\\?cap)|(\\?sub)|(#cap)|(#sub))").find(it)?.groupValues?.get(1)
            }
            val streamsb = res.selectFirst("div#list-eps div#server-13 a[episode-data=$episode]")?.attr("player-data")
            val doodstream = res.selectFirst("div#list-eps div#server-14 a[episode-data=$episode]")?.attr("player-data")
            sources.addAll(listOf(xstreamcdn, streamsb, doodstream))
        }

        sources.apmap { link ->
            loadExtractor(link ?: return@apmap null, url, subtitleCallback, callback)
        }

    }

}

//private fun getHdMovieBoxUrl(link: String?): String? {
//    if (link == null) return null
//    return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w500/$link" else link
//}

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
            Qualities.P1080.value,
            true
        )
    )
}

data class HdMovieBoxSource(
    @JsonProperty("videoUrl") val videoUrl: String? = null,
    @JsonProperty("videoServer") val videoServer: String? = null,
    @JsonProperty("videoDisk") val videoDisk: String? = null,
)

data class HdMovieBoxIframe(
    @JsonProperty("api_iframe") val apiIframe: String? = null,
)

