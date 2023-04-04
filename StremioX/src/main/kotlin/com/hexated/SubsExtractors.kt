package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.utils.SubtitleHelper

const val openSubAPI = "https://opensubtitles.strem.io/stremio/v1"
const val watchSomuchAPI = "https://watchsomuch.tv"

object SubsExtractors {
    suspend fun invokeOpenSubs(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val id = if(season == null) {
            imdbId
        } else {
            "$imdbId $season $episode"
        }
        val data = base64Encode("""{"id":1,"jsonrpc":"2.0","method":"subtitles.find","params":[null,{"query":{"itemHash":"$id"}}]}""".toByteArray())
        app.get("${openSubAPI}/q.json?b=$data").parsedSafe<OsResult>()?.result?.all?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    SubtitleHelper.fromThreeLettersToLanguage(sub.lang ?: "") ?: sub.lang
                    ?: "",
                    sub.url ?: return@map
                )
            )
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
            "${watchSomuchAPI}/Watch/ajMovieTorrents.aspx", data = mapOf(
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
            "${watchSomuchAPI}/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part="
        } else {
            "${watchSomuchAPI}/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part=S${seasonSlug}E${episodeSlug}"
        }

        app.get(subUrl).parsedSafe<WatchsomuchSubResponses>()?.subtitles?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    sub.label ?: "", fixUrl(sub.url ?: return@map null, watchSomuchAPI)
                )
            )
        }


    }

    data class OsSubtitles(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("lang") val lang: String? = null,
    )

    data class OsAll(
        @JsonProperty("all") val all: ArrayList<OsSubtitles>? = arrayListOf(),
    )

    data class OsResult(
        @JsonProperty("result") val result: OsAll? = null,
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
}