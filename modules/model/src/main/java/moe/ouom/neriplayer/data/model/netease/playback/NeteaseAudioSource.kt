package moe.ouom.neriplayer.data.model.netease.playback

import java.util.Locale

const val DEFAULT_NETEASE_PLAYBACK_SOURCE = "gd_music_first"
const val DEFAULT_NETEASE_DOWNLOAD_SOURCE = "follow_playback"

enum class NeteaseAudioSourcePreference(
    val storageValue: String
) {
    GD_MUSIC_FIRST("gd_music_first"),
    OFFICIAL_FIRST("official_first"),
    GD_MUSIC_ONLY("gd_music_only"),
    OFFICIAL_ONLY("official_only");

    fun usesGdMusic(): Boolean = this != OFFICIAL_ONLY

    fun cacheNamespace(): String = when (this) {
        GD_MUSIC_FIRST -> "gd_first_v1"
        OFFICIAL_FIRST -> "official_gd_v1"
        GD_MUSIC_ONLY -> "gd_only_v1"
        OFFICIAL_ONLY -> ""
    }
}

enum class NeteaseDownloadSourcePreference(
    val storageValue: String
) {
    FOLLOW_PLAYBACK("follow_playback"),
    GD_MUSIC_FIRST("gd_music_first"),
    OFFICIAL_FIRST("official_first"),
    GD_MUSIC_ONLY("gd_music_only"),
    OFFICIAL_ONLY("official_only");

    fun resolveForPlayback(playbackSource: NeteaseAudioSourcePreference): NeteaseAudioSourcePreference {
        return when (this) {
            FOLLOW_PLAYBACK -> playbackSource
            GD_MUSIC_FIRST -> NeteaseAudioSourcePreference.GD_MUSIC_FIRST
            OFFICIAL_FIRST -> NeteaseAudioSourcePreference.OFFICIAL_FIRST
            GD_MUSIC_ONLY -> NeteaseAudioSourcePreference.GD_MUSIC_ONLY
            OFFICIAL_ONLY -> NeteaseAudioSourcePreference.OFFICIAL_ONLY
        }
    }
}

object NeteasePlaybackSourcePreferencePolicy {
    fun normalize(value: String): String = fromStorage(value).storageValue

    fun fromStorage(value: String?): NeteaseAudioSourcePreference {
        return when (value?.trim()?.lowercase(Locale.ROOT)) {
            NeteaseAudioSourcePreference.GD_MUSIC_FIRST.storageValue,
            "gdmusic_first",
            "gd_first" -> NeteaseAudioSourcePreference.GD_MUSIC_FIRST
            NeteaseAudioSourcePreference.OFFICIAL_FIRST.storageValue,
            "official" -> NeteaseAudioSourcePreference.OFFICIAL_FIRST
            NeteaseAudioSourcePreference.GD_MUSIC_ONLY.storageValue,
            "gdmusic_only",
            "gd_only" -> NeteaseAudioSourcePreference.GD_MUSIC_ONLY
            NeteaseAudioSourcePreference.OFFICIAL_ONLY.storageValue ->
                NeteaseAudioSourcePreference.OFFICIAL_ONLY
            else -> NeteaseAudioSourcePreference.GD_MUSIC_FIRST
        }
    }
}

object NeteaseDownloadSourcePreferencePolicy {
    fun normalize(value: String): String = fromStorage(value).storageValue

    fun fromStorage(value: String?): NeteaseDownloadSourcePreference {
        return when (value?.trim()?.lowercase(Locale.ROOT)) {
            NeteaseDownloadSourcePreference.FOLLOW_PLAYBACK.storageValue ->
                NeteaseDownloadSourcePreference.FOLLOW_PLAYBACK
            NeteaseDownloadSourcePreference.GD_MUSIC_FIRST.storageValue ->
                NeteaseDownloadSourcePreference.GD_MUSIC_FIRST
            NeteaseDownloadSourcePreference.OFFICIAL_FIRST.storageValue ->
                NeteaseDownloadSourcePreference.OFFICIAL_FIRST
            NeteaseDownloadSourcePreference.GD_MUSIC_ONLY.storageValue ->
                NeteaseDownloadSourcePreference.GD_MUSIC_ONLY
            NeteaseDownloadSourcePreference.OFFICIAL_ONLY.storageValue ->
                NeteaseDownloadSourcePreference.OFFICIAL_ONLY
            else -> NeteaseDownloadSourcePreference.FOLLOW_PLAYBACK
        }
    }
}
