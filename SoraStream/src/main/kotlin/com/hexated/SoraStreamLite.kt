package com.hexated

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
                SoraExtractor.invokeSoraStream(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                SoraExtractor.invokeTwoEmbed(res.id, res.season, res.episode, subtitleCallback, callback)
            },
            {
                SoraExtractor.invokeVidSrc(res.id, res.season, res.episode, subtitleCallback, callback)
            },
            {
                SoraExtractor.invokeDbgo(res.imdbId, res.season, res.episode, subtitleCallback, callback)
            },
            {
                SoraExtractor.invoke123Movie(
                    res.id,
                    res.imdbId,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                SoraExtractor.invokeMovieHab(res.imdbId, res.season, res.episode, subtitleCallback, callback)
            },
            {
                if (res.isAnime) SoraExtractor.invokeAnimes(
                    res.id,
                    res.title,
                    res.epsTitle,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (res.season != null && res.isAnime) SoraExtractor.invokeCrunchyroll(
                    res.title,
                    res.epsTitle,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                if (!res.isAnime) SoraExtractor.invokeHDMovieBox(
                    res.title,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                SoraExtractor.invokeSeries9(res.title, res.season, res.episode, subtitleCallback, callback)
            },
            {
                SoraExtractor.invokeIdlix(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                SoraExtractor.invokeUniqueStream(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                SoraExtractor.invokeFilmxy(res.imdbId, res.season, res.episode, subtitleCallback, callback)
            },
            {
                SoraExtractor.invokeKimcartoon(res.title, res.season, res.episode, subtitleCallback, callback)
            },
            {
                SoraExtractor.invokeXmovies(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                SoraExtractor.invokeFlixhq(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                SoraExtractor.invokeKisskh(res.title, res.season, res.episode, subtitleCallback, callback)
            },
            {
                SoraExtractor.invokeLing(
                    res.title,
                    res.airedYear ?: res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                SoraExtractor.invokeFwatayako(res.imdbId, res.season, res.episode, callback)
            },
            {
                SoraExtractor.invokeM4uhd(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                SoraExtractor.invokeRStream(res.id, res.season, res.episode, callback)
            },
            {
                SoraExtractor.invokeFlixon(res.id, res.imdbId, res.season, res.episode, callback)
            },
        )

        return true
    }

}