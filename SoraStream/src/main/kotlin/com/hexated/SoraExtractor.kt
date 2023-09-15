package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.Session
import com.lagradost.cloudstream3.extractors.helper.AesHelper.cryptoAESHandler
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements

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
                    link.type,
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
        invokeWpmovies(url, subtitleCallback, callback, encrypt = true)
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
        encrypt: Boolean = false,
    ) {
        fun String.fixBloat() : String {
            return this.replace("\"", "").replace("\\", "")
        }
        val res = app.get(url ?: return)
        val headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        val referer = getBaseUrl(res.url)
        val document = res.document
        document.select("ul#playeroptionsul > li").map {
            Triple(
                it.attr("data-post"),
                it.attr("data-nume"),
                it.attr("data-type")
            )
        }.apmap { (id, nume, type) ->
            val json = app.post(
                url = "$referer/wp-admin/admin-ajax.php", data = mapOf(
                    "action" to "doo_player_ajax", "post" to id, "nume" to nume, "type" to type
                ), headers = headers, referer = url
            )
            val source = tryParseJson<ResponseHash>(json.text)?.let {
                when {
                    encrypt -> cryptoAESHandler(it.embed_url,(it.key ?: return@apmap).toByteArray(), false)?.fixBloat()
                    fixIframe -> Jsoup.parse(it.embed_url).select("IFRAME").attr("SRC")
                    else -> it.embed_url
                }
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
        val filmxyCookies = getFilmxyCookies(url)
        val doc = app.get(url, cookies = filmxyCookies).document
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

    suspend fun invokeDramaday(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        fun String.getQuality(): String? =
            Regex("""\d{3,4}[pP]""").find(this)?.groupValues?.getOrNull(0)

        fun String.getTag(): String? =
            Regex("""\d{3,4}[pP]\s*(.*)""").find(this)?.groupValues?.getOrNull(1)

        val slug = title.createSlug()
        val epsSlug = getEpisodeSlug(season, episode)
        val url = if (season == null) {
            "$dramadayAPI/$slug-$year/"
        } else {
            "$dramadayAPI/$slug/"
        }
        val res = app.get(url).document

        val servers = if (season == null) {
            val player = res.select("div.tabs__pane p a[href*=https://ouo]").attr("href")
            val ouo = bypassOuo(player)
            app.get(ouo ?: return).document.select("article p:matches(\\d{3,4}[pP]) + p:has(a)")
                .flatMap { ele ->
                    val entry = ele.previousElementSibling()?.text() ?: ""
                    ele.select("a").map {
                        Triple(entry.getQuality(), entry.getTag(), it.attr("href"))
                    }.filter {
                        it.third.startsWith("https://pixeldrain.com") || it.third.startsWith("https://krakenfiles.com")
                    }
                }
        } else {
            val data = res.select("tbody tr:has(td[data-order=${epsSlug.second}])")
            val qualities =
                data.select("td:nth-child(2)").attr("data-order").split("<br>").map { it }
            val iframe = data.select("a[href*=https://ouo]").map { it.attr("href") }
            qualities.zip(iframe).map {
                Triple(it.first.getQuality(), it.first.getTag(), it.second)
            }
        }

        servers.filter { it.first == "720p" || it.first == "1080p" }.apmap {
            val server = if (it.third.startsWith("https://ouo")) bypassOuo(it.third) else it.third
            loadExtractor(server ?: return@apmap, "$dramadayAPI/", subtitleCallback) { link ->
                callback.invoke(
                    ExtractorLink(
                        link.source,
                        "${link.name} ${it.second}",
                        link.url,
                        link.referer,
                        when (link.type) {
                            ExtractorLinkType.M3U8 -> link.quality
                            else -> getQualityFromName(it.first)
                        },
                        link.type,
                        link.headers,
                        link.extractorData
                    )
                )
            }
        }

    }

    suspend fun invokeWatchflx(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val epsSlug = getEpisodeSlug(season, episode)
        val cookies = getWatchflxCookies()
        val url = if (season == null) {
            "$watchflxAPI/browse/playmovie/$tmdbId/directplay"
        } else {
            "$watchflxAPI/Playseries/series/$tmdbId/directplay"
        }
        val res = app.get(url, cookies = cookies).document

        val showUrl = if (season == null) {
            res.selectFirst("iframe.movie_player")?.attr("src")
        } else {
            val seasonUrl =
                res.select("ul.nav.nav-tabs.tabs-left li:matches(Season $season\$) a").attr("href")
            val episodeUrl = app.get(
                seasonUrl,
                cookies = cookies
            ).document.select("div.thumb-nail-list a:contains(${epsSlug.second}:)").attr("href")
            app.get(episodeUrl, cookies = cookies).document.selectFirst("iframe.movie_player")
                ?.attr("src")
        }
        val iframe = app.get(
            showUrl ?: return, referer = "$watchflxAPI/"
        ).document.selectFirst("div#the_frame iframe")?.attr("src")
            ?.let { fixUrl(it, getBaseUrl(showUrl)) } ?: return

        val video = app.get(iframe.replace("/loc/", "/pro/"), referer = iframe).text.let {
            """mp4_url\s*=\s*["'](.*)["'];""".toRegex().find(it)?.groupValues?.getOrNull(1)
        }

        callback.invoke(
            ExtractorLink(
                "Watchflx",
                "Watchflx",
                video ?: return,
                "$watchflxAPI/",
                Qualities.P1080.value,
                INFER_TYPE
            )
        )


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

    suspend fun invokeVidsrcto(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = if(season == null) {
            "$vidsrctoAPI/embed/movie/$imdbId"
        } else {
            "$vidsrctoAPI/embed/tv/$imdbId/$season/$episode"
        }

        val id = app.get(url).document.selectFirst("ul.episodes li a")?.attr("data-id") ?: return

        val subtitles = app.get("$vidsrctoAPI/ajax/embed/episode/$id/subtitles").text
        tryParseJson<List<FmoviesSubtitles>>(subtitles)?.map {
            subtitleCallback.invoke(
                SubtitleFile(
                    it.label ?: "",
                    it.file ?: return@map
                )
            )
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
                invokeAnimetosho(malId, season, episode, subtitleCallback, callback)
            },
            {
                invokeAniwatch(malId, episode, subtitleCallback, callback)
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

    private suspend fun invokeAnimetosho(
        malId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        fun Elements.getLinks(): List<Triple<String, String, Int>> {
            return this.flatMap { ele ->
                ele.select("div.links a:matches(KrakenFiles|GoFile)").map {
                    Triple(
                        it.attr("href"),
                        ele.select("div.size").text(),
                        getIndexQuality(ele.select("div.link a").text())
                    )
                }
            }
        }

        val (seasonSLug, episodeSlug) = getEpisodeSlug(season, episode)
        val jikan = app.get("$jikanAPI/anime/$malId/full").parsedSafe<JikanResponse>()?.data
        val aniId = jikan?.external?.find { it.name == "AniDB" }?.url?.substringAfterLast("=")
        val res = app.get("$animetoshoAPI/series/${jikan?.title?.createSlug()}.$aniId?filter[0][t]=nyaa_class&filter[0][v]=trusted").document

        val servers = if (season == null) {
            res.select("div.home_list_entry:has(div.links)").getLinks()
        } else {
            res.select("div.home_list_entry:has(div.link a:matches([\\.\\s]$episodeSlug[\\.\\s]|S${seasonSLug}E$episodeSlug))")
                .getLinks()
        }

        servers.filter { it.third in arrayOf(Qualities.P1080.value,Qualities.P720.value) }.apmap {
            loadExtractor(it.first, "$animetoshoAPI/", subtitleCallback) { link ->
                callback.invoke(
                    ExtractorLink(
                        link.source,
                        "${link.name} [${it.second}]",
                        link.url,
                        link.referer,
                        when (link.type) {
                            ExtractorLinkType.M3U8 -> link.quality
                            else -> it.third
                        },
                        link.type,
                        link.headers,
                        link.extractorData
                    )
                )
            }
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

//        val sources = mutableListOf<Pair<String, String?>>()
//        if (iframeList.any {
//                it.first.contains(
//                    "2160p",
//                    true
//                )
//            }) {
//            sources.addAll(iframeList.filter {
//                it.first.contains(
//                    "2160p",
//                    true
//                )
//            })
//            sources.add(iframeList.first {
//                it.first.contains(
//                    "1080p",
//                    true
//                )
//            })
//        } else {
//            sources.addAll(iframeList.filter { it.first.contains("1080p", true) })
//        }

        iframeList.apmap { (quality, link) ->
            val driveLink =
                if (link?.contains("driveleech") == true) bypassDriveleech(link) else bypassTechmny(
                    link ?: return@apmap
                )
            val base = getBaseUrl(driveLink ?: return@apmap)
            val driveReq = app.get(driveLink)
            val driveRes = driveReq.document
            val bitLink = driveRes.select("a.btn.btn-outline-success").attr("href")
            val insLink = driveRes.select("a.btn.btn-danger:contains(Instant Download)").attr("href")
            val downloadLink = when {
                insLink.isNotEmpty() -> extractInstantUHD(insLink)
                driveRes.select("button.btn.btn-success").text()
                    .contains("Direct Download", true) -> extractDirectUHD(driveLink, driveReq)
                bitLink.isNullOrEmpty() -> {
                    val backupIframe = driveRes.select("a.btn.btn-outline-warning").attr("href")
                    extractBackupUHD(backupIframe ?: return@apmap)
                }
                else -> {
                    extractMirrorUHD(bitLink, base)
                }
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

    suspend fun invokeVegamovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var res = app.get("$vegaMoviesAPI/search/$title").document
        val match = if (season == null) "$year" else "Season $season"
        val media = res.selectFirst("div.blog-items article:has(h3.entry-title:matches((?i)$title.*$match)) a")
            ?.attr("href")

        res = app.get(media ?: return).document
        val hTag = if (season == null) "h5" else "h3"
        val aTag = if (season == null) "Download Now" else "V-Cloud"
        res.select("div.entry-content > $hTag:matches(1080p|2160p)").apmap {
            val tags = """(?:1080p|2160p)(.*)""".toRegex().find(it.text())?.groupValues?.get(1)?.trim()
            val href = it.nextElementSibling()?.select("a:contains($aTag)")?.attr("href")?.let { url ->
                app.post(
                    "${getBaseUrl(url)}/red.php",
                    data = mapOf("link" to url),
                    referer = "$vegaMoviesAPI/"
                ).text.substringAfter("location.href = \"").substringBefore("\"")
            }
            val selector = if (season == null) "p a:contains(V-Cloud)" else "h4:matches(0?$episode) + p a:contains(V-Cloud)"
            val server = app.get(href ?: return@apmap).document.selectFirst("div.entry-content > $selector")
                ?.attr("href")
            loadExtractor(server ?: return@apmap, "$vegaMoviesAPI/", subtitleCallback) { link ->
                callback.invoke(
                    ExtractorLink(
                        link.name,
                        "${link.name} $tags",
                        link.url,
                        link.referer,
                        getIndexQuality(it.text()),
                        link.type,
                        link.headers,
                    )
                )
            }
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

    suspend fun invokeHdmovies4u(
        title: String? = null,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        fun String.decodeLink(): String {
            return base64Decode(this.substringAfterLast("/"))
        }
        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
        val media = app.get("$hdmovies4uAPI/?s=${if (season == null) imdbId else title}").document
            .let {
                val selector = if (season == null) "a" else "a:matches((?i)$title.*Season $season)"
                it.selectFirst("div.gridxw.gridxe $selector")?.attr("href")
            }
        val selector = if (season == null) "1080p|2160p" else "(?i)Episode.*(?:1080p|2160p)"
        app.get(
            media ?: return
        ).document.select("section h4:matches($selector)").apmap { ele ->
            val (tags, size) = ele.select("span").map {
                it.text()
            }.let { it[it.lastIndex - 1] to it.last().substringAfter("-").trim() }
            val link = ele.nextElementSibling()?.select("a:contains(DriveTOT)")?.attr("href")
            val iframe = bypassBqrecipes(link?.decodeLink() ?: return@apmap).let {
                if (it?.contains("/pack/") == true) {
                    val href =
                        app.get(it).document.select("table tbody tr:contains(S${seasonSlug}E${episodeSlug}) a")
                            .attr("href")
                    bypassBqrecipes(href.decodeLink())
                } else {
                    it
                }
            }
            invokeDrivetot(iframe ?: return@apmap, tags, size, subtitleCallback, callback)
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
        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
        val req = app.get("$m4uhdAPI/search/${title.createSlug()}.html")
        val referer = getBaseUrl(req.url)
        val res = req.document
        val scriptData = res.select("div.row div.item").map { ele ->
            Triple(
                ele.select("div.tiptitle p").text(),
                ele.select("div.jtip-top div:last-child").text().filter { it.isDigit() },
                ele.selectFirst("a")?.attr("href")
            )
        }

        val script = if (scriptData.size == 1) {
            scriptData.firstOrNull()
        } else {
            scriptData.find {
                it.first.contains(
                    "$title", true
                ) && it.second == "$year"
            }
        }

        val link = fixUrl(script?.third ?: return, referer)
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
                doc.selectFirst("div.col-lg-9.col-xl-9 p:matches((?i)S$seasonSlug-E$episodeSlug)")
                    ?: return
            val idepisode = episodeData.select("button").attr("idepisode") ?: return
            val requestEmbed = app.post(
                "$referer/ajaxtv", data = mapOf(
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
                cookiesSet.find { it.second.contains("laravel_session") }?.second?.substringAfter(
                    "laravel_session="
                )
                    ?.substringBefore(";")
            requestEmbed.document.select("div.le-server span").map { it.attr("data") }
        }

        m4uData.apmap { data ->
            val iframe = app.post(
                "$referer/ajax",
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

            loadExtractor(iframe, referer, subtitleCallback, callback)
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

                it.second.equals("Player FM", true) && !isAnime -> invokeSmashyFm(
                    it.second,
                    it.first,
                    url,
                    callback
                )

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
                    referer = apiUrl,
                    timeout = 120L
                ).text else app.post(
                    "${apiUrl}search",
                    data = data,
                    referer = apiUrl
                ).text
            )
        } else {
            app.post("${apiUrl}search", requestBody = body, referer = apiUrl, timeout = 120L).text
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
                        referer = apiUrl,
                        timeout = 120L
                    )
                } else {
                    app.post(
                        "${apiUrl}id2path", data = pathData, referer = apiUrl, timeout = 120L
                    )
                }
            } else {
                app.post("${apiUrl}id2path", requestBody = pathBody, referer = apiUrl, timeout = 120L)
            }).text.let { path ->
                if (api in ddomainIndex) {
                    val worker = app.get(
                        "${fixUrl(path, apiUrl).encodeUrl()}?a=view",
                        referer = if (api in needRefererIndex) apiUrl else "",
                        timeout = 120L
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

        val request = app.get(url, timeout = 120L)
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

    suspend fun invoke2embed(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        val server = "https://stream.2embed.cc"
        val url = if(season == null) {
            "$twoEmbedAPI/embed/$imdbId"
        } else {
            "$twoEmbedAPI/embedtv/$imdbId&s=$season&e=$episode"
        }

        val iframesrc = app.get(url).document.selectFirst("iframe#iframesrc")?.attr("src")
        val framesrc = app.get(fixUrl(iframesrc ?: return, twoEmbedAPI)).document.selectFirst("iframe#framesrc")?.attr("src")
        val video = app.get(fixUrl(framesrc ?: return, "$server/e/")).text.let {
            Regex("file:\\s*\"(.*?m3u8.*?)\"").find(it)?.groupValues?.getOrNull(1)
        }

        M3u8Helper.generateM3u8(
            "2embed",
            video ?: return,
            "$server/",
        ).forEach(callback)

    }

    suspend fun invokePrimewire(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        fun String.decrypt(key: String) : List<PrimewireSources>? {
            return tryParseJson<List<PrimewireSources>>(base64Decode(this).decodePrimewireXor(key))
        }
        val slug = getEpisodeSlug(season, episode)
        val query = if (season == null) {
            title
        } else {
            "$title Season $season"
        }

        val doc = app.get("$primewireAPI/search/$query").document

        val media = doc.select("div.RvnMfoxhgm").map {
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
                    primewireAPI
                )
            ).document.selectFirst("div#vvqUtffkId a:contains(Episode ${slug.second})")
                ?.attr("href")
        } ?: return

        val res = app.get(fixUrl(iframe, primewireAPI), verify = false)
        val serverUrl = "var url = '(/user/servers/.*?\\?ep=.*?)';".toRegex().find(res.text)?.groupValues?.get(1) ?: return
        val cookies = res.okhttpResponse.headers.getPrimewireCookies()
        val url = res.document.select("meta[property=og:url]").attr("content")
        val headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        val qualities = intArrayOf(2160, 1440, 1080, 720, 480, 360)
        val serverRes = app.get(
            "$primewireAPI$serverUrl",
            cookies = cookies, referer = url, headers = headers
        )
        val unpack = getAndUnpack(serverRes.text)
        val key = unpack.substringAfter("(key=").substringBefore(")")
        val key2 = unpack.substringAfter("<\"").substringBefore("\".")
        serverRes.document.select("ul li").amap { el ->
            val server = el.attr("data-value")
            val encryptedData = app.get(
                "$url?server=$server&_=${System.currentTimeMillis()}",
                cookies = cookies,
                referer = url,
                headers = headers
            ).text
            val links = encryptedData.decrypt(key) ?: encryptedData.decrypt(key2) ?: return@amap
            links.forEach { video ->
                qualities.filter { it <= video.max.toInt() }.forEach {
                    callback(
                        ExtractorLink(
                            "Primewire",
                            "Primewire",
                            video.src.split("360", limit = 3).joinToString(it.toString()),
                            "$primewireAPI/",
                            it,
                        )
                    )
                }
            }
        }

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
        argamap(
            {
                invokeMonster(res.url.substringAfterLast("/"), episodeId, season, callback)
            },
            {
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
        )

    }

    private suspend fun invokeMonster(
        urlSlug: String? = null,
        episodeId: String? = null,
        season: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val monsterMainUrl = "https://lookmovie.foundation"
        val viewSlug = if (season == null) {
            "movies/view/$urlSlug"
        } else {
            "shows/view/$urlSlug"
        }
        val streamUrl = app.get("$monsterMainUrl/$viewSlug").document.select("a.round-button:first-child").attr("href")
        val res = app.get(fixUrl(streamUrl, monsterMainUrl)).document
        val script = res.selectFirst("script:containsData(hash:)")?.data()
        val hash =
            Regex("hash:\\s*['\"](\\S+)['\"],").find(script ?: return)?.groupValues?.get(1)
        val expires = Regex("expires:\\s*(\\d+),").find(script)?.groupValues?.get(1)

        val videoUrl = if (season == null) {
            "$monsterMainUrl/api/v1/security/movie-access?id_movie=$episodeId&hash=$hash&expires=$expires"
        } else {
            "$monsterMainUrl/api/v1/security/episode-access?id_episode=$episodeId&hash=$hash&expires=$expires"
        }

        app.get(videoUrl, referer = streamUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
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
        val url =
            if (season == null) "$nowTvAPI/$tmdbId.mp4" else "$nowTvAPI/tv/$tmdbId/s${season}e${slug.second}.mp4"
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

    suspend fun invokeSusflix(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = if(season == null) {
            "$susflixAPI/view/movie/$tmdbId"
        } else {
            "$susflixAPI/view/tv/$tmdbId/$season/$episode"
        }

        val res = app.get(url,cookies = mapOf(
            "session" to "eyJfZnJlc2giOmZhbHNlLCJwaG9uZV9udW1iZXIiOiJzdXNoZXg5OCJ9.ZO6CsA.XUs6Y5gna8ExAUX55-myMi1QpYU"
        )).text.substringAfter("response = {").substringBefore("};").replace("\'", "\"")

        val sources = tryParseJson<SusflixSources>("{$res}")
        sources?.qualities?.map { source ->
            callback.invoke(
                ExtractorLink(
                    "Susflix",
                    "Susflix",
                    source.path ?: return@map,
                    "$susflixAPI/",
                    getQualityFromName(source.quality)
                )
            )
        }

        sources?.srtfiles?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    sub.caption ?: return@map,
                    sub.url ?: return@map,
                )
            )
        }

    }

    suspend fun invokeJump1(
        tmdbId: Int? = null,
        tvdbId: Int? = null,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val referer = "https://jump1.net/"
        val res = if(season == null) {
            val body = """{"filters":[{"type":"slug","args":{"slugs":["${title.createSlug()}-$year"]}}],"sort":"addedRecent","skip":0,"limit":100}""".toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
            app.post("$jump1API/api/movies", requestBody = body, referer = referer)
        } else {
            app.get("$jump1API/api/shows/$tvdbId/seasons", referer = referer)
        }.text

        val source = if(season == null) {
            tryParseJson<Jump1Movies>(res)?.movies?.find { it.id == tmdbId }?.videoId
        } else {
            val jumpSeason = tryParseJson<ArrayList<Jump1Season>>(res)?.find { it.seasonNumber == season }?.id
            val seasonRes = app.get("$jump1API/api/shows/seasons/${jumpSeason ?: return}/episodes", referer = referer)
            tryParseJson<ArrayList<Jump1Episodes>>(seasonRes.text)?.find { it.episodeNumber == episode }?.videoId
        }

        callback.invoke(
            ExtractorLink(
                "Jump1",
                "Jump1",
                "$jump1API/hls/${source ?: return}/master.m3u8?ts=${APIHolder.unixTimeMS}",
                referer,
                Qualities.P1080.value,
                true
            )
        )
    }

    suspend fun invokeNetflix(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Cookie" to "hd=on")
        val netflixId = imdbToNetflixId(imdbId, season)
        val (title, id) = app.get(
            "$netflixAPI/post.php?id=${netflixId ?: return}&t=${APIHolder.unixTime}",
            headers = headers
        ).parsedSafe<NetflixResponse>().let { media ->
            if (season == null) {
                media?.title to netflixId
            } else {
                val seasonId = media?.season?.find { it.s == "$season" }?.id
                val episodeId =
                    app.get(
                        "$netflixAPI/episodes.php?s=${seasonId}&series=$netflixId&t=${APIHolder.unixTime}",
                        headers = headers
                    )
                        .parsedSafe<NetflixResponse>()?.episodes?.find { it.ep == "E$episode" }?.id
                media?.title to episodeId
            }
        }

        app.get(
            "$netflixAPI/playlist.php?id=${id ?: return}&t=${title ?: return}&tm=${APIHolder.unixTime}",
            headers = headers
        ).text.let {
            tryParseJson<ArrayList<NetflixResponse>>(it)
        }?.firstOrNull()?.sources?.map {
            callback.invoke(
                ExtractorLink(
                    "Netflix",
                    "Netflix",
                    fixUrl(it.file ?: return@map, netflixAPI),
                    "$netflixAPI/",
                    getQualityFromName(it.file.substringAfter("q=")),
                    INFER_TYPE,
                    headers = mapOf("Cookie" to "hd=on")
                )
            )
        }

    }

}

