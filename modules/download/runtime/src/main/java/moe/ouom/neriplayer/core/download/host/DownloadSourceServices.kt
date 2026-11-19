package moe.ouom.neriplayer.core.download.host

import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.bilibili.playback.BiliPlaybackRepository
import moe.ouom.neriplayer.platform.youtube.repository.YouTubeMusicPlaybackRepository
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicRepository

interface DownloadSourceServices {
    val subsonicRepository: SubsonicRepository? get() = null
    val neteaseClient: NeteaseClient
    val gdMusicClient: moe.ouom.neriplayer.platform.gdmusic.GdMusicClient? get() = null
    val biliClient: BiliClient
    val biliPlaybackRepository: BiliPlaybackRepository
    val youtubeMusicPlaybackRepository: YouTubeMusicPlaybackRepository
    val youtubeMusicDownloadPlaybackRepository: YouTubeMusicPlaybackRepository
    suspend fun neteaseDownloadSource(): moe.ouom.neriplayer.data.model.netease.playback.NeteaseAudioSourcePreference? = null
}
