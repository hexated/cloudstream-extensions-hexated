
package com.hexated

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SoraStreamPlugin: Plugin() {

    private fun classExist(className: String) : Boolean {
        try {
            Class.forName(className)
        } catch (e: Exception) {
            return false
        }

        return true
    }

    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(SoraStream())
        registerMainAPI(SoraStreamLite())
        registerExtractorAPI(Animefever())
        registerExtractorAPI(Multimovies())
        registerExtractorAPI(MultimoviesSB())
        registerExtractorAPI(Yipsu())
        registerExtractorAPI(Mwish())
        registerExtractorAPI(TravelR())
        registerExtractorAPI(Playm4u())
        registerExtractorAPI(VCloud())

        if (classExist("com.lagradost.cloudstream3.extractors.PixelDrain")) {
            registerExtractorAPI(Pixeldra())
        }
        
        registerExtractorAPI(M4ufree())
        registerExtractorAPI(Streamruby())
        registerExtractorAPI(Streamwish())
        registerExtractorAPI(FilelionsTo())
        registerExtractorAPI(Embedwish())
        registerExtractorAPI(UqloadsXyz())
        registerExtractorAPI(Uploadever())
        registerExtractorAPI(Netembed())
        registerExtractorAPI(Flaswish())
        registerExtractorAPI(Comedyshow())
        registerExtractorAPI(Ridoo())
        registerExtractorAPI(Streamvid())
        registerExtractorAPI(Embedrise())
        registerExtractorAPI(Gdmirrorbot())
        registerExtractorAPI(FilemoonNl())
        registerExtractorAPI(Alions())
    }
}