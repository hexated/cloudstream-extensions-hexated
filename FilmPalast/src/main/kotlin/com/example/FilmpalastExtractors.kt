package com.example

import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.Streamhub
import com.lagradost.cloudstream3.extractors.Voe

class StreamTapeTo : StreamTape() {
    override var mainUrl = "https://streamtape.com"
}

class StreamHubGg : Streamhub() {
    override var name = "Streamhub Gg"
    override var mainUrl = "https://streamhub.gg"
}

class VoeSx: Voe() {
    override val name = "Voe Sx"
    override val mainUrl = "https://voe.sx"
}

class MetaGnathTuggers : Voe() {
    override val name = "Metagnathtuggers"
    override val mainUrl = "https://metagnathtuggers.com"
}

class FileLions : Filesim() {
    override val name = "Filelions"
    override var mainUrl = "https://filelions.to"
}
