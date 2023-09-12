package com.elostoratv
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
<<<<<<<< HEAD:ElOstora/src/main/kotlin/com/elostoratv/ElOstoraTVPlugin.kt
class ElOstoraTVPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ElOstoraTV())
========
class AnimasuPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(Animasu())
>>>>>>>> bf89248a7d2a64a6e7dfa4049ac908b967e111a0:Animasu/src/main/kotlin/com/hexated/AnimasuPlugin.kt
    }
}