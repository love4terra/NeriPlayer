package moe.ouom.neriplayer.platform.gdmusic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.platform.gdmusic.GdMusicRateLimiter.RequestTier
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

data class GdMusicSearchTrack(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String = "",
    val source: String = "joox",
    val durationMs: Long = 0L
)

data class GdMusicResolvedUrl(
    val url: String,
    val source: String,
    val actualBitrate: Int,
    val qualityLevel: String,
    val sizeBytes: Long = 0L,
    val headers: Map<String, String> = emptyMap()
)

/**
 * GD Studio 音乐 API 客户端（https://music-api.gdstudio.xyz/api.php）。
 * 支持网易云直连取流、多挡位音质降级、Joox 同歌名/歌手精确匹配回退与 5 分钟限流保护。
 */
class GdMusicClient(
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val rateLimiter: GdMusicRateLimiter = GdMusicRateLimiter()
) {
    companion object {
        private const val TAG = "NERI-GdMusic"
        const val DEFAULT_BASE_URL = "https://music-api.gdstudio.xyz/api.php"
        const val DEFAULT_REFERER = "https://music.gdstudio.xyz/"
        const val DEFAULT_ORIGIN = "https://music.gdstudio.xyz"
        const val NETEASE_MEDIA_REFERER = "https://music.163.com/"
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun normalizePreferredQuality(preferredQuality: String?): String {
            return when (preferredQuality?.trim()?.lowercase(Locale.ROOT)) {
                "jymaster", "sky", "jyeffect", "hires", "lossless", "flac", "999", "740" -> "lossless"
                "exhigh", "hq", "320" -> "exhigh"
                "higher", "192" -> "higher"
                "standard", "128" -> "standard"
                else -> "exhigh"
            }
        }

        fun bitrateLadderForQuality(preferredQuality: String?): List<Int> {
            return when (preferredQuality?.trim()?.lowercase(Locale.ROOT)) {
                "jymaster", "sky", "jyeffect", "hires", "lossless", "flac", "999", "740" -> listOf(999, 320, 192)
                "exhigh", "hq", "320" -> listOf(320, 192, 128)
                "higher", "192" -> listOf(192, 320, 128)
                "standard", "128" -> listOf(128, 192)
                else -> listOf(320, 192, 128)
            }
        }

        fun qualityLevelForBitrate(bitrate: Int): String = when {
            bitrate >= 740 -> "lossless"
            bitrate >= 320 -> "exhigh"
            bitrate >= 192 -> "higher"
            else -> "standard"
        }

        fun isNeteaseCdnUrl(rawUrl: String?): Boolean {
            val host = rawUrl?.trim()?.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT) ?: return false
            return host == "music.126.net" ||
                host.endsWith(".music.126.net") ||
                host == "126.net" ||
                host.endsWith(".126.net") ||
                host == "163.com" ||
                host.endsWith(".163.com")
        }

        fun normalizeMediaUrl(rawUrl: String?): String? {
            val trimmed = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val lower = trimmed.lowercase(Locale.ROOT)
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
            if (lower.contains("/404")) return null
            return if (lower.startsWith("http://") && isNeteaseCdnUrl(trimmed)) {
                "https://${trimmed.substring(7)}"
            } else {
                trimmed
            }
        }

        fun mediaHeadersForUrl(rawUrl: String?): Map<String, String> {
            return if (isNeteaseCdnUrl(rawUrl)) {
                mapOf(
                    "Referer" to NETEASE_MEDIA_REFERER,
                    "User-Agent" to BROWSER_USER_AGENT
                )
            } else {
                emptyMap()
            }
        }
    }

    suspend fun resolveNeteaseUrl(
        songId: Long,
        title: String,
        artist: String,
        durationMs: Long = 0L,
        preferredQuality: String = "exhigh",
        allowJooxFallback: Boolean = true,
        isPrefetch: Boolean = false
    ): GdMusicResolvedUrl? = withContext(Dispatchers.IO) {
        if (songId <= 0L) return@withContext null
        val normalizedQuality = normalizePreferredQuality(preferredQuality)
        val cacheKey = "netease:$songId:$normalizedQuality"
        rateLimiter.getCachedUrl(cacheKey)?.let { return@withContext it }

        if (rateLimiter.isInCooldown()) {
            NPLogger.d(TAG, "GD Music处于429冷却中，跳过 songId=$songId")
            return@withContext null
        }

        val neteaseTier = if (isPrefetch) RequestTier.PREFETCH else RequestTier.PRIMARY
        val directUrl = fetchTrackUrlWithLadder(
            source = "netease",
            trackId = songId.toString(),
            bitrates = bitrateLadderForQuality(preferredQuality),
            tier = neteaseTier
        )
        if (directUrl != null) {
            rateLimiter.putCachedUrl(cacheKey, directUrl)
            return@withContext directUrl
        }

        if (!allowJooxFallback || title.isBlank() || rateLimiter.isInCooldown()) {
            return@withContext null
        }

        val jooxTier = if (isPrefetch) RequestTier.PREFETCH else RequestTier.SECONDARY_FALLBACK
        val matched = searchMatchingJooxTrack(
            title = title,
            artist = artist,
            durationMs = durationMs,
            tier = jooxTier
        ) ?: return@withContext null

        val jooxLadder = bitrateLadderForQuality(preferredQuality)
            .map { br -> if (br > 320) 320 else br }
            .distinct()
        val jooxUrl = fetchTrackUrlWithLadder(
            source = "joox",
            trackId = matched.id,
            bitrates = jooxLadder,
            tier = jooxTier
        ) ?: return@withContext null

        rateLimiter.putCachedUrl(cacheKey, jooxUrl)
        jooxUrl
    }

    private fun fetchTrackUrlWithLadder(
        source: String,
        trackId: String,
        bitrates: List<Int>,
        tier: RequestTier
    ): GdMusicResolvedUrl? {
        for ((index, bitrate) in bitrates.withIndex()) {
            if (rateLimiter.isInCooldown()) return null
            val effectiveTier = if (index > 0 && tier == RequestTier.PRIMARY) RequestTier.PRIMARY_RETRY else tier
            if (!rateLimiter.tryAcquireSlot(effectiveTier)) return null

            val responseBody = executeApiGet(
                mapOf(
                    "types" to "url",
                    "source" to source,
                    "id" to trackId,
                    "br" to bitrate.toString()
                )
            ) ?: continue

            val parsed = parseResolvedUrl(responseBody, source = source, requestedBitrate = bitrate)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun searchMatchingJooxTrack(
        title: String,
        artist: String,
        durationMs: Long,
        tier: RequestTier
    ): GdMusicSearchTrack? {
        val targetArtists = GdMusicTextMatcher.splitArtists(artist)
        val cacheKey = buildString {
            append(GdMusicTextMatcher.compactText(title))
            append('|')
            append(targetArtists.joinToString(",") { GdMusicTextMatcher.compactText(it) })
        }
        val candidates = rateLimiter.getCachedJooxSearch(cacheKey) ?: run {
            if (!rateLimiter.tryAcquireSlot(tier)) return null
            val cleanTitle = GdMusicTextMatcher.stripParentheticals(title).ifBlank { title }.trim()
            val primaryArtist = targetArtists.firstOrNull().orEmpty()
            val keyword = listOf(cleanTitle, primaryArtist)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val body = executeApiGet(
                mapOf(
                    "types" to "search",
                    "source" to "joox",
                    "name" to keyword,
                    "count" to "10",
                    "pages" to "1"
                )
            ) ?: return null
            val parsed = parseSearchTracks(body, source = "joox")
            rateLimiter.putCachedJooxSearch(cacheKey, parsed)
            parsed
        }

        if (candidates.isEmpty()) return null
        val target = GdMusicTextMatcher.SongMatchTarget(
            title = title,
            artists = targetArtists,
            durationMs = durationMs
        )
        val wrapped = candidates.map { track ->
            GdMusicTextMatcher.SongMatchCandidate(
                item = track,
                title = track.title,
                artists = track.artists,
                durationMs = track.durationMs
            )
        }
        return GdMusicTextMatcher.findBestMatch(
            target = target,
            candidates = wrapped,
            maxDurationDiffMs = 12_000L,
            requireArtistWhenPresent = targetArtists.isNotEmpty()
        )
    }

    private fun executeApiGet(params: Map<String, String>): String? {
        val urlBuilder = baseUrl.toHttpUrl().newBuilder()
        params.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .header("Referer", DEFAULT_REFERER)
            .header("Origin", DEFAULT_ORIGIN)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()
                    rateLimiter.recordRateLimited(retryAfter)
                    NPLogger.w(TAG, "GD Music触发HTTP 429限流 retryAfter=$retryAfter")
                    return null
                }
                if (!response.isSuccessful) {
                    NPLogger.w(TAG, "GD Music HTTP失败 code=${response.code}")
                    return null
                }
                response.body.string()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (io: IOException) {
            NPLogger.w(TAG, "GD Music网络请求异常: ${io.message}")
            null
        } catch (t: Exception) {
            NPLogger.w(TAG, "GD Music请求异常", t)
            null
        }
    }

    internal fun parseResolvedUrl(
        rawBody: String,
        source: String,
        requestedBitrate: Int
    ): GdMusicResolvedUrl? {
        val trimmed = rawBody.trim()
        if (!trimmed.startsWith("{")) return null
        val root = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            return null
        }
        val payload = root.optJSONObject("data") ?: root
        val normalizedUrl = normalizeMediaUrl(payload.optString("url", "")) ?: return null
        val actualBitrate = payload.optInt("br", requestedBitrate).takeIf { it > 0 } ?: requestedBitrate
        val sizeBytes = payload.optLong("size", 0L).coerceAtLeast(0L)
        return GdMusicResolvedUrl(
            url = normalizedUrl,
            source = source,
            actualBitrate = actualBitrate,
            qualityLevel = qualityLevelForBitrate(actualBitrate),
            sizeBytes = sizeBytes,
            headers = mediaHeadersForUrl(normalizedUrl)
        )
    }

    internal fun parseSearchTracks(rawBody: String, source: String): List<GdMusicSearchTrack> {
        val trimmed = rawBody.trim()
        val array: JSONArray = when {
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
            trimmed.startsWith("{") -> runCatching {
                val obj = JSONObject(trimmed)
                obj.optJSONArray("data") ?: obj.optJSONArray("result")
            }.getOrNull()
            else -> null
        } ?: return emptyList()

        val result = ArrayList<GdMusicSearchTrack>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.opt("id")?.toString()?.trim().orEmpty()
            val title = item.optString("name", "").trim()
            if (id.isEmpty() || title.isEmpty()) continue
            val artists = extractArtists(item.opt("artist"))
            val album = item.optString("album", "").trim()
            val durationMs = parseDurationMs(item)
            result.add(
                GdMusicSearchTrack(
                    id = id,
                    title = title,
                    artists = artists,
                    album = album,
                    source = item.optString("source", source).ifBlank { source },
                    durationMs = durationMs
                )
            )
        }
        return result
    }

    private fun extractArtists(rawArtist: Any?): List<String> {
        return when (rawArtist) {
            is JSONArray -> buildList {
                for (i in 0 until rawArtist.length()) {
                    val item = rawArtist.opt(i)
                    val name = when (item) {
                        is JSONObject -> item.optString("name", "").trim()
                        else -> item?.toString()?.trim().orEmpty()
                    }
                    if (name.isNotEmpty()) add(name)
                }
            }
            is String -> GdMusicTextMatcher.splitArtists(rawArtist)
            else -> emptyList()
        }
    }

    private fun parseDurationMs(item: JSONObject): Long {
        val dt = item.optLong("dt", 0L)
        if (dt > 0L) return dt
        val duration = item.optLong("duration", 0L)
        if (duration > 0L) {
            return if (duration <= 3_600L) duration * 1000L else duration
        }
        val time = item.optLong("time", 0L)
        if (time > 0L) {
            return if (time <= 3_600L) time * 1000L else time
        }
        return 0L
    }
}
