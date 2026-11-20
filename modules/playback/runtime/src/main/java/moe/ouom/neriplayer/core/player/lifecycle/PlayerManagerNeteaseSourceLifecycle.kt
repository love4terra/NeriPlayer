package moe.ouom.neriplayer.core.player.lifecycle

import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.createNeteaseScrobbleTrack
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.host.settingsRepo
import moe.ouom.neriplayer.core.player.scrobble.BiliPlaybackScrobbleController
import moe.ouom.neriplayer.core.player.scrobble.NeteasePlaybackScrobbleController
import moe.ouom.neriplayer.data.model.netease.playback.NeteasePlaybackSourcePreferencePolicy
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.platform.scrobble.BiliPlaybackHistoryReporter
import moe.ouom.neriplayer.platform.scrobble.NeteaseWeblogReporter

/**
 * 网易云播放源、自动回退与听歌打卡生命周期协调。
 * 独立成文件是为了让 [PlayerManagerLifecycleExtensions] 保持在源码行数预算内。
 */
internal fun PlayerManager.createNeteaseScrobbleController(): NeteasePlaybackScrobbleController? {
    val client = runCatching { PlayerDependencies.repositories.neteaseClient }.getOrNull()
        ?: return null
    return NeteasePlaybackScrobbleController(
        ioScopeProvider = { ioScope },
        isScrobbleEnabled = { neteaseScrobbleEnabled },
        isLocalScrobbleEnabled = { neteaseLocalScrobbleEnabled },
        reporter = NeteaseWeblogReporter(client),
        resolveTrack = { song -> createNeteaseScrobbleTrack(song) }
    )
}

internal fun PlayerManager.createBiliScrobbleController(): BiliPlaybackScrobbleController? {
    val client = runCatching { PlayerDependencies.repositories.biliClient }.getOrNull()
        ?: return null
    return BiliPlaybackScrobbleController(
        ioScopeProvider = { ioScope },
        isScrobbleEnabled = { biliScrobbleEnabled },
        reporter = BiliPlaybackHistoryReporter(client)
    )
}

internal fun PlayerManager.applyInitialNeteaseSourceAndScrobblePreferences(
    initialPlaybackPreferences: PlaybackPreferenceSnapshot
) {
    neteasePlaybackSourcePreference = NeteasePlaybackSourcePreferencePolicy.fromStorage(
        initialPlaybackPreferences.neteasePlaybackSource
    )
    neteaseScrobbleEnabled = initialPlaybackPreferences.neteaseScrobbleEnabled
    neteaseLocalScrobbleEnabled = initialPlaybackPreferences.neteaseLocalScrobbleEnabled
    biliScrobbleEnabled = initialPlaybackPreferences.biliScrobbleEnabled
    neteaseAutoSourceSwitchEnabled = initialPlaybackPreferences.neteaseAutoSourceSwitch
    neteaseLocalSourceFallbackEnabled = initialPlaybackPreferences.neteaseLocalSourceFallback
}

internal fun PlayerManager.observeNeteaseSourceAndScrobbleSettings() {
    ioScope.launch {
        settingsRepo.neteasePlaybackSourceFlow.collect { source ->
            val previousSource = neteasePlaybackSourcePreference
            neteasePlaybackSourcePreference = source
            if (previousSource != source) {
                scheduleQualityRefresh(
                    source = PlaybackAudioSource.NETEASE,
                    reason = "netease_playback_source_changed"
                )
            }
        }
    }
    ioScope.launch {
        settingsRepo.neteaseScrobbleEnabledFlow.collect { enabled ->
            neteaseScrobbleEnabled = enabled
        }
    }
    ioScope.launch {
        settingsRepo.neteaseLocalScrobbleEnabledFlow.collect { enabled ->
            neteaseLocalScrobbleEnabled = enabled
        }
    }
    ioScope.launch {
        settingsRepo.biliScrobbleEnabledFlow.collect { enabled ->
            biliScrobbleEnabled = enabled
        }
    }
    ioScope.launch {
        settingsRepo.neteaseAutoSourceSwitchFlow.collect { enabled ->
            val previousEnabled = neteaseAutoSourceSwitchEnabled
            neteaseAutoSourceSwitchEnabled = enabled
            if (!previousEnabled && enabled) {
                scheduleQualityRefresh(
                    source = PlaybackAudioSource.NETEASE,
                    reason = "netease_auto_source_switch_enabled"
                )
            }
        }
    }
    ioScope.launch {
        settingsRepo.neteaseLocalSourceFallbackFlow.collect { enabled ->
            val previousEnabled = neteaseLocalSourceFallbackEnabled
            neteaseLocalSourceFallbackEnabled = enabled
            if (!previousEnabled && enabled) {
                scheduleQualityRefresh(
                    source = PlaybackAudioSource.NETEASE,
                    reason = "netease_local_source_fallback_enabled"
                )
            }
        }
    }
}
