package moe.ouom.neriplayer.platform.scrobble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.platform.gdmusic.GdMusicTextMatcher
import moe.ouom.neriplayer.platform.gdmusic.GdMusicTextMatcher.SongMatchCandidate
import moe.ouom.neriplayer.platform.gdmusic.GdMusicTextMatcher.SongMatchTarget
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 严格匹配本地歌曲到网易云曲库：
 * 1. 先校验已有的 CLOUD_MUSIC matchedSongId 详情是否匹配当前标题/歌手/版本/时长
 * 2. 否则调用 cloudsearch 接口搜索并按标题、版本标记、歌手与时长（<= 8s）严格筛选
 * 3. 无明确艺术家或无精确命中时直接返回 null 跳过上报
 */
class NeteaseLocalSongMatcher(
    private val neteaseClient: NeteaseClient
) {
    companion object {
        private const val TAG = "NERI-NeteaseLocalMatcher"
        private const val MAX_CACHE_ENTRIES = 512
        private const val MAX_DURATION_DIFF_MS = 8_000L
        private const val CACHE_MISS_ID = -1L

        private val unknownArtistTokens = setOf(
            "",
            "unknown",
            "unknown artist",
            "<unknown>",
            "未知歌手",
            "未知艺术家",
            "群星",
            "various artists"
        )
    }

    private val lock = Any()
    private val matchCache = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    suspend fun matchLocalTrack(track: NeteaseScrobbleTrack): Long? = withContext(Dispatchers.IO) {
        val cleanTitle = track.title.trim()
        val artists = GdMusicTextMatcher.splitArtists(track.artist)
            .filter { GdMusicTextMatcher.compactText(it) !in unknownArtistTokens && it.lowercase(Locale.ROOT) !in unknownArtistTokens }
        if (cleanTitle.isEmpty() || artists.isEmpty()) {
            return@withContext null
        }

        val cacheKey = buildCacheKey(cleanTitle, artists, track.durationMs)
        synchronized(lock) {
            val cached = matchCache[cacheKey]
            if (cached != null) {
                return@withContext if (cached == CACHE_MISS_ID) null else cached
            }
        }

        val target = SongMatchTarget(
            title = cleanTitle,
            artists = artists,
            durationMs = track.durationMs
        )

        val matchedId = try {
            verifyMatchedId(track, target) ?: searchStrictMatch(target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            NPLogger.w(TAG, "本地歌曲匹配网易云失败: title=$cleanTitle", e)
            return@withContext null
        }

        synchronized(lock) {
            matchCache[cacheKey] = matchedId ?: CACHE_MISS_ID
        }
        matchedId
    }

    private suspend fun verifyMatchedId(
        track: NeteaseScrobbleTrack,
        target: SongMatchTarget
    ): Long? {
        if (track.matchedLyricSource != MusicPlatform.CLOUD_MUSIC) return null
        val matchedId = track.matchedSongId?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val detailJson = neteaseClient.getSongDetailCancellable(listOf(matchedId))
        val candidates = parseDetailSongs(detailJson)
        return GdMusicTextMatcher.findBestMatch(
            target = target,
            candidates = candidates,
            maxDurationDiffMs = MAX_DURATION_DIFF_MS,
            requireArtistWhenPresent = true
        )
    }

    private suspend fun searchStrictMatch(target: SongMatchTarget): Long? {
        val cleanTitle = GdMusicTextMatcher.stripParentheticals(target.title).ifBlank { target.title }
        val primaryArtist = target.artists.firstOrNull().orEmpty()
        val keyword = listOf(cleanTitle, primaryArtist).filter { it.isNotBlank() }.joinToString(" ")
        if (keyword.isBlank()) return null

        val rawSearch = neteaseClient.searchSongsCancellable(
            keyword = keyword,
            limit = 10,
            offset = 0,
            type = 1,
            usePersistedCookies = true
        )
        val candidates = parseSearchSongs(rawSearch)
        if (candidates.isEmpty()) return null
        return GdMusicTextMatcher.findBestMatch(
            target = target,
            candidates = candidates,
            maxDurationDiffMs = MAX_DURATION_DIFF_MS,
            requireArtistWhenPresent = true
        )
    }

    private fun parseDetailSongs(rawJson: String): List<SongMatchCandidate<Long>> {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
        return parseSongArray(root.optJSONArray("songs"))
    }

    private fun parseSearchSongs(rawJson: String): List<SongMatchCandidate<Long>> {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
        val songs = root.optJSONObject("result")?.optJSONArray("songs")
        return parseSongArray(songs)
    }

    private fun parseSongArray(array: JSONArray?): List<SongMatchCandidate<Long>> {
        if (array == null) return emptyList()
        val list = ArrayList<SongMatchCandidate<Long>>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optLong("id", 0L).takeIf { it > 0L } ?: continue
            val name = obj.optString("name", "").trim().takeIf { it.isNotEmpty() } ?: continue
            val artists = extractArtists(obj)
            val durationMs = obj.optLong("dt", 0L).takeIf { it > 0L }
                ?: obj.optLong("duration", 0L).coerceAtLeast(0L)
            list.add(
                SongMatchCandidate(
                    item = id,
                    title = name,
                    artists = artists,
                    durationMs = durationMs
                )
            )
        }
        return list
    }

    private fun extractArtists(songObj: JSONObject): List<String> {
        val arArray = songObj.optJSONArray("ar") ?: songObj.optJSONArray("artists") ?: return emptyList()
        return buildList {
            for (i in 0 until arArray.length()) {
                val artistObj = arArray.optJSONObject(i) ?: continue
                val artistName = artistObj.optString("name", "").trim()
                if (artistName.isNotEmpty()) add(artistName)
            }
        }
    }

    private fun buildCacheKey(title: String, artists: List<String>, durationMs: Long): String {
        val roundedDurationSec = if (durationMs > 0L) (durationMs / 1000L) else 0L
        return buildString {
            append(GdMusicTextMatcher.compactText(title))
            append('|')
            append(artists.joinToString(",") { GdMusicTextMatcher.compactText(it) })
            append('|')
            append(roundedDurationSec)
        }
    }
}
