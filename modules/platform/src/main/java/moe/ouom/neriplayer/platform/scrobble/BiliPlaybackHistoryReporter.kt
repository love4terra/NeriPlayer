package moe.ouom.neriplayer.platform.scrobble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient

data class BiliScrobbleTrack(
    val aid: Long,
    val bvid: String,
    val cid: Long,
    val durationSec: Int
)

/**
 * 哔哩哔哩观看历史心跳上报器（`POST /x/click-interface/web/heartbeat`）。
 */
class BiliPlaybackHistoryReporter(
    private val biliClient: BiliClient
) {
    companion object {
        private const val TAG = "NERI-BiliScrobble"
        const val PLAY_TYPE_PLAYING = 0
        const val PLAY_TYPE_START = 1
        const val PLAY_TYPE_PAUSE = 2
        const val PLAY_TYPE_END = 4
    }

    private val reportMutex = Mutex()

    fun hasLoginSession(): Boolean = biliClient.hasLoginSession()

    suspend fun resolveMissingTarget(aid: Long, bvid: String): BiliScrobbleTrack? = withContext(Dispatchers.IO) {
        if (!hasLoginSession()) return@withContext null
        try {
            val info = when {
                bvid.isNotBlank() -> biliClient.getVideoBasicInfoByBvid(bvid)
                aid > 0L -> biliClient.getVideoBasicInfoByAvid(aid)
                else -> return@withContext null
            }
            val firstPage = info.pages.firstOrNull() ?: return@withContext null
            if (info.aid <= 0L || info.bvid.isBlank() || firstPage.cid <= 0L) return@withContext null
            BiliScrobbleTrack(
                aid = info.aid,
                bvid = info.bvid,
                cid = firstPage.cid,
                durationSec = firstPage.durationSec.takeIf { it > 0 } ?: info.durationSec
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            NPLogger.w(TAG, "补全 B 站视频 cid 失败: aid=$aid bvid=$bvid", e)
            null
        }
    }

    suspend fun reportHeartbeat(
        track: BiliScrobbleTrack,
        playedTimeSec: Long,
        playType: Int,
        startTsSec: Long
    ): Boolean = withContext(Dispatchers.IO) {
        if (!hasLoginSession()) return@withContext false
        if (track.aid <= 0L && track.bvid.isBlank()) return@withContext false
        if (track.cid <= 0L) return@withContext false
        reportMutex.withLock {
            try {
                val ok = biliClient.reportPlaybackHeartbeat(
                    aid = track.aid,
                    bvid = track.bvid,
                    cid = track.cid,
                    playedTimeSec = playedTimeSec,
                    playType = playType,
                    startTsSec = startTsSec
                )
                if (ok) {
                    NPLogger.d(
                        TAG,
                        "B站播放心跳上报成功: bvid=${track.bvid} cid=${track.cid} playType=$playType played=$playedTimeSec"
                    )
                }
                ok
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                NPLogger.w(
                    TAG,
                    "B站播放心跳上报失败: bvid=${track.bvid} cid=${track.cid} playType=$playType",
                    e
                )
                false
            }
        }
    }
}
