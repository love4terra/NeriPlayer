package moe.ouom.neriplayer.core.player.scrobble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.biliAvidOrNull
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.biliBvidOrNull
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.biliCidOrNull
import moe.ouom.neriplayer.platform.scrobble.BiliPlaybackHistoryReporter
import moe.ouom.neriplayer.platform.scrobble.BiliScrobbleTrack

/**
 * 哔哩哔哩播放历史心跳控制器（`/x/click-interface/web/heartbeat`）：
 * - 切歌时识别 B 站曲目，播放稳定满 1.5s 后发送 start 心跳（`play_type = 1`）
 * - 持续播放期间每 15s 发送一次播放中心跳（`play_type = 0`）
 * - 暂停、切歌或停止时上报最新播放秒数（`play_type = 2`），自然播完上报 `play_type = 4`（`played_time = -1`）
 */
class BiliPlaybackScrobbleController(
    private val ioScopeProvider: () -> CoroutineScope,
    private val isScrobbleEnabled: () -> Boolean,
    private val reporter: BiliPlaybackHistoryReporter,
    private val resolveInitialTrack: (SongItem) -> BiliScrobbleTrack? = ::defaultResolveBiliScrobbleTrack,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val startPlayDelayMs: Long = DEFAULT_START_PLAY_DELAY_MS,
    private val heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS
) {
    companion object {
        const val DEFAULT_START_PLAY_DELAY_MS = 1_500L
        const val DEFAULT_HEARTBEAT_INTERVAL_MS = 15_000L
    }

    private class ActiveSession(
        val sessionToken: Long,
        val songKey: String,
        var track: BiliScrobbleTrack?,
        val needsCidResolution: Boolean,
        var cidResolutionAttempted: Boolean = false,
        var startSent: Boolean = false,
        var endSent: Boolean = false,
        var startTsSec: Long = 0L,
        var lastPositionMs: Long = 0L,
        var accumulatedPlayMs: Long = 0L,
        var playingSinceMs: Long? = null
    )

    private data class HeartbeatPayload(
        val track: BiliScrobbleTrack,
        val playedTimeSec: Long,
        val playType: Int,
        val startTsSec: Long
    )

    private val lock = Any()
    private var nextToken = 1L
    private var activeSession: ActiveSession? = null
    private var resolveJob: Job? = null
    private var startJob: Job? = null
    private var heartbeatJob: Job? = null

    fun onSongChanged(song: SongItem?) {
        val closingPayload: HeartbeatPayload?
        val initialTrack: BiliScrobbleTrack?
        val newSession: ActiveSession?
        val scrobbleOn = isScrobbleEnabled()

        synchronized(lock) {
            closingPayload = closeActiveSessionLocked(clockMs(), scrobbleOn)
            cancelJobsLocked()
            initialTrack = if (scrobbleOn && song != null) resolveInitialTrack(song) else null
            newSession = if (song != null && initialTrack != null) {
                ActiveSession(
                    sessionToken = nextToken++,
                    songKey = song.stableKey(),
                    track = initialTrack.takeIf { it.cid > 0L },
                    needsCidResolution = initialTrack.cid <= 0L &&
                        (initialTrack.aid > 0L || initialTrack.bvid.isNotBlank())
                )
            } else {
                null
            }
            activeSession = newSession
        }

        launchHeartbeat(closingPayload)

        if (newSession != null && newSession.needsCidResolution && initialTrack != null) {
            resolveMissingCid(newSession.sessionToken, initialTrack.aid, initialTrack.bvid)
        }
    }

    fun onTrackResolved(
        song: SongItem,
        aid: Long,
        bvid: String,
        cid: Long,
        durationSec: Int = (song.durationMs / 1000L).toInt().coerceAtLeast(0)
    ) {
        if (aid <= 0L && bvid.isBlank()) return
        if (cid <= 0L) return
        val songKey = song.stableKey()
        val tokenToStart: Long? = synchronized(lock) {
            val session = activeSession ?: return@synchronized null
            if (session.songKey != songKey || !isScrobbleEnabled()) return@synchronized null
            session.track = BiliScrobbleTrack(
                aid = aid,
                bvid = bvid,
                cid = cid,
                durationSec = durationSec.coerceAtLeast(0)
            )
            session.cidResolutionAttempted = true
            if (session.playingSinceMs != null && !session.startSent && startJob?.isActive != true) {
                session.sessionToken
            } else {
                null
            }
        }
        if (tokenToStart != null) {
            scheduleStartAndHeartbeat(tokenToStart)
        }
    }

    fun onPlayingChanged(playing: Boolean) {
        if (playing) {
            startOrResumeTracking()
        } else {
            pauseTracking()
        }
    }

    fun onEnginePlayingChanged(isPlaying: Boolean, progressAdvanced: Boolean) {
        if (isPlaying && progressAdvanced) {
            startOrResumeTracking()
        } else if (!isPlaying) {
            pauseTracking()
        }
    }

    fun onProgress(positionMs: Long) {
        val resumeAfterRepeat: Long? = synchronized(lock) {
            val session = activeSession ?: return@synchronized null
            val boundedMs = positionMs.coerceAtLeast(0L)
            session.lastPositionMs = boundedMs
            if (session.endSent && boundedMs in 0L..4_999L && session.playingSinceMs != null) {
                session.startSent = false
                session.endSent = false
                session.startTsSec = 0L
                session.accumulatedPlayMs = 0L
                session.playingSinceMs = clockMs()
                session.sessionToken
            } else {
                null
            }
        }
        if (resumeAfterRepeat != null) {
            scheduleStartAndHeartbeat(resumeAfterRepeat)
        }
    }

    fun onTrackEnded() {
        val endPayload: HeartbeatPayload? = synchronized(lock) {
            val session = activeSession ?: return@synchronized null
            val nowMs = clockMs()
            accumulateLocked(session, nowMs)
            session.playingSinceMs = null
            cancelStartJobLocked()
            cancelHeartbeatJobLocked()
            val track = session.track
            val canReportEnd = isScrobbleEnabled() &&
                reporter.hasLoginSession() &&
                track != null &&
                !session.endSent &&
                (session.startSent || session.accumulatedPlayMs >= startPlayDelayMs)
            if (!canReportEnd) {
                null
            } else {
                val resolvedTrack = track!!
                val startTs = session.startTsSec.takeIf { it > 0L } ?: (nowMs / 1000L)
                session.endSent = true
                session.accumulatedPlayMs = 0L
                HeartbeatPayload(
                    track = resolvedTrack,
                    playedTimeSec = -1L,
                    playType = BiliPlaybackHistoryReporter.PLAY_TYPE_END,
                    startTsSec = startTs
                )
            }
        }
        launchHeartbeat(endPayload)
    }

    fun flushOnStopOrRelease() {
        val payload = synchronized(lock) {
            val pending = closeActiveSessionLocked(clockMs(), isScrobbleEnabled())
            cancelJobsLocked()
            activeSession = null
            pending
        }
        launchHeartbeat(payload)
    }

    private fun startOrResumeTracking() {
        val startToken: Long?
        val heartbeatToken: Long?
        synchronized(lock) {
            val session = activeSession
            if (session == null || !isScrobbleEnabled() || !reporter.hasLoginSession()) {
                return
            }
            if (session.endSent) {
                session.startSent = false
                session.endSent = false
                session.startTsSec = 0L
                session.accumulatedPlayMs = 0L
            }
            if (session.playingSinceMs == null) {
                session.playingSinceMs = clockMs()
            }
            val hasTrack = session.track != null
            startToken = if (!session.startSent && hasTrack && startJob?.isActive != true) {
                session.sessionToken
            } else {
                null
            }
            heartbeatToken = if (session.startSent && hasTrack && heartbeatJob?.isActive != true) {
                session.sessionToken
            } else {
                null
            }
        }
        if (startToken != null) {
            scheduleStartAndHeartbeat(startToken)
        } else if (heartbeatToken != null) {
            schedulePeriodicHeartbeat(heartbeatToken)
        }
    }

    private fun pauseTracking() {
        val pausePayload: HeartbeatPayload? = synchronized(lock) {
            val session = activeSession ?: return@synchronized null
            val nowMs = clockMs()
            accumulateLocked(session, nowMs)
            session.playingSinceMs = null
            cancelStartJobLocked()
            cancelHeartbeatJobLocked()
            buildPauseOrProgressPayloadLocked(
                session = session,
                nowMs = nowMs,
                scrobbleEnabled = isScrobbleEnabled(),
                playType = BiliPlaybackHistoryReporter.PLAY_TYPE_PAUSE
            )
        }
        launchHeartbeat(pausePayload)
    }

    private fun resolveMissingCid(sessionToken: Long, aid: Long, bvid: String) {
        resolveJob = launchSafe {
            val resolved = reporter.resolveMissingTarget(aid, bvid) ?: return@launchSafe
            val tokenToStart: Long? = synchronized(lock) {
                val session = activeSession ?: return@synchronized null
                if (session.sessionToken != sessionToken || session.track != null) {
                    return@synchronized null
                }
                session.track = resolved
                session.cidResolutionAttempted = true
                if (session.playingSinceMs != null && !session.startSent && isScrobbleEnabled()) {
                    session.sessionToken
                } else {
                    null
                }
            }
            if (tokenToStart != null) {
                scheduleStartAndHeartbeat(tokenToStart)
            }
        }
    }

    private fun scheduleStartAndHeartbeat(sessionToken: Long) {
        synchronized(lock) {
            if (activeSession?.sessionToken != sessionToken) return
            cancelStartJobLocked()
            startJob = launchSafe {
                if (startPlayDelayMs > 0L) {
                    delay(startPlayDelayMs)
                }
                val startPayload = synchronized(lock) {
                    val session = activeSession ?: return@synchronized null
                    if (session.sessionToken != sessionToken ||
                        session.playingSinceMs == null ||
                        session.startSent ||
                        !isScrobbleEnabled() ||
                        !reporter.hasLoginSession()
                    ) {
                        return@synchronized null
                    }
                    val track = session.track ?: return@synchronized null
                    val nowMs = clockMs()
                    accumulateLocked(session, nowMs)
                    val startTs = (nowMs / 1000L).coerceAtLeast(1L)
                    session.startTsSec = startTs
                    session.startSent = true
                    HeartbeatPayload(
                        track = track,
                        playedTimeSec = effectivePlayedSecondsLocked(session),
                        playType = BiliPlaybackHistoryReporter.PLAY_TYPE_START,
                        startTsSec = startTs
                    )
                } ?: return@launchSafe

                reporter.reportHeartbeat(
                    track = startPayload.track,
                    playedTimeSec = startPayload.playedTimeSec,
                    playType = startPayload.playType,
                    startTsSec = startPayload.startTsSec
                )
                schedulePeriodicHeartbeat(sessionToken)
            }
        }
    }

    private fun schedulePeriodicHeartbeat(sessionToken: Long) {
        if (heartbeatIntervalMs <= 0L) return
        synchronized(lock) {
            val session = activeSession
            if (session == null ||
                session.sessionToken != sessionToken ||
                session.playingSinceMs == null ||
                !session.startSent ||
                session.endSent
            ) {
                return
            }
            cancelHeartbeatJobLocked()
            heartbeatJob = launchSafe {
                while (isActive) {
                    delay(heartbeatIntervalMs)
                    val payload = synchronized(lock) {
                        val current = activeSession ?: return@synchronized null
                        if (current.sessionToken != sessionToken ||
                            current.playingSinceMs == null ||
                            !current.startSent ||
                            current.endSent ||
                            !isScrobbleEnabled() ||
                            !reporter.hasLoginSession()
                        ) {
                            return@synchronized null
                        }
                        val track = current.track ?: return@synchronized null
                        val nowMs = clockMs()
                        accumulateLocked(current, nowMs)
                        HeartbeatPayload(
                            track = track,
                            playedTimeSec = effectivePlayedSecondsLocked(current),
                            playType = BiliPlaybackHistoryReporter.PLAY_TYPE_PLAYING,
                            startTsSec = current.startTsSec.takeIf { it > 0L } ?: (nowMs / 1000L)
                        )
                    } ?: break

                    reporter.reportHeartbeat(
                        track = payload.track,
                        playedTimeSec = payload.playedTimeSec,
                        playType = payload.playType,
                        startTsSec = payload.startTsSec
                    )
                }
            }
        }
    }

    private fun launchHeartbeat(payload: HeartbeatPayload?) {
        if (payload == null) return
        launchSafe {
            reporter.reportHeartbeat(
                track = payload.track,
                playedTimeSec = payload.playedTimeSec,
                playType = payload.playType,
                startTsSec = payload.startTsSec
            )
        }
    }

    private fun closeActiveSessionLocked(nowMs: Long, scrobbleEnabled: Boolean): HeartbeatPayload? {
        val session = activeSession ?: return null
        accumulateLocked(session, nowMs)
        session.playingSinceMs = null
        return buildPauseOrProgressPayloadLocked(
            session = session,
            nowMs = nowMs,
            scrobbleEnabled = scrobbleEnabled,
            playType = BiliPlaybackHistoryReporter.PLAY_TYPE_PAUSE
        )
    }

    private fun buildPauseOrProgressPayloadLocked(
        session: ActiveSession,
        nowMs: Long,
        scrobbleEnabled: Boolean,
        playType: Int
    ): HeartbeatPayload? {
        if (!scrobbleEnabled || !reporter.hasLoginSession()) return null
        if (!session.startSent || session.endSent) return null
        val track = session.track ?: return null
        val startTs = session.startTsSec.takeIf { it > 0L } ?: (nowMs / 1000L)
        return HeartbeatPayload(
            track = track,
            playedTimeSec = effectivePlayedSecondsLocked(session),
            playType = playType,
            startTsSec = startTs
        )
    }

    private fun effectivePlayedSecondsLocked(session: ActiveSession): Long {
        val rawSec = maxOf(
            session.lastPositionMs / 1000L,
            session.accumulatedPlayMs / 1000L
        ).coerceAtLeast(0L)
        val durationSec = session.track?.durationSec?.toLong() ?: 0L
        return if (durationSec > 0L) rawSec.coerceAtMost(durationSec) else rawSec
    }

    private fun accumulateLocked(session: ActiveSession, nowMs: Long) {
        val startedAt = session.playingSinceMs ?: return
        if (nowMs > startedAt) {
            session.accumulatedPlayMs += (nowMs - startedAt)
        }
        session.playingSinceMs = nowMs
    }

    private fun cancelStartJobLocked() {
        startJob?.cancel()
        startJob = null
    }

    private fun cancelHeartbeatJobLocked() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun cancelJobsLocked() {
        resolveJob?.cancel()
        resolveJob = null
        cancelStartJobLocked()
        cancelHeartbeatJobLocked()
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

internal fun defaultResolveBiliScrobbleTrack(song: SongItem): BiliScrobbleTrack? {
    val aid = song.biliAvidOrNull() ?: 0L
    val bvid = song.biliBvidOrNull().orEmpty()
    if (aid <= 0L && bvid.isBlank()) return null
    val cid = song.biliCidOrNull() ?: 0L
    return BiliScrobbleTrack(
        aid = aid,
        bvid = bvid,
        cid = cid,
        durationSec = (song.durationMs / 1000L).toInt().coerceAtLeast(0)
    )
}
