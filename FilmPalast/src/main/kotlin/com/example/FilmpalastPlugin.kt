package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class FilmpalastPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(FilmpalastProvider())
        registerExtractorAPI(StreamTapeTo())
        registerExtractorAPI(StreamHubGg())
        registerExtractorAPI(VoeSx())
        registerExtractorAPI(MetaGnathTuggers())
        registerExtractorAPI(FileLions())
    }
}