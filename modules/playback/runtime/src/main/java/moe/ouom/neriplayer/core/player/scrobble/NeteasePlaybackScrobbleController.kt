package moe.ouom.neriplayer.core.player.scrobble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.scrobble.NeteaseScrobbleTrack
import moe.ouom.neriplayer.platform.scrobble.NeteaseWeblogEndReason
import moe.ouom.neriplayer.platform.scrobble.NeteaseWeblogPolicy
import moe.ouom.neriplayer.platform.scrobble.NeteaseWeblogReporter

/**
 * 网易云听歌记录（weblog startplay / play）播放会话协调器：
 * - 切歌时先结算上一首真实累计播放时长并按阈值补报 play
 * - 真正出声稳定满 1.5s 后发送 startplay
 * - 暂停、缓冲期间不计入有效听歌时长；单曲循环或播完时发送 playend 并支持下一轮重置
 */
class NeteasePlaybackScrobbleController(
    private val ioScopeProvider: () -> CoroutineScope,
    private val isScrobbleEnabled: () -> Boolean,
    private val isLocalScrobbleEnabled: () -> Boolean,
    private val reporter: NeteaseWeblogReporter,
    private val resolveTrack: (SongItem) -> NeteaseScrobbleTrack?,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val startPlayDelayMs: Long = DEFAULT_START_PLAY_DELAY_MS
) {
    companion object {
        const val DEFAULT_START_PLAY_DELAY_MS = 1_500L
    }

    private class ActiveSession(
        val sessionToken: Long,
        val songKey: String,
        val track: NeteaseScrobbleTrack,
        var resolvedSongId: Long? = null,
        var resolutionDone: Boolean = false,
        var startPlaySent: Boolean = false,
        var playSent: Boolean = false,
        var accumulatedPlayMs: Long = 0L,
        var playingSinceMs: Long? = null
    )

    private data class PendingPlayReport(
        val songId: Long,
        val playedMs: Long,
        val durationMs: Long,
        val endReason: NeteaseWeblogEndReason
    )

    private val lock = Any()
    private var nextToken = 1L
    private var activeSession: ActiveSession? = null
    private var resolveJob: Job? = null
    private var startPlayJob: Job? = null

    fun onSongChanged(song: SongItem?) {
        val pendingReport: PendingPlayReport?
        val newSession: ActiveSession?
        val allowLocal = isLocalScrobbleEnabled()
        val scrobbleOn = isScrobbleEnabled()

        synchronized(lock) {
            pendingReport = closeActiveSessionLocked(
                endReason = NeteaseWeblogEndReason.UI,
                nowMs = clockMs(),
                scrobbleEnabled = scrobbleOn
            )
            cancelJobsLocked()
            val mappedTrack = if (scrobbleOn && song != null) resolveTrack(song) else null
            newSession = if (song != null && mappedTrack != null) {
                ActiveSession(
                    sessionToken = nextToken++,
                    songKey = song.stableKey(),
                    track = mappedTrack,
                    resolvedSongId = if (!mappedTrack.isLocal) mappedTrack.songId?.takeIf { it > 0L } else null,
                    resolutionDone = !mappedTrack.isLocal
                )
            } else {
                null
            }
            activeSession = newSession
        }

        launchPendingPlayReport(pendingReport)

        if (newSession != null && !newSession.resolutionDone) {
            resolveJob = launchSafe {
                val songId = reporter.resolveReportableSongId(
                    track = newSession.track,
                    allowLocalMatch = allowLocal
                )
                val shouldScheduleStartPlay = synchronized(lock) {
                    val current = activeSession
                    if (current == null || current.sessionToken != newSession.sessionToken) {
                        false
                    } else {
                        current.resolvedSongId = songId
                        current.resolutionDone = true
                        current.playingSinceMs != null && !current.startPlaySent
                    }
                }
                if (shouldScheduleStartPlay && songId != null) {
                    scheduleStartPlay(newSession.sessionToken)
                }
            }
        }
    }

    fun onPlayingChanged(playing: Boolean) {
        if (playing) {
            startOrResumeTracking()
        } else {
            pauseTrackingLocked()
        }
    }

    fun onEnginePlayingChanged(isPlaying: Boolean, progressAdvanced: Boolean) {
        if (isPlaying && progressAdvanced) {
            startOrResumeTracking()
        } else if (!isPlaying) {
            pauseTrackingLocked()
        }
    }

    fun onProgress(positionMs: Long) {
        val resumeAfterRepeat = synchronized(lock) {
            val session = activeSession ?: return@synchronized null
            if (session.playSent && positionMs in 0L..4_999L && session.playingSinceMs != null) {
                session.playSent = false
                session.startPlaySent = false
                session.accumulatedPlayMs = 0L
                session.playingSinceMs = clockMs()
                session.sessionToken
            } else {
                null
            }
        }
        if (resumeAfterRepeat != null) {
            scheduleStartPlay(resumeAfterRepeat)
        }
    }

    fun onTrackEnded() {
        val pendingReport = synchronized(lock) {
            val session = activeSession ?: return@synchronized null
            val now = clockMs()
            accumulateLocked(session, now)
            session.playingSinceMs = null
            cancelStartPlayJobLocked()
            val report = buildPendingReportLocked(
                session = session,
                endReason = NeteaseWeblogEndReason.PLAY_END,
                scrobbleEnabled = isScrobbleEnabled()
            )
            session.playSent = true
            session.accumulatedPlayMs = 0L
            report
        }
        launchPendingPlayReport(pendingReport)
    }

    fun flushOnStopOrRelease() {
        val pendingReport = synchronized(lock) {
            val report = closeActiveSessionLocked(
                endReason = NeteaseWeblogEndReason.INTERRUPT,
                nowMs = clockMs(),
                scrobbleEnabled = isScrobbleEnabled()
            )
            cancelJobsLocked()
            activeSession = null
            report
        }
        launchPendingPlayReport(pendingReport)
    }

    private fun startOrResumeTracking() {
        val tokenToSchedule: Long? = synchronized(lock) {
            val session = activeSession ?: return@synchronized null
            if (!isScrobbleEnabled()) return@synchronized null
            if (session.playSent) {
                session.playSent = false
                session.startPlaySent = false
                session.accumulatedPlayMs = 0L
            }
            if (session.playingSinceMs == null) {
                session.playingSinceMs = clockMs()
            }
            if (!session.startPlaySent && session.resolvedSongId != null && startPlayJob?.isActive != true) {
                session.sessionToken
            } else {
                null
            }
        }
        if (tokenToSchedule != null) {
            scheduleStartPlay(tokenToSchedule)
        }
    }

    private fun pauseTrackingLocked() {
        synchronized(lock) {
            val session = activeSession ?: return
            accumulateLocked(session, clockMs())
            session.playingSinceMs = null
            cancelStartPlayJobLocked()
        }
    }

    private fun scheduleStartPlay(sessionToken: Long) {
        synchronized(lock) {
            if (activeSession?.sessionToken != sessionToken) return
            cancelStartPlayJobLocked()
            startPlayJob = launchSafe {
                if (startPlayDelayMs > 0L) {
                    delay(startPlayDelayMs)
                }
                val songId = synchronized(lock) {
                    val current = activeSession ?: return@synchronized null
                    if (current.sessionToken != sessionToken ||
                        current.playingSinceMs == null ||
                        current.startPlaySent ||
                        !isScrobbleEnabled()
                    ) {
                        return@synchronized null
                    }
                    val id = current.resolvedSongId ?: return@synchronized null
                    current.startPlaySent = true
                    id
                } ?: return@launchSafe
                reporter.sendStartPlay(songId)
            }
        }
    }

    private fun launchPendingPlayReport(report: PendingPlayReport?) {
        if (report == null) return
        launchSafe {
            reporter.sendPlay(
                songId = report.songId,
                playedMs = report.playedMs,
                durationMs = report.durationMs,
                endReason = report.endReason
            )
        }
    }

    private fun closeActiveSessionLocked(
        endReason: NeteaseWeblogEndReason,
        nowMs: Long,
        scrobbleEnabled: Boolean
    ): PendingPlayReport? {
        val session = activeSession ?: return null
        accumulateLocked(session, nowMs)
        session.playingSinceMs = null
        return buildPendingReportLocked(session, endReason, scrobbleEnabled)
    }

    private fun buildPendingReportLocked(
        session: ActiveSession,
        endReason: NeteaseWeblogEndReason,
        scrobbleEnabled: Boolean
    ): PendingPlayReport? {
        if (!scrobbleEnabled || session.playSent) return null
        if (session.track.isLocal && !isLocalScrobbleEnabled()) return null
        val songId = session.resolvedSongId ?: return null
        if (!NeteaseWeblogPolicy.shouldReportPlayLog(session.accumulatedPlayMs, session.track.durationMs)) {
            return null
        }
        session.playSent = true
        return PendingPlayReport(
            songId = songId,
            playedMs = session.accumulatedPlayMs,
            durationMs = session.track.durationMs,
            endReason = endReason
        )
    }

    private fun accumulateLocked(session: ActiveSession, nowMs: Long) {
        val startedAt = session.playingSinceMs ?: return
        if (nowMs > startedAt) {
            session.accumulatedPlayMs += (nowMs - startedAt)
        }
        session.playingSinceMs = nowMs
    }

    private fun cancelStartPlayJobLocked() {
        startPlayJob?.cancel()
        startPlayJob = null
    }

    private fun cancelJobsLocked() {
        resolveJob?.cancel()
        resolveJob = null
        cancelStartPlayJobLocked()
    }

    private fun launchSafe(block: suspend () -> Unit): Job {
        return ioScopeProvider().launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
    }
}
