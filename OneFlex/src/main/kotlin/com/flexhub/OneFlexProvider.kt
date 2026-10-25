package com.flexhub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val media = runCatching { mapper.readValue(data, MediaData::class.java) }.getOrNull() ?: return false
        val mediaIdStr = media.id.toString()
        val referer = "https://vidy.st/"

        val seed = try {
            app.get("$vidyApi/seed?mediaId=$mediaIdStr", headers = vidyHeaders())
                .parsed<WecollegeSeed>().seed
        } catch (e: Exception) {
            null
        } ?: return false

        // Multiple city servers = alternative sources; up to 3 so the player
        // can fall back when one CDN path errors (ExoPlayer 2004).
        val cities = listOf("miami", "boise", "atlanta", "dallas", "seattle", "phoenix", "portland", "austin")
        val subtitleUrls = mutableSetOf<String>()
        var found = false
        var added = 0

        for (city in cities) {
            if (added >= 3) break
            val payload = getVidyPayload(media, city, seed) ?: continue
            val sourceName = "${name} - ${city.replaceFirstChar { it.uppercase() }}"
            val streamUrl = payload.playlist ?: payload.sources.firstNotNullOfOrNull { it.url } ?: continue

            val links = try {
                M3u8Helper.generateM3u8(
                    source = sourceName,
                    streamUrl = streamUrl,
                    referer = referer,
                )
            } catch (e: Exception) {
                null
            }

            if (links.isNullOrEmpty()) {
                // Fallback: emit the raw links if variant enumeration failed
                payload.sources.forEach { src ->
                    val url = src.url ?: return@forEach
                    callback.invoke(
                        newExtractorLink(name, "$sourceName (${src.quality ?: "auto"})", url) {
                            this.quality = src.quality?.trimEnd('p', 'P')?.toIntOrNull() ?: Qualities.Unknown.value
                            this.referer = referer
                            this.type = ExtractorLinkType.M3U8
                        }
                    )
                    found = true
                }
                payload.playlist?.let { master ->
                    callback.invoke(
                        newExtractorLink(name, "$sourceName (Auto)", master) {
                            this.referer = referer
                            this.type = ExtractorLinkType.M3U8
                        }
                    )
                    found = true
                }
            } else {
                links.forEach { callback.invoke(it) }
                found = true
            }
            added++

            for (sub in payload.subtitles) {
                val url = sub.url ?: continue
                if (subtitleUrls.add(url)) {
                    subtitleCallback.invoke(newSubtitleFile(sub.label ?: sub.language ?: "Subtitle", url))
                }
            }
        }

        return found
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
