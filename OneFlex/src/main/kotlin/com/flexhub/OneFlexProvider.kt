package com.flexhub

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// ============================================================
//  SHARED INFRASTRUCTURE (file-scoped, shared by all family sites)
// ============================================================

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

/** Preferred quality order when no user preference matches. Lower index wins. */
private val QUALITY_ORDER = listOf(720, 1080, 2160, 1440, 480, 360, 240)

private val appPrefs: SharedPreferences?
    get() = try {
        CommonActivity.activity?.getSharedPreferences("OneFlexPrefs", Context.MODE_PRIVATE)
    } catch (_: Exception) {
        null
    }

private fun prefInt(key: String, default: Int): Int =
    appPrefs?.getInt(key, default) ?: default

/**
 * Resolved-link cache shared by every family site (10-minute TTL, 64 entries).
 * Keys include the site name because emitted link labels carry it.
 */
private val linkCache = object : LruCache<String, Pair<Long, List<ExtractorLink>>>(64) {}

private fun cacheGet(k: String): List<ExtractorLink>? =
    linkCache.get(k)?.takeIf { it.first > System.currentTimeMillis() }?.second

private fun cachePut(k: String, links: List<ExtractorLink>) {
    if (links.isNotEmpty()) {
        linkCache.put(k, System.currentTimeMillis() + 10 * 60_000L to links)
    }
}

/** Fire-and-forget scope so subtitle work never delays video start. */
private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * One provider instance per 1flex-family site (fubuki's multi-server stack:
 * Next.js frontends over shared TMDB proxies + the api.wecollege.net player API).
 * Metadata and playback are shared; only the site name and main URL differ.
 */
open class FlexhubFamilyProvider(override var name: String, override var mainUrl: String) : MainAPI() {
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val instantLinkLoading = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val apiUrl = "https://db.1flex.org"
    private val vidyApi = "https://api.wecollege.net"
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
        @JsonProperty("imdb_id") val imdbId: String? = null,
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
        @JsonProperty("imdb_id") val imdbId: String? = null,
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
            val data = mediaDataJson(
                MediaData(
                    id = id,
                    type = "movie",
                    title = d.title ?: d.originalTitle,
                    year = d.releaseDate?.substringBefore('-')?.toIntOrNull(),
                    imdb = d.imdbId,
                )
            )
            return newMovieLoadResponse(
                d.title ?: d.originalTitle ?: "Unknown",
                "$mainUrl/movie/$id",
                TvType.Movie,
                data,
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
                        val epData = mediaDataJson(
                            MediaData(
                                id = id,
                                type = "tv",
                                title = d.name ?: d.originalName,
                                year = d.firstAirDate?.substringBefore('-')?.toIntOrNull(),
                                imdb = d.imdbId,
                                season = sn,
                                episode = epNum,
                            )
                        )
                        newEpisode(epData) {
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

    // --- Playback: 1flex family player API (api.wecollege.net, as used by vidy.st) ---

    private data class MediaData(
        @JsonProperty("id") val id: Int,
        @JsonProperty("type") val type: String,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("imdb") val imdb: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
    )

    private data class WecollegeSeed(
        @JsonProperty("seed") val seed: String? = null,
    )

    private data class WecollegeSource(
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("url") val url: String? = null,
    )

    private data class WecollegeSubtitle(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("language") val language: String? = null,
    )

    private data class WecollegePayload(
        @JsonProperty("sources") val sources: List<WecollegeSource> = emptyList(),
        @JsonProperty("subtitles") val subtitles: List<WecollegeSubtitle> = emptyList(),
        @JsonProperty("playlist") val playlist: String? = null,
    )

    private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

    private fun mediaDataJson(data: MediaData): String = mapper.writeValueAsString(data)

    private fun vidyHeaders(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Accept" to "*/*",
        "Referer" to "https://vidy.st/",
        "Origin" to "https://vidy.st",
    )

    /**
     * Port of the decryption used by the 1flex/vidy.st family player
     * (obfuscated JS: custom FNV-seeded keystream + "mvm1" magic prefix).
     */
    private object VidyCipher {
        private val K = longArrayOf(
            1116352408L, 1899447441L, 3049323471L, 3921009573L, 961987163L, 1508970993L,
            2453635748L, 2870763221L, 3624381080L, 310598401L, 607225278L, 1426881987L,
            1925078388L, 2162078206L, 2614888103L, 3248222580L,
        )

        private fun imul(a: Int, b: Int): Int = (a.toLong() * b.toLong()).toInt()

        private fun m(e0: Int): Int {
            var e = e0
            e = e xor (e ushr 16)
            e = imul(e, 2246822507.toInt())
            e = e xor (e ushr 13)
            e = imul(e, 3266489909.toInt())
            e = e xor (e ushr 16)
            return e
        }

        private fun p(e0: Int, t0: Int): Int {
            val t = t0 and 31
            if (t == 0) return e0
            return (e0 shl t) or (e0 ushr (32 - t))
        }

        private class State {
            val s = IntArray(61)
            val assigned = BooleanArray(61)
            var acc = 0
        }

        private fun makeState(seed: String, mediaId: String): State {
            val st = State()
            var t = 2166136261.toInt()
            for (ch in seed) t = imul(t xor ch.code, 16777619)
            t = m(t)
            val mediaU = mediaId.trim().toLongOrNull() ?: 0L
            var s = m(t xor m(mediaU.toInt() xor 2654435769.toInt()))
            for (e in 0 until 8) {
                val tt = (s.toLong() and 0xFFFFFFFFL).rem(61).toInt()
                s = p(s + 2654435769.toInt(), 7 + (7 and e))
                st.s[tt] = s xor m(s)
                st.assigned[tt] = true
                s = m(s + tt)
            }
            st.acc = m(2779096485.toInt() xor s)
            return st
        }

        private fun next(st: State, counter: Int): Int {
            val d = (st.acc.toLong() and 0xFFFFFFFFL).rem(61).toInt()
            val exists = st.assigned[d]
            val r = if (exists) -1 else 0
            val i = if (exists) st.s[d] else 0
            val s = i xor imul(2654435769.toInt(), counter + 1)
            val l = (st.acc xor s) or ((st.acc and s) and r)
            val a = p(l + st.acc, 31 and d)
            val b = p(st.acc, 31 and imul(d, 7))
            val n = m((a xor b) + 2654435769.toInt())
            st.s[d] = n
            st.assigned[d] = true
            st.acc = n
            return n
        }

        private fun keystream(seed: String, mediaId: String, len: Int): ByteArray {
            val st = makeState(seed, mediaId)
            val out = ByteArray(len)
            var i = 0
            var counter = 0
            while (i < len) {
                val v = next(st, counter++)
                out[i++] = (v and 255).toByte()
                if (i < len) out[i++] = ((v ushr 8) and 255).toByte()
                if (i < len) out[i++] = ((v ushr 16) and 255).toByte()
                if (i < len) out[i++] = ((v ushr 24) and 255).toByte()
            }
            return out
        }

        fun decrypt(payloadB64: String, seed: String, mediaId: String): String {
            val clean = payloadB64.trim().replace('-', '+').replace('_', '/')
            val pad = (4 - clean.length % 4) % 4
            val data = android.util.Base64.decode(clean + "=".repeat(pad), android.util.Base64.DEFAULT)
            val ks = keystream(seed, mediaId, data.size)
            for (i in data.indices) data[i] = (data[i].toInt() xor ks[i].toInt()).toByte()
            val ok = data.size >= 4 &&
                data[0] == 109.toByte() && data[1] == 118.toByte() &&
                data[2] == 109.toByte() && data[3] == 49.toByte()
            if (!ok) throw ErrorLoadingException("decrypt failed: bad seed or tampered payload")
            return String(data, 4, data.size - 4, Charsets.UTF_8)
        }
    }

    private suspend fun getVidyPayload(media: MediaData, city: String, seed: String): WecollegePayload? {
        val mediaIdStr = media.id.toString()
        val encodedTitle = java.net.URLEncoder.encode(media.title ?: "", "UTF-8")
        val query = buildString {
            append("title=").append(encodedTitle)
            append("&mediaType=").append(media.type)
            media.year?.let { append("&year=").append(it) }
            append("&episodeId=").append(media.episode ?: 1)
            append("&seasonId=").append(media.season ?: 1)
            append("&tmdbId=").append(media.id)
            media.imdb?.let { append("&imdbId=").append(it) }
            append("&enc=2&seed=").append(java.net.URLEncoder.encode(seed, "UTF-8"))
        }

        return try {
            val text = app.get("$vidyApi/$city/sources?$query", headers = vidyHeaders()).text
            VidyCipher.decrypt(text, seed, mediaIdStr).let { json ->
                runCatching { mapper.readValue(json, WecollegePayload::class.java) }.getOrNull()
            }?.takeIf { it.sources.isNotEmpty() || !it.playlist.isNullOrBlank() }
        } catch (e: Exception) {
            null
        }
    }

    // --------------------------------------------------------
    //  External backup resolvers — last resort when the vidy
    //  player API fails entirely. Each call is time-bounded so
    //  one dead provider can't block the others.
    // --------------------------------------------------------

    private fun httpHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Referer" to referer,
        "Accept" to "*/*",
    )

    private data class ExternalProvider(
        val tag: String,
        val servers: List<String>,
        val resolver: suspend (MediaData, String, Int) -> List<ExtractorLink>,
    )

    private val externalProviders: List<ExternalProvider> = listOf(
        ExternalProvider("Rivestream", listOf("quasar", "pulsar", "vega", "neutron", "orion")) { m, s, q -> resolveRivestream(m, s, q) },
        ExternalProvider("Vidlink", listOf("default", "alpha", "beta")) { m, s, q -> resolveVidlink(m, s, q) },
        ExternalProvider("Vidsrc", listOf("default")) { m, _, q -> resolveVidsrc(m, q) },
        ExternalProvider("Embed.su", listOf("default")) { m, _, q -> resolveEmbedSu(m, q) },
        ExternalProvider("2Embed", listOf("default")) { m, _, q -> resolve2Embed(m, q) },
        ExternalProvider("AutoEmbed", listOf("default")) { m, _, q -> resolveAutoEmbed(m, q) },
        ExternalProvider("Videasy", listOf("default")) { m, _, q -> resolveVideasy(m, q) },
        ExternalProvider("Smashy", listOf("default")) { m, _, q -> resolveSmashy(m, q) },
    )

    /** Follows the first <iframe> and pulls the first m3u8/mp4 out of it. */
    private suspend fun resolveEmbed(
        embedUrl: String,
        embedReferer: String,
        quality: Int,
        tag: String,
    ): ExtractorLink? = withTimeoutOrNull(6_000L) {
        try {
            val headers = httpHeaders(embedReferer)
            val html = app.get(embedUrl, headers = headers).text
            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
                .find(html)?.groupValues?.get(1) ?: return@withTimeoutOrNull null
            val iframeUrl = if (iframeSrc.startsWith("http")) iframeSrc
                else embedReferer.trimEnd('/') + "/" + iframeSrc.trimStart('/')
            val iframeHtml = app.get(iframeUrl, headers = headers).text
            val streamUrl = Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""")
                .find(iframeHtml)?.groupValues?.get(1)
                ?: Regex("""(https?://[^"'\s\\]+\.mp4[^"'\s\\]*)""")
                    .find(iframeHtml)?.groupValues?.get(1)
                ?: return@withTimeoutOrNull null
            newExtractorLink(name, "$name - $tag (${quality}p)", streamUrl) {
                this.quality = quality
                this.referer = embedReferer
                this.type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                            else ExtractorLinkType.VIDEO
                this.headers = headers
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolveRivestream(m: MediaData, service: String, q: Int): List<ExtractorLink> {
        val isMovie = m.type.equals("movie", ignoreCase = true)
        val api = buildString {
            append("https://www.rivestream.app/api/backendfetch?requestID=")
            append(if (isMovie) "movieVideoProvider" else "tvVideoProvider")
            append("&id=${m.id}&service=$service&secretKey=NzJiNjIxMjE=&proxyMode=undefined")
            if (!isMovie) {
                val s = m.season ?: return emptyList()
                val e = m.episode ?: return emptyList()
                append("&season=$s&episode=$e")
            }
        }
        return withTimeoutOrNull(6_000L) {
            val body = app.get(api, headers = httpHeaders("https://www.rivestream.app/")).text
            val url = Regex("\"file\"\\s*:\\s*\"(https?[^\"]+)\"")
                .find(body)?.groupValues?.get(1) ?: return@withTimeoutOrNull emptyList()
            listOf(newExtractorLink(name, "$name - Rivestream ($service ${q}p)", url) {
                this.quality = q
                this.referer = "https://www.rivestream.app/"
                this.type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                this.headers = httpHeaders("https://www.rivestream.app/")
            })
        } ?: emptyList()
    }

    private suspend fun resolveVidlink(m: MediaData, server: String, q: Int): List<ExtractorLink> {
        val url = if (m.type.equals("movie", ignoreCase = true))
            "https://vidlink.pro/api/b/movie/${m.id}"
        else {
            val s = m.season ?: return emptyList()
            val e = m.episode ?: return emptyList()
            "https://vidlink.pro/api/b/tv/${m.id}/$s/$e"
        }
        return withTimeoutOrNull(6_000L) {
            val json = app.get(url, headers = httpHeaders("https://vidlink.pro/")).text
            val playlist = Regex("\"playlist\"\\s*:\\s*\"(https?[^\"]+)\"")
                .find(json)?.groupValues?.get(1)
                ?: Regex("\"file\"\\s*:\\s*\"(https?[^\"]+)\"")
                    .find(json)?.groupValues?.get(1)
                ?: return@withTimeoutOrNull emptyList()
            listOf(newExtractorLink(name, "$name - Vidlink ($server ${q}p)", playlist) {
                this.quality = q
                this.referer = "https://vidlink.pro/"
                this.type = ExtractorLinkType.M3U8
                this.headers = httpHeaders("https://vidlink.pro/")
            })
        } ?: emptyList()
    }

    private suspend fun resolveVidsrc(m: MediaData, q: Int): List<ExtractorLink> {
        val embed = if (m.type.equals("movie", ignoreCase = true))
            "https://vidsrc.to/embed/movie/${m.id}"
        else {
            val s = m.season ?: return emptyList()
            val e = m.episode ?: return emptyList()
            "https://vidsrc.to/embed/tv/${m.id}/$s/$e"
        }
        return listOfNotNull(resolveEmbed(embed, "https://vidsrc.to/", q, "Vidsrc"))
    }

    private suspend fun resolveEmbedSu(m: MediaData, q: Int): List<ExtractorLink> {
        val embed = if (m.type.equals("movie", ignoreCase = true))
            "https://embed.su/embed/movie/${m.id}"
        else {
            val s = m.season ?: return emptyList()
            val e = m.episode ?: return emptyList()
            "https://embed.su/embed/tv/${m.id}/$s/$e"
        }
        return listOfNotNull(resolveEmbed(embed, "https://embed.su/", q, "Embed.su"))
    }

    private suspend fun resolve2Embed(m: MediaData, q: Int): List<ExtractorLink> {
        val embed = if (m.type.equals("movie", ignoreCase = true))
            "https://www.2embed.cc/embed/${m.id}"
        else {
            val s = m.season ?: return emptyList()
            val e = m.episode ?: return emptyList()
            "https://www.2embed.cc/embedtv/${m.id}&s=$s&e=$e"
        }
        return listOfNotNull(resolveEmbed(embed, "https://www.2embed.cc/", q, "2Embed"))
    }

    private suspend fun resolveAutoEmbed(m: MediaData, q: Int): List<ExtractorLink> {
        val embed = if (m.type.equals("movie", ignoreCase = true))
            "https://player.autoembed.cc/embed/movie/${m.id}"
        else {
            val s = m.season ?: return emptyList()
            val e = m.episode ?: return emptyList()
            "https://player.autoembed.cc/embed/tv/${m.id}/$s/$e"
        }
        return listOfNotNull(resolveEmbed(embed, "https://player.autoembed.cc/", q, "AutoEmbed"))
    }

    private suspend fun resolveVideasy(m: MediaData, q: Int): List<ExtractorLink> {
        val embed = if (m.type.equals("movie", ignoreCase = true))
            "https://player.videasy.net/movie/${m.id}"
        else {
            val s = m.season ?: return emptyList()
            val e = m.episode ?: return emptyList()
            "https://player.videasy.net/tv/${m.id}/$s/$e"
        }
        return listOfNotNull(resolveEmbed(embed, "https://player.videasy.net/", q, "Videasy"))
    }

    private suspend fun resolveSmashy(m: MediaData, q: Int): List<ExtractorLink> {
        val embed = if (m.type.equals("movie", ignoreCase = true))
            "https://player.smashy.stream/movie/${m.id}"
        else {
            val s = m.season ?: return emptyList()
            val e = m.episode ?: return emptyList()
            "https://player.smashy.stream/tv/${m.id}?season=$s&episode=$e"
        }
        return listOfNotNull(resolveEmbed(embed, "https://player.smashy.stream/", q, "Smashy"))
    }

    /** Runs every external provider in parallel (hard 8s cap), sorted preferred-quality first. */
    private suspend fun resolveExternalFallback(media: MediaData, preferredQ: Int): List<ExtractorLink> {
        val raw = withTimeoutOrNull(8_000L) {
            externalProviders.amap { provider ->
                runCatching {
                    provider.servers.flatMap { server -> provider.resolver(media, server, preferredQ) }
                }.getOrElse { emptyList() }
            }
        }?.flatten() ?: emptyList()
        return raw.sortedBy { link ->
            if (link.quality == preferredQ) 0
            else QUALITY_ORDER.indexOf(link.quality).let { idx ->
                if (idx == -1) Int.MAX_VALUE else idx + 1
            }
        }
    }

    /** Subtitle-only vidy pass (used when playback came from external backups or cache). */
    private suspend fun fetchVidySubtitlesOnly(
        media: MediaData,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val seed = try {
            app.get("$vidyApi/seed?mediaId=${media.id}", headers = vidyHeaders())
                .parsed<WecollegeSeed>().seed
        } catch (_: Exception) {
            null
        } ?: return
        val seen = mutableSetOf<String>()
        for (city in listOf("miami", "boise", "atlanta")) {
            val payload = getVidyPayload(media, city, seed) ?: continue
            var emitted = false
            for (sub in payload.subtitles) {
                val url = sub.url ?: continue
                if (seen.add(url)) {
                    subtitleCallback(newSubtitleFile(sub.label ?: sub.language ?: "Subtitle", url))
                    emitted = true
                }
            }
            if (emitted) return
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val media = runCatching { mapper.readValue(data, MediaData::class.java) }.getOrNull() ?: return false
        val mediaIdStr = media.id.toString()
        val referer = "https://vidy.st/"
        val preferredQ = prefInt("quality", 720)

        // ---- Cache hit? (resolved links stay valid ~10 minutes) ----
        val cacheKey = "$name:${media.id}:${media.type}:${media.season}:${media.episode}:$preferredQ"
        cacheGet(cacheKey)?.let { cached ->
            cached.forEach { callback.invoke(it) }
            backgroundScope.launch {
                runCatching { fetchVidySubtitlesOnly(media, subtitleCallback) }
            }
            return true
        }

        // Preferred quality first, then QUALITY_ORDER, unknowns last.
        fun rank(q: Int): Int =
            if (q == preferredQ) 0
            else QUALITY_ORDER.indexOf(q).let { idx -> if (idx == -1) Int.MAX_VALUE else idx + 1 }

        val seed = try {
            app.get("$vidyApi/seed?mediaId=$mediaIdStr", headers = vidyHeaders())
                .parsed<WecollegeSeed>().seed
        } catch (e: Exception) {
            null
        }

        // Up to 3 city servers: the first exposes the full per-quality list
        // (direct media playlists = fast start), the rest are direct
        // fallback sources for CDN errors (ExoPlayer 2004).
        val cities = listOf("miami", "boise", "atlanta", "dallas", "seattle", "phoenix", "portland", "austin")
        val subtitles = linkedMapOf<String, SubtitleFile>()
        val links = mutableListOf<ExtractorLink>()
        var added = 0

        if (seed != null) {
            for (city in cities) {
                if (added >= 3) break
                val payload = getVidyPayload(media, city, seed) ?: continue
                val cityLabel = city.replaceFirstChar { it.uppercase() }
                val sorted = payload.sources
                    .filter { !it.url.isNullOrBlank() }
                    .sortedBy { src -> rank(src.quality?.trimEnd('p', 'P')?.toIntOrNull() ?: -1) }

                if (added == 0) {
                    // Primary city: expose every available quality.
                    for (src in sorted) {
                        val url = src.url ?: continue
                        links.add(
                            newExtractorLink(name, "$name - $cityLabel ${src.quality ?: "auto"}", url) {
                                this.quality = src.quality?.trimEnd('p', 'P')?.toIntOrNull()
                                    ?: Qualities.Unknown.value
                                this.referer = referer
                                this.type = ExtractorLinkType.M3U8
                                this.headers = vidyHeaders()
                            }
                        )
                    }
                } else {
                    // Alt cities: one direct link, preferred quality first.
                    val alt = sorted.firstOrNull() ?: continue
                    val url = alt.url ?: continue
                    links.add(
                        newExtractorLink(name, "$name - $cityLabel (Alt)", url) {
                            this.quality = alt.quality?.trimEnd('p', 'P')?.toIntOrNull()
                                ?: Qualities.Unknown.value
                            this.referer = referer
                            this.type = ExtractorLinkType.M3U8
                            this.headers = vidyHeaders()
                        }
                    )
                }
                added++

                for (sub in payload.subtitles) {
                    val url = sub.url ?: continue
                    if (!subtitles.containsKey(url)) {
                        subtitles[url] = newSubtitleFile(sub.label ?: sub.language ?: "Subtitle", url)
                    }
                }
            }
        }

        // ---- Safety net: external backup resolvers when vidy fails entirely ----
        if (links.isEmpty()) {
            links.addAll(resolveExternalFallback(media, preferredQ))
            if (links.isNotEmpty()) {
                backgroundScope.launch {
                    runCatching { fetchVidySubtitlesOnly(media, subtitleCallback) }
                }
            }
        }

        // ---- Subtitles in background (never delay video start) ----
        if (subtitles.isNotEmpty()) {
            val subs = subtitles.values.toList()
            backgroundScope.launch {
                subs.forEach { subtitleCallback.invoke(it) }
            }
        }

        if (links.isNotEmpty()) {
            links.forEach { callback.invoke(it) }
            cachePut(cacheKey, links)
        }

        return links.isNotEmpty()
    }
}


// Alt domains are noted per site; they serve identical catalogs, so each site
// is registered once. 1Shows / 1Tube / 1shows.bz are alts of 1Flex.
class OneFlexProvider : FlexhubFamilyProvider("1Flex (Flexhub)", "https://www.1flex.org") // alts: www.1shows.org, www.1tube.org, 1shows.bz
class AniCineProvider : FlexhubFamilyProvider("AniCine (Flexhub)", "https://anicine.sbs") // alt: anicine.xyz
class ZetMoonProvider : FlexhubFamilyProvider("ZetMoon (Flexhub)", "https://zetmoon.live")
class SixtySevenMoviesProvider : FlexhubFamilyProvider("67movies (Flexhub)", "https://67movies.st") // alt: phantomflix.net
class TonkaCineProvider : FlexhubFamilyProvider("TonkaCine (Flexhub)", "https://tonkacine.watch") // alt: multi.tonkacine.watch
class PrimeShowsProvider : FlexhubFamilyProvider("PrimeShows (Flexhub)", "https://www.primeshows.org") // alts: netshows.xyz, youshows.org
class MoovieProvider : FlexhubFamilyProvider("Moovie (Flexhub)", "https://moovie.fun") // alt: streamaggregator.in
class AniXxProvider : FlexhubFamilyProvider("AniXx (Flexhub)", "https://anixx.fun")
class AuroraScreenProvider : FlexhubFamilyProvider("AuroraScreen (Flexhub)", "https://aurorascreen.org")
class HydraHdProvider : FlexhubFamilyProvider("HydraHD (Flexhub)", "https://hydrahd.ws") // alt: hydrahd.com
class FireFlixProvider : FlexhubFamilyProvider("FireFlix (Flexhub)", "https://fireflix4.pages.dev")
class VidBoxProvider : FlexhubFamilyProvider("VidBox (Flexhub)", "https://vidbox.vc") // alts: cinehd.vc, hotflix.to
class ZenCineProvider : FlexhubFamilyProvider("ZenCine (Flexhub)", "https://zencine.org")
class CineWaveProvider : FlexhubFamilyProvider("CineWave (Flexhub)", "https://watch.cinewave.qzz.io")
class CineStreamProvider : FlexhubFamilyProvider("CineStream (Flexhub)", "https://cinestream.kje.us") // geo-blocked in some regions
class YouFlexProvider : FlexhubFamilyProvider("YouFlex (Flexhub)", "https://youflex.top")
class FilmCaveProvider : FlexhubFamilyProvider("FilmCave (Flexhub)", "https://flixway.ru") // alt: filmcave.ru
class FlixMixProvider : FlexhubFamilyProvider("FlixMix (Flexhub)", "https://flixmix.site")
class FlyStreamProvider : FlexhubFamilyProvider("FlyStream (Flexhub)", "https://flystream.net")
class StreamoProvider : FlexhubFamilyProvider("Streamo (Flexhub)", "https://streamo.pro")

// Additional multi-server sites (verified reachable, same TMDB catalog stack).
class BingeFlixProvider : FlexhubFamilyProvider("BingeFlix (Flexhub)", "https://bingeflix.tv")
class CinebyProvider : FlexhubFamilyProvider("Cineby (Flexhub)", "https://cinebytv.com")
class MeowlyProvider : FlexhubFamilyProvider("Meowly (Flexhub)", "https://meowly.qzz.io")
class OnoflixProvider : FlexhubFamilyProvider("Onoflix (Flexhub)", "https://onoflix.live")
class StreamGoblinProvider : FlexhubFamilyProvider("StreamGoblin (Flexhub)", "https://streamgoblin.cc")
class ZerostreamProvider : FlexhubFamilyProvider("Zerostream (Flexhub)", "https://zerostream.alwaysdata.net")
class SevenReelsProvider : FlexhubFamilyProvider("7Reels (Flexhub)", "https://7reels.cc")
class CandleStreamProvider : FlexhubFamilyProvider("CandleStream (Flexhub)", "https://candlestream.xyz")
class ErnaxProvider : FlexhubFamilyProvider("Ernax (Flexhub)", "https://ernax.pro")
class Ev01Provider : FlexhubFamilyProvider("Ev01 (Flexhub)", "https://ev01.ws")
class HeartiveProvider : FlexhubFamilyProvider("Heartive (Flexhub)", "https://heartivelovestv.pages.dev")
class PlayTorrioProvider : FlexhubFamilyProvider("PlayTorrio (Flexhub)", "https://playtorrio.pages.dev")
class WatchOrbitProvider : FlexhubFamilyProvider("WatchOrbit (Flexhub)", "https://watchorbit.me")
class FlickerProvider : FlexhubFamilyProvider("Flicker (Flexhub)", "https://flicker-mini.pages.dev")
class CineStream2Provider : FlexhubFamilyProvider("CineStream Alt (Flexhub)", "https://cinestream2.kje.us") // alt: cinestream.kje.us
