package com.hexated

import com.lagradost.cloudstream3.extractors.Filesim

class Oppadrama : DramaidProvider() {
    override var mainUrl = "http://185.217.95.34"
    override var name = "Oppadrama"
}

class Filelions : Filesim() {
    override val name = "Filelions"
    override var mainUrl = "https://filelions.live"
}