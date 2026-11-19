package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.netease.playback.NeteaseAudioSourcePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlayerManagerNeteaseCacheKeyTest {

    @Test
    fun fallbackNamespaceDoesNotReuseLegacyNeteaseCache() {
        val legacyKey = PlayerManager.buildNeteasePlaybackCacheKey(
            songId = 123L,
            preferredQuality = "exhigh",
            useFallbackNamespace = false,
            sourceNamespace = ""
        )
        val fallbackKey = PlayerManager.buildNeteasePlaybackCacheKey(
            songId = 123L,
            preferredQuality = "exhigh",
            useFallbackNamespace = true,
            sourceNamespace = ""
        )

        assertEquals("netease-123-exhigh", legacyKey)
        assertEquals("netease-123-exhigh-fallback-v1", fallbackKey)
        assertNotEquals(legacyKey, fallbackKey)
    }

    @Test
    fun blankQualityUsesStableDefaultInBothNamespaces() {
        assertEquals(
            "netease-123-exhigh",
            PlayerManager.buildNeteasePlaybackCacheKey(
                songId = 123L,
                preferredQuality = "   ",
                useFallbackNamespace = false,
                sourceNamespace = ""
            )
        )
        assertEquals(
            "netease-123-exhigh-fallback-v1",
            PlayerManager.buildNeteasePlaybackCacheKey(
                songId = 123L,
                preferredQuality = "   ",
                useFallbackNamespace = true,
                sourceNamespace = ""
            )
        )
    }

    @Test
    fun previewCacheKeyDoesNotReusePlaybackCacheNamespaces() {
        val previewKey = PlayerManager.buildNeteasePreviewCacheKey(
            songId = 123L,
            preferredQuality = "exhigh"
        )

        assertEquals("netease-preview-v1-123-exhigh", previewKey)
        assertNotEquals(
            previewKey,
            PlayerManager.buildNeteasePlaybackCacheKey(
                songId = 123L,
                preferredQuality = "exhigh",
                useFallbackNamespace = false,
                sourceNamespace = ""
            )
        )
        assertNotEquals(
            previewKey,
            PlayerManager.buildNeteasePlaybackCacheKey(
                songId = 123L,
                preferredQuality = "exhigh",
                useFallbackNamespace = true,
                sourceNamespace = ""
            )
        )
        assertNotEquals(
            previewKey,
            PlayerManager.buildNeteasePlaybackCacheKey(
                songId = 123L,
                preferredQuality = "exhigh",
                useFallbackNamespace = false,
                sourceNamespace = NeteaseAudioSourcePreference.GD_MUSIC_FIRST.cacheNamespace()
            )
        )
    }

    @Test
    fun gdMusicSourceNamespacesIsolateFromOfficialAndEachOther() {
        val keys = NeteaseAudioSourcePreference.entries.map { pref ->
            PlayerManager.buildNeteasePlaybackCacheKey(
                songId = 123L,
                preferredQuality = "exhigh",
                useFallbackNamespace = false,
                sourceNamespace = pref.cacheNamespace()
            )
        }

        assertEquals(keys.size, keys.toSet().size)
        assertEquals("netease-123-exhigh-gd_first_v1", keys[NeteaseAudioSourcePreference.GD_MUSIC_FIRST.ordinal])
        assertEquals("netease-123-exhigh", keys[NeteaseAudioSourcePreference.OFFICIAL_ONLY.ordinal])
    }
}
