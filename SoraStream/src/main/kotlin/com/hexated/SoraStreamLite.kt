package com.hexated

import com.hexated.SoraExtractor.invoke123Movie
import com.hexated.SoraExtractor.invokeAnimes
import com.hexated.SoraExtractor.invokeAsk4Movies
import com.hexated.SoraExtractor.invokeCrunchyroll
import com.hexated.SoraExtractor.invokeDbgo
import com.hexated.SoraExtractor.invokeFilmxy
import com.hexated.SoraExtractor.invokeFlixon
import com.hexated.SoraExtractor.invokeFmovies
import com.hexated.SoraExtractor.invokeFwatayako
import com.hexated.SoraExtractor.invokeGomovies
import com.hexated.SoraExtractor.invokeHDMovieBox
import com.hexated.SoraExtractor.invokeIdlix
import com.hexated.SoraExtractor.invokeKimcartoon
import com.hexated.SoraExtractor.invokeKisskh
import com.hexated.SoraExtractor.invokeLing
import com.hexated.SoraExtractor.invokeM4uhd
import com.hexated.SoraExtractor.invokeMovie123Net
import com.hexated.SoraExtractor.invokeMovieHab
import com.hexated.SoraExtractor.invokeNinetv
import com.hexated.SoraExtractor.invokePutlocker
import com.hexated.SoraExtractor.invokeRStream
import com.hexated.SoraExtractor.invokeSeries9
import com.hexated.SoraExtractor.invokeSmashyStream
import com.hexated.SoraExtractor.invokeSoraStream
import com.hexated.SoraExtractor.invokeTwoEmbed
import com.hexated.SoraExtractor.invokeUniqueStream
import com.hexated.SoraExtractor.invokeVidSrc
import com.hexated.SoraExtractor.invokeWatchOnline
import com.hexated.SoraExtractor.invokeWatchsomuch
import com.hexated.SoraExtractor.invokeXmovies
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.argamap
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink

class SoraStreamLite : SoraStream() {
    override var name = "SoraStream-Lite"

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val res = AppUtils.parseJson<LinkData>(data)

        argamap(
            {
                invokePutlocker(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    callback
                )
            },
            {
                invokeWatchsomuch(
                    res.imdbId,
                    res.season,
                    res.episode,
                    subtitleCallback
                )
            },
            {
                invokeSoraStream(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeNinetv(
                    res.id,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeTwoEmbed(res.id, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeVidSrc(res.id, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeDbgo(res.imdbId, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invoke123Movie(
                    res.id,
                    res.imdbId,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeMovie123Net(
                    res.title,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeMovieHab(res.imdbId, res.season, res.episode, subtitleCallback, callback)
            },
            {
                if (res.isAnime) invokeAnimes(
                    res.id,
                    res.title,
                    res.epsTitle,
                    res.airedYear ?: res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
//            {
//                if (res.season != null && res.isAnime) invokeCrunchyroll(
//                    res.title,
//                    res.epsTitle,
//                    res.season,
//                    res.episode,
//                    subtitleCallback,
//                    callback
//                )
//            },
            {
                if (!res.isAnime) invokeHDMovieBox(
                    res.title,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeSeries9(res.title, res.year, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeIdlix(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
//            {
//                invokeUniqueStream(
//                    res.title,
//                    res.year,
//                    res.season,
//                    res.episode,
//                    subtitleCallback,
//                    callback
//                )
//            },
            {
                if (!res.isAnime) invokeFilmxy(res.imdbId, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeKimcartoon(res.title, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeSmashyStream(res.imdbId, res.season, res.episode, callback)
            },
            {
                invokeXmovies(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeFmovies(
                    res.title,
                    res.airedYear ?: res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeKisskh(res.title, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeLing(
                    res.title,
                    res.airedYear ?: res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeFwatayako(res.imdbId, res.season, res.episode, callback)
            },
            {
                invokeM4uhd(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeRStream(res.id, res.season, res.episode, callback)
            },
            {
                invokeFlixon(res.id, res.imdbId, res.season, res.episode, callback)
            },
            {
                invokeGomovies(res.title, res.year, res.season, res.episode, callback)
            },
            {
                if (!res.isAnime) invokeAsk4Movies(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeWatchOnline(
                    res.imdbId,
                    res.id,
                    res.title,
                    res.airedYear ?: res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
        )

        return true
    }

}