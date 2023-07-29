package com.hexated

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.nicehttp.NiceResponse
import java.net.URI

var mainServer: String? = null

object TimefourTvExtractor : TimefourTv() {

    fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private suspend fun getSportLink(url: String): String? {
        val iframe = app.get(url, referer = "$mainUrl/").document.select("iframe").attr("src")
            .let { fixUrl(it) }
        val ref = getBaseUrl(url)
        val data = app.get(iframe, referer = ref).document.select("script")
            .find { it.data().contains("eval(function(p,a,c,k,e,d)") }?.data()
            .let { getAndUnpack(it.toString()) }

        mainServer = getBaseUrl(iframe)

        return Regex("var\\ssrc=['|\"](.*?.m3u8.*?)['|\"];").find(data)?.groupValues?.get(1)

    }

    private suspend fun getCricfreeLink(url: String, ref: String): String? {
        val doc = app.get(url, referer = ref).document
        val iframe = doc.select("iframe").attr("src")
        val channel = iframe.split("/").last().removeSuffix(".php")
        val refTwo = getBaseUrl(url)

        val docTwo =
            app.get(fixUrl(iframe), referer = refTwo).document.selectFirst("script[src*=embed.]")
                ?.attr("src")
        val refThree = getBaseUrl(iframe)
        val linkTwo = fixUrl("${docTwo?.replace(".js", ".php")}?player=desktop&live=$channel")

        val docThree = app.get(
            linkTwo,
            referer = "$refThree/",
        )
        mainServer = getBaseUrl(linkTwo)

        val scriptData = docThree.document.selectFirst("div#player")?.nextElementSibling()?.data()
            ?.substringAfterLast("return(")?.substringBefore(".join")
        val link = scriptData?.removeSurrounding("[", "]")?.replace("\"", "")?.split(",")
            ?.joinToString("") ?: return null
        return app.get(link, referer = "$mainUrl/").url
    }

    private suspend fun getFootyhunter(url: String, ref: String): String? {
        val doc = app.get(url, referer = ref).document
        val iframe = doc.selectFirst("iframe")?.attr("src") ?: return null
        val referer = getBaseUrl(url)
        val docTwo = app.get(fixUrl(iframe), referer = "$referer/").text
        mainServer = getBaseUrl(iframe)
        val link = Regex("""source:['|"](\S+.m3u8)['|"],""").find(docTwo)?.groupValues?.getOrNull(1)
            ?: return null
        return app.get(link, referer = "$mainUrl/").url
    }

    suspend fun getLink(url: String): String? {

        if (url.contains("sportzonline") || url.contains("sportsonline")) {
            return getSportLink(url)
        }

        if(url.contains(daddyUrl.getHost(), true)) {
            mainServer = getBaseUrl(url)
            return getFinalLink(app.get(url, referer = daddyUrl))
        }

        val (channel, iframe) = if (url.contains("width=") || url.contains("/link")) {
            val doc = app.get(url, referer = "$mainUrl/").document
            val tempIframe = doc.selectFirst("iframe")?.attr("src") ?: return null

            if (tempIframe.contains("cricfree")) {
                return getCricfreeLink(tempIframe, getBaseUrl(url))
            }
            if (tempIframe.contains("footyhunter")) {
                return getFootyhunter(tempIframe, getBaseUrl(url))
            }

            val doctwo = app.get(fixUrl(tempIframe), referer = url).text
            listOf(
                tempIframe.split("?").last().removePrefix("id=").replace(".php", ""),
                doctwo.substringAfterLast("<iframe  src=\"").substringBefore("'+")
            )
        } else {
            val doc = app.get(url, referer = "$mainUrl/").text
            listOf(
                url.split("?").last().removePrefix("id=").replace(".php", ""),
                doc.substringAfterLast("<iframe  src=\"").substringBefore("'+")
            )
        }

        val linkFirst = "$iframe$channel.php"
        val refFirst = getBaseUrl(url)

        val docSecond = app.get(fixUrl(linkFirst), referer = refFirst).document
        val iframeSecond = docSecond.select("iframe:last-child, iframe#thatframe").attr("src")

        val refSecond = getBaseUrl(linkFirst)
        val docThird = app.get(fixUrl(iframeSecond), referer = "$refSecond/")
        mainServer = getBaseUrl(iframeSecond)

        return getFinalLink(docThird)

    }

    private fun getFinalLink(res: NiceResponse): String? {
        return Regex("""source:['|"](\S+.m3u8)['|"],""").find(res.text)?.groupValues?.getOrNull(
            1
        ) ?: run {
            val scriptData =
                res.document.selectFirst("div#player")?.nextElementSibling()?.data()
                    ?.substringAfterLast("return(")?.substringBefore(".join")
            scriptData?.removeSurrounding("[", "]")?.replace("\"", "")?.split(",")
                ?.joinToString("")
        }
    }

    private fun String.getHost(): String {
        return URI(this).host.substringBeforeLast(".").substringAfterLast(".")
    }

}