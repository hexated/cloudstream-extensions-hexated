package com.hexated

import com.lagradost.cloudstream3.TvType

class Serienstream : Aniworld() {
    override var mainUrl = "https://s.to"
    override var name = "Serienstream"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
}