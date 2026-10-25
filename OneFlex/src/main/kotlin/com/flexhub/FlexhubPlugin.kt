package com.flexhub

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FlexhubPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OneFlexProvider())
        registerMainAPI(AniCineProvider())
        registerMainAPI(ZetMoonProvider())
        registerMainAPI(SixtySevenMoviesProvider())
        registerMainAPI(TonkaCineProvider())
        registerMainAPI(PrimeShowsProvider())
        registerMainAPI(MoovieProvider())
        registerMainAPI(AniXxProvider())
        registerMainAPI(AuroraScreenProvider())
        registerMainAPI(HydraHdProvider())
        registerMainAPI(FireFlixProvider())
        registerMainAPI(VidBoxProvider())
        registerMainAPI(ZenCineProvider())
        registerMainAPI(CineWaveProvider())
        registerMainAPI(CineStreamProvider())
        registerMainAPI(YouFlexProvider())
        registerMainAPI(FilmCaveProvider())
        registerMainAPI(FlixMixProvider())
        registerMainAPI(FlyStreamProvider())
        registerMainAPI(StreamoProvider())
    }
}
