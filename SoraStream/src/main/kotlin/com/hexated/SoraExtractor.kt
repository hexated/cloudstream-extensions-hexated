package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.Session
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.GMPlayer
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.extractors.helper.GogoHelper
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

val session = Session(Requests().baseClient)

object SoraExtractor : SoraStream() {

    suspend fun invokeGoku(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        lastSeason: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest"
        )

        fun Document.getServers(): List<String> {
            return this.select("a").map { it.attr("data-id") }
        }

        val media = app.get(
            "$gokuAPI/ajax/movie/search?keyword=$title", headers = headers
        ).document.select("div.item").find { ele ->
            val url = ele.selectFirst("a.movie-link")?.attr("href")
            val titleMedia = ele.select("h3.movie-name").text()
            val titleSlug = title.createSlug()
            val yearMedia = ele.select("div.info-split > div:first-child").text().toIntOrNull()
            val lastSeasonMedia =
                ele.select("div.info-split > div:nth-child(2)").text().substringAfter("SS")
                    .substringBefore("/").trim().toIntOrNull()
            (titleMedia.equals(title, true) || titleMedia.createSlug()
                .equals(titleSlug) || url?.contains("$titleSlug-") == true) &&
                    (if (season == null) {
                        yearMedia == year && url?.contains("/movie/") == true
                    } else {
                        lastSeasonMedia == lastSeason && url?.contains("/series/") == true
                    })
        } ?: return

        val serversId = if (season == null) {
            val movieId = app.get(
                fixUrl(
                    media.selectFirst("a")?.attr("href") ?: return,
                    gokuAPI
                )
            ).url.substringAfterLast("/")
            app.get(
                "$gokuAPI/ajax/movie/episode/servers/$movieId",
                headers = headers
            ).document.getServers()
        } else {
            val seasonId = app.get(
                "$gokuAPI/ajax/movie/seasons/${
                    media.selectFirst("a.btn-wl")?.attr("data-id") ?: return
                }", headers = headers
            ).document.select("a.ss-item").find { it.ownText().equals("Season $season", true) }
                ?.attr("data-id")
            val episodeId =
                app.get(
                    "$gokuAPI/ajax/movie/season/episodes/${seasonId ?: return}",
                    headers = headers
                ).document.select("div.item").find {
                    it.selectFirst("strong")?.text().equals("Eps $episode:", true)
                }?.selectFirst("a")?.attr("data-id")

            app.get(
                "$gokuAPI/ajax/movie/episode/servers/${episodeId ?: return}",
                headers = headers
            ).document.getServers()
        }

        serversId.apmap { id ->
            val iframe =
                app.get("$gokuAPI/ajax/movie/episode/server/sources/$id", headers = headers)
                    .parsedSafe<GokuServer>()?.data?.link ?: return@apmap
            loadCustomExtractor(
                if (iframe.contains("rabbitstream")) "Vidcloud" else "Upcloud",
                iframe,
                "$gokuAPI/",
                subtitleCallback,
                callback,
            )
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

    suspend fun invokeHDMovieBox(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = "$hdMovieBoxAPI/watch/$fixTitle"
        val doc = app.get(url).document
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
        val iframe = app.get(iframeUrl, referer = url).document.selectFirst("iframe")
            ?.attr("src").let { httpsify(it ?: return) }

        if (iframe.startsWith("https://vidmoly.to")) {
            loadExtractor(iframe, "$hdMovieBoxAPI/", subtitleCallback) { video ->
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
        } else {
            val base = getBaseUrl(iframe)
            val script = app.get(
                httpsify(iframe), referer = "$hdMovieBoxAPI/"
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


    }

    suspend fun invokeDreamfilm(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = if (season == null) {
            "$dreamfilmAPI/$fixTitle"
        } else {
            "$dreamfilmAPI/series/$fixTitle/season-$season/episode-$episode"
        }

        val doc = app.get(url).document
        doc.select("div#videosen a").apmap {
            val iframe = app.get(it.attr("href")).document.selectFirst("div.card-video iframe")
                ?.attr("data-src")
            loadCustomExtractor(
                null,
                iframe ?: return@apmap,
                "$dreamfilmAPI/",
                subtitleCallback,
                callback,
                Qualities.P1080.value
            )
        }
    }

    suspend fun invokeSeries9(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val doc = if (season == null) {
            val res = app.get("$series9API/film/$fixTitle/watching.html")
            if (!res.isSuccessful) app.get("$series9API/film/$fixTitle-$year/watching.html").document else res.document
        } else {
            app.get("$series9API/film/$fixTitle-season-$season/watching.html").document
        }

        val server = doc.select("div#list-eps div.le-server").map { ele ->
            if (season == null) {
                ele.select("a").attr("player-data")
            } else {
                ele.select("a[episode-data=$episode]").attr("player-data")
            }
        }.find { it.contains(Regex("movembed|membed")) }

        val iframe = app.get(httpsify(server ?: return))
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
            val iv = "9225679083961858"
            val secretKey = "25742532592138496744665879883281"
            GogoHelper.extractVidstream(
                iframe.url,
                "Vidstream",
                callback,
                iv,
                secretKey,
                secretKey,
                isUsingAdaptiveKeys = false,
                isUsingAdaptiveData = true,
                iframeDocument = iframeDoc
            )
        })
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
        invokeWpmovies(url, subtitleCallback, callback)
    }

    suspend fun invokeMultimovies(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = if (season == null) {
            "$multimoviesAPI/movies/$fixTitle"
        } else {
            "$multimoviesAPI/episodes/$fixTitle-${season}x${episode}"
        }
        invokeWpmovies(url, subtitleCallback, callback, true)
    }

    suspend fun invokeNetmovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = if (season == null) {
            "$netmoviesAPI/movies/$fixTitle-$year"
        } else {
            "$netmoviesAPI/episodes/$fixTitle-${season}x${episode}"
        }
        invokeWpmovies(url, subtitleCallback, callback)
    }

    private suspend fun invokeWpmovies(
        url: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        fixIframe: Boolean = false,
    ) {
        val res = session.get(url ?: return)
        val referer = getBaseUrl(res.url)
        val document = res.document
        document.select("ul#playeroptionsul > li").map {
            Triple(
                it.attr("data-post"),
                it.attr("data-nume"),
                it.attr("data-type")
            )
        }.apmap { (id, nume, type) ->
            val json = session.post(
                url = "$referer/wp-admin/admin-ajax.php", data = mapOf(
                    "action" to "doo_player_ajax", "post" to id, "nume" to nume, "type" to type
                ), headers = mapOf("X-Requested-With" to "XMLHttpRequest"), referer = url
            )
            val source = tryParseJson<ResponseHash>(json.text)?.embed_url?.let {
                if (fixIframe) Jsoup.parse(it).select("IFRAME").attr("SRC") else it
            } ?: return@apmap
            if (!source.contains("youtube")) {
                loadExtractor(source, "$referer/", subtitleCallback, callback)
            }
        }
    }

    suspend fun invokeDoomovies(
        title: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get("$doomoviesAPI/movies/${title.createSlug()}/")
        val host = getBaseUrl(res.url)
        val document = res.document
        document.select("ul#playeroptionsul > li")
            .filter { element -> element.select("span.flag img").attr("src").contains("/en.") }
            .map {
                Triple(
                    it.attr("data-post"),
                    it.attr("data-nume"),
                    it.attr("data-type")
                )
            }.apmap { (id, nume, type) ->
                val source = app.get(
                    "$host/wp-json/dooplayer/v2/${id}/${type}/${nume}",
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    referer = "$host/"
                ).parsed<ResponseHash>().embed_url
                if (!source.contains("youtube")) {
                    loadExtractor(source, "$host/", subtitleCallback, callback)
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
        val filmxyCookies = getFilmxyCookies(imdbId, season)
        val doc = session.get(url, cookies = filmxyCookies).document
        val script = doc.selectFirst("script:containsData(var isSingle)")?.data() ?: return

        val sourcesData =
            Regex("listSE\\s*=\\s?(.*?),[\\n|\\s]").find(script)?.groupValues?.get(1).let {
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

        val sources = if (season == null) {
            sourcesData?.get("movie")?.get("movie")
        } else {
            sourcesData?.get("s$seasonSlug")?.get("e$episodeSlug")
        }
        val subSources = if (season == null) {
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

        val json = app.post(
            "$filmxyAPI/wp-admin/admin-ajax.php",
            requestBody = "action=get_vid_links$linkIDs&user_id=$userId&nonce=$userNonce".toRequestBody(),
            referer = url,
            headers = mapOf(
                "Accept" to "*/*",
                "DNT" to "1",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Origin" to filmxyAPI,
                "X-Requested-With" to "XMLHttpRequest",
            ),
            cookies = filmxyCookies
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
        val servers =
            app.get(fixUrl(iframe, kimcartoonAPI)).document.select("#selectServer > option")
                .map { fixUrl(it.attr("value"), kimcartoonAPI) }

        servers.apmap {
            app.get(it).document.select("#my_video_1").attr("src").let { iframe ->
                if (iframe.isNotEmpty()) {
                    loadExtractor(iframe, "$kimcartoonAPI/", subtitleCallback, callback)
                }
            }
        }
    }

    suspend fun invokeDumpStream(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val (id, type) = getDumpIdAndType(title, year, season)
        val json = fetchDumpEpisodes("$id", "$type", episode) ?: return

        json.subtitlingList?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    getVipLanguage(sub.languageAbbr ?: return@map),
                    sub.subtitlingUrl ?: return@map
                )
            )
        }
    }

    suspend fun invokeFmovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val query = title?.replace(Regex("[^\\w-\\s]"), "")
        val html =
            app.get("$fmoviesAPI/ajax/film/search?keyword=$query")
                .parsedSafe<FmoviesResponses>()?.result?.html

        val mediaId = Jsoup.parse(html ?: return).select("a.item").map {
            Triple(
                it.attr("href"),
                it.select("div.name").text(),
                it.select("span.dot")[1].text(),
            )
        }.find {
            if (season == null) {
                it.first.contains("/movie/")
            } else {
                it.first.contains("/tv/")
            } && (it.second.equals(title, true) || it.second.createSlug()
                .equals(title.createSlug())) && it.third.toInt() == year
        }?.first

        val watchId =
            app.get(fixUrl(mediaId ?: return, fmoviesAPI)).document.selectFirst("div.watch")
                ?.attr("data-id")

        val episodeId = app.get(
            "$fmoviesAPI/ajax/episode/list/${watchId ?: return}?vrf=${
                comsumetEncodeVrf(watchId)
            }"
        ).parsedSafe<FmoviesResult>()?.result?.let { Jsoup.parse(it) }
            ?.selectFirst("ul[data-season=${season ?: 1}] li a[data-num=${episode ?: 1}]")
            ?.attr("data-id")

        val servers =
            app.get(
                "$fmoviesAPI/ajax/server/list/${episodeId ?: return}?vrf=${
                    comsumetEncodeVrf(
                        episodeId
                    )
                }"
            )
                .parsedSafe<FmoviesResult>()?.result?.let { Jsoup.parse(it) }
                ?.select("ul li")?.map { it.attr("data-id") to it.attr("data-link-id") }

        servers?.filter {
            it.first == "41" || it.first == "45"
        }?.apmap { (serverid, linkId) ->
            delay(2000)
            val decryptServer =
                app.get("$fmoviesAPI/ajax/server/$linkId?vrf=${comsumetEncodeVrf(linkId)}")
                    .parsedSafe<FmoviesResponses>()?.result?.url?.let { comsumetDecodeVrf(it) }
            if (serverid == "41") {
                invokeVizcloud(serverid, decryptServer ?: return@apmap, subtitleCallback, callback)
            } else {
                loadExtractor(decryptServer ?: return@apmap, fmoviesAPI, subtitleCallback, callback)
            }
        }
    }

    suspend fun invokeKisskh(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        isAnime: Boolean = false,
        lastSeason: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val slug = title.createSlug() ?: return
        val type = when {
            isAnime -> "3"
            season == null -> "2"
            else -> "1"
        }
        val res = app.get(
            "$kissKhAPI/api/DramaList/Search?q=$title&type=$type", referer = "$kissKhAPI/"
        ).text.let {
            tryParseJson<ArrayList<KisskhResults>>(it)
        } ?: return

        val (id, contentTitle) = if (res.size == 1) {
            res.first().id to res.first().title
        } else {
            val data = res.find {
                val slugTitle = it.title.createSlug()
                when {
                    season == null -> slugTitle?.equals(slug) == true
                    lastSeason == 1 -> slugTitle?.contains(slug) == true
                    else -> slugTitle?.contains(slug) == true && it.title?.contains(
                        "Season $season",
                        true
                    ) == true
                }
            }
            data?.id to data?.title
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
        title: String? = null,
        epsTitle: String? = null,
        date: String?,
        airedDate: String?,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val (aniId, malId) = convertTmdbToAnimeId(
            title,
            date,
            airedDate,
            if (season == null) TvType.AnimeMovie else TvType.Anime
        )

        argamap(
            {
                invokeAniwatch(malId, episode, subtitleCallback, callback)
            },
            {
                invokeBiliBili(aniId, episode, subtitleCallback, callback)
            },
            {
                if (season != null) invokeCrunchyroll(
                    aniId,
                    malId,
                    epsTitle,
                    season,
                    episode,
                    subtitleCallback,
                    callback
                )
            }
        )
    }

    private suspend fun invokeBiliBili(
        aniId: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(
            "$biliBiliAPI/anime/episodes?id=${aniId ?: return}&source_id=bilibili",
            referer = otakuzBaseUrl
        )
            .parsedSafe<BiliBiliDetails>()?.episodes?.find {
                it.episodeNumber == episode
            } ?: return

        val sources =
            app.get(
                "$biliBiliAPI/source?episode_id=${res.sourceEpisodeId}&source_media_id=${res.sourceMediaId}&source_id=${res.sourceId}",
                referer = otakuzBaseUrl
            )
                .parsedSafe<BiliBiliSourcesResponse>()

        sources?.sources?.apmap { source ->
            val quality =
                app.get(
                    source.file ?: return@apmap null,
                    referer = otakuzBaseUrl
                ).document.selectFirst("Representation")
                    ?.attr("height")
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

    private suspend fun invokeAniwatch(
        malId: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
        )
        val animeId = app.get("$malsyncAPI/mal/anime/${malId ?: return}")
            .parsedSafe<MALSyncResponses>()?.sites?.zoro?.keys?.map { it }
        animeId?.apmap { id ->
            val episodeId = app.get(
                "$aniwatchAPI/ajax/v2/episode/list/${id ?: return@apmap}",
                headers = headers
            )
                .parsedSafe<AniwatchResponses>()?.html?.let {
                    Jsoup.parse(it)
                }?.select("div.ss-list a")?.find { it.attr("data-number") == "${episode ?: 1}" }
                ?.attr("data-id")

            val servers =
                app.get(
                    "$aniwatchAPI/ajax/v2/episode/servers?episodeId=${episodeId ?: return@apmap}",
                    headers = headers
                )
                    .parsedSafe<AniwatchResponses>()?.html?.let { Jsoup.parse(it) }
                    ?.select("div.item.server-item")?.map {
                        Triple(
                            it.text(),
                            it.attr("data-id"),
                            it.attr("data-type"),
                        )
                    }

            servers?.apmap servers@{ server ->
                val iframe = app.get(
                    "$aniwatchAPI/ajax/v2/episode/sources?id=${server.second ?: return@servers}",
                    headers = headers
                )
                    .parsedSafe<AniwatchResponses>()?.link ?: return@servers
                val audio = if (server.third == "sub") "Raw" else "English Dub"
                loadCustomExtractor(
                    "${server.first} [$audio]",
                    iframe,
                    "$aniwatchAPI/",
                    subtitleCallback,
                    callback,
                )
            }
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
                        ?.select("a")?.find { child ->
                            child.select("span").text().equals("Episode $episode", true)
                        }
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
            val driveLink =
                if (link?.contains("driveleech") == true) bypassDriveleech(link) else bypassTechmny(
                    link ?: return@apmap
                )
            val base = getBaseUrl(driveLink ?: return@apmap)
            val resDoc = app.get(driveLink).document
            val bitLink = resDoc.selectFirst("a.btn.btn-outline-success")?.attr("href")
            val downloadLink = if (bitLink.isNullOrEmpty()) {
                val backupIframe = resDoc.select("a.btn.btn-outline-warning").attr("href")
                extractBackupUHD(backupIframe ?: return@apmap)
            } else {
                extractMirrorUHD(bitLink, base)
            }

            val tags = getUhdTags(quality)
            val qualities = getIndexQuality(quality)
            val size = getIndexSize(quality)
            callback.invoke(
                ExtractorLink(
                    "UHDMovies",
                    "UHDMovies $tags [$size]",
                    downloadLink ?: return@apmap,
                    "",
                    qualities
                )
            )

        }


    }

    suspend fun invokePobmovies(
        title: String? = null,
        year: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val detailDoc = app.get("$pobmoviesAPI/${title.createSlug()}-$year").document
        val iframeList = detailDoc.select("div.entry-content p").map { it }
            .filter { it.text().filterIframe(year = year, title = title) }.mapNotNull {
                it.text() to it.nextElementSibling()?.select("a")?.attr("href")
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

        sources.apmap { (name, link) ->
            if (link.isNullOrEmpty()) return@apmap
            val videoLink = when {
                link.contains("gdtot") -> {
                    val gdBotLink = extractGdbot(link)
                    extractGdflix(gdBotLink ?: return@apmap)
                }

                link.contains("gdflix") -> {
                    extractGdflix(link)
                }

                else -> {
                    return@apmap
                }
            }

            val tags = getUhdTags(name)
            val qualities = getIndexQuality(name)
            val size = getIndexSize(name)
            callback.invoke(
                ExtractorLink(
                    "Pobmovies",
                    "Pobmovies $tags [${size}]",
                    videoLink ?: return@apmap,
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
        val ref = "https://videoapi.tv/"
        val files = app.get(
            "$fwatayakoAPI/IAF0wWTdNYZm?imdb_id=$imdbId",
            referer = ref
        ).document.selectFirst("input#files")?.attr("value") ?: return
        val data = files.let {
            if (season == null) {
                it.replace("\"381\"", "\"movie\"").replace("\"30\"", "\"movie_dl\"")
            } else {
                it.replace("\"381\"", "\"tv\"").replace("\"30\"", "\"tv_dl\"")
            }
        }.let { tryParseJson<SourcesFwatayako>(it) } ?: return

        val sourcesLink = if (season == null) {
            data.sourcesMovie
        } else {
            data.sourcesTv?.find { it.id == season }?.folder?.find { it.id == "${season}_${episode}" }?.file
        }

        val downoadLink = if (season == null) {
            data.movie_dl
        } else {
            data.tv_dl?.find { it.id == season }?.folder?.find { it.id == "${season}_${episode}" }?.download
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
                    ref,
                    quality ?: Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        }

        downoadLink?.mapKeys {
            callback.invoke(
                ExtractorLink(
                    "Fwatayako",
                    "Fwatayako",
                    httpsify(it.value),
                    ref,
                    getQualityFromName(it.key),
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
                type.contains("gdtot") -> {
                    val gdBotLink = extractGdbot(fdLink ?: return@apmap null)
                    extractGdflix(gdBotLink ?: return@apmap null)
                }

                type.contains("oiya") -> {
                    val oiyaLink = extractOiya(fdLink ?: return@apmap null, qualities)
                    if (oiyaLink?.contains("gdtot") == true) {
                        val gdBotLink = extractGdbot(oiyaLink)
                        extractGdflix(gdBotLink ?: return@apmap null)
                    } else {
                        oiyaLink
                    }
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
            doc.select("div.le-server span").map { it.attr("data") }
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
            requestEmbed.document.select("div.le-server span").map { it.attr("data") }
        }

        m4uData.apmap { data ->
            val iframe = app.post(
                "$m4uhdAPI/ajax",
                data = mapOf(
                    "m4u" to data, "_token" to "$token"
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

    private suspend fun invokeCrunchyroll(
        aniId: Int? = null,
        malId: Int? = null,
        epsTitle: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = getCrunchyrollId("${aniId ?: return}")
            ?: getCrunchyrollIdFromMalSync("${malId ?: return}")
        val audioLocal = listOf(
            "ja-JP",
            "en-US",
            "zh-CN",
        )
        val headers = getCrunchyrollToken()
        val seasonIdData = app.get(
            "$crunchyrollAPI/content/v2/cms/series/${id ?: return}/seasons",
            headers = headers
        )
            .parsedSafe<CrunchyrollResponses>()?.data?.let { s ->
                if (s.size == 1) {
                    s.firstOrNull()
                } else {
                    s.find {
                        when (epsTitle) {
                            "One Piece" -> it.season_number == 13
                            "Hunter x Hunter" -> it.season_number == 5
                            else -> it.season_number == season
                        }
                    } ?: s.find { it.season_number?.plus(1) == season }
                }
            }
        val seasonId = seasonIdData?.versions?.filter { it.audio_locale in audioLocal }
            ?.map { it.guid to it.audio_locale } ?: listOf(seasonIdData?.id to "ja-JP")

        seasonId.apmap { (sId, audioL) ->
            val streamsLink =
                app.get(
                    "$crunchyrollAPI/content/v2/cms/seasons/${sId ?: return@apmap}/episodes",
                    headers = headers
                ).parsedSafe<CrunchyrollResponses>()?.data?.find {
                    it.title.equals(epsTitle, true) || it.slug_title.equals(
                        epsTitle.createSlug(),
                        true
                    ) || it.episode_number == episode
                }?.streams_link
            val sources =
                app.get(fixUrl(streamsLink ?: return@apmap, crunchyrollAPI), headers = headers)
                    .parsedSafe<CrunchyrollSourcesResponses>()

            listOf(
                "adaptive_hls",
                "vo_adaptive_hls"
            ).map { hls ->
                val name = if (hls == "adaptive_hls") "Crunchyroll" else "Vrv"
                val audio = if (audioL == "en-US") "English Dub" else "Raw"
                val source = sources?.data?.firstOrNull()?.let {
                    if (hls == "adaptive_hls") it.adaptive_hls else it.vo_adaptive_hls
                }
                M3u8Helper.generateM3u8(
                    "$name [$audio]",
                    source?.get("")?.get("url") ?: return@map,
                    "https://static.crunchyroll.com/"
                ).forEach(callback)
            }

            sources?.meta?.subtitles?.map { sub ->
                subtitleCallback.invoke(
                    SubtitleFile(
                        "${fixCrunchyrollLang(sub.key) ?: sub.key} [ass]",
                        sub.value["url"] ?: return@map null
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

        media?.filter { it.startsWith("https://drive.google.com") || it.startsWith("https://cdn.moviesbay.live") }
            ?.apmap {
                val index = media.indexOf(it)
                val size = media[index.minus(1)]
                val quality = media[index.minus(2)]
                val qualityName = media[index.minus(3)]
                val link = if (it.startsWith("https://drive.google.com")) {
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
        if (!app.get(link, referer = rStreamAPI).isSuccessful) return

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
                referer = "$flixonAPI/"
            ).document.selectFirst("script:containsData(JuicyCodes.Run)")
                ?.data()
                ?.substringAfter("JuicyCodes.Run(")?.substringBefore(");")?.split("+")
                ?.joinToString("") { it.replace("\"", "").trim() }
                ?.let { getAndUnpack(base64Decode(it)) }

        val link = Regex("[\"']file[\"']:[\"'](.+?)[\"'],").find(
            unPacker ?: return
        )?.groupValues?.getOrNull(1)

        callback.invoke(
            ExtractorLink(
                "Flixon",
                "Flixon",
                link ?: return,
                "https://onionplay.stream/",
                Qualities.P720.value,
                link.contains(".m3u8")
            )
        )

    }

    suspend fun invokeSmashyStream(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        isAnime: Boolean = false,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = if (season == null) {
            "$smashyStreamAPI/playere.php?imdb=$imdbId"
        } else {
            "$smashyStreamAPI/playere.php?imdb=$imdbId&season=$season&episode=$episode"
        }

        app.get(
            url,
            referer = "https://smashystream.com/"
        ).document.select("div#_default-servers a.server").map {
            it.attr("data-id") to it.text()
        }.apmap {
            when {
                (it.second.equals("Player F", true) || it.second.equals(
                    "Player N",
                    true
                )) && !isAnime -> {
                    invokeSmashyFfix(it.second, it.first, url, callback)
                }

                it.first.contains("/gtop") -> {
                    invokeSmashyGtop(it.second, it.first, callback)
                }

                it.first.contains("/dude_tv") -> {
                    invokeSmashyDude(it.second, it.first, callback)
                }

                it.first.contains("/rip") -> {
                    invokeSmashyRip(it.second, it.first, subtitleCallback, callback)
                }

                it.first.contains("/im.php") && !isAnime -> {
                    invokeSmashyIm(it.second, it.first, subtitleCallback, callback)
                }

                it.first.contains("/rw.php") && !isAnime -> {
                    invokeSmashyRw(it.second, it.first, subtitleCallback, callback)
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
            app.post("${apiUrl}search", requestBody = body, referer = apiUrl).text
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
                if (api in ddomainIndex) {
                    val worker = app.get(
                        "${fixUrl(path, apiUrl).encodeUrl()}?a=view",
                        referer = if (api in needRefererIndex) apiUrl else ""
                    ).document.selectFirst("script:containsData(downloaddomain)")?.data()
                        ?.substringAfter("\"downloaddomain\":\"")?.substringBefore("\",")?.let {
                            "$it/0:"
                        }
                    fixUrl(path, worker ?: return@apmap null)
                } else {
                    fixUrl(path, apiUrl)
                }
            }.encodeUrl()

            val size =
                "%.2f GB".format(bytesToGigaBytes(file.size?.toDouble() ?: return@apmap null))
            val quality = getIndexQuality(file.name)
            val tags = getIndexQualityTags(file.name)

            callback.invoke(
                ExtractorLink(
                    api,
                    "$api $tags [$size]",
                    path,
                    if (api in needRefererIndex) apiUrl else "",
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
        val url = if (season == null) {
            "$dahmerMoviesAPI/movies/${title?.replace(":", "")} ($year)/"
        } else {
            "$dahmerMoviesAPI/tvs/${title?.replace(":", " -")}/Season $season/"
        }

        val request = app.get(url)
        if (!request.isSuccessful) return
        val paths = request.document.select("a").map {
            it.text() to it.attr("href")
        }.filter {
            if (season == null) {
                it.first.contains(Regex("(?i)(1080p|2160p)"))
            } else {
                val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
                it.first.contains(Regex("(?i)S${seasonSlug}E${episodeSlug}"))
            }
        }.ifEmpty { return }

        paths.map {
            val quality = getIndexQuality(it.first)
            val tags = getIndexQualityTags(it.first)
            callback.invoke(
                ExtractorLink(
                    "DahmerMovies",
                    "DahmerMovies $tags",
                    (url + it.second).encodeUrl(),
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
        val slug = getEpisodeSlug(season, episode)
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
            if (el.size == 1) {
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
            ).document.selectFirst("div#g_MXOzFGouZrOAUioXjpddqkZK a:contains(Episode ${slug.second})")
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

        val iframeDoc = app.get(iframe, referer = "$ask4MoviesAPI/").text
        val script =
            Regex("""eval\(function\(p,a,c,k,e,.*\)\)""").findAll(iframeDoc).lastOrNull()?.value
        val unpacked = getAndUnpack(script ?: return)
        val m3u8 = Regex("file:\\s*\"(.*?m3u8.*?)\"").find(unpacked)?.groupValues?.getOrNull(1)
        M3u8Helper.generateM3u8(
            "Ask4movie",
            m3u8 ?: return,
            mainUrl
        ).forEach(callback)

    }

    suspend fun invokeWatchOnline(
        imdbId: String? = null,
        tmdbId: Int? = null,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = imdbId?.removePrefix("tt")
        val slug = title.createSlug()
        val url = if (season == null) {
            "$watchOnlineAPI/movies/view/$id-$slug-$year"
        } else {
            "$watchOnlineAPI/shows/view/$id-$slug-$year"
        }

        var res = app.get(url)
        if (res.code == 403) return
        if (!res.isSuccessful) res = searchWatchOnline(title, season, imdbId, tmdbId) ?: return

        val doc = res.document
        val episodeId = if (season == null) {
            doc.selectFirst("div.movie__buttons-items a")?.attr("data-watch-list-media-id")
        } else {
            doc.select("ul[data-season-episodes=$season] li").find {
                it.select("div.episodes__number").text().equals("Episode $episode", true)
            }?.attr("data-id-episode")
        } ?: return

        val videoUrl = if (season == null) {
            "$watchOnlineAPI/api/v1/security/movie-access?id_movie=$episodeId"
        } else {
            "$watchOnlineAPI/api/v1/security/episode-access?id=$episodeId"
        }

        val json = app.get(videoUrl, referer = url).parsedSafe<WatchOnlineResponse>()

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

        argamap(
            {
                invokeMonster(
                    res.url.substringAfterLast("/"), episodeId, season, callback
                )
            },
            {
                val subtitles = json?.subtitles as ArrayList<HashMap<String, String>>
                subtitles.map { sub ->
                    subtitleCallback.invoke(
                        SubtitleFile(
                            sub["language"] ?: return@map,
                            fixUrl(sub["url"] ?: return@map, watchOnlineAPI)
                        )
                    )
                }
            })

    }

    private suspend fun invokeMonster(
        urlSlug: String? = null,
        episodeId: String? = null,
        season: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val monsterMainUrl = "https://mobirs.monster"
        val playSlug = if (season == null) {
            "movies/play/$urlSlug"
        } else {
            "shows/play/$urlSlug"
        }
        val sid = "9k9iupt5sebbnfajrc6ti3ht7l"
        val sec = "1974bc4a902c4d69fcbab261dcec69094a9b8164"
        val url =
            "$monsterMainUrl/$playSlug?mid=1&sid=$sid&sec=$sec&t=${System.currentTimeMillis()}"
        val res = app.get(url).document
        val script = res.selectFirst("script:containsData(window['show_storage'])")?.data()
        val hash = Regex("hash:\\s*['\"](\\S+)['\"],").find(script ?: return)?.groupValues?.get(1)
        val expires = Regex("expires:\\s*(\\d+),").find(script)?.groupValues?.get(1)

        val videoUrl = if (season == null) {
            "$monsterMainUrl/api/v1/security/movie-access?id_movie=$episodeId&hash=$hash&expires=$expires"
        } else {
            "$monsterMainUrl/api/v1/security/episode-access?id_episode=$episodeId&hash=$hash&expires=$expires"
        }

        app.get(videoUrl, referer = url)
            .parsedSafe<WatchOnlineResponse>()?.streams?.mapKeys { source ->
                callback.invoke(
                    ExtractorLink(
                        "WatchOnline",
                        "WatchOnline",
                        source.value,
                        "$monsterMainUrl/",
                        getQualityFromName(source.key),
                        true
                    )
                )
            }
    }

    suspend fun invokeNinetv(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = if (season == null) {
            "$nineTvAPI/movie/$tmdbId"
        } else {
            "$nineTvAPI/tv/$tmdbId-$season-$episode"
        }

        val iframe = app.get(url, referer = "https://pressplay.top/").document.selectFirst("iframe")
            ?.attr("src") ?: return
        loadExtractor(iframe, "$nineTvAPI/", subtitleCallback, callback)

    }

    suspend fun invokePutlocker(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val query = if (season == null) {
            title
        } else {
            "$title - season $season"
        }

        val res = app.get("$putlockerAPI/movie/search/$query").document
        val scripData = res.select("div.movies-list div.ml-item").map {
            it.selectFirst("h2")?.text() to it.selectFirst("a")?.attr("href")
        }
        val script = if (scripData.size == 1) {
            scripData.first()
        } else {
            scripData.find {
                if (season == null) {
                    it.first.equals(title, true) || (it.first?.contains(
                        "$title", true
                    ) == true && it.first?.contains("$year") == true)
                } else {
                    it.first?.contains("$title", true) == true && it.first?.contains(
                        "Season $season", true
                    ) == true
                }
            }
        }

        val id = fixUrl(script?.second ?: return).split("-").lastOrNull()?.removeSuffix("/")
        val iframe = app.get("$putlockerAPI/ajax/movie_episodes/$id")
            .parsedSafe<PutlockerEpisodes>()?.html?.let { Jsoup.parse(it) }?.let { server ->
                if (season == null) {
                    server.select("div.les-content a").map {
                        it.attr("data-id") to it.attr("data-server")
                    }
                } else {
                    server.select("div.les-content a").map { it }
                        .filter { it.text().contains("Episode $episode", true) }.map {
                            it.attr("data-id") to it.attr("data-server")
                        }
                }
            }

        iframe?.apmap {
            delay(3000)
            val embedUrl = app.get("$putlockerAPI/ajax/movie_embed/${it.first}")
                .parsedSafe<PutlockerEmbed>()?.src ?: return@apmap null
            val sources = extractPutlockerSources(embedUrl)?.parsedSafe<PutlockerResponses>()

            argamap(
                {
                    sources?.callback(embedUrl, "Server ${it.second}", callback)
                },
                {
                    if (!sources?.backupLink.isNullOrBlank()) {
                        extractPutlockerSources(sources?.backupLink)?.parsedSafe<PutlockerResponses>()
                            ?.callback(
                                embedUrl, "Backup ${it.second}", callback
                            )
                    } else {
                        return@argamap
                    }
                },
            )

        }

    }

    suspend fun invokeCryMovies(
        imdbId: String? = null,
        title: String? = null,
        year: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        app.get("${cryMoviesAPI}/stream/movie/$imdbId.json")
            .parsedSafe<CryMoviesResponse>()?.streams?.filter {
                matchingIndex(
                    it.title,
                    null,
                    title,
                    year,
                    null,
                    episode,
                    false
                )
            }?.apmap { stream ->
                val quality = getIndexQuality(stream.title)
                val tags = getIndexQualityTags(stream.title)
                val size = getIndexSize(stream.title)
                val headers = stream.behaviorHints?.proxyHeaders?.request ?: mapOf()

                if (!app.get(
                        stream.url ?: return@apmap,
                        headers = headers
                    ).isSuccessful
                ) return@apmap

                callback.invoke(
                    ExtractorLink(
                        "CryMovies",
                        "CryMovies $tags [${size}]",
                        stream.url,
                        "",
                        quality,
                        headers = headers
                    )
                )
            }

    }

    suspend fun invokeNowTv(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val referer = "https://2now.tv/"
        val slug = getEpisodeSlug(season, episode)
        val url = if (season == null) "$nowTvAPI/$tmdbId.mp4" else "$nowTvAPI/tv/$tmdbId/s${season}e${slug.second}.mp4"
        if (!app.get(url, referer = referer).isSuccessful) return
        callback.invoke(
            ExtractorLink(
                "NowTv",
                "NowTv",
                url,
                referer,
                Qualities.P1080.value,
            )
        )
    }

    suspend fun invokeRidomovies(
        title: String? = null,
        year: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val iframe =
            app.get("$ridomoviesAPI/movies/${title.createSlug()}-watch-online-$year").document.selectFirst(
                "div.player-div iframe"
            )?.attr("data-src")
        val unpacked = getAndUnpack(app.get(iframe ?: return, referer = "$ridomoviesAPI/").text)
        val video = Regex("=\"(aHR.*?)\";").find(unpacked)?.groupValues?.get(1)
        callback.invoke(
            ExtractorLink(
                "Ridomovies",
                "Ridomovies",
                base64Decode(video ?: return),
                "${getBaseUrl(iframe)}/",
                Qualities.P1080.value,
                isM3u8 = true
            )
        )

    }

    suspend fun invokeNavy(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeHindi(navyAPI, navyAPI, imdbId, season, episode, callback)
    }

    suspend fun invokeMoment(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeHindi(momentAPI, "https://hdmovies4u.green", imdbId, season, episode, callback)
    }

    private suspend fun invokeHindi(
        host: String? = null,
        referer: String? = null,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val res = app.get(
            "$host/play/$imdbId",
            referer = "$referer/"
        ).document.selectFirst("script:containsData(player =)")?.data()?.substringAfter("{")
            ?.substringBefore(";")?.substringBefore(")")
        val json = tryParseJson<NavyPlaylist>("{${res ?: return}")
        val headers = mapOf(
            "X-CSRF-TOKEN" to "${json?.key}"
        )

        val serverRes = app.get(
            fixUrl(json?.file ?: return, navyAPI), headers = headers, referer = "$referer/"
        ).text.replace(Regex(""",\s*\[]"""), "")
        val server = tryParseJson<ArrayList<NavyServer>>(serverRes).let { server ->
            if (season == null) {
                server?.find { it.title == "English" }?.file
            } else {
                server?.find { it.id.equals("$season") }?.folder?.find { it.episode.equals("$episode") }?.folder?.find {
                    it.title.equals(
                        "English"
                    )
                }?.file
            }
        }

        val path = app.post(
            "${host}/playlist/${server ?: return}.txt",
            headers = headers,
            referer = "$referer/"
        ).text

        M3u8Helper.generateM3u8(
            if (host == navyAPI) "Navy" else "Moment",
            path,
            "${referer}/"
        ).forEach(callback)

    }

    suspend fun invokeEmovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val slug = title.createSlug()
        val url = if (season == null) {
            "$emoviesAPI/watch-$slug-$year-1080p-hd-online-free/watching.html"
        } else {
            val first = "$emoviesAPI/watch-$slug-season-$season-$year-1080p-hd-online-free.html"
            val second = "$emoviesAPI/watch-$slug-$year-1080p-hd-online-free.html"
            if (app.get(first).isSuccessful) first else second
        }

        val res = app.get(url).document
        val id = (if (season == null) {
            res.selectFirst("select#selectServer option[sv=oserver]")?.attr("value")
        } else {
            res.select("div.le-server a").find {
                val num =
                    Regex("Episode (\\d+)").find(it.text())?.groupValues?.get(1)?.toIntOrNull()
                num == episode
            }?.attr("href")
        })?.substringAfter("id=")?.substringBefore("&")

        val server =
            app.get(
                "$emoviesAPI/ajax/v4_get_sources?s=oserver&id=${id ?: return}&_=${APIHolder.unixTimeMS}",
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).parsedSafe<EMovieServer>()?.value

        val script = app.get(
            server ?: return,
            referer = "$emoviesAPI/"
        ).document.selectFirst("script:containsData(sources:)")?.data() ?: return
        val sources = Regex("sources:\\s*\\[(.*)],").find(script)?.groupValues?.get(1)?.let {
            tryParseJson<List<EMovieSources>>("[$it]")
        }
        val tracks = Regex("tracks:\\s*\\[(.*)],").find(script)?.groupValues?.get(1)?.let {
            tryParseJson<List<EMovieTraks>>("[$it]")
        }

        sources?.map { source ->
            M3u8Helper.generateM3u8(
                "Emovies",
                source.file ?: return@map,
                "https://embed.vodstream.xyz/"
            ).forEach(callback)
        }

        tracks?.map { track ->
            subtitleCallback.invoke(
                SubtitleFile(
                    track.label ?: "",
                    track.file ?: return@map,
                )
            )
        }


    }

    suspend fun invokeFourCartoon(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest"
        )
        val url = if (season == null) {
            "$fourCartoonAPI/movies/$fixTitle-$year"
        } else {
            "$fourCartoonAPI/episode/$fixTitle-season-$season-episode-$episode"
        }

        val document = app.get(url).document
        val id = document.selectFirst("input[name=idpost]")?.attr("value")
        val server = app.get(
            "$fourCartoonAPI/ajax-get-link-stream/?server=streamango&filmId=${id ?: return}",
            headers = headers
        ).text
        val hash =
            getAndUnpack(app.get(server, referer = fourCartoonAPI).text).substringAfter("(\"")
                .substringBefore("\",")
        val iframeUrl = getBaseUrl(server)
        val source = app.post(
            "$iframeUrl/player/index.php?data=$hash&do=getVideo", data = mapOf(
                "hast" to hash,
                "r" to "$fourCartoonAPI/",
            ),
            headers = headers
        ).parsedSafe<FourCartoonSources>()?.videoSource

        callback.invoke(
            ExtractorLink(
                "4Cartoon",
                "4Cartoon",
                source ?: return,
                "$iframeUrl/",
                Qualities.P720.value,
                true,
            )
        )

    }


}

class TravelR : GMPlayer() {
    override val name = "TravelR"
    override val mainUrl = "https://travel-russia.xyz"
}

class Mwish : Filesim() {
    override val name = "Mwish"
    override var mainUrl = "https://mwish.pro"
}

class Animefever : Filesim() {
    override val name = "Animefever"
    override var mainUrl = "https://animefever.fun"
}

class Multimovies : Filesim() {
    override val name = "Multimovies"
    override var mainUrl = "https://multimovies.cloud"
}

class MultimoviesSB : StreamSB() {
    override var name = "Multimovies"
    override var mainUrl = "https://multimovies.website"
}

class Yipsu : Voe() {
    override val name = "Yipsu"
    override var mainUrl = "https://yip.su"
}

