package moe.ouom.neriplayer.platform.scrobble

import moe.ouom.neriplayer.data.model.music.MusicPlatform
import kotlin.math.max

data class NeteaseScrobbleTrack(
    val songId: Long?,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val isLocal: Boolean,
    val album: String = "",
    val matchedLyricSource: MusicPlatform? = null,
    val matchedSongId: String? = null
)

enum class NeteaseWeblogEndReason(val wireValue: String) {
    PLAY_END("playend"),
    INTERRUPT("interrupt"),
    UI("ui")
}

object NeteaseWeblogPolicy {
    const val STANDARD_THRESHOLD_MS = 30_000L
    const val SHORT_TRACK_MIN_MS = 5_000L

    fun shouldReportPlayLog(playedMs: Long, durationMs: Long): Boolean {
        if (playedMs <= 0L) return false
        if (durationMs in SHORT_TRACK_MIN_MS until STANDARD_THRESHOLD_MS) {
            val eightyPercentMs = (durationMs * 4L + 4L) / 5L
            return playedMs >= max(SHORT_TRACK_MIN_MS, eightyPercentMs)
        }
        return playedMs > STANDARD_THRESHOLD_MS
    }

    fun computeReportedSeconds(playedMs: Long, durationMs: Long): Long {
        val rawSec = ((playedMs + 500L) / 1000L).coerceAtLeast(1L)
        if (durationMs <= 0L) return rawSec
        val durationSec = ((durationMs + 500L) / 1000L).coerceAtLeast(1L)
        return rawSec.coerceAtMost(durationSec)
    }
}
