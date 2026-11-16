package com.flexhub

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.extractors.ByseSX
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// ============================================================
//  SHARED INFRASTRUCTURE (file-scoped, shared by all family sites)
// ============================================================

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

/** Preferred quality order when no user preference matches. Lower index wins. */
private val QUALITY_ORDER = listOf(720, 1080, 2160, 1440, 480, 360, 240)

/** Diagnostics for logcat (tag OneFlex) — never throws. */
private fun logDiag(message: String) {
    runCatching { android.util.Log.d("OneFlex", message) }
}

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
 * Per-site server templates, cached per process (site + media type).
 * Filled from each site's own source catalog or its JS bundle.
 */
private val siteTemplateCache = object : LruCache<String, List<SiteTemplate>>(64) {}

/** Hosts running the same player chain as vsembed.ru (vs_src.php + wasm decrypt). */
private val vidsrcFamilyHosts = setOf(
    "vsembed.ru", "vidsrc.me", "vidsrc.sh", "vidsrc.to", "vidsrc.in", "vidsrc.link",
    "vidsrc.net", "vidsrc.cc", "vidsrc.pro", "vidsrc.party", "vidsrc.life", "vidsrc.lol",
    "vidsrc.pm", "vidsrc.rip", "vidsrc.ch", "vidsrc.ws", "vidsrc.bz", "vidsrc.one",
    "vidsrc.live", "vidsrc.tv", "vidsrc.mov", "vidsrc.top", "vidsrc.click",
    "vidsrc-embed.ru", "vixsrc.to",
)

/**
 * One server template of a site.
 *  - [url] is either a full template (".../movie/{id}") or, when [isPrefix],
 *    a base the site appends the id to (".../movie/").
 *  - [isTv] marks the templates that belong to series instead of movies.
 *  - [idType], [urlFormat] and [extraParams] carry the server descriptor
 *    format used by the Next.js sites ("id/season/episode" + query flags).
 */
private data class SiteTemplate(
    val label: String,
    val url: String,
    val isPrefix: Boolean = false,
    val isTv: Boolean = false,
    val idType: String? = null,
    val urlFormat: String? = null,
    val extraParams: String? = null,
)

/** One entry of a site's own server list (api.viduki.net/embed_providers). */
private data class SiteProvider(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("movie") val movie: String? = null,
    @JsonProperty("tv") val tv: String? = null,
)

/** Response of the shared server-list endpoint. */
private data class SiteProviderList(
    @JsonProperty("site") val site: String? = null,
    @JsonProperty("providers") val providers: List<SiteProvider> = emptyList(),
)

/** One server of the vidrock multi-server API. */
private data class VidrockServer(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("type") val type: String? = null,
)

/** Decodes base64 that may be URL-safe, un-padded or contain whitespace. */
private fun decodeBase64Flexible(value: String): ByteArray? = runCatching {
    val clean = value.trim()
        .filter { !it.isWhitespace() }
        .replace('-', '+')
        .replace('_', '/')
    val pad = (4 - clean.length % 4) % 4
    android.util.Base64.decode(clean + "=".repeat(pad), android.util.Base64.DEFAULT)
}.getOrNull()

/**
 * Decryption used by the vidrock player stack (AES-256-GCM, key baked into
 * the site bundle). Payload layout: base64url(iv[12] || ciphertext || tag[16]).
 */
private object VidrockCipher {
    private const val KEY_HEX =
        "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"

    private fun hexBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }
        return out
    }

    fun decrypt(encrypted: String): String? {
        val raw = decodeBase64Flexible(encrypted) ?: return null
        if (raw.size < 28) return null
        val iv = raw.copyOfRange(0, 12)
        val body = raw.copyOfRange(12, raw.size)
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(hexBytes(KEY_HEX), "AES"),
                GCMParameterSpec(128, iv),
            )
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }
}

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

    // City servers in the player's own order (extracted from the site bundle);
    // unknown cities 404, so this list is authoritative.
    private val vidyCities = listOf(
        "miami", "boise", "seattle", "atlanta", "phoenix", "portland", "austin",
        "dallas", "chicago", "nashville", "vegas", "reno", "oakland",
        "munich", "berlin", "paris", "delhi", "cancun",
    )

    /** Set when a vidy response comes back as an HTML block page (Cloudflare). */
    @Volatile
    private var vidyBlockedHit = false

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
            val trimmed = text.trimStart()
            if (trimmed.startsWith("<")) {
                // Cloudflare WAF or error page, not ciphertext. Flag it so the
                // city sweep stops hammering the WAF and falls back early.
                vidyBlockedHit = true
                return null
            }
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
        ExternalProvider("Vsembed", listOf("default")) { m, _, q -> resolveVsembed(m, q) },
        ExternalProvider("OneFlex", listOf("self")) { m, _, q -> resolveRivestreamEmbed(m, q) },
        ExternalProvider("OneFlex", listOf("prime")) { m, _, q -> resolveRivestreamProvider(m, q) },
    )


    private data class VsembedApiData(
        @JsonProperty("file_name") val fileName: String? = null,
        // String (encrypted, protection on) or List<String> (plain, protection off)
        @JsonProperty("stream_urls") val streamUrls: Any? = null,
    )

    private data class VsembedVs(
        @JsonProperty("wasm_url") val wasmUrl: String? = null,
        @JsonProperty("wasm") val wasm: String? = null,
    )

    private data class VsembedApiResponse(
        @JsonProperty("data") val data: VsembedApiData? = null,
        @JsonProperty("vs") val vs: VsembedVs? = null,
    )

    /** Decodes base64 that may be URL-safe, un-padded or contain whitespace. */
    private fun decodeB64(value: String): ByteArray? = runCatching {
        val clean = value.trim()
            .filter { !it.isWhitespace() }
            .replace('-', '+')
            .replace('_', '/')
        val pad = (4 - clean.length % 4) % 4
        android.util.Base64.decode(clean + "=".repeat(pad), android.util.Base64.DEFAULT)
    }.getOrNull()

    /**
     * Vsembed chain (the vidsrc.sh family player, fully verified live):
     *  1. vsembed.ru embed page -> <iframe data-api="/vs_src.php?...">
     *  2. vs_src.php -> {"src": "https://<host>/embed/{movie,tv}/...?vs=..."}
     *  3. outer embed page (Referer = vsembed embed URL) -> CFG.playerUrl
     *  4. player page (Referer = outer URL) -> CONFIG: api/streamBase + apiToken
     *  5. metaApi -> data.stream_urls_api_host (pool host)
     *  6. stream_urls API + &api_token= (single-use) -> encrypted blob + vs.wasm_url
     *  7. WASM decrypt (VsWasm interpreter) -> newline URL list
     *  8. <stream host>/generate.php?api_token= -> playlist JWT (?token= covers
     *     the whole playlist chain; variant/segment URLs come back pre-stamped)
     */
    private suspend fun resolveVsembed(
        m: MediaData,
        q: Int,
        base: String = "https://vsembed.ru",
        label: String = "Vsembed",
    ): List<ExtractorLink> = withTimeoutOrNull(25_000L) {
        try {
            val embedUrl = if (m.type.equals("movie", ignoreCase = true))
                "$base/embed/movie/${m.id}"
            else {
                val s = m.season ?: return@withTimeoutOrNull emptyList()
                val e = m.episode ?: return@withTimeoutOrNull emptyList()
                "$base/embed/tv/${m.id}/$s/$e"
            }

            // Step 1: embed page -> data-api attr (HTML-escaped &)
            val embedHeaders = mapOf(
                "User-Agent" to UA,
                "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
                "Referer" to "$base/",
            )
            val embedRes = app.get(embedUrl, headers = embedHeaders)
            logDiag("vsembed embed page ${embedRes.code} $embedUrl")
            val embedHtml = embedRes.text
            val dataApi = Regex("""data-api=["']([^"']+)["']""")
                .find(embedHtml)?.groupValues?.get(1)?.replace("&" + "amp;", "&")
                ?: return@withTimeoutOrNull emptyList()
            val vsSrcUrl = if (dataApi.startsWith("http")) dataApi else "$base$dataApi"

            // Step 2: vs_src.php -> {"src": "..."} with exact embed referer
            val srcRes = app.get(vsSrcUrl, headers = httpHeaders(embedUrl))
            logDiag("vsembed vs_src ${srcRes.code} $vsSrcUrl")
            val srcJson = srcRes.text
            val innerUrl = Regex(""""src"\s*:\s*"([^"]+)"""")
                .find(srcJson)?.groupValues?.get(1)
                ?: return@withTimeoutOrNull emptyList()

            // Step 3: outer embed page -> CFG.playerUrl
            val outerRes = app.get(innerUrl, headers = httpHeaders(embedUrl))
            logDiag("vsembed outer ${outerRes.code} $innerUrl")
            val outerHtml = outerRes.text
            val playerPath = Regex(""""playerUrl"\s*:\s*"([^"]+)"""")
                .find(outerHtml)?.groupValues?.get(1)?.replace("\\u0026", "&")
                ?: return@withTimeoutOrNull emptyList()
            val playerUrl = resolveRelativeUrl(innerUrl, playerPath)
            val playerOrigin = runCatching { java.net.URI(playerUrl).let { "${it.scheme}://${it.host}" } }
                .getOrDefault("https://stellarconductornexus.com")

            // Step 4: player page -> CONFIG (api for movies, streamBase for tv) + apiToken
            val playerRes = app.get(playerUrl, headers = httpHeaders(innerUrl))
            logDiag("vsembed player ${playerRes.code} $playerUrl")
            val playerHtml = playerRes.text
            val cfg = Regex("""CONFIG\s*=\s*(\{.*?\})\s*;""").find(playerHtml)?.groupValues?.get(1)
                ?: return@withTimeoutOrNull emptyList()

            fun cfgString(key: String): String? =
                Regex(""""$key"\s*:\s*"([^"]*)""").find(cfg)?.groupValues?.get(1)?.replace("\\u0026", "&")

            val apiToken = cfgString("apiToken") ?: return@withTimeoutOrNull emptyList()
            val streamBase = cfgString("api") ?: run {
                val base = cfgString("streamBase") ?: return@withTimeoutOrNull emptyList()
                if (m.type.equals("movie", ignoreCase = true)) "${base}&stream_urls"
                else "${base}&season=${m.season ?: 1}&episode=${m.episode ?: 1}&stream_urls"
            }
            val metaApi = cfgString("metaApi")

            // Step 5: metadata first (captures pool host), like bootWithMeta does
            var poolHost: String? = null
            if (metaApi != null) {
                runCatching {
                    val metaJson = app.get(metaApi, headers = httpHeaders(playerUrl)).text
                    poolHost = Regex(""""stream_urls_api_host"\s*:\s*"([^"]+)"""")
                        .find(metaJson)?.groupValues?.get(1)
                }
            }

            // Step 6: stream_urls request against the pool host (origin swap only)
            val requestUrl = if (!poolHost.isNullOrBlank()) {
                runCatching {
                    val u = java.net.URI(streamBase)
                    "${u.scheme}://$poolHost${u.rawPath}?${u.rawQuery}"
                }.getOrDefault(streamBase)
            } else streamBase
            val apiHeaders = mapOf(
                "User-Agent" to UA,
                "Accept" to "application/json",
                "Referer" to playerUrl,
                "Origin" to playerOrigin,
            )
            val apiRes = app.get("$requestUrl&api_token=$apiToken", headers = apiHeaders)
            logDiag("vsembed stream_urls ${apiRes.code} $requestUrl")
            val apiJson = apiRes.text

            val apiResponse = runCatching { mapper.readValue(apiJson, VsembedApiResponse::class.java) }
                .getOrNull()
                ?: return@withTimeoutOrNull emptyList()

            // stream_urls is a plain array when protection is off, or a single
            // encrypted base64 string (nonce[12] || ciphertext) when on.
            val urls: List<String> = when (val raw = apiResponse.data?.streamUrls) {
                is List<*> -> raw.filterIsInstance<String>().filter { it.startsWith("http") }
                is String -> {
                    val wasmUrl = apiResponse.vs?.wasmUrl
                    val wasmInlineB64 = apiResponse.vs?.wasm
                    if (wasmUrl.isNullOrBlank() && wasmInlineB64.isNullOrBlank()) emptyList()
                    else {
                        val wasmBytes = if (!wasmUrl.isNullOrBlank())
                            app.get(wasmUrl, headers = mapOf("User-Agent" to UA)).body?.bytes()
                        else null
                        val inlineBytes = wasmInlineB64?.let { decodeB64(it) }
                        val module = wasmBytes ?: inlineBytes
                        if (module == null) emptyList()
                        else {
                            val encBytes = decodeB64(raw)
                            if (encBytes == null) emptyList()
                            else runCatching { VsWasm.decrypt(module, encBytes) }.getOrNull()
                                ?.let { out ->
                                    String(out, Charsets.UTF_8)
                                        .split("\n").map { it.trim() }.filter { it.startsWith("http") }
                                }
                                ?: emptyList()
                        }
                    }
                }
                else -> emptyList()
            }
            if (urls.isEmpty()) {
                logDiag("vsembed: no stream urls decrypted")
                return@withTimeoutOrNull emptyList()
            }
            logDiag("vsembed decrypted ${urls.size} urls")

            // Step 8: per-host playlist token (JWT) from the stream host
            val streamHost = runCatching { java.net.URI(urls.first()).let { "${it.scheme}://${it.host}" } }
                .getOrDefault(poolHost?.let { "https://$it" } ?: "")
            val playlistToken = runCatching {
                val tokText = app.get(
                    "$streamHost/generate.php?api_token=$apiToken",
                    headers = mapOf(
                        "User-Agent" to UA,
                        "Referer" to playerUrl,
                        "Origin" to playerOrigin,
                        "Accept" to "*/*",
                    ),
                ).text.trim()
                if (tokText.startsWith("{")) Regex(""""token"\s*:\s*"([^"]+)"""")
                    .find(tokText)?.groupValues?.get(1) ?: tokText
                else tokText
            }.getOrDefault("")
            logDiag("vsembed token length ${playlistToken.length} host $streamHost")

            val fileName = apiResponse.data?.fileName ?: ""
            val parsedQ = Regex("""(\d{3,4})p""").find(fileName)?.groupValues?.get(1)?.toIntOrNull() ?: q
            val labelQ = if (parsedQ in 240..2160) parsedQ else Qualities.Unknown.value

            // The stream hosts sit behind a CDN that rejects browser-less requests:
            // the playlist (and every segment) needs the player's Referer/Origin.
            val headerSets = listOf(
                mapOf(
                    "User-Agent" to UA,
                    "Referer" to playerUrl,
                    "Origin" to playerOrigin,
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site",
                ),
                mapOf("User-Agent" to UA, "Referer" to playerUrl, "Origin" to playerOrigin),
                mapOf("User-Agent" to UA, "Referer" to playerUrl),
                mapOf("User-Agent" to UA, "Origin" to playerOrigin),
                mapOf("User-Agent" to UA, "Referer" to innerUrl),
            )

            val candidates = urls.map { url ->
                when {
                    url.contains("__TOKEN__") -> url.replace("__TOKEN__", playlistToken)
                    playlistToken.isBlank() -> url
                    url.contains('?') -> "$url&token=$playlistToken"
                    else -> "$url?token=$playlistToken"
                }
            }

            // Probe once to learn which header set the CDN accepts, reuse for all URLs.
            var workingHeaders: Map<String, String>? = null
            for (headers in headerSets) {
                val probe = runCatching { app.get(candidates.first(), headers = headers) }.getOrNull()
                val code = probe?.code ?: -1
                val body = probe?.text?.trimStart().orEmpty()
                logDiag("vsembed probe ${headers.keys} -> $code")
                if (code == 200 && body.startsWith("#EXTM3U")) {
                    workingHeaders = headers
                    break
                }
            }
            val emitHeaders = workingHeaders ?: headerSets.first()
            if (workingHeaders == null) logDiag("vsembed: no header set accepted, emitting best effort")

            candidates.mapIndexedNotNull { idx, finalUrl ->
                val verified = workingHeaders != null && verifyUpstreamChain(finalUrl, emitHeaders)
                if (!verified) logDiag("vsembed link $idx not verified: $finalUrl")
                newExtractorLink(
                    "OneFlex",
                    "OneFlex - $label ${idx + 1} (${labelQ}p)",
                    finalUrl,
                ) {
                    this.quality = labelQ
                    this.referer = playerUrl
                    this.type = ExtractorLinkType.M3U8
                    this.headers = emitHeaders
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    } ?: emptyList()







    private data class RivestreamSource(
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("format") val format: String? = null,
    )

    private data class RivestreamResponse(
        @JsonProperty("data") val data: RivestreamData? = null,
    )

    private data class RivestreamData(
        @JsonProperty("sources") val sources: List<RivestreamSource> = emptyList(),
        @JsonProperty("captions") val captions: List<Map<String, String>> = emptyList(),
    )

    private fun queryParams(raw: String): Map<String, String> =
        raw.split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null
            else runCatching {
                java.net.URLDecoder.decode(part.substring(0, i), "UTF-8") to
                    java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
            }.getOrNull()
        }.toMap()

    /**
     * Rivestream wraps every stream in proxy.valhallastream.com, which rejects
     * direct access (403). The real upstream is hidden inside:
     * proxy?url=<inner>&headers=...  where inner may carry u=<base64 upstream>.
     */
    private fun rivestreamUpstream(proxyUrl: String): String? = runCatching {
        val outer = queryParams(java.net.URI(proxyUrl).rawQuery ?: "")
        val inner = outer["url"] ?: return null
        val innerQuery = runCatching { java.net.URI(inner).rawQuery }.getOrNull()
        if (innerQuery.isNullOrBlank()) return inner
        val b64 = queryParams(innerQuery)["u"] ?: return inner
        val padded = b64.replace(" ", "+").replace('-', '+').replace('_', '/') +
            "=".repeat((4 - b64.length % 4) % 4)
        val decoded = String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.UTF_8)
        if (decoded.startsWith("http")) decoded else inner
    }.getOrNull()

    private data class RivestreamEmbedSource(
        @JsonProperty("host") val host: String? = null,
        @JsonProperty("link") val link: String? = null,
    )

    private data class RivestreamEmbedData(
        @JsonProperty("sources") val sources: List<RivestreamEmbedSource> = emptyList(),
    )

    private data class RivestreamEmbedResponse(
        @JsonProperty("data") val data: RivestreamEmbedData? = null,
    )

    /**
     * The "self" provider lists file-host embeds (byse.sx family, streamtape).
     * The bundled CloudStream extractors decode them to direct streams.
     */
    private suspend fun resolveRivestreamEmbed(m: MediaData, q: Int): List<ExtractorLink> {
        val url = if (m.type.equals("movie", ignoreCase = true))
            "https://scrapper.rivestream.app/api/embed?provider=self&id=${m.id}"
        else {
            val s = m.season ?: return emptyList()
            val e = m.episode ?: return emptyList()
            "https://scrapper.rivestream.app/api/embed?provider=self&id=${m.id}&season=$s&episode=$e"
        }
        return withTimeoutOrNull(15_000L) {
            try {
                val json = app.get(url, headers = httpHeaders("https://www.rivestream.app/")).text
                val response = mapper.readValue(json, RivestreamEmbedResponse::class.java)
                val sources = response.data?.sources ?: return@withTimeoutOrNull emptyList()

                val perSource: List<List<Pair<ExtractorLink, Pair<String, Int>>>> = sources.amap { src ->
                    val link = src.link ?: return@amap emptyList()
                    val host = runCatching { java.net.URI(link).host }.getOrDefault("")
                    if (host.isBlank()) return@amap emptyList()
                    val parsedQ = src.host?.let {
                        Regex("""(\d{3,4})""").find(it)?.groupValues?.get(1)?.toIntOrNull()
                    } ?: -1
                    val serverName = src.host?.substringBefore("-")?.replaceFirstChar { it.uppercase() }
                        ?: host.substringBefore(".")
                    val collected = mutableListOf<ExtractorLink>()
                    runCatching {
                        when {
                            host.contains("streamtape") ->
                                StreamTape().getUrl(link).orEmpty().forEach { collected.add(it) }
                            host.contains("byse") ->
                                ByseSX().getUrl(link, "", { _ -> }, { collected.add(it) })
                        }
                    }
                    collected.map { it to (serverName to parsedQ) }
                }

                perSource.flatten().map { (el, meta) ->
                    val (serverName, parsedQ) = meta
                    val qLabel = if (parsedQ > 0) "(${parsedQ}p)" else "(auto)"
                    newExtractorLink("OneFlex", "OneFlex - $serverName $qLabel", el.url) {
                        this.quality = if (parsedQ in 1..2160) parsedQ else Qualities.Unknown.value
                        this.referer = el.referer
                        this.type = el.type
                        this.headers = el.headers
                        this.extractorData = el.extractorData
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        } ?: emptyList()
    }

    private fun resolveRelativeUrl(base: String, url: String): String =
        if (url.startsWith("http")) url
        else runCatching { java.net.URI(base).resolve(url).toString() }.getOrDefault(url)

    private val imageMagic: (ByteArray) -> Boolean = { head ->
        val png = head.size >= 4 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte()
        val gif = head.size >= 3 && head[0] == 0x47.toByte() && head[1] == 0x49.toByte()
        val jpg = head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
        val riff = head.size >= 4 && head[0] == 0x52.toByte() && head[1] == 0x49.toByte() &&
            head[2] == 0x46.toByte() && head[3] == 0x46.toByte()
        png || gif || jpg || riff
    }

    private data class PlaylistCheck(
        val ok: Boolean,
        val isMaster: Boolean,
        val lines: List<String> = emptyList(),
    )

    private suspend fun playlistCheck(
        url: String,
        headers: Map<String, String> = mapOf("User-Agent" to UA),
    ): PlaylistCheck {
        val res = runCatching { app.get(url, headers = headers) }.getOrNull()
            ?: return PlaylistCheck(false, false)
        if (res.code != 200) return PlaylistCheck(false, false)
        val text = res.text.trimStart()
        if (!text.startsWith("#EXTM3U")) return PlaylistCheck(false, false)
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.isEmpty()) return PlaylistCheck(false, false)
        return PlaylistCheck(true, text.contains("#EXT-X-STREAM-INF"), lines)
    }

    /**
     * Verifies the FULL playback chain: master -> (variant) -> first segment.
     * Some upstreams serve ad-image playlists whose segments all return 4xx or
     * are literally PNG/JPEG ad slides — those break the player with error
     * 2004, so they must never be exposed.
     */
    private suspend fun verifyUpstreamChain(
        upstream: String,
        headers: Map<String, String> = mapOf("User-Agent" to UA),
    ): Boolean = runCatching {
        val master = playlistCheck(upstream, headers)
        if (!master.ok) return@runCatching false

        var playlistUrl = upstream
        var segments = master.lines
        if (master.isMaster) {
            val variantUrl = resolveRelativeUrl(upstream, master.lines.first())
            val variant = playlistCheck(variantUrl, headers)
            if (!variant.ok) return@runCatching false
            playlistUrl = variantUrl
            segments = variant.lines
        }

        val segUrl = resolveRelativeUrl(playlistUrl, segments.first())
        val seg = app.get(segUrl, headers = headers + ("Range" to "bytes=0-2047"))
        if (seg.code != 200 && seg.code != 206) return@runCatching false
        val head = seg.body?.bytes() ?: return@runCatching false
        if (head.isEmpty()) return@runCatching false
        !imageMagic(head)
    }.getOrDefault(false)

    private suspend fun resolveRivestreamProvider(m: MediaData, q: Int): List<ExtractorLink> {
        val url = if (m.type.equals("movie", ignoreCase = true))
            "https://scrapper.rivestream.app/api/provider?provider=prime&id=${m.id}"
        else {
            val s = m.season ?: return emptyList()
            val e = m.episode ?: return emptyList()
            "https://scrapper.rivestream.app/api/provider?provider=prime&id=${m.id}&season=$s&episode=$e"
        }
        return withTimeoutOrNull(15_000L) {
            try {
                val json = app.get(url, headers = httpHeaders("https://www.rivestream.app/")).text
                val response = mapper.readValue(json, RivestreamResponse::class.java)
                val sources = response.data?.sources ?: return@withTimeoutOrNull emptyList()

                // upstream -> (server name, quality), first quality wins per upstream
                val candidates = linkedMapOf<String, Pair<String, Int>>()
                for (src in sources) {
                    val upstream = rivestreamUpstream(src.url ?: continue) ?: continue
                    val quality = src.quality?.trimEnd('p', 'P')?.toIntOrNull() ?: q
                    if (!candidates.containsKey(upstream)) {
                        candidates[upstream] = (src.source ?: "Server") to quality
                    }
                }

                // Verify every playback chain before exposing it.
                val candidateList: List<Pair<String, Pair<String, Int>>> = candidates.toList()
                val verified: List<Pair<String, Pair<String, Int>>> = candidateList.amap { candidate ->
                    if (verifyUpstreamChain(candidate.first)) listOf(candidate) else emptyList()
                }.flatten()

                verified.map { link ->
                    val upstream = link.first
                    val server = link.second.first
                    val quality = link.second.second
                    newExtractorLink("OneFlex", "OneFlex - $server (${quality}p)", upstream) {
                        this.quality = quality
                        this.referer = ""
                        this.type = ExtractorLinkType.M3U8
                        this.headers = mapOf("User-Agent" to UA)
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        } ?: emptyList()
    }

    // --------------------------------------------------------
    //  Per-site servers — THIS provider's own server list,
    //  fetched from the site itself: either the JSON source
    //  catalog it publishes or the templates it hardcodes in
    //  its own JS bundle. Every family site keeps its own
    //  list, so each provider serves its own servers first.
    // --------------------------------------------------------

    private data class SiteSourceEntry(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("movieUrl") val movieUrl: String? = null,
        @JsonProperty("tvUrl") val tvUrl: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("id") val id: String? = null,
    )

    private data class SiteSourcesResponse(
        @JsonProperty("sources") val sources: List<SiteSourceEntry>? = null,
    )

    private fun siteHeaders(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl.trimEnd('/'),
    )

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host?.lowercase() }.getOrNull().orEmpty()

    private fun watchPageUrl(media: MediaData): String =
        if (media.type.equals("movie", ignoreCase = true)) "$mainUrl/watch/movie/${media.id}"
        else "$mainUrl/watch/tv/${media.id}/${media.season ?: 1}/${media.episode ?: 1}"

    /** Site keys the shared server-list endpoint may know this site under. */
    private fun siteKeyCandidates(): List<String> {
        val out = LinkedHashSet<String>()
        val brand = name.substringBefore(" (").lowercase().replace(Regex("[^a-z0-9]+"), "")
        if (brand.isNotBlank()) out.add(brand)
        val host = hostOf(mainUrl)
        if (host.isNotBlank()) {
            host.split(".").filter { it != "www" && it.length > 2 }.forEach { out.add(it) }
        }
        return out.toList()
    }

    /**
     * The sites of this family keep their own server list on the shared
     * endpoint api.viduki.net/embed_providers?site=<key>. The endpoint only
     * answers for the site origin that owns the key, so the request carries
     * that origin and a small set of key candidates is tried.
     */
    private suspend fun fetchSiteProviders(): List<SiteProvider> {
        val origin = mainUrl.trimEnd('/')
        for (key in siteKeyCandidates()) {
            val res = runCatching {
                app.get(
                    "https://api.viduki.net/embed_providers?site=$key",
                    headers = mapOf(
                        "User-Agent" to UA,
                        "Accept" to "application/json",
                        "Referer" to "$origin/",
                        "Origin" to origin,
                    ),
                )
            }.getOrNull() ?: continue
            if (res.code != 200) continue
            val parsed = runCatching {
                mapper.readValue(res.text, SiteProviderList::class.java)
            }.getOrNull() ?: continue
            if (parsed.providers.isEmpty()) continue
            return parsed.providers
        }
        return emptyList()
    }

    /**
     * Server group of the vidrock stack: every site that embeds vidrock uses
     * this API, which returns the server names with AES-GCM encrypted
     * playlist urls.
     */
    private suspend fun resolveVidrockServers(
        media: MediaData,
        onLink: (ExtractorLink) -> Unit,
    ): List<ExtractorLink> {
        val isMovie = media.type.equals("movie", ignoreCase = true)
        val apiUrl = if (isMovie) "https://vidrock.net/api/movie/${media.id}"
        else "https://vidrock.net/api/tv/${media.id}/${media.season ?: 1}/${media.episode ?: 1}"
        val res = runCatching {
            app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Accept" to "application/json",
                    "Referer" to "https://vidrock.to/",
                ),
            )
        }.getOrNull() ?: return emptyList()
        if (res.code != 200) return emptyList()
        val servers = runCatching {
            mapper.readValue(res.text, object : TypeReference<Map<String, VidrockServer>>() {})
        }.getOrNull() ?: return emptyList()

        val out = mutableListOf<ExtractorLink>()
        for ((serverName, server) in servers) {
            val encrypted = server.url ?: continue
            if (encrypted.isBlank()) continue
            val playlist = VidrockCipher.decrypt(encrypted) ?: continue
            if (!playlist.startsWith("http")) continue
            val headers = mapOf(
                "User-Agent" to UA,
                "Referer" to "https://vidrock.to/",
                "Origin" to "https://vidrock.to",
            )
            if (!verifyUpstreamChain(playlist, headers)) {
                logDiag("vidrock server $serverName not reachable")
                continue
            }
            val label = serverName
            val link = newExtractorLink(name, "$name - Vidrock $label", playlist) {
                this.quality = Qualities.Unknown.value
                this.referer = "https://vidrock.to/"
                this.type = ExtractorLinkType.M3U8
                this.headers = headers
            }
            out.add(link)
            onLink.invoke(link)
        }
        logDiag("vidrock servers ${out.size}/${servers.size} from $apiUrl")
        return out
    }

    /** Pulls the site's own server templates. */
    private suspend fun discoverSiteTemplates(media: MediaData): List<SiteTemplate> {
        val cacheKey = "$mainUrl:${media.type}"
        siteTemplateCache.get(cacheKey)?.let { return it }

        val found = LinkedHashMap<String, SiteTemplate>()
        val isMovie = media.type.equals("movie", ignoreCase = true)

        // 0. sites that publish their server list as a JSON catalog keyed by site
        runCatching {
            val providers = fetchSiteProviders()
            if (providers.isNotEmpty()) {
                for (provider in providers) {
                    val raw = if (isMovie) provider.movie else provider.tv
                    if (raw.isNullOrBlank()) continue
                    val template = SiteTemplate(
                        label = provider.label ?: provider.id ?: hostOf(raw),
                        url = raw,
                        isTv = !isMovie,
                    )
                    if (!found.containsKey(raw)) found[raw] = template
                }
                logDiag("site providers ${found.size} servers from api.viduki.net")
            }
        }.onFailure { logDiag("site providers failed: ${it.message}") }

        // 1. sites that publish their server catalog as JSON
        if (found.isEmpty()) {
            runCatching {
                val res = app.get("$mainUrl/api/sources/cached", headers = siteHeaders())
                if (res.code == 200 && res.text.trimStart().startsWith("{")) {
                    val parsed = mapper.readValue(res.text, SiteSourcesResponse::class.java)
                    for (src in parsed.sources ?: emptyList()) {
                        val tpl = if (isMovie) (src.movieUrl ?: src.url) else (src.tvUrl ?: src.url)
                        if (tpl.isNullOrBlank()) continue
                        val template = SiteTemplate(
                            label = src.name ?: src.id ?: hostOf(tpl),
                            url = tpl,
                            isTv = tpl.contains("/tv/") || tpl.contains("{season}"),
                        )
                        if (!found.containsKey(tpl)) found[tpl] = template
                    }
                    logDiag("site catalog ${found.size} sources from $mainUrl")
                }
            }.onFailure { logDiag("site catalog api failed: ${it.message}") }
        }

        // 2. sites that hardcode their server templates in their JS bundle
        if (found.isEmpty()) {
            runCatching {
                val page = app.get(watchPageUrl(media), headers = siteHeaders())
                if (page.code != 200) {
                    logDiag("site watch page ${page.code} $mainUrl")
                    return@runCatching
                }
                val scripts = Regex("""<script[^>]+src=["']([^"']+)["']""")
                    .findAll(page.text).map { it.groupValues[1] }.distinct().take(20).toList()
                for (src in scripts) {
                    val url = if (src.startsWith("http")) src
                    else "${mainUrl.trimEnd('/')}/${src.trimStart('/')}"
                    val js = runCatching { app.get(url, headers = siteHeaders()) }.getOrNull() ?: continue
                    if (js.code != 200) continue
                    val descriptors = extractServerDescriptors(js.text)
                    for (template in descriptors) {
                        val key = "${template.label}|${template.url}"
                        if (!found.containsKey(key)) found[key] = template
                    }
                    val descriptorUrls = descriptors.map { it.url }.toSet()
                    for (template in extractSiteTemplates(js.text)) {
                        if (descriptorUrls.contains(template.url)) continue
                        if (!found.containsKey(template.url)) found[template.url] = template
                    }
                    if (found.size >= 8) break
                }
                logDiag("site bundle scan ${found.size} templates from $mainUrl")
            }.onFailure { logDiag("site bundle scan failed: ${it.message}") }
        }

        val list = found.values.toList()
        if (list.isNotEmpty()) siteTemplateCache.put(cacheKey, list)
        return list
    }

    /** Template urls found inside a site's JS bundle. */
    private fun extractSiteTemplates(js: String): List<SiteTemplate> {
        val out = LinkedHashMap<String, SiteTemplate>()

        for (m in Regex("""["'](https?://[^"'\s]{4,200}\{id\}[^"'\s]{0,200})["']""").findAll(js)) {
            val tpl = m.groupValues[1]
            if (!out.containsKey(tpl)) {
                out[tpl] = SiteTemplate(
                    label = hostOf(tpl),
                    url = tpl,
                    isTv = tpl.contains("/tv/") || tpl.contains("{season}"),
                )
            }
        }

        for (m in Regex("""["'`](https?://[^"'`\s]{4,180}/(?:movie|tv)/)["'`]\s*\.concat\(""").findAll(js)) {
            val base = m.groupValues[1]
            if (!out.containsKey(base)) {
                out[base] = SiteTemplate(
                    label = hostOf(base),
                    url = base,
                    isPrefix = true,
                    isTv = base.contains("/tv/"),
                )
            }
        }

        return out.values.toList()
    }

    /**
     * Server descriptors of the Next.js sites: the bundle keeps the player's
     * server list as {name, url, idType, urlFormat, extraParams} objects, the
     * same list the site's own server dropdown shows.
     */
    private fun extractServerDescriptors(js: String): List<SiteTemplate> {
        val out = LinkedHashMap<String, SiteTemplate>()
        val pattern = Regex(
            """\{\s*name\s*:\s*"([^"]+)"\s*,\s*url\s*:\s*"([^"]+)"(?:[^}]*?idType\s*:\s*"([^"]*)")?(?:[^}]*?urlFormat\s*:\s*"([^"]*)")?(?:[^}]*?extraParams\s*:\s*"([^"]*)")?"""
        )
        for (m in pattern.findAll(js)) {
            val label = m.groupValues[1]
            val url = m.groupValues[2]
            val idType = m.groupValues[3].ifBlank { null }
            val urlFormat = m.groupValues[4].ifBlank { null }
            val extraParams = m.groupValues[5].ifBlank { null }
            if (urlFormat == null) continue
            val key = "$label|$url"
            if (out.containsKey(key)) continue
            out[key] = SiteTemplate(
                label = label,
                url = url,
                isTv = url.contains("/tv/") || urlFormat.contains("season"),
                idType = idType,
                urlFormat = urlFormat,
                extraParams = extraParams,
            )
        }
        return out.values.toList()
    }

    private fun buildTemplateUrl(template: SiteTemplate, media: MediaData): String {
        val id = media.id.toString()
        val season = (media.season ?: 1).toString()
        val episode = (media.episode ?: 1).toString()

        if (template.urlFormat != null) {
            val idPart = if (template.idType == "imdb") (media.imdb ?: id) else id

            fun fillPath(text: String): String = text
                .replace("{id}", idPart)
                .replace("{season}", season)
                .replace("{episode}", episode)
                .replace(Regex("(?<![A-Za-z0-9_])id(?![A-Za-z0-9_])"), idPart)
                .replace(Regex("(?<![A-Za-z0-9_])season(?![A-Za-z0-9_])"), season)
                .replace(Regex("(?<![A-Za-z0-9_])episode(?![A-Za-z0-9_])"), episode)

            fun fillQuery(text: String): String = text
                .replace("{id}", idPart)
                .replace("{season}", season)
                .replace("{episode}", episode)

            fun splitQuery(text: String): Pair<String, String?> {
                val at = text.indexOf('?')
                return if (at >= 0) text.substring(0, at) to text.substring(at + 1) else text to null
            }

            val (basePathRaw, baseQueryRaw) = splitQuery(template.url)
            val (formatPathRaw, formatQueryRaw) = splitQuery(template.urlFormat)
            var formatPath = formatPathRaw
            val formatFlags = mutableListOf<String>()
            val amp = formatPath.indexOf('&')
            if (amp >= 0) {
                formatFlags += formatPath.substring(amp + 1)
                formatPath = formatPath.substring(0, amp)
            }

            val baseHasPlaceholder = basePathRaw.contains("{id}") ||
                basePathRaw.contains("{season}") ||
                (baseQueryRaw?.contains("{id}") == true)
            val path = if (baseHasPlaceholder) {
                // the base already carries the placeholder, only the remaining
                // path segments of the format are appended
                val extra = formatPath.split("/").filter { it.isNotBlank() && it != "id" }
                val filled = fillPath(basePathRaw)
                if (extra.isEmpty()) filled
                else "${filled.trimEnd('/')}/${fillPath(extra.joinToString("/"))}"
            } else {
                "${fillPath(basePathRaw).trimEnd('/')}/${fillPath(formatPath).trimStart('/')}"
            }

            val queryParts = mutableListOf<String>()
            baseQueryRaw?.let { queryParts += fillQuery(it) }
            formatQueryRaw?.let { queryParts += fillQuery(it) }
            formatFlags.forEach { queryParts += fillQuery(it) }
            template.extraParams?.let { extra ->
                val body = extra.removePrefix("?")
                if (body.isNotBlank()) queryParts += fillQuery(body)
            }
            var url = path
            val usable = queryParts.filter { it.isNotBlank() }
            if (usable.isNotEmpty()) url += "?" + usable.joinToString("&")
            return url
        }

        var url = if (template.isPrefix) {
            if (template.isTv) "${template.url}$id/$season/$episode" else "${template.url}$id"
        } else {
            template.url
                .replace("{id}", id)
                .replace("{s}", season)
                .replace("{e}", episode)
                .replace("{season}", season)
                .replace("{episode}", episode)
        }
        url = Regex("""\{[a-zA-Z_][a-zA-Z0-9_]*\}""").replace(url, "")
        url = url.replace("%23", "")
        url = url.replace("?&", "?").replace("&&", "&")
        if (url.endsWith("?") || url.endsWith("&")) url = url.dropLast(1)
        return url
    }

    /** First playlist url inside a player page (raw or JSON encoded). */
    private fun extractStreamUrl(text: String): String? {
        val normalized = text.replace("\\/", "/").replace("\\u002F", "/").replace("\\u0026", "&")
        return Regex("""(?:https?:)?//[^"'\\\s<>]+?\.m3u8[^"'\\\s<>]*""")
            .findAll(normalized)
            .map { m ->
                var v = m.value.trimEnd(')', ',', ';', '\\')
                if (v.startsWith("//")) v = "https:$v"
                v
            }
            .firstOrNull()
    }

    /** Resolves the site's own servers into verified playback links. */
    private suspend fun resolveSiteServers(
        media: MediaData,
        preferredQ: Int,
        onLink: (ExtractorLink) -> Unit = {},
    ): List<ExtractorLink> {
        val resolved = mutableListOf<ExtractorLink>()

        // Server group the sites embed through vidrock: every entry is a
        // playlist of the site's own player stack.
        resolved.addAll(
            withTimeoutOrNull(20_000L) { resolveVidrockServers(media, onLink) }.orEmpty()
        )

        val templates = withTimeoutOrNull(12_000L) { discoverSiteTemplates(media) } ?: emptyList()
        if (templates.isEmpty()) {
            logDiag("site servers resolved ${resolved.size} from $mainUrl")
            return resolved
        }
        val isMovie = media.type.equals("movie", ignoreCase = true)
        val preferred = templates.filter { !it.isTv }
        val ordered = if (isMovie) preferred + templates.filter { it.isTv }
        else templates.filter { it.isTv } + preferred
        // vidrock servers are already resolved as their own group, vidy is
        // covered by the dedicated vidy tier
        val picked = ordered.filter {
            val host = hostOf(it.url)
            !host.contains("vidrock") && !host.contains("vidy")
        }.take(12)
        val extra = coroutineScope {
            picked.map { template ->
                async {
                    resolveSiteTemplate(template, media, preferredQ).also { found ->
                        found.forEach { onLink.invoke(it) }
                    }
                }
            }.awaitAll()
        }.flatten()
        resolved.addAll(extra)
        logDiag("site servers resolved ${resolved.size}/${picked.size + 1} from $mainUrl")
        return resolved
    }

    private suspend fun resolveSiteTemplate(
        template: SiteTemplate,
        media: MediaData,
        preferredQ: Int,
    ): List<ExtractorLink> = withTimeoutOrNull(15_000L) {
        try {
            val label = template.label
            val host = hostOf(template.url)
            if (host in vidsrcFamilyHosts) {
                val chain = resolveVsembed(media, preferredQ, "https://$host", label)
                if (chain.isNotEmpty()) return@withTimeoutOrNull chain
            }

            val url = buildTemplateUrl(template, media)
            val page = runCatching { app.get(url, headers = siteHeaders()) }.getOrNull()
                ?: return@withTimeoutOrNull emptyList()
            if (page.code != 200) return@withTimeoutOrNull emptyList()

            var streamUrl = extractStreamUrl(page.text)
            var streamReferer = url
            if (streamUrl == null) {
                val iframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
                    .find(page.text)?.groupValues?.get(1)
                if (!iframe.isNullOrBlank()) {
                    val iframeUrl = if (iframe.startsWith("http")) iframe
                    else "${mainUrl.trimEnd('/')}/${iframe.trimStart('/')}"
                    val inner = runCatching {
                        app.get(iframeUrl, headers = siteHeaders() + ("Referer" to url))
                    }.getOrNull()
                    if (inner != null && inner.code == 200) {
                        streamUrl = extractStreamUrl(inner.text)
                        streamReferer = iframeUrl
                    }
                }
            }
            val target = streamUrl ?: return@withTimeoutOrNull emptyList()

            val refererHost = hostOf(streamReferer)
            val headerSets = buildList {
                add(mapOf("User-Agent" to UA, "Referer" to streamReferer))
                if (refererHost.isNotBlank()) {
                    add(mapOf("User-Agent" to UA, "Referer" to streamReferer, "Origin" to "https://$refererHost"))
                }
                add(mapOf("User-Agent" to UA))
            }
            var working: Map<String, String>? = null
            for (headers in headerSets) {
                if (verifyUpstreamChain(target, headers)) {
                    working = headers
                    break
                }
            }
            val emitHeaders = working ?: return@withTimeoutOrNull emptyList()
            logDiag("site source $label -> $target")

            listOf(
                newExtractorLink(name, "$name - $label", target) {
                    this.quality = Qualities.Unknown.value
                    this.referer = streamReferer
                    this.type = ExtractorLinkType.M3U8
                    this.headers = emitHeaders
                }
            )
        } catch (_: Exception) {
            emptyList()
        }
    } ?: emptyList()

    private suspend fun resolveExternalFallback(media: MediaData, preferredQ: Int): List<ExtractorLink> {
        val raw = withTimeoutOrNull(60_000L) {
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
        for (city in vidyCities) {
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

        // Every city server: the first successful one exposes the full
        // per-quality list (direct media playlists = fast start), every
        // other city adds one direct fallback link for CDN errors (2004).
        val subtitles = linkedMapOf<String, SubtitleFile>()
        val links = mutableListOf<ExtractorLink>()
        var added = 0

        // This site's own servers run beside vidy and stream their links out as
        // soon as each server resolves, so they never delay the other links.
        val siteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val siteEmitted = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val siteJob = siteScope.async {
            runCatching {
                resolveSiteServers(media, preferredQ) { link ->
                    runCatching {
                        siteEmitted.add(link)
                        callback.invoke(link)
                    }
                }
            }.getOrDefault(emptyList())
        }

        if (seed != null) withTimeoutOrNull(20_000L) {
            vidyBlockedHit = false
            for (city in vidyCities) {
                if (vidyBlockedHit) break
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
                    // v7 feature: the API can answer with a master playlist only.
                    // Enumerate its variants into one link per quality; if that
                    // fails, expose the master playlist itself.
                    val master = payload.playlist
                    if (!master.isNullOrBlank()) {
                        val variants = runCatching {
                            M3u8Helper.generateM3u8(
                                source = "$name - $cityLabel",
                                streamUrl = master,
                                referer = referer,
                            )
                        }.getOrNull()
                        if (!variants.isNullOrEmpty()) {
                            for (v in variants) {
                                links.add(
                                    newExtractorLink(name, "$name - $cityLabel ${v.quality}p", v.url) {
                                        this.quality = v.quality
                                        this.referer = referer
                                        this.type = ExtractorLinkType.M3U8
                                        this.headers = vidyHeaders()
                                    }
                                )
                            }
                        } else {
                            links.add(
                                newExtractorLink(name, "$name - $cityLabel (Auto)", master) {
                                    this.quality = Qualities.Unknown.value
                                    this.referer = referer
                                    this.type = ExtractorLinkType.M3U8
                                    this.headers = vidyHeaders()
                                }
                            )
                        }
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

        // ---- Tier 0: this site's own servers (site catalog / bundle) ----
        // Their links were already handed to the callback as they resolved, so
        // they are only collected here when nothing else was found.
        val siteLinks = try {
            if (links.isEmpty()) {
                withTimeoutOrNull(20_000L) { siteJob.await() }.orEmpty()
            } else {
                emptyList()
            }
        } finally {
            if (links.isEmpty()) runCatching { siteScope.cancel() }
        }
        if (siteLinks.isNotEmpty()) {
            logDiag("loadLinks tier0 site servers -> ${siteLinks.size}")
        }

        // ---- Tier 2: external backup resolvers when vidy fails ----
        if (links.isEmpty()) {
            links.addAll(resolveExternalFallback(media, preferredQ))
            logDiag("loadLinks tier2 external -> ${links.size}")
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

        return links.isNotEmpty() || siteEmitted.isNotEmpty()
    }
}


// Alt domains are noted per site; they serve identical catalogs, so each site
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
