
package com.hexated

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MoflixPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(Moflix())
        registerMainAPI(Cineclix())
        registerExtractorAPI(MoflixClick())
        registerExtractorAPI(Highstream())
        registerExtractorAPI(MoflixFans())
        registerExtractorAPI(MoflixLink())
        registerExtractorAPI(Doodstream())
    }
}