package moe.ouom.neriplayer.core.player

import moe.ouom.neriplayer.data.identity.remoteDownloadIdentityOrNull
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.server.isServerSong
import moe.ouom.neriplayer.platform.scrobble.NeteaseScrobbleTrack

/** 同步播放统计与网易云打卡状态，避免 [PlayerManager] 超过单文件行数预算 */
internal fun PlayerManager.syncPlaybackStatsPlayingStateImpl(
    playing: Boolean,
    reason: String
) {
    playbackStatsOwner.onPlayingChanged(playing, reason, writesEnabled = initialized)
    if (initialized) {
        neteaseScrobbleController?.onPlayingChanged(playing)
    }
}

internal fun PlayerManager.syncPlaybackStatsEnginePlayingStateImpl(isPlaying: Boolean) {
    playbackStatsOwner.onEnginePlayingChanged(
        isPlaying,
        playbackProgressAdvanceReported,
        writesEnabled = initialized
    )
    if (initialized) {
        neteaseScrobbleController?.onEnginePlayingChanged(
            isPlaying,
            playbackProgressAdvanceReported
        )
    }
}

internal fun PlayerManager.drainPlaybackStatsPersistJobBlockingImpl(reason: String) {
    playbackStatsOwner.drainBlocking(reason, writesEnabled = initialized)
}

internal fun PlayerManager.flushPlaybackStatsBlockingImpl(
    reason: String,
    stopTracking: Boolean = false
) {
    playbackStatsOwner.flushBlocking(reason, stopTracking, writesEnabled = initialized)
    if (initialized && stopTracking) {
        neteaseScrobbleController?.flushOnStopOrRelease()
    }
}

internal fun PlayerManager.flushPlaybackStatsAsyncImpl(
    reason: String,
    stopTracking: Boolean = false
) {
    playbackStatsOwner.flushAsync(reason, stopTracking, writesEnabled = initialized)
    if (initialized && stopTracking) {
        neteaseScrobbleController?.flushOnStopOrRelease()
    }
}

internal fun buildNeteaseScrobbleTrack(
    song: SongItem,
    isLocalFile: Boolean,
    isBiliOrYouTube: Boolean = false
): NeteaseScrobbleTrack? {
    if (isBiliOrYouTube || song.isServerSong()) {
        return null
    }
    val remoteNeteaseId = song.remoteDownloadIdentityOrNull()
        ?.takeIf { it.album.equals("netease", ignoreCase = true) && it.mediaUri == null && it.id > 0L }
        ?.id
    val isLocal = isLocalFile && remoteNeteaseId == null
    val songId = when {
        remoteNeteaseId != null -> remoteNeteaseId
        !isLocal && song.id > 0L -> song.id
        else -> null
    }
    if (!isLocal && songId == null) {
        return null
    }
    val albumName = song.album.trim().takeUnless(LocalSongSupport::isPlaceholderAlbum).orEmpty()
    return NeteaseScrobbleTrack(
        songId = songId,
        title = song.name,
        artist = song.artist,
        durationMs = song.durationMs,
        isLocal = isLocal,
        album = albumName,
        matchedLyricSource = song.matchedLyricSource,
        matchedSongId = song.matchedSongId
    )
}

internal fun PlayerManager.createNeteaseScrobbleTrack(song: SongItem): NeteaseScrobbleTrack? {
    return buildNeteaseScrobbleTrack(
        song = song,
        isLocalFile = isLocalSong(song),
        isBiliOrYouTube = isBiliTrack(song) || isYouTubeMusicTrack(song)
    )
}
