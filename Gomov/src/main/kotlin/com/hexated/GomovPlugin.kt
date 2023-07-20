
package com.hexated

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class GomovPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(Gomov())
        registerMainAPI(DutaMovie())
        registerMainAPI(Ngefilm())
        registerExtractorAPI(Filelions())
        registerExtractorAPI(Likessb())
        registerExtractorAPI(DbGdriveplayer())
        registerExtractorAPI(Dutamovie21())
    }
}