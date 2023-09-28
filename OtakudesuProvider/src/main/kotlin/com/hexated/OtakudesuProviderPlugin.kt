
package com.hexated

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class OtakudesuProviderPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(OtakudesuProvider())
        registerExtractorAPI(Moedesu())
        registerExtractorAPI(DesuBeta())
        registerExtractorAPI(Desudesuhd())
    }
}