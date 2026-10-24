package com.flexhub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * Provider for 1flex.org.
 *
 * The site is a client-rendered Next.js app backed by a TMDB proxy (db.1flex.org)
 * and third-party embed players (vidlink.pro / viduki / vidzee). Scraping the HTML
 * yields nothing, so all data comes from the same JSON API the site itself uses.
 */
class OneFlexProvider : MainAPI() {
    override var name = "Flexhub (1Flex/1Shows)"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val instantLinkLoading = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override var mainUrl = "https://www.1flex.org"

    private val apiUrl = "https://db.1flex.org"
    private val embedProvidersUrl = "https://api.viduki.net/embed_providers?site=1flex"
    private val imageUrl = "https://image.tmdb.org/t/p/w500"
    private val stillUrl = "https://image.tmdb.org/t/p/w300"

    // The proxy rejects requests without browser-like headers
    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Accept" to "application/json",
        "Referer" to "https://www.1flex.org/",
        "Origin" to "https://www.1flex.org",
    )

    private data class TmdbGenre(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )

    private data class TmdbItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_average") val rating: Double? = null,
        @JsonProperty("genre_ids") val genreIds: List<Int> = emptyList(),
    )

    private data class TmdbResult(
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("results") val results: List<TmdbItem> = emptyList(),
    )

    private data class TmdbMovieDetails(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("tagline") val tagline: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("vote_average") val rating: Double? = null,
        @JsonProperty("genres") val genres: List<TmdbGenre> = emptyList(),
    )

    private data class TmdbSeason(
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("episode_count") val episodeCount: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("air_date") val airDate: String? = null,
    )

    private data class TmdbTvDetails(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("vote_average") val rating: Double? = null,
        @JsonProperty("number_of_seasons") val numberOfSeasons: Int? = null,
        @JsonProperty("seasons") val seasons: List<TmdbSeason> = emptyList(),
        @JsonProperty("genres") val genres: List<TmdbGenre> = emptyList(),
    )

    private data class TmdbEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("air_date") val airDate: String? = null,
    )

    private data class TmdbSeasonDetails(
        @JsonProperty("episodes") val episodes: List<TmdbEpisode> = emptyList(),
    )

    private data class EmbedProvider(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("movie") val movie: String? = null,
        @JsonProperty("tv") val tv: String? = null,
    )

    private data class EmbedProvidersResponse(
        @JsonProperty("providers") val providers: List<EmbedProvider> = emptyList(),
    )

    private fun TmdbItem.tvType(): TvType {
        val animated = genreIds.contains(16)
        return when {
            this.tvTypeIsMovie() -> TvType.Movie
            animated -> TvType.Anime
            else -> TvType.TvSeries
        }
    }

    private fun TmdbItem.tvTypeIsMovie(): Boolean =
        (mediaType ?: title?.let { "movie" }) == "movie"

    private fun TmdbItem.displayTitle(): String? = title ?: name

    private fun TmdbItem.toSearchResponse(fallbackType: String? = null): SearchResponse? {
        val id = id ?: return null
        val type = mediaType ?: fallbackType ?: return null
        val title = displayTitle() ?: return null
        val poster = posterPath?.let { "$imageUrl$it" } ?: backdropPath?.let { "$imageUrl$it" }
        val data = "$mainUrl/$type/$id"
        return newMovieSearchResponse(title, data, tvType()) {
            this.posterUrl = poster
            this.score = Score.from(rating, 10)
        }
    }

    private suspend fun getTmdb(path: String): TmdbResult =
        app.get("$apiUrl$path", headers = apiHeaders).parsed()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rows = listOf(
            "Trending Now" to "/trending/all/week",
            "Popular Movies" to "/movie/popular",
            "Top Rated Movies" to "/movie/top_rated",
            "In Theaters" to "/movie/now_playing",
            "Popular TV Shows" to "/tv/popular",
            "Top Rated TV Shows" to "/tv/top_rated",
            "Airing Today" to "/tv/airing_today",
        )

        val lists = rows.amap { (title, path) ->
            val items = try {
                getTmdb("$path?page=$page").results
            } catch (e: Exception) {
                emptyList()
            }
            val mapped = items.mapNotNull { item ->
                // /trending/all mixes in "person" entries
                if (item.mediaType == "person") null else item.toSearchResponse(item.mediaType ?: path.trim('/').substringBefore('/'))
            }
            HomePageList(title, mapped)
        }.filter { it.list.isNotEmpty() }

        if (lists.isEmpty()) throw ErrorLoadingException("No rows loaded from $apiUrl")
        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val res = app.get("$apiUrl/search/multi?query=$encoded", headers = apiHeaders)
            .parsed<TmdbResult>()
        return res.results.mapNotNull { it.toSearchResponse() }
    }

    private fun parseDataUrl(url: String): Triple<String, Int, Pair<Int, Int>?> {
        // data urls: {mainUrl}/movie/{id}  |  {mainUrl}/tv/{id}  |  {mainUrl}/tv/{id}/{season}/{episode}
        val parts = url.removePrefix(mainUrl).trim('/').split("/")
        val type = parts.getOrNull(0) ?: throw ErrorLoadingException("Invalid data url: $url")
        val id = parts.getOrNull(1)?.toIntOrNull() ?: throw ErrorLoadingException("Invalid id in: $url")
        val seasonEpisode = if (parts.size >= 4) {
            (parts[2].toIntOrNull() ?: 1) to (parts[3].toIntOrNull() ?: 1)
        } else null
        return Triple(type, id, seasonEpisode)
    }

    override suspend fun load(url: String): LoadResponse {
        val (type, id, _) = parseDataUrl(url)

        if (type == "movie") {
            val d = app.get("$apiUrl/movie/$id", headers = apiHeaders).parsed<TmdbMovieDetails>()
            return newMovieLoadResponse(
                d.title ?: d.originalTitle ?: "Unknown",
                "$mainUrl/movie/$id",
                TvType.Movie,
                "$mainUrl/movie/$id",
            ) {
                posterUrl = d.posterPath?.let { "$imageUrl$it" }
                backgroundPosterUrl = d.backdropPath?.let { "$imageUrl$it" }
                plot = if (d.tagline.isNullOrBlank()) d.overview else "${d.tagline}\n\n${d.overview}"
                year = d.releaseDate?.substringBefore('-')?.toIntOrNull()
                score = Score.from(d.rating, 10)
                tags = d.genres.mapNotNull { it.name }
                duration = d.runtime
                comingSoon = d.status == "Upcoming" || d.releaseDate?.takeIf { it.isNotBlank() }?.let {
                    runCatching { it > todayIso() }.getOrNull() == true
                } == true
            }
        }

        val d = app.get("$apiUrl/tv/$id", headers = apiHeaders).parsed<TmdbTvDetails>()
        val seasons = d.seasons.filter { (it.seasonNumber ?: 0) > 0 && (it.episodeCount ?: 0) > 0 }
        val episodes = seasons.amap { s ->
            val sn = s.seasonNumber ?: return@amap emptyList()
            try {
                app.get("$apiUrl/tv/$id/season/$sn", headers = apiHeaders)
                    .parsed<TmdbSeasonDetails>().episodes.mapNotNull { ep ->
                        val epNum = ep.episodeNumber ?: return@mapNotNull null
                        newEpisode("$mainUrl/tv/$id/$sn/$epNum") {
                            name = ep.name
                            episode = epNum
                            season = sn
                            posterUrl = ep.stillPath?.let { "$stillUrl$it" }
                            description = ep.overview
                            date = isoToEpoch(ep.airDate)
                        }
                    }
            } catch (e: Exception) {
                emptyList()
            }
        }.flatten()

        return newTvSeriesLoadResponse(
            d.name ?: d.originalName ?: "Unknown",
            "$mainUrl/tv/$id",
            d.tvType(),
            episodes,
        ) {
            posterUrl = d.posterPath?.let { "$imageUrl$it" }
            backgroundPosterUrl = d.backdropPath?.let { "$imageUrl$it" }
            plot = d.overview
            year = d.firstAirDate?.substringBefore('-')?.toIntOrNull()
            score = Score.from(d.rating, 10)
            tags = d.genres.mapNotNull { it.name }
            showStatus = if (d.status == "Ended" || d.status == "Canceled") ShowStatus.Completed else ShowStatus.Ongoing
        }
    }

    private fun TmdbTvDetails.tvType(): TvType =
        if (genres.any { it.id == 16 }) TvType.Anime else TvType.TvSeries

    private fun todayIso(): String {
        val cal = java.util.Calendar.getInstance()
        return "%04d-%02d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
    }

    private fun isoToEpoch(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            format.parse(iso)?.time
        }.getOrNull()
    }

    private suspend fun getEmbedUrls(type: String, id: Int, season: Int?, episode: Int?): List<String> {
        val templates = mutableListOf<String>()
        try {
            val providers = app.get(embedProvidersUrl, headers = apiHeaders)
                .parsed<EmbedProvidersResponse>().providers
            providers.forEach { p ->
                val template = if (type == "movie") p.movie else p.tv
                if (!template.isNullOrBlank()) templates += template
            }
        } catch (e: Exception) {
            // Cloudflare-protected; fall back to the known default embed
        }
        if (templates.isEmpty()) {
            if (type == "movie") {
                templates += "https://vidlink.pro/movie/{id}"
            } else {
                templates += "https://vidlink.pro/tv/{id}/{s}/{e}"
            }
        }
        return templates.map { t ->
            t.replace("{id}", id.toString())
                .replace("{s}", (season ?: 1).toString())
                .replace("{e}", (episode ?: 1).toString())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val (type, id, seasonEpisode) = parseDataUrl(data)
        val embeds = if (type == "movie") {
            getEmbedUrls("movie", id, null, null)
        } else {
            getEmbedUrls("tv", id, seasonEpisode?.first ?: 1, seasonEpisode?.second ?: 1)
        }

        var found = false
        for (embed in embeds.distinct()) {
            try {
                if (loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)) found = true
            } catch (e: Exception) {
                // try next embed
            }
        }
        return found
    }
}
