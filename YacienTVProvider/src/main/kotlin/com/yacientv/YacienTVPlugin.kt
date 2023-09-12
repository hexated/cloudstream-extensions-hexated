package com.yacientv
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class YacienTVPlugin: Plugin() {
    override fun load(context: Context) {
<<<<<<< HEAD:YacienTVProvider/src/main/kotlin/com/yacientv/YacienTVPlugin.kt
        registerMainAPI(YacienTV())
=======
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(Minioppai())
        registerExtractorAPI(Streampai())
        registerExtractorAPI(Paistream())
        registerExtractorAPI(TvMinioppai())
>>>>>>> bf89248a7d2a64a6e7dfa4049ac908b967e111a0:Minioppai/src/main/kotlin/com/hexated/MinioppaiPlugin.kt
    }
}