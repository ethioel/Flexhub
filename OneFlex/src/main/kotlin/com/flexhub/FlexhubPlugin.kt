package com.flexhub

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.registerMainAPI

@CloudstreamPlugin
class FlexhubPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OneFlexProvider())
    }
}
