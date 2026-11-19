package moe.ouom.neriplayer.platform.gdmusic

import moe.ouom.neriplayer.platform.gdmusic.GdMusicTextMatcher.SongMatchCandidate
import moe.ouom.neriplayer.platform.gdmusic.GdMusicTextMatcher.SongMatchTarget
import moe.ouom.neriplayer.platform.gdmusic.GdMusicTextMatcher.VersionTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GdMusicTextMatcherTest {

    @Test
    fun `t2s converts traditional Chinese titles consistently`() {
        assertEquals("晴天 爱在西元前", GdMusicTextMatcher.t2s("晴天 愛在西元前"))
        assertEquals(
            GdMusicTextMatcher.compactText("紅豆"),
            GdMusicTextMatcher.compactText("红豆")
        )
    }

    @Test
    fun `stripParentheticals removes bracketed subtitles`() {
        assertEquals(
            "告白气球",
            GdMusicTextMatcher.stripParentheticals("告白气球 (Live) [伴奏]")
        )
        assertEquals(
            "七里香",
            GdMusicTextMatcher.stripParentheticals("七里香（Acoustic Ver.）")
        )
    }

    @Test
    fun `version tags prevent remix or live mismatch`() {
        assertEquals(setOf(VersionTag.LIVE), GdMusicTextMatcher.extractVersionTags("晴天 (Live 现场版)"))
        assertEquals(setOf(VersionTag.REMIX), GdMusicTextMatcher.extractVersionTags("晴天 (Remix)"))
        assertEquals(setOf(VersionTag.REMIX, VersionTag.DJ), GdMusicTextMatcher.extractVersionTags("晴天 (DJ Remix)"))

        val target = SongMatchTarget(
            title = "晴天",
            artists = listOf("周杰伦"),
            durationMs = 269_000L
        )
        val liveCandidate = SongMatchCandidate(
            item = "live-id",
            title = "晴天 (Live)",
            artists = listOf("周杰伦"),
            durationMs = 269_000L
        )
        val exactCandidate = SongMatchCandidate(
            item = "studio-id",
            title = "晴天",
            artists = listOf("周杰倫"),
            durationMs = 269_500L
        )

        assertNull(GdMusicTextMatcher.findBestMatch(target, listOf(liveCandidate)))
        assertEquals(
            "studio-id",
            GdMusicTextMatcher.findBestMatch(target, listOf(liveCandidate, exactCandidate))
        )
    }

    @Test
    fun `splitArtists and artistsMatch support multi-artist strings`() {
        val artists = GdMusicTextMatcher.splitArtists("周杰倫 / 費玉清 feat. 張惠妹")
        assertEquals(listOf("周杰倫", "費玉清", "張惠妹"), artists)
        assertTrue(GdMusicTextMatcher.artistsMatch(listOf("周杰伦"), artists))
        assertFalse(GdMusicTextMatcher.artistsMatch(listOf("林俊杰"), artists))
    }

    @Test
    fun `duration mismatch beyond threshold is rejected`() {
        val target = SongMatchTarget(
            title = "稻香",
            artists = listOf("周杰伦"),
            durationMs = 223_000L
        )
        val wrongDuration = SongMatchCandidate(
            item = "bad",
            title = "稻香",
            artists = listOf("周杰伦"),
            durationMs = 150_000L
        )
        assertNull(
            GdMusicTextMatcher.findBestMatch(
                target = target,
                candidates = listOf(wrongDuration),
                maxDurationDiffMs = 8_000L
            )
        )
    }
}
