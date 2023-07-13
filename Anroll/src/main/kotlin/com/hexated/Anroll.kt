package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class Anroll : MainAPI() {
    override var mainUrl = "https://www.anroll.net"
    override var name = "Anroll"
    override val hasMainPage = true
    override var lang = "pt"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private const val searchUrl = "https://apiv2-prd.anroll.net"
        private const val episodeUrl = "https://apiv3-prd.anroll.net"
        private const val posterUrl = "https://static.anroll.net"
        private const val videoUrl = "https://cdn-zenitsu.gamabunta.xyz"
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/home").document
        val home = mutableListOf<HomePageList>()
        document.select("div.hAbQAe").map { div ->
            val header = div.selectFirst("h2")?.text() ?: return@map
            val child = HomePageList(
                header,
                div.select("ul li").mapNotNull {
                    it.toSearchResult()
                },
                header == "Últimos Laçamentos"
            )
            home.add(child)
        }
        return HomePageResponse(home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("h1")?.text()?.trim() ?: ""
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.select("img").attr("src"))
        val epNum = this.selectFirst("span.sc-f5d5b250-3.fsTgnD b")?.text()?.toIntOrNull()
        val isDub = this.selectFirst("div.sc-9dbd1f1d-5.efznig")?.text() == "DUB"
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(isDub, epNum)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get("$searchUrl/search?q=$query").parsedSafe<SearchAnime>()
        val collection = mutableListOf<SearchResponse>()
        val anime = res?.data_anime?.mapNotNull {
            addAnimeSearch(
                it.titulo ?: return@mapNotNull null,
                "a/${it.generate_id}",
                it.slug_serie ?: return@mapNotNull null,
                Image.Anime
            )
        }
        if (anime?.isNotEmpty() == true) collection.addAll(anime)
        val filme = res?.data_filme?.mapNotNull {
            addAnimeSearch(
                it.nome_filme ?: return@mapNotNull null,
                "f/${it.generate_id}",
                it.slug_filme ?: return@mapNotNull null,
                Image.Filme
            )
        }
        if (filme?.isNotEmpty() == true) collection.addAll(filme)
        return collection
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixUrl = getProperAnimeLink(url) ?: throw ErrorLoadingException()
        val document = app.get(fixUrl).document

        val article = document.selectFirst("article.animedetails") ?: return null
        val title = article.selectFirst("h2")?.text() ?: return null
        val poster = fixUrlNull(document.select("section.animecontent img").attr("src"))
        val tags = article.select("div#generos a").map { it.text() }
        val year = article.selectFirst("div.dfuefM")?.nextElementSibling()?.text()
            ?.toIntOrNull()
        val description = document.select("div.sinopse").text().trim()
        val type = if (fixUrl.contains("/a/")) TvType.Anime else TvType.AnimeMovie

        val episodes = mutableListOf<Episode>()

        if (type == TvType.Anime) {
            for (i in 1..10) {
                val dataEpisode = app.get("$episodeUrl/animes/${fixUrl.substringAfterLast("/")}/episodes?page=$i&order=desc")
                    .parsedSafe<LoadAnime>()?.data?.map {
                        Episode(
                            Load(it.anime?.get("slug_serie"), it.n_episodio, "animes").toJson(),
                            it.titulo_episodio,
                            episode = it.n_episodio?.toIntOrNull(),
                            posterUrl = it.anime?.get("slug_serie")?.fixImageUrl(Image.Episode),
                            description = it.sinopse_episodio
                        )
                    }?.reversed() ?: emptyList()
                if(dataEpisode.isEmpty()) break else episodes.addAll(dataEpisode)
            }
        } else {
            val dataEpisode = listOf(
                Episode(
                    Load(
                        document.selectFirst("script:containsData(slug_filme)")?.data()?.let {
                            Regex("[\"']slug_filme[\"']:[\"'](\\S+?)[\"']").find(it)?.groupValues?.get(1)
                        } ?: return null, "movie", "movies"
                    ).toJson()

                )
            )
            episodes.addAll(dataEpisode)
        }

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            plot = description
            this.tags = tags
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val load = tryParseJson<Load>(data)
        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                if(load?.type == "movies") {
                    "$videoUrl/hls/${load.type}/${load.slug_serie}/${load.n_episodio}.mp4/media-1/stream.m3u8"
                } else {
                    "$videoUrl/cf/hls/${load?.type}/${load?.slug_serie}/${load?.n_episodio}.mp4/media-1/stream.m3u8"
                },
                "$mainUrl/",
                Qualities.Unknown.value,
                true
            )
        )
        return true
    }

    private suspend fun getProperAnimeLink(uri: String): String? {
        return if (uri.contains("/e/")) {
            app.get(uri).document.selectFirst("div.epcontrol2 a[href*=/a/]")?.attr("href")?.let {
                fixUrl(it)
            }
        } else {
            uri
        }
    }

    private fun addAnimeSearch(titulo: String, id: String, slug: String, type: Image): AnimeSearchResponse {
        return newAnimeSearchResponse(titulo, "$mainUrl/$id", TvType.Anime) {
            this.posterUrl = slug.fixImageUrl(type)
        }
    }

    private fun String.fixImageUrl(param: Image): String {
        return when (param) {
            Image.Episode -> {
                "$posterUrl/images/animes/screens/$this/130x74/007.jpg"
            }
            Image.Anime -> {
                "$mainUrl/_next/image?url=$posterUrl/images/animes/capas/130x209/$this.jpg&w=384&q=75"
            }
            Image.Filme -> {
                "$mainUrl/_next/image?url=$posterUrl/images/filmes/capas/130x209/$this.jpg&w=384&q=75"
            }
        }
    }

    enum class Image {
        Episode,
        Anime,
        Filme,
    }

    data class Load(
        val slug_serie: String? = null,
        val n_episodio: String? = null,
        val type: String? = null,
    )

    data class DataEpisode(
        @JsonProperty("id_series_episodios") val id_series_episodios: Int? = null,
        @JsonProperty("n_episodio") val n_episodio: String? = null,
        @JsonProperty("titulo_episodio") val titulo_episodio: String? = null,
        @JsonProperty("sinopse_episodio") val sinopse_episodio: String? = null,
        @JsonProperty("generate_id") val generate_id: String? = null,
        @JsonProperty("anime") val anime: HashMap<String, String>? = null,
    )

    data class LoadAnime(
        @JsonProperty("data") val data: ArrayList<DataEpisode>? = arrayListOf()
    )

    data class DataAnime(
        @JsonProperty("titulo") val titulo: String? = null,
        @JsonProperty("generate_id") val generate_id: String? = null,
        @JsonProperty("slug_serie") val slug_serie: String? = null,
        @JsonProperty("total_eps_anime") val total_eps_anime: Int? = null,
    )

    data class DataFilme(
        @JsonProperty("nome_filme") val nome_filme: String? = null,
        @JsonProperty("generate_id") val generate_id: String? = null,
        @JsonProperty("slug_filme") val slug_filme: String? = null,
    )

    data class SearchAnime(
        @JsonProperty("data_anime") val data_anime: ArrayList<DataAnime>? = arrayListOf(),
        @JsonProperty("data_filme") val data_filme: ArrayList<DataFilme>? = arrayListOf(),
    )

}