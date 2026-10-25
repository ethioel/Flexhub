package com.flexhub

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FlexhubPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OneFlexProvider())
        registerMainAPI(OneShowsProvider())
        registerMainAPI(OneShowsAltProvider())
        registerMainAPI(OneTubeProvider())
        registerMainAPI(AniCineProvider())
        registerMainAPI(SixtySevenMoviesProvider())
        registerMainAPI(NetShowsProvider())
        registerMainAPI(CineVibeProvider())
        registerMainAPI(KaitoTvProvider())
        registerMainAPI(CineHdProvider())
    }
}
