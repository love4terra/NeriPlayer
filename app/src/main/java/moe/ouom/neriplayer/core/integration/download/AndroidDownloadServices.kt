package moe.ouom.neriplayer.core.integration.download

import kotlinx.coroutines.flow.first
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.host.DownloadCredentials
import moe.ouom.neriplayer.core.download.host.DownloadLyricServices
import moe.ouom.neriplayer.core.download.host.DownloadSourceServices
import moe.ouom.neriplayer.data.model.netease.playback.NeteaseAudioSourcePreference

internal object AndroidDownloadServices : DownloadSourceServices, DownloadLyricServices, DownloadCredentials {
    override val subsonicRepository get() = AppContainer.subsonicRepository
    override val neteaseClient get() = AppContainer.neteaseClient
    override val gdMusicClient get() = AppContainer.gdMusicClient
    override val biliClient get() = AppContainer.biliClient
    override val biliPlaybackRepository get() = AppContainer.biliPlaybackRepository
    override val youtubeMusicPlaybackRepository get() = AppContainer.youtubeMusicPlaybackRepository
    override val youtubeMusicDownloadPlaybackRepository get() = AppContainer.youtubeMusicDownloadPlaybackRepository
    override val youtubeMusicClient get() = AppContainer.youtubeMusicClient
    override val lrcLibClient get() = AppContainer.lrcLibClient
    override fun biliCookies() = AppContainer.biliCookieRepo.getCookiesOnce()
    override fun youtubeAuth() = AppContainer.youtubeAuthRepo.getAuthOnce()
    override suspend fun neteaseDownloadSource(): NeteaseAudioSourcePreference {
        val settings = AppContainer.settingsRepo
        val downloadPref = settings.neteaseDownloadSourceFlow.first()
        val playbackPref = settings.neteasePlaybackSourceFlow.first()
        return downloadPref.resolveForPlayback(playbackPref)
    }
}
