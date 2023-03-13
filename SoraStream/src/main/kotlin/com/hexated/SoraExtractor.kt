package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.Session
import com.hexated.RabbitStream.extractRabbitStream
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.XStreamCdn
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.encode
import org.jsoup.Jsoup

val session = Session(Requests().baseClient)

object SoraExtractor : SoraStream() {

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
                "$twoEmbedAPI/ajax/embed/play?id=$serverID&_token=$token", referer = url
            ).parsedSafe<EmbedJson>()?.let { source ->
                val link = source.link ?: return@let
                if (link.contains("rabbitstream")) {
                    extractRabbitStream(link, subtitleCallback, callback, false, decryptKey = RabbitStream.getKey()) { it }
                } else {
                    loadExtractor(
                        link, twoEmbedAPI, subtitleCallback, callback
                    )
                }
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
                    if (link.name == "VidSrc") Qualities.P1080.value else link.quality,
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
        val url = "$olgplyAPI/${id}${season?.let { "/$it" } ?: ""}${episode?.let { "/$it" } ?: ""}"
        loadLinksWithWebView(url, callback)
    }

    suspend fun invokeDbgo(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val iframeDbgo: String?
        val script = if (season == null) {
            val doc = app.get("$dbgoAPI/imdb.php?id=$id").document
            iframeDbgo = doc.select("div.myvideo iframe").attr("src")
            app.get(iframeDbgo, referer = "$dbgoAPI/").document.select("script")
                .find { it.data().contains("CDNplayerConfig =") }?.data()
        } else {
            val doc = app.get("$dbgoAPI/tv-imdb.php?id=$id&s=$season").document
            iframeDbgo = doc.select("div.myvideo iframe").attr("src")
            val token = app.get(
                iframeDbgo, referer = "$dbgoAPI/"
            ).document.selectFirst("select#translator-name option")?.attr("data-token")
            app.get("https://voidboost.net/serial/$token/iframe?s=$season&e=$episode&h=dbgo.fun").document.select(
                "script"
            ).find { it.data().contains("CDNplayerConfig =") }?.data()
        } ?: return

        val source =
            Regex("['|\"]file['|\"]:\\s['|\"](#\\S+?)['|\"]").find(script)?.groupValues?.get(
                1
            ) ?: return
        val subtitle =
            Regex("['|\"]subtitle['|\"]:\\s['|\"](\\S+?)['|\"]").find(script)?.groupValues?.get(
                1
            )

        val ref = getBaseUrl(iframeDbgo)
        decryptStreamUrl(source).split(",").map { links ->
            val quality =
                Regex("\\[(\\d*p.*?)]").find(links)?.groupValues?.getOrNull(1)?.trim()
                    ?: return@map null
            links.replace("[$quality]", "").split(" or ").map { it.trim() }.map { link ->
                val name = if (link.contains(".m3u8")) "Dbgo (Main)" else "Dbgo (Backup)"
                callback.invoke(
                    ExtractorLink(
                        name,
                        name,
                        link,
                        "$ref/",
                        getQuality(quality),
                        isM3u8 = link.contains(".m3u8"),
                        headers = mapOf(
                            "Origin" to ref
                        )
                    )
                )
            }
        }

        subtitle?.split(",")?.map { sub ->
            val language = Regex("\\[(.*)]").find(sub)?.groupValues?.getOrNull(1) ?: return@map null
            val link = sub.replace("[$language]", "").trim()
            subtitleCallback.invoke(
                SubtitleFile(
                    getDbgoLanguage(language), link
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
        val iframe = app.get(url).document.selectFirst("iframe")?.attr("src") ?: return

        val doc = app.get(
            iframe,
            referer = url,
            headers = mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        ).document

        doc.select("ul.list-server-items li.linkserver").mapNotNull { server ->
            server.attr("data-video").let {
                Regex("(.*?)((\\?cap)|(\\?sub)|(#cap)|(#sub))").find(it)?.groupValues?.get(1)
            }
        }.apmap { link ->
            loadExtractor(
                link, "https://123moviesjr.cc/", subtitleCallback, callback
            )
        }
    }

    suspend fun invokeMovieHab(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = if (season == null) {
            "$movieHabAPI/embed/movie?imdb=$imdbId"
        } else {
            "$movieHabAPI/embed/series?imdb=$imdbId&sea=$season&epi=$episode"
        }

        val doc = app.get(url, referer = "$movieHabAPI/").document
        val movieId = doc.selectFirst("div#embed-player")?.attr("data-movie-id") ?: return

        doc.select("div.dropdown-menu a").apmap {
            val dataId = it.attr("data-id")
            app.get(
                "$movieHabAPI/ajax/get_stream_link?id=$dataId&movie=$movieId&is_init=true&captcha=&ref=",
                referer = url,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<MovieHabRes>()?.data?.let { res ->
                loadExtractor(
                    res.link ?: return@let null, movieHabAPI, subtitleCallback, callback
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

    suspend fun invokeHDMovieBox(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val request = app.get("$hdMovieBoxAPI/watch/$fixTitle")
        if (!request.isSuccessful) return
        val doc = request.document
        val id = if (season == null) {
            doc.selectFirst("div.player div#not-loaded")?.attr("data-whatwehave")
        } else {
            doc.select("div.season-list-column div[data-season=$season] div.list div.item")[episode?.minus(
                1
            ) ?: 0].selectFirst("div.ui.checkbox")?.attr("data-episode")
        } ?: return

        val iframeUrl = app.post(
            "$hdMovieBoxAPI/ajax/service", data = mapOf(
                "e_id" to id,
                "v_lang" to "en",
                "type" to "get_whatwehave",
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<HdMovieBoxIframe>()?.apiIframe ?: return

        val iframe = app.get(iframeUrl, referer = "$hdMovieBoxAPI/").document.selectFirst("iframe")
            ?.attr("src")
        val base = getBaseUrl(iframe ?: return)

        val script = app.get(
            iframe, referer = "$hdMovieBoxAPI/"
        ).document.selectFirst("script:containsData(var vhash =)")?.data()
            ?.substringAfter("vhash, {")?.substringBefore("}, false")

        tryParseJson<HdMovieBoxSource>("{$script}").let { source ->
            val disk = if (source?.videoDisk == null) {
                ""
            } else {
                base64Encode(source.videoDisk.toString().toByteArray())
            }
            val link = getBaseUrl(iframe) + source?.videoUrl?.replace(
                "\\", ""
            ) + "?s=${source?.videoServer}&d=$disk"
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

            source?.tracks?.map { sub ->
                subtitleCallback.invoke(
                    SubtitleFile(
                        sub.label ?: "",
                        fixUrl(sub.file ?: return@map null, base),
                    )
                )
            }
        }
    }

    suspend fun invokeSeries9(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = if (season == null) {
            "$series9API/film/$fixTitle/watching.html"
        } else {
            "$series9API/film/$fixTitle-season-$season/watching.html"
        }

        val request = app.get(url)
        if (!request.isSuccessful) return
        val res = request.document
        val sources: ArrayList<String?> = arrayListOf()

        if (season == null) {
            val xstreamcdn =
                res.selectFirst("div#list-eps div#server-29 a")?.attr("player-data")?.let {
                    Regex("(.*?)((\\?cap)|(\\?sub)|(#cap)|(#sub))").find(it)?.groupValues?.get(1)
                }
            val streamsb = res.selectFirst("div#list-eps div#server-13 a")?.attr("player-data")
            val doodstream = res.selectFirst("div#list-eps div#server-14 a")?.attr("player-data")
            sources.addAll(listOf(xstreamcdn, streamsb, doodstream))
        } else {
            val xstreamcdn = res.selectFirst("div#list-eps div#server-29 a[episode-data=$episode]")
                ?.attr("player-data")?.let {
                    Regex("(.*?)((\\?cap)|(\\?sub)|(#cap)|(#sub))").find(it)?.groupValues?.get(1)
                }
            val streamsb = res.selectFirst("div#list-eps div#server-13 a[episode-data=$episode]")
                ?.attr("player-data")
            val doodstream = res.selectFirst("div#list-eps div#server-14 a[episode-data=$episode]")
                ?.attr("player-data")
            sources.addAll(listOf(xstreamcdn, streamsb, doodstream))
        }

        sources.apmap { link ->
            loadExtractor(link ?: return@apmap null, url, subtitleCallback, callback)
        }
    }

    suspend fun invokeIdlix(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = if (season == null) {
            "$idlixAPI/movie/$fixTitle-$year"
        } else {
            "$idlixAPI/episode/$fixTitle-season-$season-episode-$episode"
        }

        val res = app.get(url)
        if (!res.isSuccessful) return
        val referer = getBaseUrl(res.url)
        val document = res.document
        val id = document.select("meta#dooplay-ajax-counter").attr("data-postid")
        val type = if (url.contains("/movie/")) "movie" else "tv"

        document.select("ul#playeroptionsul > li").map {
            it.attr("data-nume")
        }.apmap { nume ->
            val source = app.post(
                url = "$referer/wp-admin/admin-ajax.php", data = mapOf(
                    "action" to "doo_player_ajax", "post" to id, "nume" to nume, "type" to type
                ), headers = mapOf("X-Requested-With" to "XMLHttpRequest"), referer = url
            ).parsed<ResponseHash>().embed_url

            if (!source.contains("youtube")) {
                loadExtractor(source, "$referer/", subtitleCallback, callback)
            }
        }
    }

    suspend fun invokeUniqueStream(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = if (season == null) {
            "$uniqueStreamAPI/movies/$fixTitle-$year"
        } else {
            "$uniqueStreamAPI/episodes/$fixTitle-season-$season-episode-$episode"
        }

        val res = app.get(url)
        if (!res.isSuccessful) return
        val baseApi = getBaseUrl(res.url)

        val document = res.document
        val type = if (url.contains("/movies/")) "movie" else "tv"
        document.select("ul#playeroptionsul > li").apmap { el ->
            val id = el.attr("data-post")
            val nume = el.attr("data-nume")
            val source = app.post(
                url = "$baseApi/wp-admin/admin-ajax.php", data = mapOf(
                    "action" to "doo_player_ajax", "post" to id, "nume" to nume, "type" to type
                ), headers = mapOf("X-Requested-With" to "XMLHttpRequest"), referer = url
            ).parsed<ResponseHash>().embed_url.let { fixUrl(it) }

            when {
                source.contains("uniquestream") -> {
                    val resDoc = app.get(
                        source, referer = "$baseApi/", headers = mapOf(
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                        )
                    ).document
                    val srcm3u8 =
                        resDoc.selectFirst("script:containsData(let url =)")?.data()?.let {
                            Regex("['|\"](.*?.m3u8)['|\"]").find(it)?.groupValues?.getOrNull(1)
                        }
                    callback.invoke(
                        ExtractorLink(
                            "UniqueStream",
                            "UniqueStream",
                            srcm3u8 ?: return@apmap null,
                            source,
                            Qualities.P1080.value,
                            true,
                        )
                    )
                }
                !source.contains("youtube") -> loadExtractor(
                    source, "$uniqueStreamAPI/", subtitleCallback, callback
                )
                else -> {
                    // pass
                }
            }
        }
    }

    suspend fun invokeNoverse(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = if (season == null) {
            "$noverseAPI/movie/$fixTitle/download/"
        } else {
            "$noverseAPI/serie/$fixTitle/season-$season"
        }

        val doc = app.get(url).document

        val links = if (season == null) {
            doc.select("table.table-striped tbody tr").map {
                it.select("a").attr("href") to it.selectFirst("td")?.text()
            }
        } else {
            doc.select("table.table-striped tbody tr")
                .find { it.text().contains("Episode $episode") }?.select("td")?.map {
                    it.select("a").attr("href") to it.select("a").text()
                }
        } ?: return

        delay(4000)
        links.map { (link, quality) ->
            val name =
                quality?.replace(Regex("\\d{3,4}p"), "Noverse")?.replace(".", " ") ?: "Noverse"
            callback.invoke(
                ExtractorLink(
                    "Noverse",
                    name,
                    link,
                    "",
                    getQualityFromName("${quality?.substringBefore("p")?.trim()}p"),
                )
            )
        }

    }

    suspend fun invokeFilmxy(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = if (season == null) {
            "${filmxyAPI}/movie/$imdbId"
        } else {
            "${filmxyAPI}/tv/$imdbId"
        }
        val filmxyCookies =
            getFilmxyCookies(imdbId, season) ?: throw ErrorLoadingException("No Cookies Found")

        val cookiesDoc = mapOf(
            "G_ENABLED_IDPS" to "google",
            "wordpress_logged_in_8bf9d5433ac88cc9a3a396d6b154cd01" to "${filmxyCookies.wLog}",
            "PHPSESSID" to "${filmxyCookies.phpsessid}"
        )

        val doc = session.get(url, cookies = cookiesDoc).document
        val script = doc.selectFirst("script:containsData(var isSingle)")?.data() ?: return

        val sourcesData = Regex("listSE\\s*=\\s?(.*?),[\\n|\\s]").find(script)?.groupValues?.get(1).let {
            tryParseJson<HashMap<String, HashMap<String, List<String>>>>(it)
        }
        val sourcesDetail =
            Regex("linkDetails\\s*=\\s?(.*?),[\\n|\\s]").find(script)?.groupValues?.get(1).let {
                tryParseJson<HashMap<String, HashMap<String, String>>>(it)
            }
        val subSourcesData =
            Regex("dSubtitles\\s*=\\s?(.*?),[\\n|\\s]").find(script)?.groupValues?.get(1).let {
                tryParseJson<HashMap<String, HashMap<String, HashMap<String, String>>>>(it)
            }

        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)

        val sources = if(season == null) {
            sourcesData?.get("movie")?.get("movie")
        } else {
            sourcesData?.get("s$seasonSlug")?.get("e$episodeSlug")
        }
        val subSources = if(season == null) {
            subSourcesData?.get("movie")?.get("movie")
        } else {
            subSourcesData?.get("s$seasonSlug")?.get("e$episodeSlug")
        }

        val scriptUser =
            doc.select("script").find { it.data().contains("var userNonce") }?.data() ?: return
        val userNonce =
            Regex("var\\suserNonce.*?[\"|'](\\S+?)[\"|'];").find(scriptUser)?.groupValues?.get(1)
        val userId =
            Regex("var\\suser_id.*?[\"|'](\\S+?)[\"|'];").find(scriptUser)?.groupValues?.get(1)
        val linkIDs = sources?.joinToString("") {
            "&linkIDs%5B%5D=$it"
        }?.replace("\"", "")

        val body = "action=get_vid_links$linkIDs&user_id=$userId&nonce=$userNonce".toRequestBody()
        val cookiesJson = mapOf(
            "G_ENABLED_IDPS" to "google",
            "PHPSESSID" to "${filmxyCookies.phpsessid}",
            "wordpress_logged_in_8bf9d5433ac88cc9a3a396d6b154cd01" to "${filmxyCookies.wLog}",
            "wordpress_sec_8bf9d5433ac88cc9a3a396d6b154cd01" to "${filmxyCookies.wSec}"
        )
        val json = app.post(
            "$filmxyAPI/wp-admin/admin-ajax.php",
            requestBody = body,
            referer = url,
            headers = mapOf(
                "Accept" to "*/*",
                "DNT" to "1",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Origin" to filmxyAPI,
                "X-Requested-With" to "XMLHttpRequest",
            ),
            cookies = cookiesJson
        ).text.let { tryParseJson<HashMap<String, String>>(it) }

        sources?.map { source ->
            val link = json?.get(source)
            val quality = sourcesDetail?.get(source)?.get("resolution")
            val server = sourcesDetail?.get(source)?.get("server")
            val size = sourcesDetail?.get(source)?.get("size")

            callback.invoke(
                ExtractorLink(
                    "Filmxy",
                    "Filmxy $server [$size]",
                    link ?: return@map,
                    "$filmxyAPI/",
                    getQualityFromName(quality)
                )
            )
        }

        subSources?.mapKeys { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    SubtitleHelper.fromTwoLettersToLanguage(sub.key) ?: return@mapKeys,
                    "https://www.mysubs.org/get-subtitle/${sub.value}"
                )
            )
        }

    }

    suspend fun invokeKimcartoon(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val doc = if (season == null || season == 1) {
            app.get("$kimcartoonAPI/Cartoon/$fixTitle").document
        } else {
            val res = app.get("$kimcartoonAPI/Cartoon/$fixTitle-Season-$season")
            if (res.url == "$kimcartoonAPI/")
                app.get("$kimcartoonAPI/Cartoon/$fixTitle-Season-0$season").document else res.document
        }

        val iframe = if (season == null) {
            doc.select("table.listing tr td a").firstNotNullOf { it.attr("href") }
        } else {
            doc.select("table.listing tr td a").find {
                it.attr("href").contains(Regex("(?i)Episode-0*$episode"))
            }?.attr("href")
        } ?: return
        val source =
            app.get(
                fixUrl(iframe, kimcartoonAPI)
            ).document.selectFirst("div#divContentVideo iframe")
                ?.attr("src") ?: return
        loadExtractor(source, "$kimcartoonAPI/", subtitleCallback, callback)
    }

    suspend fun invokeSoraStream(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val (id, type) = getSoraIdAndType(title, year, season) ?: return
        val json = fetchSoraEpisodes(id, type, episode) ?: return

        json.subtitlingList?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    getVipLanguage(sub.languageAbbr ?: return@map),
                    sub.subtitlingUrl ?: return@map
                )
            )
        }
    }

    suspend fun invokeSoraStreamLite(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val (id, type) = getSoraIdAndType(title, year, season) ?: return
        val json = fetchSoraEpisodes(id, type, episode) ?: return

        json.subtitlingList?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    getVipLanguage(sub.languageAbbr ?: return@map),
                    sub.subtitlingUrl ?: return@map
                )
            )
        }

        json.definitionList?.map { video ->
            val media = app.get(
                "${soraAPI}/media/previewInfo?category=${type}&contentId=${id}&episodeId=${json.id}&definition=${video.code}",
                headers = soraHeaders,
            ).parsedSafe<SorastreamResponse>()?.data

            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    media?.mediaUrl ?: return@map null,
                    "",
                    getSoraQuality(media.currentDefinition ?: ""),
                    true,
                )
            )
        }
    }

    suspend fun invokeXmovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val doc = if (season == null) {
            val res = app.get("$xMovieAPI/movies/$fixTitle/watch")
            if (res.url == "$xMovieAPI/") app.get("$xMovieAPI/movies/$fixTitle-$year/watch").document else res.document
        } else {
            app.get("$xMovieAPI/series/$fixTitle-season-$season-episode-$episode/watch").document
        }

        val script = doc.selectFirst("script:containsData(const player =)")?.data() ?: return
        val link =
            Regex("[\"|']file[\"|']:\\s?[\"|'](http.*?.(mp4|m3u8))[\"|'],").find(script)?.groupValues?.getOrNull(
                1
            ) ?: return

        if (link.contains(".m3u8")) {
            M3u8Helper.generateM3u8(
                "Xmovie",
                link,
                "",
            ).forEach(callback)
        } else {
            callback.invoke(
                ExtractorLink(
                    "Xmovie",
                    "Xmovie",
                    link,
                    "",
                    Qualities.P720.value,
                )
            )
        }

        Regex(""""file":\s+?"(\S+\.(vtt|srt))""").find(script)?.groupValues?.getOrNull(1)
            ?.let { sub ->
                subtitleCallback.invoke(
                    SubtitleFile(
                        "English",
                        sub,
                    )
                )
            }


    }

    suspend fun invokeFlixhq(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        lastSeason: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title?.replace("–", "-")
        val id = app.get("$haikeiFlixhqAPI/$title")
            .parsedSafe<ConsumetSearchResponse>()?.results?.find {
                if (season == null) {
                    it.title?.equals(
                        "$fixTitle", true
                    ) == true && it.releaseDate?.equals("$year") == true && it.type == "Movie"
                } else {
                    it.title?.equals("$fixTitle", true) == true && it.type == "TV Series" && it.seasons == lastSeason
                }
            }?.id ?: return

        val episodeId =
            app.get("$haikeiFlixhqAPI/info?id=$id").parsedSafe<ConsumetDetails>()?.let {
                if (season == null) {
                    it.episodes?.first()?.id
                } else {
                    it.episodes?.find { ep -> ep.number == episode && ep.season == season }?.id
                }
            } ?: return

        listOf(
            "vidcloud", "upcloud"
        ).apmap { server ->
            val sources = app.get(
                if(server == "upcloud") {
                    "$haikeiFlixhqAPI/watch?episodeId=$episodeId&mediaId=$id"
                } else {
                    "$haikeiFlixhqAPI/watch?episodeId=$episodeId&mediaId=$id&server=$server"
                },
            ).parsedSafe<ConsumetSourcesResponse>()
            val name = fixTitle(server)
            sources?.sources?.map {
                callback.invoke(
                    ExtractorLink(
                        name,
                        name,
                        it.url ?: return@map null,
                        sources.headers?.referer ?: "",
                        it.quality?.toIntOrNull() ?: Qualities.Unknown.value,
                        it.isM3U8 ?: true
                    )
                )
            }

            sources?.subtitles?.map {
                subtitleCallback.invoke(
                    SubtitleFile(
                        it.lang ?: "", it.url ?: return@map null
                    )
                )
            }

        }


    }

    suspend fun invokeKisskh(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title?.replace("–", "-")
        val res = app.get(
            "$kissKhAPI/api/DramaList/Search?q=$title&type=0", referer = "$kissKhAPI/"
        ).text.let {
            tryParseJson<ArrayList<KisskhResults>>(it)
        } ?: return

        val (id, contentTitle) = if (res.size == 1) {
            res.first().id to res.first().title
        } else {
            if (season == null) {
                val data = res.find { it.title.equals(fixTitle, true) }
                data?.id to data?.title
            } else {
                val data = res.find {
                    it.title?.contains(
                        "$fixTitle", true
                    ) == true && it.title.contains("Season $season", true)
                }
                data?.id to data?.title
            }
        }

        val resDetail = app.get(
            "$kissKhAPI/api/DramaList/Drama/$id?isq=false", referer = "$kissKhAPI/Drama/${
                getKisskhTitle(contentTitle)
            }?id=$id"
        ).parsedSafe<KisskhDetail>() ?: return

        val epsId = if (season == null) {
            resDetail.episodes?.first()?.id
        } else {
            resDetail.episodes?.find { it.number == episode }?.id
        }

        app.get(
            "$kissKhAPI/api/DramaList/Episode/$epsId.png?err=false&ts=&time=",
            referer = "$kissKhAPI/Drama/${getKisskhTitle(contentTitle)}/Episode-${episode ?: 0}?id=$id&ep=$epsId&page=0&pageSize=100"
        ).parsedSafe<KisskhSources>()?.let { source ->
            listOf(source.video, source.thirdParty).apmap { link ->
                if (link?.contains(".m3u8") == true) {
                    M3u8Helper.generateM3u8(
                        "Kisskh",
                        link,
                        "$kissKhAPI/",
                        headers = mapOf("Origin" to kissKhAPI)
                    ).forEach(callback)
                } else {
                    loadExtractor(
                        link?.substringBefore("=http") ?: return@apmap null,
                        "$kissKhAPI/",
                        subtitleCallback,
                        callback
                    )
                }
            }
        }

        app.get("$kissKhAPI/api/Sub/$epsId").text.let { resSub ->
            tryParseJson<List<KisskhSubtitle>>(resSub)?.map { sub ->
                subtitleCallback.invoke(
                    SubtitleFile(
                        getLanguage(sub.label ?: return@map), sub.src ?: return@map
                    )
                )
            }
        }


    }

    suspend fun invokeAnimes(
        id: Int? = null,
        title: String? = null,
        epsTitle: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val malId =
            if (season != null) app.get("$tmdb2mal/?id=$id&s=$season").text.trim()
            else app.get("${jikanAPI}/anime?q=${title}&start_date=${year}&type=movie&limit=1")
                .parsedSafe<JikanResponse>()?.data?.firstOrNull()?.mal_id

        val aniId = app.post(
            "https://graphql.anilist.co/", data = mapOf(
                "query" to "{Media(idMal:$malId,type:ANIME){id}}",
            )
        ).parsedSafe<DataAni>()?.data?.media?.id

        argamap(
            {
                invokeZoro(aniId, episode, subtitleCallback, callback)
            },
            {
                invokeAnimeKaizoku(malId, epsTitle, season, episode, callback)
            },
            {
                invokeBiliBili(aniId, episode, subtitleCallback, callback)
            },
        )
    }

    private suspend fun invokeBiliBili(
        aniId: String? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get("$biliBiliAPI/anime/episodes?id=$aniId&source_id=bilibili")
            .parsedSafe<BiliBiliDetails>()?.episodes?.find {
                it.episodeNumber == episode
            } ?: return

        val sources =
            app.get("$biliBiliAPI/source?episode_id=${res.sourceEpisodeId}&source_media_id=${res.sourceMediaId}&source_id=${res.sourceId}")
                .parsedSafe<BiliBiliSourcesResponse>()

        sources?.sources?.apmap { source ->
            val quality = app.get(source.file ?: return@apmap null).document.selectFirst("Representation")?.attr("height")
            callback.invoke(
                ExtractorLink(
                    "BiliBili",
                    "BiliBili",
                    source.file,
                    "",
                    quality?.toIntOrNull() ?: Qualities.Unknown.value,
                    isDash = true
                )
            )
        }

        sources?.subtitles?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    SubtitleHelper.fromTwoLettersToLanguage(sub.lang ?: "") ?: sub.language
                    ?: return@map null,
                    sub.file ?: return@map null
                )
            )
        }


    }

    private suspend fun invokeZoro(
        aniId: String? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val episodeId = app.get("$consumetAnilistAPI/info/$aniId?provider=zoro")
            .parsedSafe<ConsumetDetails>()?.episodes?.find {
                it.number == (episode ?: 1)
            }?.id?.substringBeforeLast("$") ?: return

        listOf(
            "$episodeId\$sub" to "Raw",
            "$episodeId\$dub" to "English Dub",
        ).apmap { (id, type) ->
            val sources = app.get("$consumetZoroAPI/watch?episodeId=$id")
                .parsedSafe<ConsumetSourcesResponse>() ?: return@apmap null

            sources.sources?.map sources@{
                callback.invoke(
                    ExtractorLink(
                        "Zoro [$type]",
                        "Zoro [$type]",
                        it.url ?: return@sources null,
                        "",
                        getQualityFromName(it.quality),
                        it.isM3U8 ?: true
                    )
                )
            }

            sources.subtitles?.map subtitles@{
                subtitleCallback.invoke(
                    SubtitleFile(
                        it.lang ?: "",
                        it.url ?: return@subtitles null
                    )
                )
            }
        }

    }

    private suspend fun invokeAnimeKaizoku(
        malId: String? = null,
        epsTitle: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val search = app.get("$animeKaizokuAPI/?s=$malId").document
        val detailHref =
            search.select("ul#posts-container li").map { it.selectFirst("a")?.attr("href") }
                .find {
                    it?.contains(malId ?: return) == true
                }?.let { fixUrl(it, animeKaizokuAPI) }

        val detail = app.get(detailHref ?: return).document
        val postId =
            detail.selectFirst("link[rel=shortlink]")?.attr("href")?.substringAfter("?p=") ?: return
        val script = detail.selectFirst("script:containsData(DDL)")?.data()?.splitData() ?: return

        val media = fetchingKaizoku(animeKaizokuAPI, postId, script, detailHref).document
        val iframe = media.select("tbody td[colspan=2]").map { it.attr("onclick") to it.text() }
            .filter { it.second.contains("1080p", true) }

        val eps = if (season == null) {
            null
        } else {
            if (episode!! < 10) "0$episode" else episode
        }

        iframe.apmap { (data, name) ->
            val worker =
                fetchingKaizoku(animeKaizokuAPI, postId, data.splitData(), detailHref).document
                    .select("tbody td")
                    .map { Triple(it.attr("onclick"), it.text(), it.nextElementSibling()?.text()) }

            val episodeData = worker.let { list ->
                if (season == null) list.firstOrNull() else list.find {
                    it.second.contains(
                        Regex("($eps\\.)|(-\\s$eps)")
                    ) || it.second.contains("$epsTitle", true)
                }
            } ?: return@apmap null

            val ouo = fetchingKaizoku(
                animeKaizokuAPI,
                postId,
                episodeData.first.splitData(),
                detailHref
            ).text.substringAfter("openInNewTab(\"")
                .substringBefore("\")").let { base64Decode(it) }

            if (!ouo.startsWith("https://ouo")) return@apmap null
            callback.invoke(
                ExtractorLink(
                    "AnimeKaizoku",
                    "AnimeKaizoku [${episodeData.third}]",
                    bypassOuo(ouo) ?: return@apmap null,
                    "$animeKaizokuAPI/",
                    Qualities.P1080.value,
                )
            )
        }
    }

    suspend fun invokeLing(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title?.replace("–", "-")
        val url = if (season == null) {
            "$lingAPI/en/videos/films/?title=$fixTitle"
        } else {
            "$lingAPI/en/videos/serials/?title=$fixTitle"
        }

        val scriptData = app.get(url).document.select("div.blk.padding_b0 div.col-sm-30").map {
            Triple(
                it.selectFirst("div.video-body h5")?.text(),
                it.selectFirst("div.video-body > p")?.text(),
                it.selectFirst("div.video-body a")?.attr("href"),
            )
        }

        val script = if (scriptData.size == 1) {
            scriptData.first()
        } else {
            scriptData.find {
                it.first?.contains(
                    "$fixTitle", true
                ) == true && it.second?.contains("$year") == true
            }
        }

        val doc = app.get(fixUrl(script?.third ?: return, lingAPI)).document
        val iframe = (if (season == null) {
            doc.selectFirst("a.video-js.vjs-default-skin")?.attr("data-href")
        } else {
            doc.select("div.blk div#tab_$season li")[episode!!.minus(1)].select("h5 a")
                .attr("data-href")
        })?.let { fixUrl(it, lingAPI) }

        val source = app.get(iframe ?: return)
        val link = Regex("((https:|http:)//.*\\.mp4)").find(source.text)?.value ?: return
        callback.invoke(
            ExtractorLink(
                "Ling", "Ling", link, "$lingAPI/", Qualities.Unknown.value, headers = mapOf(
                    "Range" to "bytes=0-"
                )
            )
        )

        source.document.select("div#player-tracks track").map {
            subtitleCallback.invoke(
                SubtitleFile(
                    SubtitleHelper.fromTwoLettersToLanguage(it.attr("srclang")) ?: return@map null,
                    it.attr("src")
                )
            )
        }

    }

    suspend fun invokeUhdmovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        lastSeason: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val slug = title.createSlug()?.replace("-", " ")
        val url = "$uhdmoviesAPI/?s=$slug"
        var doc = app.get(url).document
        if (doc.select("title").text() == "Just a moment...") {
            doc = app.get(url, interceptor = CloudflareKiller()).document
        }
        val scriptData = doc.select("div.row.gridlove-posts article").map {
            it.selectFirst("a")?.attr("href") to it.selectFirst("h1")?.text()
        }

        val detailUrl = (if (scriptData.size == 1) {
            scriptData.first()
        } else {
            scriptData.find { it.second?.filterMedia(title, year, lastSeason) == true }
        })?.first

        val detailDoc = app.get(detailUrl ?: return).document

        val iframeList = detailDoc.select("div.entry-content p").map { it }
            .filter { it.text().filterIframe(season, lastSeason, year, title) }.mapNotNull {
                if (season == null) {
                    it.text() to it.nextElementSibling()?.select("a")?.attr("href")
                } else {
                    it.text() to it.nextElementSibling()
                        ?.select("a")?.find { child -> child.select("span").text().equals("Episode $episode", true) }
                        ?.attr("href")
                }
            }.filter { it.second?.contains(Regex("(https:)|(http:)")) == true }

        val sources = mutableListOf<Pair<String, String?>>()
        if (iframeList.any {
                it.first.contains(
                    "2160p",
                    true
                )
            }) {
            sources.addAll(iframeList.filter {
                it.first.contains(
                    "2160p",
                    true
                )
            })
            sources.add(iframeList.first {
                it.first.contains(
                    "1080p",
                    true
                )
            })
        } else {
            sources.addAll(iframeList.filter { it.first.contains("1080p", true) })
        }

        sources.apmap { (quality, link) ->
            val driveLink = bypassHrefli(link ?: return@apmap null)
            val base = getBaseUrl(driveLink ?: return@apmap null)
            val resDoc = app.get(driveLink).document
            val bitLink = resDoc.selectFirst("a.btn.btn-outline-success")?.attr("href")
            val downloadLink = if (bitLink.isNullOrEmpty()) {
                val backupIframe = resDoc.select("a.btn.btn-outline-warning").attr("href")
                extractBackupUHD(backupIframe ?: return@apmap null)
            } else {
                extractMirrorUHD(bitLink, base)
            }

            val tags =
                Regex("\\d{3,4}[Pp]\\.?(.*?)\\[").find(quality)?.groupValues?.getOrNull(1)
                    ?.replace(".", " ")?.trim()
                    ?: ""
            val qualities =
                Regex("(\\d{3,4})[Pp]").find(quality)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Qualities.Unknown.value
            val size =
                Regex("(?i)\\[(\\S+\\s?(gb|mb))[]/]").find(quality)?.groupValues?.getOrNull(1)
                    ?.let { "[$it]" } ?: quality
            callback.invoke(
                ExtractorLink(
                    "UHDMovies",
                    "UHDMovies $tags $size",
                    downloadLink ?: return@apmap null,
                    "",
                    qualities
                )
            )

        }


    }

    suspend fun invokeFwatayako(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val request = app.get("$fwatayakoAPI/IAF0wWTdNYZm?imdb_id=$imdbId")
        if (!request.isSuccessful) return
        val files = request.document.selectFirst("input#files")?.attr("value").let {
            if (season == null) {
                it?.replace("\"381\"", "\"movie\"")
            } else {
                it?.replace("\"381\"", "\"tv\"")
            }
        }.let { tryParseJson<SourcesFwatayako>(it) } ?: return

        val sourcesLink = if (season == null) {
            files.sourcesMovie
        } else {
            files.sourcesTv?.find { it.id == season }?.folder?.find { it.id == "${season}_${episode}" }?.file
        }

        sourcesLink?.split(",")?.map {
            val source = it.substringBefore("or").trim()
            val quality =
                Regex("\\[(\\d{3,4})p]").find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val link = httpsify(source.replace("[${quality}p]", "").trim())
            callback.invoke(
                ExtractorLink(
                    "Fwatayako",
                    "Fwatayako",
                    link,
                    "$fwatayakoAPI/",
                    quality ?: Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        }
    }

    suspend fun invokeGMovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = if (season == null || season == 1) {
            "$gMoviesAPI/$fixTitle-$year"
        } else {
            "$gMoviesAPI/$fixTitle-$year-season-$season"
        }

        val doc = app.get(url).document

        val iframe = (if (season == null) {
            doc.select("div.is-content-justification-center div.wp-block-button").map {
                it.select("a").attr("href") to it.text()
            }
        } else {
            doc.select("div.is-content-justification-center").find {
                it.previousElementSibling()?.text()
                    ?.contains(Regex("(?i)episode\\s?$episode")) == true
            }?.select("div.wp-block-button")?.map {
                it.select("a").attr("href") to it.text()
            }
        })?.filter {
            it.first.contains("gdtot") && it.second.contains(Regex("(?i)(4k|1080p)"))
        } ?: return

        iframe.apmap { (iframeLink, title) ->
            val size = Regex("(?i)\\s(\\S+gb|mb)").find(title)?.groupValues?.getOrNull(1)
            val gdBotLink = extractGdbot(iframeLink)
            val videoLink = extractGdflix(gdBotLink ?: return@apmap null)

            callback.invoke(
                ExtractorLink(
                    "GMovies",
                    "GMovies [$size]",
                    videoLink ?: return@apmap null,
                    "",
                    getGMoviesQuality(title)
                )
            )
        }
    }

    suspend fun invokeFDMovies(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = if (season == null) {
            "$fdMoviesAPI/movies/$fixTitle"
        } else {
            "$fdMoviesAPI/episodes/$fixTitle-s${season}xe${episode}/"
        }

        val request = app.get(url)
        if (!request.isSuccessful) return

        val iframe = request.document.select("div#download tbody tr").map {
            FDMovieIFrame(
                it.select("a").attr("href"),
                it.select("strong.quality").text(),
                it.select("td:nth-child(4)").text(),
                it.select("img").attr("src")
            )
        }.filter {
            it.quality.contains(Regex("(?i)(1080p|4k)")) && it.type.contains(Regex("(gdtot|oiya)"))
        }
        iframe.apmap { (link, quality, size, type) ->
            val qualities = getFDoviesQuality(quality)
            val fdLink = bypassFdAds(link)
            val videoLink = when {
                // pass due too many gdtot links
//                type.contains("gdtot") -> {
//                    val gdBotLink = extractGdbot(fdLink ?: return@apmap null)
//                    extractGdflix(gdBotLink ?: return@apmap null)
//                }
                type.contains("oiya") -> {
                    extractOiya(fdLink ?: return@apmap null, qualities)
                }
                else -> {
                    return@apmap null
                }
            }

            callback.invoke(
                ExtractorLink(
                    "FDMovies",
                    "FDMovies [$size]",
                    videoLink ?: return@apmap null,
                    "",
                    getQualityFromName(qualities)
                )
            )
        }

    }

    suspend fun invokeM4uhd(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get("$m4uhdAPI/search/${title.createSlug()}.html").document
        val scriptData = res.select("div.row div.item").map {
            Triple(
                it.selectFirst("img.imagecover")?.attr("title"),
                it.selectFirst("div.jtip-top div:last-child")?.text(),
                it.selectFirst("a")?.attr("href")
            )
        }

        val script = if (scriptData.size == 1) {
            scriptData.firstOrNull()
        } else {
            scriptData.find {
                it.first?.contains(
                    "Watch Free ${title?.replace(":", "")}", true
                ) == true && (it.first?.contains("$year") == true || it.second?.contains(
                    "$year"
                ) == true)
            }
        }

        val link = fixUrl(script?.third ?: return, m4uhdAPI)
        val request = app.get(link)
        var cookiesSet = request.headers.filter { it.first == "set-cookie" }
        var xsrf =
            cookiesSet.find { it.second.contains("XSRF-TOKEN") }?.second?.substringAfter("XSRF-TOKEN=")
                ?.substringBefore(";")
        var session =
            cookiesSet.find { it.second.contains("laravel_session") }?.second?.substringAfter("laravel_session=")
                ?.substringBefore(";")

        val doc = request.document
        val token = doc.selectFirst("meta[name=csrf-token]")?.attr("content")
        val m4uData = if (season == null) {
            doc.select("div.le-server span#fem").attr("data")
        } else {
            val episodeData =
                doc.selectFirst("div.col-lg-9.col-xl-9 p:matches((?i)S0?$season-E0?$episode$)")
                    ?: return
            val idepisode = episodeData.select("button").attr("idepisode") ?: return
            val requestEmbed = app.post(
                "$m4uhdAPI/ajaxtv", data = mapOf(
                    "idepisode" to idepisode, "_token" to "$token"
                ), referer = link, headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                ), cookies = mapOf(
                    "laravel_session" to "$session",
                    "XSRF-TOKEN" to "$xsrf",
                )
            )
            cookiesSet = requestEmbed.headers.filter { it.first == "set-cookie" }
            xsrf =
                cookiesSet.find { it.second.contains("XSRF-TOKEN") }?.second?.substringAfter("XSRF-TOKEN=")
                    ?.substringBefore(";")
            session =
                cookiesSet.find { it.second.contains("laravel_session") }?.second?.substringAfter("laravel_session=")
                    ?.substringBefore(";")
            requestEmbed.document.select("span#fem").attr("data")
        }

        val iframe = app.post(
            "$m4uhdAPI/ajax",
            data = mapOf(
                "m4u" to m4uData, "_token" to "$token"
            ),
            referer = link,
            headers = mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
            ),
            cookies = mapOf(
                "laravel_session" to "$session",
                "XSRF-TOKEN" to "$xsrf",
            ),
        ).document.select("iframe").attr("src")

        loadExtractor(iframe, m4uhdAPI, subtitleCallback, callback)

    }

    suspend fun invokeTvMovies(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = if (season == null) {
            "$tvMoviesAPI/show/$fixTitle"
        } else {
            "$tvMoviesAPI/show/index-of-$fixTitle"
        }

        val server = getTvMoviesServer(url, season, episode) ?: return
        val videoData = extractCovyn(server.second ?: return)
        val quality =
            Regex("(\\d{3,4})p").find(server.first)?.groupValues?.getOrNull(1)?.toIntOrNull()

        callback.invoke(
            ExtractorLink(
                "TVMovies",
                "TVMovies [${videoData?.second}]",
                videoData?.first ?: return,
                "",
                quality ?: Qualities.Unknown.value
            )
        )


    }

    suspend fun invokeCrunchyroll(
        title: String? = null,
        epsTitle: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = searchCrunchyrollAnimeId(title ?: return) ?: return
        val detail = app.get("$consumetCrunchyrollAPI/info/$id?fetchAllSeasons=true",timeout = 600L).text
        val epsId = tryParseJson<CrunchyrollDetails>(detail)?.findCrunchyrollId(
            title,
            season,
            episode,
            epsTitle
        ) ?: return

        epsId.apmap {
            val json =
                app.get("$consumetCrunchyrollAPI/watch/${it?.first ?: return@apmap null}",timeout = 600L)
                    .parsedSafe<ConsumetSourcesResponse>()

            json?.sources?.map source@{ source ->
                callback.invoke(
                    ExtractorLink(
                        "Crunchyroll",
                        "Crunchyroll [${it.second ?: ""}]",
                        source.url ?: return@source null,
                        "https://static.crunchyroll.com/",
                        source.quality?.removeSuffix("p")?.toIntOrNull() ?: return@source null,
                        true
                    )
                )
            }

            json?.subtitles?.map subtitle@{ sub ->
                subtitleCallback.invoke(
                    SubtitleFile(
                        fixCrunchyrollLang(sub.lang ?: return@subtitle null) ?: sub.lang,
                        sub.url ?: return@subtitle null
                    )
                )
            }
        }
    }

    suspend fun invokeKickassanime(
        title: String? = null,
        epsTitle: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val body = """{"query":"$title"}""".toRequestBody(
            RequestBodyTypes.JSON.toMediaTypeOrNull()
        )
        val animeId = app.post(
            "$kickassanimeAPI/api/search", requestBody = body
        ).text.let { tryParseJson<List<KaaSearchResponse>>(it) }.let { res ->
            (if (res?.size == 1) {
                res.firstOrNull()
            } else {
                res?.find {
                    it.title?.equals(
                        title,
                        true
                    ) == true || it.title.createSlug()
                        ?.equals("${title.createSlug()}", true) == true
                }
            })?._id
        } ?: return

        val seasonData =
            app.get("$kickassanimeAPI/api/season/$animeId").text.let { tryParseJson<List<KaaSeason>>(it) }?.find {
                val seasonNumber = when (title) {
                    "One Piece" -> 13
                    "Hunter x Hunter" -> 5
                    else -> season
                }
                it.number == seasonNumber
            }

        val language = seasonData?.languages?.filter {
            it == "ja-JP" || it == "en-US"
        }

        language?.apmap { lang ->
            val episodeSlug =
                app.get("$kickassanimeAPI/api/episodes/${seasonData.id}?lh=$lang&page=1")
                    .parsedSafe<KaaEpisodeResults>()?.result?.find { eps ->
                        eps.episodeNumber == episode || eps.slug?.contains("${epsTitle.createSlug()}", true) == true
                    }?.slug ?: return@apmap

            val server = app.get("$kickassanimeAPI/api/watch/$episodeSlug").parsedSafe<KaaServers>()?.servers?.find {
                it.contains("/sapphire-duck/")
            } ?: return@apmap

            invokeSapphire(server, lang == "en-US", subtitleCallback, callback)

        }


    }

    suspend fun invokeMoviesbay(
        title: String? = null,
        year: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val url =
            "https://sheets.googleapis.com/v4/spreadsheets/12RD3HX3NkSiCyqQJxemyS8W0R9B7J4VBl35uLBa5W0E/values/main?alt=json&key=AIzaSyA_ZY8GYxyUZYlcKGkDIHuku_gmE4z-AHQ"
        val json = app.get(url, referer = "$moviesbayAPI/")
            .parsedSafe<MoviesbayValues>()?.values

        val media = json?.find { it.first() == "${title.createSlug()}-$year" }

        media?.filter { it.startsWith("https://drive.google.com") || it.startsWith("https://cdn.moviesbay.live") }?.apmap {
            val index = media.indexOf(it)
            val size = media[index.minus(1)]
            val quality = media[index.minus(2)]
            val qualityName = media[index.minus(3)]
            val link = if(it.startsWith("https://drive.google.com")) {
                getDirectGdrive(it)
            } else {
                it.removeSuffix("?a=view")
            }

            callback.invoke(
                ExtractorLink(
                    "Moviesbay",
                    "Moviesbay $qualityName [$size]",
                    link,
                    "",
                    getQualityFromName(quality)
                )
            )

        }
    }

    suspend fun invokeMoviezAdd(
        apiUrl: String? = null,
        api: String? = null,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeBloginguru(apiUrl, api, title, year, season, episode, callback)
    }

    suspend fun invokeBollyMaza(
        apiUrl: String? = null,
        api: String? = null,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeBloginguru(apiUrl, api, title, year, season, episode, callback)
    }

    private suspend fun invokeBloginguru(
        apiUrl: String? = null,
        api: String? = null,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title?.createSlug()?.replace("-", " ")
        val doc = app.get("$apiUrl/?s=$fixTitle").document

        val matchMedia = doc.select("article.mh-loop-item").map {
            it.select("a").attr("href") to it.select("a").text()
        }.find {
            if (season == null) {
                it.second.contains(Regex("(?i)($fixTitle)|($title)")) && it.first.contains("$year")
            } else {
                it.second.contains(Regex("(?i)($fixTitle)|($title)")) && it.second.contains(Regex("(?i)(Season\\s?$season)|(S0?$season)"))
            }
        }

        val mediaLink =
            app.get(matchMedia?.first ?: return).document.selectFirst("a#jake1")?.attr("href")
        val detailDoc = app.get(mediaLink ?: return).document
        val media = detailDoc.selectFirst("div.entry-content pre span")?.text()
            ?.split("|")
            ?.map { it.trim() }

        val iframe = (if (season == null) {
            media?.mapIndexed { index, name ->
                detailDoc.select("div.entry-content > pre")[index.plus(1)].selectFirst("a")
                    ?.attr("href") to name
            }
        } else {
            media?.mapIndexed { index, name ->
                val linkMedia =
                    detailDoc.select("div.entry-content > pre")[index.plus(1)].selectFirst("a")
                        ?.attr("href")
                app.get(
                    linkMedia ?: return@mapIndexed null
                ).document.selectFirst("div.entry-content strong:matches((?i)S0?${season}E0?${episode}) a")
                    ?.attr("href") to name
            }
        })?.filter { it?.first?.startsWith("http") == true }

        iframe?.apmap {
            val token = app.get(
                it?.first ?: return@apmap null
            ).document.select("input[name=_csrf_token_645a83a41868941e4692aa31e7235f2]")
                .attr("value")
            val shortLink = app.post(
                it.first ?: return@apmap null,
                data = mapOf("_csrf_token_645a83a41868941e4692aa31e7235f2" to token)
            ).document.selectFirst("a[rel=nofollow]")?.attr("href")

//            val videoUrl = extractRebrandly(shortLink ?: return@apmapIndexed null )
            val quality =
                Regex("(\\d{3,4})p").find(it.second)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val qualityName = it.second.replace("${quality}p", "").trim()

            callback.invoke(
                ExtractorLink(
                    "$api",
                    "$api $qualityName",
                    shortLink ?: return@apmap null,
                    "",
                    quality ?: Qualities.Unknown.value
                )
            )
        }

    }

    suspend fun invokeRStream(
        id: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = if (season == null) {
            "$rStreamAPI/e/?tmdb=$id"
        } else {
            "$rStreamAPI/e/?tmdb=$id&s=$season&e=$episode"
        }

        val res = app.get(url).text
        val link = Regex("\"file\":\"(http.*?)\"").find(res)?.groupValues?.getOrNull(1) ?: return

        delay(1000)
        if(!app.get(link, referer = rStreamAPI).isSuccessful) return

        callback.invoke(
            ExtractorLink(
                "RStream",
                "RStream",
                link,
                rStreamAPI,
                Qualities.P720.value,
                link.contains(".m3u8")
            )
        )
    }

    suspend fun invokeFlixon(
        tmdbId: Int? = null,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val onionUrl = "https://onionplay.se/"
        val request = if (season == null) {
            val res = app.get("$flixonAPI/$imdbId", referer = onionUrl)
            if (res.text.contains("BEGIN PGP SIGNED MESSAGE")
            ) app.get("$flixonAPI/$imdbId-1", referer = onionUrl) else res
        } else {
            app.get("$flixonAPI/$tmdbId-$season-$episode", referer = onionUrl)
        }

        val script = request.document.selectFirst("script:containsData(= \"\";)")?.data()
        val collection = script?.substringAfter("= [")?.substringBefore("];")
        val num = script?.substringAfterLast("(value) -")?.substringBefore(");")?.trim()?.toInt()
            ?: return

        val iframe = collection?.split(",")?.map { it.trim().toInt() }?.map { nums ->
            nums.minus(num).toChar()
        }?.joinToString("")?.let { Jsoup.parse(it) }?.selectFirst("button.redirect")
            ?.attr("onclick")
            ?.substringAfter("('")?.substringBefore("')")

        delay(1000)
        val unPacker =
            app.get(
                iframe ?: return,
                referer = "https://flixon.ru/"
            ).document.selectFirst("script:containsData(JuicyCodes.Run)")
                ?.data()
                ?.substringAfter("JuicyCodes.Run(")?.substringBefore(");")?.split("+")
                ?.joinToString("") { it.replace("\"", "").trim() }
                ?.let { getAndUnpack(base64Decode(it)) }

        val ref = "https://onionflix.ru/"
        val link = Regex("[\"']file[\"']:[\"'](.+?)[\"'],").find(
            unPacker ?: return
        )?.groupValues?.getOrNull(1)

        callback.invoke(
            ExtractorLink(
                "Flixon",
                "Flixon",
                link ?: return,
                ref,
                Qualities.P720.value,
                link.contains(".m3u8")
            )
        )

    }

    suspend fun invokeMovie123Net(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val server = "https://vidcloud9.org"
        val fixTitle = title.createSlug()
        val m = app.get("$movie123NetAPI/searching?q=$title&limit=40")
            .parsedSafe<Movie123Search>()?.data?.find {
                if (season == null) {
                    (it.t.equals(title, true) || it.t.createSlug()
                        .equals(fixTitle)) && it.t?.contains("season", true) == false
                } else {
                    it.t?.equals(
                        "$title - Season $season",
                        true
                    ) == true || it.s?.contains("$fixTitle-season-$season-", true) == true
                }
            }?.s?.substringAfterLast("-") ?: return

        listOf(
            "1",
            "2"
        ).apmap { serverNum ->
            val media = app.post(
                "$movie123NetAPI/datas",
                requestBody = """{"m":$m,"e":${episode ?: 1},"s":$serverNum}""".toRequestBody(
                    RequestBodyTypes.JSON.toMediaTypeOrNull()
                )
            ).parsedSafe<Movie123Media>()?.url ?: return@apmap null

            val serverUrl = "$server/watch?v=$media"
            val token =
                app.get(serverUrl).document.selectFirst("script:containsData(setRequestHeader)")
                    ?.data()?.let {
                        Regex("\\('0x1f2'\\),'(\\S+?)'\\)").find(it)?.groupValues?.getOrNull(1)
                    } ?: return@apmap null

            val videoUrl = app.post(
                "$server/data",
                requestBody = """{"doc":"$media"}""".toRequestBody(
                    RequestBodyTypes.JSON.toMediaTypeOrNull()
                ),
                headers = mapOf(
                    "x-csrf-token" to token
                ),
            ).parsedSafe<Movie123Media>()?.url ?: return@apmap null

            if (videoUrl.startsWith("https")) {
                loadExtractor(videoUrl, movie123NetAPI, subtitleCallback, callback)
            } else {
                callback.invoke(
                    ExtractorLink(
                        "123Movies",
                        "123Movies",
                        fixUrl(base64Decode(videoUrl), server),
                        serverUrl,
                        Qualities.P720.value,
                        true
                    )
                )

                subtitleCallback.invoke(
                    SubtitleFile(
                        "English",
                        "https://sub.vxdn.net/sub/$m-${episode ?: 1}.vtt"
                    )
                )
            }
        }

    }

    suspend fun invokeSmashyStream(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = if (season == null) {
            "$smashyStreamAPI/playere.php?imdb=$imdbId"
        } else {
            "$smashyStreamAPI/playere.php?imdb=$imdbId&season=$season&episode=$episode"
        }

        app.get(url, referer = "https://smashystream.com/").document.select("div#_default-servers a.server").map {
            it.attr("data-id") to it.text()
        }.apmap {
            when {
                it.first.contains("/flix") -> {
                    invokeSmashyOne(it.second, it.first, callback)
                }
                it.first.contains("/gtop") -> {
                    invokeSmashyTwo(it.second, it.first, callback)
                }
                else -> return@apmap
            }
        }

    }

    //TODO only subs
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
                "wsk" to "f6ea6cde-e42b-4c26-98d3-b4fe48cdd4fb",
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

        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)

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

    suspend fun invokeBaymovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val api = "https://thebayindexpublicgroupapi.zindex.eu.org"
        val key = base64DecodeAPI("ZW0=c3Q=c3k=b28=YWQ=Ymg=")
        val headers = mapOf(
            "Referer" to "$baymoviesAPI/",
            "Origin" to baymoviesAPI,
            "cf_cache_token" to "UKsVpQqBMxB56gBfhYKbfCVkRIXMh42pk6G4DdkXXoVh7j4BjV"
        )
        val query = getIndexQuery(title, year, season, episode)
        val search = app.get(
            "$api/0:search?q=$query&page_token=&page_index=0",
            headers = headers
        ).text
        val media = searchIndex(title, season, episode, year, search) ?: return

        media.apmap { file ->
            val expiry = (System.currentTimeMillis() + 345600000).toString()
            val hmacSign = "${file.id}@$expiry".encode()
                .hmacSha256(key.encode()).base64().replace("+", "-")
            val encryptedId =
                base64Encode(CryptoAES.encrypt(key, file.id ?: return@apmap null).toByteArray())
            val encryptedExpiry = base64Encode(CryptoAES.encrypt(key, expiry).toByteArray())
            val worker = getConfig().workers.randomOrNull() ?: return@apmap null

            val link =
                "https://api.$worker.workers.dev/download.aspx?file=$encryptedId&expiry=$encryptedExpiry&mac=$hmacSign"
//            if (!app.get(link, referer = "$baymoviesAPI/").isSuccessful) return@apmap null
            val size = file.size?.toDouble() ?: return@apmap null
            val sizeFile = "%.2f GB".format(bytesToGigaBytes(size))
            val tags = Regex("\\d{3,4}[pP]\\.?(.*?)\\.(mkv|mp4)").find(
                file.name ?: return@apmap null
            )?.groupValues?.getOrNull(1)?.replace(".", " ")?.trim()
                ?: ""
            val quality =
                Regex("(\\d{3,4})[pP]").find(file.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Qualities.P1080.value

            callback.invoke(
                ExtractorLink(
                    "Baymovies",
                    "Baymovies $tags [$sizeFile]",
                    link,
                    "$baymoviesAPI/",
                    quality,
                )
            )

        }


    }

    suspend fun invokeJsmovies(
        apiUrl: String,
        api: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeIndex(
            apiUrl,
            api,
            title,
            year,
            season,
            episode,
            callback,
        )
    }

    suspend fun invokeBlackmovies(
        apiUrl: String,
        api: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeIndex(
            apiUrl,
            api,
            title,
            year,
            season,
            episode,
            callback,
        )
    }

    suspend fun invokeGammovies(
        apiUrl: String,
        api: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeIndex(
            apiUrl,
            api,
            title,
            year,
            season,
            episode,
            callback,
        )
    }

    suspend fun invokeChillmovies0(
        apiUrl: String,
        api: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeIndex(
            apiUrl,
            api,
            title,
            year,
            season,
            episode,
            callback,
        )
    }

    suspend fun invokeChillmovies1(
        apiUrl: String,
        api: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeIndex(
            apiUrl,
            api,
            title,
            year,
            season,
            episode,
            callback,
        )
    }

    suspend fun invokeXtrememovies(
        apiUrl: String,
        api: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeIndex(
            apiUrl,
            api,
            title,
            year,
            season,
            episode,
            callback,
        )
    }

    suspend fun invokeRinzrymovies(
        apiUrl: String,
        api: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeIndex(
            apiUrl,
            api,
            title,
            year,
            season,
            episode,
            callback,
        )
    }

    suspend fun invokeCodexmovies(
        apiUrl: String,
        api: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
        password: String = "",
    ) {
        invokeIndex(
            apiUrl,
            api,
            title,
            year,
            season,
            episode,
            callback,
            password,
        )
    }

    suspend fun invokeEdithxmovies(
        apiUrl: String,
        api: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
        password: String = "",
    ) {
        invokeIndex(
            apiUrl,
            api,
            title,
            year,
            season,
            episode,
            callback,
            password,
        )
    }

    suspend fun invokePapaonMovies1(
        apiUrl: String,
        api: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeIndex(
            apiUrl,
            api,
            title,
            year,
            season,
            episode,
            callback,
        )
    }

    suspend fun invokePapaonMovies2(
        apiUrl: String,
        api: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeIndex(
            apiUrl,
            api,
            title,
            year,
            season,
            episode,
            callback,
        )
    }

    suspend fun invokeJmdkhMovies(
        apiUrl: String,
        api: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeIndex(
            apiUrl,
            api,
            title,
            year,
            season,
            episode,
            callback,
        )
    }

    suspend fun invokeRubyMovies(
        apiUrl: String,
        api: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeIndex(
            apiUrl,
            api,
            title,
            year,
            season,
            episode,
            callback,
        )
    }

    suspend fun invokeShinobiMovies(
        apiUrl: String,
        api: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeIndex(
            apiUrl,
            api,
            title,
            year,
            season,
            episode,
            callback,
        )
    }

    suspend fun invokeVitoenMovies(
        apiUrl: String,
        api: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeIndex(
            apiUrl,
            api,
            title,
            year,
            season,
            episode,
            callback,
        )
    }

    private suspend fun invokeIndex(
        apiUrl: String,
        api: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
        password: String = "",
    ) {
        val passHeaders = mapOf(
            "Authorization" to password
        )
        val query = getIndexQuery(title, year, season, episode).let {
            if (api in mkvIndex) "$it mkv" else it
        }
        val body =
            """{"q":"$query","password":null,"page_token":null,"page_index":0}""".toRequestBody(
                RequestBodyTypes.JSON.toMediaTypeOrNull()
            )
        val data = mapOf(
            "q" to query,
            "page_token" to "",
            "page_index" to "0"
        )
        val search = if (api in encodedIndex) {
            decodeIndexJson(
                if (api in lockedIndex) app.post(
                    "${apiUrl}search",
                    data = data,
                    headers = passHeaders,
                    referer = apiUrl
                ).text else app.post(
                    "${apiUrl}search",
                    data = data,
                    referer = apiUrl
                ).text
            )
        } else {
            app.post("${apiUrl}search", requestBody = body,referer = apiUrl).text
        }
        val media = if (api in untrimmedIndex) searchIndex(
            title,
            season,
            episode,
            year,
            search,
            false
        ) else searchIndex(title, season, episode, year, search)
        media?.apmap { file ->
            val pathBody = """{"id":"${file.id ?: return@apmap null}"}""".toRequestBody(
                RequestBodyTypes.JSON.toMediaTypeOrNull()
            )
            val pathData = mapOf(
                "id" to file.id,
            )
            val path = (if (api in encodedIndex) {
                if (api in lockedIndex) {
                    app.post(
                        "${apiUrl}id2path",
                        data = pathData,
                        headers = passHeaders,
                        referer = apiUrl
                    )
                } else {
                    app.post(
                        "${apiUrl}id2path", data = pathData, referer = apiUrl
                    )
                }
            } else {
                app.post("${apiUrl}id2path", requestBody = pathBody, referer = apiUrl)
            }).text.let { path ->
                if (api == "RinzryMovies") {
                    val worker = app.get(
                        "${fixUrl(path, apiUrl)}?a=view"
                    ).document.selectFirst("script:containsData(downloaddomain)")?.data()
                        ?.substringAfter("\"downloaddomain\":\"")?.substringBefore("\",")?.let {
                            "$it/0:"
                        }
                    fixUrl(path, worker ?: return@apmap null)
                } else {
                    fixUrl(path, apiUrl)
                }
            }.encodeUrl()

//            removed due to rate limit
//            if (!app.get(path).isSuccessful) return@apmap null

            val size = "%.2f GB".format(bytesToGigaBytes(file.size?.toDouble() ?: return@apmap null))
            val quality = getIndexQuality(file.name)
            val tags = getIndexQualityTags(file.name)

            callback.invoke(
                ExtractorLink(
                    api,
                    "$api $tags [$size]",
                    path,
                    if(api in needRefererIndex) apiUrl else "",
                    quality,
                )
            )

        }

    }

    suspend fun invokeTgarMovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val query = getIndexQuery(title, year, season, episode)

        val files = app.get(
            "https://api.tgarchive.superfastsearch.zindex.eu.org/search?name=${encode(query)}&page=1",
            referer = tgarMovieAPI,
            timeout = 600L
        ).parsedSafe<TgarData>()?.documents?.filter { media ->
            matchingIndex(
                media.name,
                media.mime_type,
                title,
                year,
                season,
                episode,
                true
            ) && media.name?.contains("XXX") == false
        }

        files?.map { file ->
            val size = "%.2f GB".format(bytesToGigaBytes(file.size ?: return@map null))
            val quality = getIndexQuality(file.name)
            val tags = getIndexQualityTags(file.name)
            callback.invoke(
                ExtractorLink(
                    "TgarMovies",
                    "TgarMovies $tags [$size]",
                    "https://api.southkoreacdn.workers.dev/telegram/${file._id}",
                    "$tgarMovieAPI/",
                    quality,
                )
            )
        }

    }

    suspend fun invokeGdbotMovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val query = getIndexQuery(title, null, season, episode)
        val files = app.get("$gdbot/search?q=$query").document.select("ul.divide-y li").map {
            Triple(
                it.select("a").attr("href"),
                it.select("a").text(),
                it.select("span").text()
            )
        }.filter {
            matchingIndex(
                it.second,
                null,
                title,
                year,
                season,
                episode,
            )
        }.sortedByDescending {
            it.third.getFileSize()
        }

        files.let { file ->
            listOfNotNull(
                file.find { it.second.contains("2160p", true) },
                file.find { it.second.contains("1080p", true) }
            )
        }.apmap { file ->
            val videoUrl = extractGdflix(file.first)
            val quality = getIndexQuality(file.second)
            val tags = getIndexQualityTags(file.second)
            val size = Regex("(\\d+\\.?\\d+\\sGB|MB)").find(file.third)?.groupValues?.get(0)?.trim()

            callback.invoke(
                ExtractorLink(
                    "GdbotMovies",
                    "GdbotMovies $tags [$size]",
                    videoUrl ?: return@apmap null,
                    "",
                    quality,
                )
            )

        }

    }

    suspend fun invokeDahmerMovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = if(season == null) {
            "$dahmerMoviesAPI/movies/${title?.replace(":", "")} ($year)/"
        } else {
            "$dahmerMoviesAPI/tvs/${title?.replace(":", " -")}/Season $season/"
        }

        val request = app.get(url)
        if(!request.isSuccessful) return

        val paths = request.document.select("tr.file").map {
            Triple(
                it.select("a").text(),
                it.select("a").attr("href"),
                it.select("size").text(),
            )
        }.filter {
            if(season == null) {
                it.first.contains(Regex("(?i)(1080p|2160p)"))
            } else {
                val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
                it.first.contains(Regex("(?i)S${seasonSlug}E${episodeSlug}"))
            }
        }.ifEmpty { return }

        paths.map {
            val quality = getIndexQuality(it.first)
            val tags = getIndexQualityTags(it.first)
            val size = "%.2f GB".format(bytesToGigaBytes(it.third.toDouble()))
            callback.invoke(
                ExtractorLink(
                    "DahmerMovies",
                    "DahmerMovies $tags [$size]",
                    url + it.second,
                    "",
                    quality,
                )
            )

        }

    }

    suspend fun invokeGomovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
        val query = if (season == null) {
            title
        } else {
            "$title Season $season"
        }

        val doc = app.get("$gomoviesAPI/search/$query").document

        val media = doc.select("div._gory div.g_yFsxmKnYLvpKDTrdbizeYMWy").map {
            Triple(
                it.attr("data-filmName"),
                it.attr("data-year"),
                it.select("a").attr("href")
            )
        }.let { el ->
            if(el.size == 1) {
                el.firstOrNull()
            } else {
                el.find {
                    if (season == null) {
                        (it.first.equals(title, true) || it.first.equals(
                            "$title ($year)",
                            true
                        )) && it.second.equals("$year")
                    } else {
                        it.first.equals("$title - Season $season", true)
                    }
                }
            } ?: el.find { it.first.contains("$title", true) && it.second.equals("$year") }
        } ?: return

        val iframe = if (season == null) {
            media.third
        } else {
            app.get(
                fixUrl(
                    media.third,
                    gomoviesAPI
                )
            ).document.selectFirst("div#g_MXOzFGouZrOAUioXjpddqkZK a:contains(Episode $episodeSlug:)")
                ?.attr("href")
        } ?: return

        val res = app.get(fixUrl(iframe, gomoviesAPI), verify = false)
        val match = "var url = '(/user/servers/.*?\\?ep=.*?)';".toRegex().find(res.text)
        val serverUrl = match?.groupValues?.get(1) ?: return
        val cookies = res.okhttpResponse.headers.getGomoviesCookies()
        val url = res.document.select("meta[property=og:url]").attr("content")
        val headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        val qualities = intArrayOf(2160, 1440, 1080, 720, 480, 360)
        app.get(
            "$gomoviesAPI$serverUrl",
            cookies = cookies, referer = url, headers = headers
        ).document.select("ul li").amap { el ->
            val server = el.attr("data-value")
            val encryptedData = app.get(
                "$url?server=$server&_=${System.currentTimeMillis()}",
                cookies = cookies,
                referer = url,
                headers = headers
            ).text
            val json = base64Decode(encryptedData).decryptGomoviesJson()
            val links = tryParseJson<List<GomoviesSources>>(json) ?: return@amap
            links.forEach { video ->
                qualities.filter { it <= video.max.toInt() }.forEach {
                    callback(
                        ExtractorLink(
                            "Gomovies",
                            "Gomovies",
                            video.src.split("360", limit = 3).joinToString(it.toString()),
                            "$gomoviesAPI/",
                            it,
                        )
                    )
                }
            }
        }

    }

    suspend fun invokeAsk4Movies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val query = if (season == null) {
            title
        } else {
            "$title season $season"
        }
        val mediaData =
            app.get("$ask4MoviesAPI/?s=$query").document.select("div#search-content div.item").map {
                it.selectFirst("div.main-item a")
            }

        val media = if (mediaData.size == 1) {
            mediaData.firstOrNull()
        } else {
            mediaData.find {
                if (season == null) {
                    it?.text().equals("$title ($year)", true)
                } else {
                    it?.text().equals("$title (Season $season)", true)
                }
            }
        }

        val epsDoc = app.get(media?.attr("href") ?: return).document

        val iframe = if (season == null) {
            epsDoc.select("div#player-embed iframe").attr("data-src")
        } else {
            epsDoc.select("ul.group-links-list li:nth-child($episode) a").attr("data-embed-src")
        }

        loadExtractor(iframe, ask4MoviesAPI, subtitleCallback, callback)

    }

    suspend fun invokeWatchOnline(
        imdbId: String? = null,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val slug = title.createSlug()
        val id = imdbId?.removePrefix("tt")
        val url = if (season == null) {
            "$watchOnlineAPI/movies/view/$id-$slug-$year"
        } else {
            "$watchOnlineAPI/shows/view/$id-$slug-$year"
        }

        val res = app.get(url).document

        val episodeId = if (season == null) {
            res.selectFirst("div.movie__buttons-items a")?.attr("data-watch-list-media-id")
        } else {
            res.selectFirst("ul[data-season-episodes=$season] li[data-episode=$episode]")
                ?.attr("data-id-episode")
        } ?: return

        val videoUrl = if (season == null) {
            "$watchOnlineAPI/api/v1/security/movie-access?id_movie=$episodeId"
        } else {
            "$watchOnlineAPI/api/v1/security/episode-access?id=$episodeId"
        }

        val json = app.get(videoUrl, referer = url)
            .parsedSafe<WatchOnlineResponse>()

        json?.streams?.mapKeys { source ->
            callback.invoke(
                ExtractorLink(
                    "WatchOnline",
                    "WatchOnline",
                    source.value,
                    "$watchOnlineAPI/",
                    getQualityFromName(source.key),
                    true
                )
            )
        }

        //TODO find better way
        val subtitles = json?.subtitles as ArrayList<HashMap<String, String>>

        subtitles.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    sub["language"] ?: return@map,
                    fixUrl(sub["url"] ?: return@map, watchOnlineAPI)
                )
            )
        }

    }


}

class StreamM4u : XStreamCdn() {
    override val name: String = "StreamM4u"
    override val mainUrl: String = "https://streamm4u.club"
}

class Sblongvu : StreamSB() {
    override var name = "Sblongvu"
    override var mainUrl = "https://sblongvu.com"
}

class Keephealth : StreamSB() {
    override var name = "Keephealth"
    override var mainUrl = "https://keephealth.info"
}

class FileMoonIn : Filesim() {
    override val mainUrl = "https://filemoon.in"
    override val name = "FileMoon"
}

data class FDMovieIFrame(
    val link: String,
    val quality: String,
    val size: String,
    val type: String,
)

data class BaymoviesConfig(
    val country: String,
    val downloadTime: String,
    val workers: List<String>
)

data class Movie123Media(
    @JsonProperty("url") val url: String? = null,
)

data class Movie123Data(
    @JsonProperty("t") val t: String? = null,
    @JsonProperty("s") val s: String? = null,
)

data class Movie123Search(
    @JsonProperty("data") val data: ArrayList<Movie123Data>? = arrayListOf(),
)

data class GomoviesSources(
    @JsonProperty("src") val src: String,
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: Int? = null,
    @JsonProperty("max") val max: String,
    @JsonProperty("size") val size: String,
)

data class UHDBackupUrl(
    @JsonProperty("url") val url: String? = null,
)

data class MoviesbayValues(
    @JsonProperty("values") val values: List<List<String>>? = arrayListOf(),
)

data class HdMovieBoxTracks(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("file") val file: String? = null,
)

data class HdMovieBoxSource(
    @JsonProperty("videoUrl") val videoUrl: String? = null,
    @JsonProperty("videoServer") val videoServer: String? = null,
    @JsonProperty("videoDisk") val videoDisk: Any? = null,
    @JsonProperty("tracks") val tracks: ArrayList<HdMovieBoxTracks>? = arrayListOf(),
)

data class HdMovieBoxIframe(
    @JsonProperty("api_iframe") val apiIframe: String? = null,
)

data class ResponseHash(
    @JsonProperty("embed_url") val embed_url: String,
    @JsonProperty("type") val type: String?,
)

data class SubtitlingList(
    @JsonProperty("languageAbbr") val languageAbbr: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("subtitlingUrl") val subtitlingUrl: String? = null,
)

data class DefinitionList(
    @JsonProperty("code") val code: String? = null,
    @JsonProperty("description") val description: String? = null,
)

data class EpisodeVo(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("seriesNo") val seriesNo: Int? = null,
    @JsonProperty("definitionList") val definitionList: ArrayList<DefinitionList>? = arrayListOf(),
    @JsonProperty("subtitlingList") val subtitlingList: ArrayList<SubtitlingList>? = arrayListOf(),
)

data class MediaDetail(
    @JsonProperty("episodeVo") val episodeVo: ArrayList<EpisodeVo>? = arrayListOf(),
)

data class Load(
    @JsonProperty("data") val data: MediaDetail? = null,
)

data class ConsumetHeaders(
    @JsonProperty("Referer") val referer: String? = null,
)

data class ConsumetSubtitles(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("lang") val lang: String? = null,
)

data class ConsumetSources(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("isM3U8") val isM3U8: Boolean? = null,
)

data class ConsumetSourcesResponse(
    @JsonProperty("headers") val headers: ConsumetHeaders? = null,
    @JsonProperty("sources") val sources: ArrayList<ConsumetSources>? = arrayListOf(),
    @JsonProperty("subtitles") val subtitles: ArrayList<ConsumetSubtitles>? = arrayListOf(),
)

data class ConsumetEpisodes(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("season") val season: Int? = null,
)

data class ConsumetDetails(
    @JsonProperty("episodes") val episodes: ArrayList<ConsumetEpisodes>? = arrayListOf(),
)

data class CrunchyrollDetails(
    @JsonProperty("episodes") val episodes: HashMap<String, List<HashMap<String, String>>>? = hashMapOf(),
)

data class ConsumetResults(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("seasons") val seasons: Int? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("type") val type: String? = null,
)

data class ConsumetSearchResponse(
    @JsonProperty("results") val results: ArrayList<ConsumetResults>? = arrayListOf(),
)

data class KisskhSources(
    @JsonProperty("Video") val video: String?,
    @JsonProperty("ThirdParty") val thirdParty: String?,
)

data class KisskhSubtitle(
    @JsonProperty("src") val src: String?,
    @JsonProperty("label") val label: String?,
)

data class KisskhEpisodes(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("number") val number: Int?,
)

data class KisskhDetail(
    @JsonProperty("episodes") val episodes: ArrayList<KisskhEpisodes>? = arrayListOf(),
)

data class KisskhResults(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("title") val title: String?,
)

data class EpisodesFwatayako(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("file") val file: String? = null,
)

data class SeasonFwatayako(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("folder") val folder: ArrayList<EpisodesFwatayako>? = arrayListOf(),
)

data class SourcesFwatayako(
    @JsonProperty("movie") val sourcesMovie: String? = null,
    @JsonProperty("tv") val sourcesTv: ArrayList<SeasonFwatayako>? = arrayListOf(),
)

data class DriveBotLink(
    @JsonProperty("url") val url: String? = null,
)

data class DirectDl(
    @JsonProperty("download_url") val download_url: String? = null,
)

data class Safelink(
    @JsonProperty("safelink") val safelink: String? = null,
)

data class FDAds(
    @JsonProperty("linkr") val linkr: String? = null,
)

data class DataMal(
    @JsonProperty("mal_id") val mal_id: String? = null,
)

data class JikanResponse(
    @JsonProperty("data") val data: ArrayList<DataMal>? = arrayListOf(),
)

data class IdAni(
    @JsonProperty("id") val id: String? = null,
)

data class MediaAni(
    @JsonProperty("Media") val media: IdAni? = null,
)

data class DataAni(
    @JsonProperty("data") val data: MediaAni? = null,
)

data class Smashy1Tracks(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null,
)

data class Smashy1Source(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("tracks") val tracks: ArrayList<Smashy1Tracks>? = arrayListOf(),
)

data class WatchsomuchTorrents(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("movieId") val movieId: Int? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
)

data class WatchsomuchMovies(
    @JsonProperty("torrents") val torrents: ArrayList<WatchsomuchTorrents>? = arrayListOf(),
)

data class WatchsomuchResponses(
    @JsonProperty("movie") val movie: WatchsomuchMovies? = null,
)

data class WatchsomuchSubtitles(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("label") val label: String? = null,
)

data class WatchsomuchSubResponses(
    @JsonProperty("subtitles") val subtitles: ArrayList<WatchsomuchSubtitles>? = arrayListOf(),
)

data class IndexMedia(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("driveId") val driveId: String? = null,
    @JsonProperty("mimeType") val mimeType: String? = null,
    @JsonProperty("size") val size: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("modifiedTime") val modifiedTime: String? = null,
)

data class IndexData(
    @JsonProperty("files") val files: ArrayList<IndexMedia>? = arrayListOf(),
)

data class IndexSearch(
    @JsonProperty("data") val data: IndexData? = null,
)

data class TgarMedia(
    @JsonProperty("_id") val _id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("size") val size: Double? = null,
    @JsonProperty("file_unique_id") val file_unique_id: String? = null,
    @JsonProperty("mime_type") val mime_type: String? = null,
)

data class TgarData(
    @JsonProperty("documents") val documents: ArrayList<TgarMedia>? = arrayListOf(),
)

data class Gdflix(
    @JsonProperty("url") val url: String
)

data class SorastreamResponse(
    @JsonProperty("data") val data: SorastreamVideos? = null,
)

data class SorastreamVideos(
    @JsonProperty("mediaUrl") val mediaUrl: String? = null,
    @JsonProperty("currentDefinition") val currentDefinition: String? = null,
)

data class KaaServers(
    @JsonProperty("servers") val servers: ArrayList<String>? = arrayListOf(),
)

data class KaaEpisode(
    @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
    @JsonProperty("slug") val slug: String? = null,
)

data class KaaEpisodeResults(
    @JsonProperty("result") val result: ArrayList<KaaEpisode>? = arrayListOf(),
)

data class KaaSeason(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("languages") val languages: ArrayList<String>? = arrayListOf(),
)

data class KaaSearchResponse(
    @JsonProperty("_id") val _id: String? = null,
    @JsonProperty("title") val title: String? = null,
)

data class SapphireSubtitles(
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("url") val url: String? = null,
)

data class SapphireStreams(
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("audio_lang") val audio_lang: String? = null,
    @JsonProperty("hardsub_lang") val hardsub_lang: String? = null,
    @JsonProperty("url") val url: String? = null,
)

data class SapphireSources(
    @JsonProperty("streams") val streams: ArrayList<SapphireStreams>? = arrayListOf(),
    @JsonProperty("subtitles") val subtitles: ArrayList<SapphireSubtitles>? = arrayListOf(),
)

data class BiliBiliEpisodes(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("sourceId") val sourceId: String? = null,
    @JsonProperty("sourceEpisodeId") val sourceEpisodeId: String? = null,
    @JsonProperty("sourceMediaId") val sourceMediaId: String? = null,
    @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
)

data class BiliBiliDetails(
    @JsonProperty("episodes") val episodes: ArrayList<BiliBiliEpisodes>? = arrayListOf(),
)

data class BiliBiliSubtitles(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("language") val language: String? = null,
)

data class BiliBiliSources(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("type") val type: String? = null,
)

data class BiliBiliSourcesResponse(
    @JsonProperty("sources") val sources: ArrayList<BiliBiliSources>? = arrayListOf(),
    @JsonProperty("subtitles") val subtitles: ArrayList<BiliBiliSubtitles>? = arrayListOf(),
)

data class WatchOnlineResponse(
    @JsonProperty("streams") val streams: HashMap<String, String>? = null,
    @JsonProperty("subtitles") val subtitles: Any? = null,
)