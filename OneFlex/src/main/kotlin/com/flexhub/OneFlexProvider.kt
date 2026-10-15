package com.flexhub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class OneFlexProvider : MainAPI() {
    override var name = "Flexhub (1Flex/1Shows)"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val instantLinkLoading = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // The chameleon strategy: swap this URL to target any of the alts instantly
    override var mainUrl = "https://www.1flex.org" 
    // Fallbacks if the primary gets nuked: "https://1shows.bz", "https://www.1tube.org"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/").document
        
        // Aggressive selector fallback: tries the most common multi-server DOM structures
        val items = document.select("div.item, div.flw-item, article.poster")
        
        val homeList = items.mapNotNull { element ->
            val title = element.selectFirst("h2, h3, .title")?.text() ?: return@mapNotNull null
            val href = fixUrl(element.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val poster = element.selectFirst("img")?.attr("data-src") ?: element.selectFirst("img")?.attr("src")
            
            // Infer type from URL structure
            val type = if (href.contains("/movie/") || href.contains("/film/")) TvType.Movie else TvType.TvSeries
            
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        }
        
        return HomePageResponse(listOf(HomePageList("Trending on Flexhub", homeList)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query}" // Standard WP/Dooplay search parameter
        val document = app.get(url).document
        
        return document.select("div.item, div.flw-item, article.poster").mapNotNull { element ->
            val title = element.selectFirst("h2, h3, .title")?.text() ?: return@mapNotNull null
            val href = fixUrl(element.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val poster = element.selectFirst("img")?.attr("data-src") ?: element.selectFirst("img")?.attr("src")
            val type = if (href.contains("/movie/") || href.contains("/film/")) TvType.Movie else TvType.TvSeries
            
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1, .entry-title, .heading-name")?.text()?.substringBefore("Watch")?.trim() ?: "Unknown"
        val poster = document.selectFirst("img.poster, img.film-poster-img, .wp-post-image")?.attr("src") 
            ?: document.selectFirst("img")?.attr("data-src")
        val plot = document.selectFirst("div.description, .entry-content, .summary")?.text()?.trim()
        val year = document.selectFirst("span.year, .date")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        
        val type = if (url.contains("/movie/") || url.contains("/film/")) TvType.Movie else TvType.TvSeries

        val episodes = if (type == TvType.Movie) {
            listOf(Episode(data = url, name = "Movie", episode = 1))
        } else {
            // Target the episode list container. Multi-server sites usually list them in a grid or list
            document.select("div.episode, li.season_episode, .ss-list a").map { element ->
                val epHref = fixUrl(element.selectFirst("a")?.attr("href") ?: element.attr("href"))
                val epName = element.selectFirst("a")?.text() ?: element.text()
                val epNum = epName.filter { it.isDigit() }.toIntOrNull()
                
                Episode(
                    data = epHref,
                    name = epName,
                    episode = epNum
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // The money shot: find the iframe. We try multiple common IDs and classes.
        val iframeUrl = document.selectFirst("iframe#player, iframe.player, iframe.w-player, iframe.embed-responsive-item")?.attr("data-src")
            ?: document.selectFirst("iframe#player, iframe.player, iframe.w-player, iframe.embed-responsive-item")?.attr("src")
            ?: throw ErrorLoadingException("No player iframe found. Site structure may have changed.")

        // Hand it off to CloudStream's built-in extractor army. 
        // This automatically resolves VidCloud, Streamwish, MixDrop, Filemoon, etc.
        loadExtractor(iframeUrl, referer = mainUrl, subtitleCallback, callback)
        
        return true
    }
}
