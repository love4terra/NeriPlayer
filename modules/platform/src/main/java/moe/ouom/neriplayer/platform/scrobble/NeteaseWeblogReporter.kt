package moe.ouom.neriplayer.platform.scrobble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 网易云听歌记录上报器（/eapi/feedback/weblog startplay + play）。
 */
class NeteaseWeblogReporter(
    private val neteaseClient: NeteaseClient,
    private val localSongMatcher: NeteaseLocalSongMatcher = NeteaseLocalSongMatcher(neteaseClient)
) {
    companion object {
        private const val TAG = "NERI-NeteaseWeblog"
    }

    suspend fun resolveReportableSongId(
        track: NeteaseScrobbleTrack,
        allowLocalMatch: Boolean
    ): Long? {
        if (!neteaseClient.hasLogin()) return null
        return if (track.isLocal) {
            if (!allowLocalMatch) null else localSongMatcher.matchLocalTrack(track)
        } else {
            track.songId?.takeIf { it > 0L }
        }
    }

    suspend fun sendStartPlay(songId: Long): Boolean {
        if (songId <= 0L || !neteaseClient.hasLogin()) return false
        val entry = JSONObject().apply {
            put("action", "startplay")
            put(
                "json",
                JSONObject().apply {
                    put("id", songId)
                    put("type", "song")
                    put("content", "")
                }
            )
        }
        return postWeblog(JSONArray().put(entry).toString(), "startplay songId=$songId")
    }

    suspend fun sendPlay(
        songId: Long,
        playedMs: Long,
        durationMs: Long,
        endReason: NeteaseWeblogEndReason
    ): Boolean {
        if (songId <= 0L || !neteaseClient.hasLogin()) return false
        if (!NeteaseWeblogPolicy.shouldReportPlayLog(playedMs, durationMs)) return false
        val seconds = NeteaseWeblogPolicy.computeReportedSeconds(playedMs, durationMs)
        val entry = JSONObject().apply {
            put("action", "play")
            put(
                "json",
                JSONObject().apply {
                    put("id", songId)
                    put("type", "song")
                    put("time", seconds)
                    put("end", endReason.wireValue)
                    put("wifi", 0)
                    put("download", 0)
                }
            )
        }
        return postWeblog(
            JSONArray().put(entry).toString(),
            "play songId=$songId time=${seconds}s end=${endReason.wireValue}"
        )
    }

    private suspend fun postWeblog(logsJson: String, label: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val raw = neteaseClient.reportWeblogCancellable(logsJson)
            val code = runCatching { JSONObject(raw).optInt("code", -1) }.getOrDefault(-1)
            if (code == 200) {
                NPLogger.d(TAG, "网易云打卡成功: $label")
                true
            } else {
                NPLogger.w(TAG, "网易云打卡返回非200: $label code=$code")
                false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            NPLogger.w(TAG, "网易云打卡失败: $label", e)
            false
        }
    }
}
