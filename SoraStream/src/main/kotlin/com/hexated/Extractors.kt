package com.hexated

import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.XStreamCdn

class Sbnet : StreamSB() {
    override var name = "Sbnet"
    override var mainUrl = "https://sbnet.one"
}

class StreamM4u : XStreamCdn() {
    override val name: String = "StreamM4u"
    override val mainUrl: String = "https://streamm4u.club"
}

class Sblongvu : StreamSB() {
    override var name = "Sblongvu"
    override var mainUrl = "https://sblongvu.com"
}

class Keephealth : StreamSB() {
    override var name = "Keephealth"
    override var mainUrl = "https://keephealth.info"
}

class FileMoonIn : Filesim() {
    override val mainUrl = "https://filemoon.in"
    override val name = "FileMoon"
}