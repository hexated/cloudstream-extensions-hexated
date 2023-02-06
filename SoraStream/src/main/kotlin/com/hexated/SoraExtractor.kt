package com.hexated

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.Session
import com.google.gson.JsonParser
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
                    val rabbitId = link.substringAfterLast("/").substringBefore("?")
                    app.get(
                        "https://rabbitstream.net/ajax/embed-5/getSources?id=$rabbitId",
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).parsedSafe<RabbitSources>()?.tracks?.map { sub ->
                        subtitleCallback.invoke(
                            SubtitleFile(
                                sub.label.toString(), sub.file ?: return@map null
                            )
                        )
                    }
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

        delay(1000)
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
        val subSources =
            Regex("dSubtitles\\s*=\\s?(.*?),[\\n|\\s]").find(script)?.groupValues?.get(1)

        //Gson is shit, but i don't care
        val sourcesJson = JsonParser().parse(sourcesData).asJsonObject
        val sourcesDetailJson = JsonParser().parse(sourcesDetail).asJsonObject
        val subJson = JsonParser().parse(subSources).asJsonObject

        val sources = if (season == null && episode == null) {
            sourcesJson.getAsJsonObject("movie").getAsJsonArray("movie")
        } else {
            val eps = if (episode!! < 10) "0$episode" else episode
            val sson = if (season!! < 10) "0$season" else season
            sourcesJson.getAsJsonObject("s$sson").getAsJsonArray("e$eps")
        }.asJsonArray

        val subSource = if (season == null && episode == null) {
            subJson.getAsJsonObject("movie").getAsJsonObject("movie")
        } else {
            val eps = if (episode!! < 10) "0$episode" else episode
            val sson = if (season!! < 10) "0$season" else season
            subJson.getAsJsonObject("s$sson").getAsJsonObject("e$eps")
        }.asJsonObject

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

        subSource.toString().removeSurrounding("{", "}").split(",").map {
            val slug = Regex("\"(\\w+)\":\"(\\d+)\"").find(it)?.groupValues
            slug?.getOrNull(1) to slug?.getOrNull(2)
        }.map { (lang, id) ->
            subtitleCallback.invoke(
                SubtitleFile(
                    SubtitleHelper.fromTwoLettersToLanguage(lang ?: "") ?: "$lang",
                    "https://www.mysubs.org/get-subtitle/$id"
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
        loadExtractor(source, "$kimcartoonAPI/", subtitleCallback) { link ->
            callback.invoke(
                ExtractorLink(
                    "Kimcartoon",
                    "Kimcartoon",
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

    private suspend fun invokeNetMovies(
        id: String? = null,
        type: String? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val epsId = app.get(
            "$netMoviesAPI/detail?category=$type&id=$id",
        ).parsedSafe<Load>()?.data?.episodeVo?.find {
            it.seriesNo == (episode ?: 0)
        }?.id ?: return

        val sources = app.get("$netMoviesAPI/episode?category=$type&id=$id&episode=$epsId")
            .parsedSafe<NetMoviesSources>()?.data ?: return

        sources.subtitles?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    getVipLanguage(sub.lang ?: return@map null),
                    sub.url ?: return@map null
                )
            )
        }

        sources.qualities?.map { source ->
            callback.invoke(
                ExtractorLink(
                    "NetMovies",
                    "NetMovies",
                    source.url ?: return@map null,
                    "",
                    source.quality?.toIntOrNull() ?: Qualities.Unknown.value,
                    isM3u8 = true,
                )
            )
        }

    }

    suspend fun invokeSoraStream(
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
        val searchUrl = base64DecodeAPI("b20=LmM=b2s=a2w=bG8=Ly8=czo=dHA=aHQ=")
        val doc = app.get(
            "$searchUrl/search?keyword=$title",
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
            scriptData.firstOrNull()
        } else {
            scriptData.find {
                when (season) {
                    null -> {
                        it.first.equals(
                            title,
                            true
                        ) && it.second == year
                    }
                    1 -> {
                        it.first.contains(
                            "$title",
                            true
                        ) && (it.second == year || it.first.contains("Season $season", true))
                    }
                    else -> {
                        it.first.contains(Regex("(?i)$title\\s?($season|${season.toRomanNumeral()}|Season\\s$season)")) && it.second == year
                    }
                }
            }
        }

        val id = script?.third?.last() ?: return
        val type = script.third?.get(2) ?: return

        val jsonResponse = app.get(
            "$vipAPI/movieDrama/get?id=${id}&category=${type}",
            headers = headers
        ).parsedSafe<Load>()?.data
            ?: return invokeNetMovies(id, type, episode, subtitleCallback, callback)

        val json = jsonResponse.episodeVo?.find {
            it.seriesNo == (episode ?: 0)
        }

        json?.subtitlingList?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    getVipLanguage(sub.languageAbbr ?: return@map),
                    sub.subtitlingUrl ?: return@map
                )
            )
        }

        json?.definitionList?.apmap { video ->
            val body =
                """[{"category":$type,"contentId":"$id","episodeId":${json.id},"definition":"${video.code}"}]""".toRequestBody(
                    RequestBodyTypes.JSON.toMediaTypeOrNull()
                )
            val response = app.post(
                "${vipAPI}/media/bathGetplayInfo",
                requestBody = body,
                headers = headers,
            ).text.let { tryParseJson<PreviewResponse>(it)?.data?.firstOrNull() }
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    response?.mediaUrl ?: return@apmap null,
                    "",
                    getSoraQuality(response.currentDefinition ?: ""),
                    isM3u8 = true,
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
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title?.replace("–", "-")
        val id = app.get("$consumetFlixhqAPI/$title")
            .parsedSafe<ConsumetSearchResponse>()?.results?.find {
                if (season == null) {
                    it.title?.equals(
                        "$fixTitle", true
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
            "vidcloud", "upcloud"
        ).apmap { server ->
            val sources = app.get(
                "$consumetFlixhqAPI/watch?episodeId=$episodeId&mediaId=$id&server=$server",
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
            }
        )
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
                    "AnimeKaizoku [${episodeData.third}]",
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
        val url = if (season == null) {
            "$uhdmoviesAPI/download-${title.createSlug()}-$year"
        } else {
            val url = "$uhdmoviesAPI/?s=$title"
            var doc = app.get(url).document
            if (doc.select("title").text() == "Just a moment...") {
                doc = app.get(url, interceptor = CloudflareKiller()).document
            }
            val scriptData = doc.select("div.row.gridlove-posts article").map {
                it.selectFirst("a")?.attr("href") to it.selectFirst("h1")?.text()
            }
            (if (scriptData.size == 1) {
                scriptData.first()
            } else {
                scriptData.find { it.second?.filterMedia(title, year, lastSeason) == true }
            })?.first
        }

        val detailDoc = app.get(url ?: return).document

        val iframeList = detailDoc.select("div.entry-content p").map { it }
            .filter { it.text().filterIframe(season, lastSeason, year) }.mapNotNull {
                if (season == null) {
                    it.text() to it.nextElementSibling()?.select("a")?.attr("href")
                } else {
                    it.text() to it.nextElementSibling()?.select("a:contains(Episode $episode)")
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
            delay(2000)
            val driveLink = bypassHrefli(link ?: return@apmap null)
            val base = getBaseUrl(driveLink ?: return@apmap null)
            val resDoc = app.get(driveLink).text.substringAfter("replace(\"")
                .substringBefore("\")").let {
                    app.get(fixUrl(it, base)).document
                }
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
                    "UHDMovies $tags $size",
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
            it.first.contains("gdtot") && (it.second.contains(
                "1080p", true
            ) || it.second.contains("4k", true))
        } ?: return

        iframe.apmap { (iframeLink, title) ->
            val size = Regex("(?i)\\s(\\S+gb|mb)").find(title)?.groupValues?.getOrNull(1)
            val gdBotLink = extractGdbot(iframeLink)
            val videoLink = extractGdflix(gdBotLink ?: return@apmap null)

            callback.invoke(
                ExtractorLink(
                    "GMovies [$size]",
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
            (it.quality.contains("1080p", true) || it.quality.contains(
                "4k", true
            )) && (it.type.contains("gdtot") || it.type.contains("oiya"))
        }
        iframe.apmap { (link, quality, size, type) ->
            val qualities = getFDoviesQuality(quality)
            val fdLink = bypassFdAds(link)
            val videoLink = when {
                type.contains("gdtot") -> {
                    val gdBotLink = extractGdbot(fdLink ?: return@apmap null)
                    extractGdflix(gdBotLink ?: return@apmap null)
                }
                type.contains("oiya") -> {
                    extractOiya(fdLink ?: return@apmap null, qualities)
                }
                else -> {
                    return@apmap null
                }
            }

            callback.invoke(
                ExtractorLink(
                    "FDMovies [$size]",
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
                "TVMovies [${videoData?.second}]",
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
        val detail = app.get("$consumetCrunchyrollAPI/info?id=$id&mediaType=series").text
        val epsId = tryParseJson<CrunchyrollDetails>(detail)?.findCrunchyrollId(
            title,
            season,
            episode,
            epsTitle
        ) ?: return

        epsId.apmap {
            val json =
                app.get("$consumetCrunchyrollAPI/watch?episodeId=${it?.first ?: return@apmap null}")
                    .parsedSafe<ConsumetSourcesResponse>()

            json?.sources?.map source@{ source ->
                callback.invoke(
                    ExtractorLink(
                        "Crunchyroll [${it.second ?: ""}]",
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
                        sub.lang ?: "",
                        sub.url ?: return@subtitle null
                    )
                )
            }
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

        media?.filter { it.startsWith("https://drive.google.com") }?.apmap {
            val index = media.indexOf(it)
            val size = media[index.minus(1)]
            val quality = media[index.minus(2)]
            val qualityName = media[index.minus(3)]
            val gdriveLink = getDirectGdrive(it)

            val doc = app.get(gdriveLink).document
            val form = doc.select("form#download-form").attr("action")
            val uc = doc.select("input#uc-download-link").attr("value")
            val link = app.post(
                form, data = mapOf(
                    "uc-download-link" to uc
                )
            ).url

            callback.invoke(
                ExtractorLink(
                    "Moviesbay $qualityName [$size]",
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
                    "$api $qualityName",
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
            "$rStreamAPI/Movies/$id/$id.mp4"
        } else {
            "$rStreamAPI/Shows/$id/$season/$episode.mp4"
        }
        val referer = "https://remotestre.am/"

        if (!app.get(url, referer = referer).isSuccessful) return

        delay(4000)
        callback.invoke(
            ExtractorLink(
                "RStream",
                "RStream",
                url,
                referer,
                Qualities.P720.value
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
                    ) == true || it.s?.contains("$fixTitle-season-$season", true) == true
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
            "$smashyStreamAPI/gtop/tv.php?imdb=$imdbId"
        } else {
            "$smashyStreamAPI/gtop/tv.php?imdb=$imdbId&s=$season&e=$episode"
        }

        val doc = app.get(url).document
        val script = doc.selectFirst("script:containsData(var secret)")?.data() ?: return
        val secret =
            script.substringAfter("secret = \"").substringBefore("\";").let { base64Decode(it) }
        val key = script.substringAfter("token = \"").substringBefore("\";")
        val source = app.get(
            "$secret$key",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).parsedSafe<Smashy1Source>() ?: return

        val videoUrl = base64Decode(source.file ?: return)
        val quality = Regex("(\\d{3,4})[Pp]").find(videoUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.P720.value
        callback.invoke(
            ExtractorLink(
                "SmashyStream",
                "SmashyStream",
                videoUrl,
                "",
                quality,
                videoUrl.contains(".m3u8")
            )
        )

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

        val subUrl = if (season == null) {
            "$watchSomuchAPI/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part="
        } else {
            "$watchSomuchAPI/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part=S0${season}E0${episode}"
        }

        app.get(subUrl)
            .parsedSafe<WatchsomuchSubResponses>()?.subtitles
            ?.filter { it.url?.startsWith("https") == true }
            ?.map { sub ->
                Log.i("hexated", "${sub.label} => ${sub.url}")
                subtitleCallback.invoke(
                    SubtitleFile(
                        sub.label ?: "",
                        sub.url ?: return@map null
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
        val key = base64DecodeAPI("ZW0=c3Q=c3k=b28=YWQ=Ymg=")
        val headers = mapOf(
            "Referer" to "$baymovies/",
            "Origin" to baymovies,
            "cf_cache_token" to "UKsVpQqBMxB56gBfhYKbfCVkRIXMh42pk6G4DdkXXoVh7j4BjV"
        )
        val query = getIndexQuery(title, year, season, episode)
        val search = app.get(
            "$baymoviesAPI/0:search?q=$query&page_token=&page_index=0",
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
            if (!app.get(link, referer = "$baymovies/").isSuccessful) return@apmap null
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
                    "Baymovies $tags [$sizeFile]",
                    "Baymovies $tags [$sizeFile]",
                    link,
                    "$baymovies/",
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
        invokeChillmovies(
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
        invokeChillmovies(
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
        invokeChillmovies(
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
        invokeChillmovies(
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
        invokeChillmovies(
            apiUrl,
            api,
            title,
            year,
            season,
            episode,
            callback,
        )
    }

    private suspend fun invokeChillmovies(
        apiUrl: String,
        api: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val encodedIndex = arrayOf(
            "Gammovies",
            "JSMovies",
            "Blackmovies"
        )

        val query = getIndexQuery(title, year, season, episode)
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
            decodeIndexJson(app.post("${apiUrl}search", data = data).text)
        } else {
            app.post("${apiUrl}search", requestBody = body).text
        }
        val media = searchIndex(title, season, episode, year, search) ?: return
        media.apmap { file ->
            val pathBody = """{"id":"${file.id ?: return@apmap null}"}""".toRequestBody(
                RequestBodyTypes.JSON.toMediaTypeOrNull()
            )
            val pathData = mapOf(
                "id" to file.id,
            )
            val path = (if (api in encodedIndex) {
                app.post(
                    "${apiUrl}id2path", data = pathData
                )
            } else {
                app.post("${apiUrl}id2path", requestBody = pathBody)
            }).text.let {
                fixUrl(it, apiUrl)
            }.encodeUrl()
            if (!app.get(path).isSuccessful) return@apmap null
            val size = file.size?.toDouble() ?: return@apmap null
            val sizeFile = "%.2f GB".format(bytesToGigaBytes(size))
            val quality =
                Regex("(\\d{3,4})[pP]").find(
                    file.name ?: return@apmap null
                )?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Qualities.P1080.value

            val tags = Regex("\\d{3,4}[pP]\\.?(.*?)\\.(mkv|mp4|avi)").find(
                file.name
            )?.groupValues?.getOrNull(1)?.replace(".", " ")?.trim()
                ?: ""

            callback.invoke(
                ExtractorLink(
                    "$api $tags [$sizeFile]",
                    "$api $tags [$sizeFile]",
                    path,
                    "",
                    quality,
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

data class Track(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null,
)

data class RabbitSources(
    @JsonProperty("sources") val sources: String? = null,
    @JsonProperty("tracks") val tracks: ArrayList<Track>? = arrayListOf(),
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

data class NetMoviesSubtitles(
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("url") val url: String? = null,
)

data class NetMoviesQualities(
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("url") val url: String? = null,
)

data class NetMoviesData(
    @JsonProperty("subtitles") val subtitles: ArrayList<NetMoviesSubtitles>? = arrayListOf(),
    @JsonProperty("qualities") val qualities: ArrayList<NetMoviesQualities>? = arrayListOf(),
)

data class NetMoviesSources(
    @JsonProperty("data") val data: NetMoviesData? = null,
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

data class PreviewResponse(
    @JsonProperty("data") val data: ArrayList<PreviewVideos>? = arrayListOf(),
)

data class PreviewVideos(
    @JsonProperty("mediaUrl") val mediaUrl: String? = null,
    @JsonProperty("currentDefinition") val currentDefinition: String? = null,
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