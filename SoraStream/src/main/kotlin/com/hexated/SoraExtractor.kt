package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.hexated.SoraStream.Companion.filmxyAPI
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.Session
import com.lagradost.nicehttp.requestCreator
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI

val session = Session(Requests().baseClient)

object SoraExtractor : SoraStream() {

    /*
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
    */

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
                val link = source.link ?: return@let
                if (link.contains("rabbitstream")) {
                    val rabbitId = link.substringAfterLast("/").substringBefore("?")
                    app.get(
                        "https://rabbitstream.net/ajax/embed-5/getSources?id=$rabbitId",
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).parsedSafe<RabbitSources>()?.tracks?.map { sub ->
                        subtitleCallback.invoke(
                            SubtitleFile(
                                sub.label.toString(),
                                sub.file ?: return@map null
                            )
                        )
                    }
                } else {
                    loadExtractor(
                        link,
                        twoEmbedAPI,
                        subtitleCallback,
                        callback
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
        val url =
            "$olgplyAPI/${id}${season?.let { "/$it" } ?: ""}${episode?.let { "/$it" } ?: ""}"
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
            ) ?: return
        val subtitle =
            Regex("['|\"]subtitle['|\"]:\\s['|\"](\\S+?)['|\"]").find(script.toString())?.groupValues?.get(
                1
            )

        val ref = getBaseUrl(iframeDbgo)
        decryptStreamUrl(source).split(",").map { links ->
            val quality =
                Regex("\\[([0-9]*p.*?)]").find(links)?.groupValues?.getOrNull(1).toString().trim()
            links.replace("[$quality]", "").split(" or ").map { it.trim() }
                .map { link ->
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
                link,
                "https://123moviesjr.cc/",
                subtitleCallback,
                callback
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

    /*    suspend fun invokeSoraVIP(
            title: String? = null,
            orgTitle: String? = null,
            year: Int? = null,
            season: Int? = null,
            episode: Int? = null,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            val apiUrl = base64DecodeAPI("aQ==YXA=cC8=YXA=bC4=Y2U=ZXI=LnY=b2s=a2w=bG8=Ly8=czo=dHA=aHQ=")
            val url = if(season == null) {
                "$apiUrl/search/one?title=$title&orgTitle=$orgTitle&year=$year"
            } else {
                "$apiUrl/search/one?title=$title&orgTitle=$orgTitle&year=$year&season=$season"
            }

            val id = app.get(url).parsedSafe<DetailVipResult>()?.data?.id

            val sourcesUrl = if(season == null) {
                "$apiUrl/movie/detail?id=$id"
            } else {
                "$apiUrl/tv/detail?id=$id&episodeId=${episode?.minus(1)}"
            }

            val json = app.get(sourcesUrl).parsedSafe<LoadVIPLinks>()

            json?.sources?.map { source ->
                callback.invoke(
                    ExtractorLink(
                        "${this.name} (VIP)",
                        "${this.name} (VIP)",
                        source.url ?: return@map null,
                        "$apiUrl/",
                        source.quality ?: Qualities.Unknown.value,
                        isM3u8 = source.url.contains(".m3u8"),
                    )
                )
            }

            json?.subtitles?.map { sub ->
                subtitleCallback.invoke(
                    SubtitleFile(
                        getLanguage(sub.language.toString()),
                        sub.url ?: return@map null
                    )
                )
            }
        }
     */

    suspend fun invokeHDMovieBox(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.fixTitle()
        val url = "$hdMovieBoxAPI/watch/$fixTitle"
//        val ref = if (season == null) {
//            "$hdMovieBoxAPI/watch/$fixTitle"
//        } else {
//            "$hdMovieBoxAPI/watch/$fixTitle/season-$season/episode-$episode"
//        }

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

        delay(1000)
        val iframe = app.get(iframeUrl, referer = "$hdMovieBoxAPI/").document.selectFirst("iframe")
            ?.attr("src")

        val script = app.get(
            iframe ?: return,
            referer = "$hdMovieBoxAPI/"
        ).document.selectFirst("script:containsData(var vhash =)")?.data()
            ?.substringAfter("vhash, {")?.substringBefore("}, false")

        tryParseJson<HdMovieBoxSource>("{$script}").let { source ->
            val disk = if (source?.videoDisk == null) {
                ""
            } else {
                base64Encode(source.videoDisk.toString().toByteArray())
            }
            val link = getBaseUrl(iframe) + source?.videoUrl?.replace(
                "\\",
                ""
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
        }
    }

    suspend fun invokeSeries9(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.fixTitle()
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
        val fixTitle = title.fixTitle()
        val url = if (season == null) {
            "$idlixAPI/movie/$fixTitle-$year"
        } else {
            "$idlixAPI/episode/$fixTitle-season-$season-episode-$episode"
        }

        val res = app.get(url)
        if (!res.isSuccessful) return
        val document = res.document
        val id = document.select("meta#dooplay-ajax-counter").attr("data-postid")
        val type = if (url.contains("/movie/")) "movie" else "tv"

        document.select("ul#playeroptionsul > li").map {
            it.attr("data-nume")
        }.apmap { nume ->
            val source = app.post(
                url = "$idlixAPI/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to id,
                    "nume" to nume,
                    "type" to type
                ),
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                referer = url
            ).parsed<ResponseHash>().embed_url

            if (!source.contains("youtube")) {
                loadExtractor(source, "$idlixAPI/", subtitleCallback, callback)
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
        val fixTitle = title.fixTitle()
        val url = if (season == null) {
            "$uniqueStreamAPI/movies/$fixTitle-$year"
        } else {
            "$uniqueStreamAPI/episodes/$fixTitle-season-$season-episode-$episode"
        }

        val res = app.get(url)
        if (!res.isSuccessful) return
        val document = res.document
        val type = if (url.contains("/movies/")) "movie" else "tv"
        document.select("ul#playeroptionsul > li").apmap { el ->
            val id = el.attr("data-post")
            val nume = el.attr("data-nume")
            val source = app.post(
                url = "$uniqueStreamAPI/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to id,
                    "nume" to nume,
                    "type" to type
                ),
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                referer = url
            ).parsed<ResponseHash>().embed_url.let { fixUrl(it) }

            when {
                source.contains("uniquestream") -> {
                    val resDoc = app.get(
                        source, referer = "$uniqueStreamAPI/", headers = mapOf(
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                        )
                    ).document
                    val srcm3u8 = resDoc.selectFirst("script:containsData(let url =)")?.data()?.let {
                        Regex("['|\"](.*?.m3u8)['|\"]").find(it)?.groupValues?.getOrNull(1)
                    } ?: return@apmap null
                    val quality = app.get(
                        srcm3u8, referer = source, headers = mapOf(
                            "Accept" to "*/*",
                        )
                    ).text.let { quality ->
                        if (quality.contains("RESOLUTION=1920")) Qualities.P1080.value else Qualities.P720.value
                    }
                    callback.invoke(
                        ExtractorLink(
                            "UniqueStream",
                            "UniqueStream",
                            srcm3u8,
                            source,
                            quality,
                            true,
                            headers = mapOf(
                                "Accept" to "*/*",
                            )
                        )
                    )
                }
                !source.contains("youtube") -> loadExtractor(source, "$uniqueStreamAPI/", subtitleCallback, callback)
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
        val fixTitle = title.fixTitle()
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
                .find { it.text().contains("Episode $episode") }
                ?.select("td")?.map {
                    it.select("a").attr("href") to it.select("a").text()
                }
        } ?: return

        delay(4000)
        links.map { (link, quality) ->
            val name =
                quality?.replace(Regex("[0-9]{3,4}p"), "Noverse")?.replace(".", " ") ?: "Noverse"
            callback.invoke(
                ExtractorLink(
                    name,
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

        val request = session.get(url, cookies = cookiesDoc)
        if (!request.isSuccessful) return

        val doc = request.document
        val script = doc.selectFirst("script:containsData(var isSingle)")?.data().toString()
        val sourcesData = Regex("listSE\\s*=\\s?(.*?),[\\n|\\s]").find(script)?.groupValues?.get(1)
        val sourcesDetail =
            Regex("linkDetails\\s*=\\s?(.*?),[\\n|\\s]").find(script)?.groupValues?.get(1)

        //Gson is shit, but i don't care
        val sourcesJson = JsonParser().parse(sourcesData).asJsonObject
        val sourcesDetailJson = JsonParser().parse(sourcesDetail).asJsonObject

        val sources = if (season == null && episode == null) {
            sourcesJson.getAsJsonObject("movie").getAsJsonArray("movie")
        } else {
            val eps = if (episode!! < 10) "0$episode" else episode
            val sson = if (season!! < 10) "0$season" else season
            sourcesJson.getAsJsonObject("s$sson").getAsJsonArray("e$eps")
        }.asJsonArray

        val scriptUser =
            doc.select("script").find { it.data().contains("var userNonce") }?.data().toString()
        val userNonce =
            Regex("var\\suserNonce.*?[\"|'](\\S+?)[\"|'];").find(scriptUser)?.groupValues?.get(1)
        val userId =
            Regex("var\\suser_id.*?[\"|'](\\S+?)[\"|'];").find(scriptUser)?.groupValues?.get(1)
        val linkIDs = sources.joinToString("") {
            "&linkIDs%5B%5D=$it"
        }.replace("\"", "")

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
        ).text.let { JsonParser().parse(it).asJsonObject }

        sources.map { source ->
            val src = source.asString
            val link = json.getAsJsonPrimitive(src).asString
            val quality =
                sourcesDetailJson.getAsJsonObject(src).getAsJsonPrimitive("resolution").asString
            val server =
                sourcesDetailJson.getAsJsonObject(src).getAsJsonPrimitive("server").asString
            val size = sourcesDetailJson.getAsJsonObject(src).getAsJsonPrimitive("size").asString

            callback.invoke(
                ExtractorLink(
                    "Filmxy $size ($server)",
                    "Filmxy $size ($server)",
                    link,
                    "$filmxyAPI/",
                    getQualityFromName(quality)
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
        val fixTitle = title.fixTitle()
        val url = if (season == null) {
            "$kimcartoonAPI/Cartoon/$fixTitle"
        } else {
            "$kimcartoonAPI/Cartoon/$fixTitle-season-$season"
        }

        val doc = app.get(url).document
        val iframe = if (season == null) {
            doc.select("table.listing tr td a").firstNotNullOf { it.attr("href") }
        } else {
            doc.select("table.listing tr td a").map {
                it.attr("href")
            }.first { it.contains("Season-$season", true) && it.contains("Episode-$episode", true) }
        } ?: return

        val source =
            app.get(fixUrl(iframe, kimcartoonAPI)).document.select("div#divContentVideo iframe")
                .attr("src")
        loadExtractor(source, "$kimcartoonAPI/", subtitleCallback) { link ->
            callback.invoke(
                ExtractorLink(
                    "Luxubu",
                    "Luxubu",
                    link.url,
                    link.referer,
                    link.quality,
                    link.isM3u8,
                    link.headers,
                    link.extractorData
                )
            )
        }
    }

    suspend fun invokeSoraVIP(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "lang" to "en",
            "versioncode" to "11",
            "clienttype" to "ios_jike_default"
        )
        val vipAPI =
            base64DecodeAPI("cA==YXA=cy8=Y20=di8=LnQ=b2s=a2w=bG8=aS4=YXA=ZS0=aWw=b2I=LW0=Z2E=Ly8=czo=dHA=aHQ=")
        val vipUrl = base64DecodeAPI("b20=LmM=b2s=a2w=bG8=Ly8=czo=dHA=aHQ=")

        val doc = app.get(
            "$vipUrl/search?keyword=$title",
        ).document

        val scriptData = doc.select("div.search-list div.search-video-card").map {
            Triple(
                it.selectFirst("h2.title")?.text().toString(),
                it.selectFirst("div.desc")?.text()
                    ?.substringBefore(".")?.toIntOrNull(),
                it.selectFirst("a")?.attr("href")?.split("/")
            )
        }

        val script = if (scriptData.size == 1) {
            scriptData.first()
        } else {
            scriptData.first {
                if (season == null) {
                    it.first.equals(
                        title,
                        true
                    ) && it.second == year
                } else {
                    it.first.contains(
                        "$title",
                        true
                    ) && (it.second == year || it.first.contains("Season $season", true))
                }
            }
        }

        val id = script.third?.last()
        val type = script.third?.get(2)

        val jsonResponse = app.get(
            "$vipAPI/movieDrama/get?id=${id}&category=${type}",
            headers = headers
        )

        if (!jsonResponse.isSuccessful) return

        val json = jsonResponse.parsedSafe<Load>()?.data?.episodeVo?.first {
            it.seriesNo == (episode ?: 0)
        }

        json?.definitionList?.apmap { video ->
            delay(1000)
            app.get(
                "${vipAPI}/media/previewInfo?category=${type}&contentId=${id}&episodeId=${json.id}&definition=${video.code}",
                headers = headers
            ).parsedSafe<Video>()?.data.let { link ->
                callback.invoke(
                    ExtractorLink(
                        "${this.name} (vip)",
                        "${this.name} (vip)",
                        link?.mediaUrl ?: return@let,
                        "",
                        getQualityFromName(video.description),
                        isM3u8 = true,
                        headers = headers
                    )
                )
            }
        }

        json?.subtitlingList?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    sub.language ?: "",
                    sub.subtitlingUrl ?: return@map
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
        val url = if (season == null || season == 1) {
            "$xMovieAPI/search?q=$title"
        } else {
            "$xMovieAPI/search?q=$title Season $season"
        }

        val res = app.get(url).document
        val scriptData = (if (season == null) {
            res.select("div.py-10")
                .find { it.selectFirst("h2")?.text()?.contains("Movie", true) == true }
        } else {
            res.select("div.py-10")
                .find { it.selectFirst("h2")?.text()?.contains("TV Shows", true) == true }
        })?.select("div.grid > div")?.map {
            Triple(
                it.selectFirst("h6")?.text(),
                it.select("div.float-right.text-neutral-dark").last()?.text(),
                it.selectFirst("a")?.attr("href")
            )
        } ?: return

        val script = if (scriptData.size == 1) {
            scriptData.first()
        } else {
            scriptData.first {
                it.first?.contains(
                    "$title",
                    true
                ) == true && (it.first?.contains("$season") == true || it.second?.contains(
                    "$year"
                ) == true)
            }
        }

        val doc = app.get(script.third ?: return).document
        val iframe = if (season == null) {
            doc.selectFirst("div.flex.gap-3.py-3 a")?.attr("href")
        } else {
            doc.select("div.grid.grid-cols-3.gap-3 a")[episode?.minus(1)!!]?.attr("href")
        }

        val link = app.get(iframe ?: return).text.let {
            Regex("[\"|']file[\"|']:\\s?[\"|'](http.*?.mp4)[\"|'],").find(it)?.groupValues?.getOrNull(
                1
            )
        }

        callback.invoke(
            ExtractorLink(
                "Xmovie",
                "Xmovie",
                link ?: return,
                "",
                Qualities.Unknown.value,
            )
        )


    }

    suspend fun invokeFlixhq(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title?.replace("–", "-")
        val id =
            app.get("$consumetFlixhqAPI/$title")
                .parsedSafe<ConsumetSearchResponse>()?.results?.find {
                    if (season == null) {
                        it.title?.equals(
                            "$fixTitle",
                            true
                        ) == true && it.releaseDate?.equals("$year") == true && it.type == "Movie"
                    } else {
                        it.title?.equals("$fixTitle", true) == true && it.type == "TV Series"
                    }
                }?.id ?: return

        val episodeId =
            app.get("$consumetFlixhqAPI/info?id=$id").parsedSafe<ConsumetDetails>()?.let {
                if (season == null) {
                    it.episodes?.first()?.id
                } else {
                    it.episodes?.find { ep -> ep.number == episode && ep.season == season }?.id
                }
            } ?: return

        listOf(
            "vidcloud",
            "upcloud"
        ).apmap { server ->
            delay(1000)
            val sources =
                app.get("$consumetFlixhqAPI/watch?episodeId=$episodeId&mediaId=$id&server=$server", timeout = 120L)
                    .parsedSafe<ConsumetSourcesResponse>()
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
                        it.lang ?: "",
                        it.url ?: return@map null
                    )
                )
            }

        }


    }

    suspend fun invoKisskh(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title?.replace("–", "-")
        val res = app.get(
            "$kissKhAPI/api/DramaList/Search?q=$title&type=0",
            referer = "$kissKhAPI/"
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
                        "$fixTitle",
                        true
                    ) == true && it.title.contains("Season $season", true)
                }
                data?.id to data?.title
            }
        }

        val resDetail = app.get(
            "$kissKhAPI/api/DramaList/Drama/$id?isq=false",
            referer = "$kissKhAPI/Drama/${
                getKisskhTitle(contentTitle)
            }?id=$id"
        ).parsedSafe<KisskhDetail>() ?: return

        val epsId = if (season == null) {
            resDetail.episodes?.first()?.id
        } else {
            resDetail.episodes?.find { it.number == episode }?.id
        }

        delay(2000)
        app.get(
            "$kissKhAPI/api/DramaList/Episode/$epsId.png?err=false&ts=&time=",
            referer = "$kissKhAPI/Drama/${getKisskhTitle(contentTitle)}/Episode-${episode ?: 0}?id=$id&ep=$epsId&page=0&pageSize=100"
        ).parsedSafe<KisskhSources>()?.let { source ->
            listOf(source.video, source.thirdParty).apmap { link ->
                if (link?.contains(".m3u8") == true) {
                    M3u8Helper.generateM3u8(
                        "Kisskh",
                        link,
                        referer = "$kissKhAPI/",
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
                        getLanguage(sub.label ?: return@map),
                        sub.src ?: return@map
                    )
                )
            }
        }


    }

    suspend fun invoZoro(
        id: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val malId = app.get("$tmdb2mal/?id=$id&s=$season").text.trim()
        val (engTitle, japTitle) = app.get("https://animixplay.to/assets/mal/$malId.json")
            .parsedSafe<AnimixData>()?.let {
                it.title_english to it.title
            } ?: return

        val animeId =
            app.get("$consumetZoroAPI/$japTitle").parsedSafe<ConsumetSearchResponse>()?.results?.find {
                val title = it.title ?: return
                (title.equals(engTitle, true) || title.equals(japTitle, true) ) && it.type == "TV"
            }?.id ?: return

        val episodeId = app.get("$consumetZoroAPI/info?id=$animeId")
            .parsedSafe<ConsumetDetails>()?.episodes?.find {
                it.number == episode
            }?.id ?: return

        delay(1000)
        val sources = app.get("$consumetZoroAPI/watch?episodeId=$episodeId", timeout = 120L)
            .parsedSafe<ConsumetSourcesResponse>() ?: return

        sources.sources?.map {
            callback.invoke(
                ExtractorLink(
                    "Zoro",
                    "Zoro",
                    it.url ?: return@map null,
                    "",
                    getQualityFromName(it.quality),
                    it.isM3U8 ?: true
                )
            )
        }

        sources.subtitles?.map {
            subtitleCallback.invoke(
                SubtitleFile(
                    it.lang ?: "",
                    it.url ?: return@map null
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
        val url = if(season == null) {
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
                it.first?.contains("$fixTitle", true) == true && it.second?.contains("$year") == true
            }
        }

        val doc = app.get(fixUrl(script?.third ?: return, lingAPI)).document
        val iframe = (if(season == null) {
            doc.selectFirst("a.video-js.vjs-default-skin")?.attr("data-href")
        } else {
            doc.select("div.blk div#tab_$season li")[episode!!.minus(1)].select("h5 a").attr("data-href")
        })?.let { fixUrl(it, lingAPI) }

        val source = app.get(iframe ?: return)
        val link = Regex("((https:|http:)//.*\\.mp4)").find(source.text)?.value ?: return
        callback.invoke(
            ExtractorLink(
                "Ling",
                "Ling",
                link,
                "$lingAPI/",
                Qualities.Unknown.value,
                headers = mapOf(
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
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val doc = app.get("$uhdmoviesAPI/?s=$title").document
        val scriptData = doc.select("div.row.gridlove-posts article").map {
            it.selectFirst("a")?.attr("href") to it.selectFirst("h1")?.text()
        }
        val script = if (scriptData.size == 1) {
            scriptData.first()
        } else {
            scriptData.find { it.second?.filterMedia(title, year, lastSeason) == true }
        }

        val detailDoc = app.get(script?.first ?: return).document

        val iframe =
            detailDoc.select("div.entry-content p").map { it }
                .filter { it.text().filterIframe(season, year) }
                .mapNotNull {
                    if (season == null) {
                        Triple(
                            it.text(),
                            it.selectFirst("span")?.text() ?: it.select("strong").last()?.text() ?: "",
                            it.nextElementSibling()?.select("a")?.attr("href")
                        )
                    } else {
                        Triple(
                            it.text(),
                            it.selectFirst("span")?.text() ?: it.select("strong").last()?.text() ?: "",
                            it.nextElementSibling()?.select("a:contains(Episode $episode)")
                                ?.attr("href")
                        )
                    }
                }.filter { it.third?.contains(Regex("(https:)|(http:)")) == true }

        val base = getBaseUrl(iframe.first().third ?: return)
        iframe.apmap { (quality, size, link) ->
            delay(1000)
            val res = app.get(link ?: return@apmap null).document
            val resDoc = res.selectFirst("script")?.data()?.substringAfter("replace(\"")
                ?.substringBefore("\")")?.let {
                    app.get(fixUrl(it, base)).document
                }
            val bitLink = resDoc?.selectFirst("a.btn.btn-outline-success")?.attr("href") ?: return@apmap null
            val baseDoc = app.get(fixUrl(bitLink, base)).document
            val downLink = baseDoc.select("div.mb-4 a").randomOrNull()
                ?.attr("href") ?: run {
                val server = baseDoc.select("div.text-center a:contains(Server 2)").attr("href")
                app.get(fixUrl(server, base)).document.selectFirst("div.mb-4 a")
                    ?.attr("href")
            }
            val downPage = app.get(downLink ?: return@apmap null).document
            val downloadLink = downPage.selectFirst("form[method=post] a.btn.btn-success")
                ?.attr("onclick")?.substringAfter("Openblank('")?.substringBefore("')") ?: run {
                val mirror = downPage.selectFirst("form[method=post] a.btn.btn-primary")
                    ?.attr("onclick")?.substringAfter("Openblank('")?.substringBefore("')")
                app.get(mirror ?: return@apmap null).document.selectFirst("script:containsData(input.value =)")
                    ?.data()?.substringAfter("input.value = '")?.substringBefore("';")
            }

            val videoQuality = Regex("(\\d{3,4})p").find(quality)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Qualities.Unknown.value
            val videoSize = size.substringBeforeLast("/")
            callback.invoke(
                ExtractorLink(
                    "UHDMovies [$videoSize]",
                    "UHDMovies [$videoSize]",
                    downloadLink ?: return@apmap null,
                    "",
                    videoQuality
                )
            )

        }


    }

    suspend fun invokeFwatayako(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val request = app.get("$fwatayakoAPI/IAF0wWTdNYZm?imdb_id=$imdbId")
        if (!request.isSuccessful) return
        val files = request.document.selectFirst("input#files")?.attr("value")
            .let {
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
                Regex("\\[([0-9]{3,4})p]").find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
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

}

data class FilmxyCookies(
    val phpsessid: String? = null,
    val wLog: String? = null,
    val wSec: String? = null,
)

fun String.filterIframe(seasonNum: Int?, year: Int?): Boolean {
    return if (seasonNum != null) {
        this.contains(Regex("(?i)(S0?$seasonNum)|(Season\\s0?$seasonNum)")) && !this.contains("Download", true)
    } else {
        this.contains("$year", true) && !this.contains("Download", true)
    }
}

fun String.filterMedia(title: String?, yearNum: Int?, seasonNum: Int?): Boolean {
    return if (seasonNum != null) {
        when {
            seasonNum > 1 -> this.contains(Regex("(?i)(Season\\s0?1-0?$seasonNum)|(S0?1-S?0?$seasonNum)")) && this.contains(
                "$title",
                true
            ) && this.contains("$yearNum")
            else -> this.contains(Regex("(?i)(Season\\s0?1)|(S0?1)")) && this.contains(
                "$title",
                true
            ) && this.contains("$yearNum")
        }
    } else {
        this.contains("$title", true) && this.contains("$yearNum")
    }
}

suspend fun getFilmxyCookies(imdbId: String? = null, season: Int? = null): FilmxyCookies? {

    val url = if (season == null) {
        "${filmxyAPI}/movie/$imdbId"
    } else {
        "${filmxyAPI}/tv/$imdbId"
    }
    val cookieUrl = "${filmxyAPI}/wp-admin/admin-ajax.php"

    val res = session.get(
        url,
        headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        ),
    )

    if (!res.isSuccessful) return FilmxyCookies()

    val userNonce =
        res.document.select("script").find { it.data().contains("var userNonce") }?.data()?.let {
            Regex("var\\suserNonce.*?\"(\\S+?)\";").find(it)?.groupValues?.get(1)
        }

    var phpsessid = session.baseClient.cookieJar.loadForRequest(url.toHttpUrl())
        .first { it.name == "PHPSESSID" }.value

    session.post(
        cookieUrl,
        data = mapOf(
            "action" to "guest_login",
            "nonce" to "$userNonce",
        ),
        headers = mapOf(
            "Cookie" to "PHPSESSID=$phpsessid; G_ENABLED_IDPS=google",
            "X-Requested-With" to "XMLHttpRequest",
        )
    )

    val cookieJar = session.baseClient.cookieJar.loadForRequest(cookieUrl.toHttpUrl())
    phpsessid = cookieJar.first { it.name == "PHPSESSID" }.value
    val wLog =
        cookieJar.first { it.name == "wordpress_logged_in_8bf9d5433ac88cc9a3a396d6b154cd01" }.value
    val wSec = cookieJar.first { it.name == "wordpress_sec_8bf9d5433ac88cc9a3a396d6b154cd01" }.value

    return FilmxyCookies(phpsessid, wLog, wSec)
}

private fun String?.fixTitle(): String? {
    return this?.replace(Regex("[!%:]|( &)"), "")?.replace(" ", "-")?.lowercase()
        ?.replace("-–-", "-")
}

fun getLanguage(str: String): String {
    return if (str.contains("(in_ID)")) "Indonesian" else str
}

private fun getKisskhTitle(str: String?): String? {
    return str?.replace(Regex("[^a-zA-Z0-9]"), "-")
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
            Qualities.P1080.value,
            true
        )
    )
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

data class HdMovieBoxSource(
    @JsonProperty("videoUrl") val videoUrl: String? = null,
    @JsonProperty("videoServer") val videoServer: String? = null,
    @JsonProperty("videoDisk") val videoDisk: Any? = null,
)

data class HdMovieBoxIframe(
    @JsonProperty("api_iframe") val apiIframe: String? = null,
)

data class ResponseHash(
    @JsonProperty("embed_url") val embed_url: String,
    @JsonProperty("type") val type: String?,
)

data class Track(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null,
)

data class RabbitSources(
    @JsonProperty("sources") val sources: String? = null,
    @JsonProperty("tracks") val tracks: ArrayList<Track>? = arrayListOf(),
)

data class VideoData(
    @JsonProperty("mediaUrl") val mediaUrl: String? = null,
)

data class Video(
    @JsonProperty("data") val data: VideoData? = null,
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
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("season") val season: Int? = null,
)

data class ConsumetDetails(
    @JsonProperty("episodes") val episodes: ArrayList<ConsumetEpisodes>? = arrayListOf(),
)

data class ConsumetResults(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
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

data class AnimixData(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("title_english") val title_english: String? = null,
    @JsonProperty("title_japanese") val title_japanese: String? = null,
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