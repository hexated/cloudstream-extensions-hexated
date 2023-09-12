package com.hexated

import com.lagradost.cloudstream3.extractors.*

class Doods : DoodLaExtractor() {
    override var name = "Doods"
    override var mainUrl = "https://doods.pro"
}

class Dutamovie21 : StreamSB() {
    override var name = "Dutamovie21"
    override var mainUrl = "https://dutamovie21.xyz"
}

class FilelionsTo : Filesim() {
    override val name = "Filelions"
    override var mainUrl = "https://filelions.to"
}

class Lylxan : Filesim() {
    override val name = "Lylxan"
    override var mainUrl = "https://lylxan.com"
}

class Embedwish : Filesim() {
    override val name = "Embedwish"
    override var mainUrl = "https://embedwish.com"
}

class Likessb : StreamSB() {
    override var name = "Likessb"
    override var mainUrl = "https://likessb.com"
}

class DbGdriveplayer : Gdriveplayer() {
    override var mainUrl = "https://database.gdriveplayer.us"
}