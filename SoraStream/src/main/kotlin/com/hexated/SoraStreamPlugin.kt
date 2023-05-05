
package com.hexated

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SoraStreamPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(SoraStream())
        registerMainAPI(SoraStreamLite())
        registerExtractorAPI(StreamM4u())
        registerExtractorAPI(Sblongvu())
        registerExtractorAPI(Keephealth())
        registerExtractorAPI(FileMoonIn())
        registerExtractorAPI(Sbnet())
        registerExtractorAPI(Chillx())
        registerExtractorAPI(Watchx())
        registerExtractorAPI(StreamhideCom())
        registerExtractorAPI(Movhide())
        registerExtractorAPI(Moviesm4u())
        registerExtractorAPI(Fembed9hd())
        registerExtractorAPI(Sbasian())
        registerExtractorAPI(Bestx())
    }
}