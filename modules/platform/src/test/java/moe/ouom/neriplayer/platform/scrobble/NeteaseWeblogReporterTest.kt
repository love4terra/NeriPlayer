package moe.ouom.neriplayer.platform.scrobble

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class NeteaseWeblogReporterTest {

    @Test
    fun `weblog policy enforces 30s threshold and 80 percent rule for short tracks`() {
        assertFalse(NeteaseWeblogPolicy.shouldReportPlayLog(30_000L, 200_000L))
        assertTrue(NeteaseWeblogPolicy.shouldReportPlayLog(31_000L, 200_000L))
        assertFalse(NeteaseWeblogPolicy.shouldReportPlayLog(7_000L, 10_000L))
        assertTrue(NeteaseWeblogPolicy.shouldReportPlayLog(8_000L, 10_000L))
        assertFalse(NeteaseWeblogPolicy.shouldReportPlayLog(4_000L, 5_000L))
        assertTrue(NeteaseWeblogPolicy.shouldReportPlayLog(5_000L, 5_000L))
        assertEquals(200L, NeteaseWeblogPolicy.computeReportedSeconds(250_000L, 200_000L))
    }

    @Test
    fun `local song matcher verifies matchedSongId detail before searching`(): Unit = runBlocking {
        val client = mock(NeteaseClient::class.java)
        `when`(client.hasLogin()).thenReturn(true)
        `when`(client.getSongDetailCancellable(listOf(186016L))).thenReturn(
            """{"code":200,"songs":[{"id":186016,"name":"晴天","ar":[{"name":"周杰伦"}],"dt":269000}]}"""
        )

        val reporter = NeteaseWeblogReporter(client)
        val track = NeteaseScrobbleTrack(
            songId = null,
            title = "晴天",
            artist = "周杰倫",
            durationMs = 269_400L,
            isLocal = true,
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "186016"
        )

        val resolved = reporter.resolveReportableSongId(track, allowLocalMatch = true)
        assertEquals(186016L, resolved)
        verify(client, times(1)).getSongDetailCancellable(listOf(186016L))
        verify(client, never()).searchSongsCancellable("晴天 周杰倫", 10, 0, 1, true)
    }

    @Test
    fun `local song matcher skips tracks with missing match or version mismatch`(): Unit = runBlocking {
        val client = mock(NeteaseClient::class.java)
        `when`(client.hasLogin()).thenReturn(true)
        `when`(client.searchSongsCancellable("七里香 周杰伦", 10, 0, 1, true)).thenReturn(
            """{"code":200,"result":{"songs":[{"id":1001,"name":"七里香 (Live)","ar":[{"name":"周杰伦"}],"dt":299000}]}}"""
        )
        `when`(client.searchSongsCancellable("稻香 周杰伦", 10, 0, 1, true)).thenReturn(
            """{"code":200,"result":{"songs":[{"id":2002,"name":"稻香","ar":[{"name":"周杰倫"}],"dt":223000}]}}"""
        )

        val reporter = NeteaseWeblogReporter(client)

        val rejectedLive = reporter.resolveReportableSongId(
            NeteaseScrobbleTrack(songId = null, title = "七里香", artist = "周杰伦", durationMs = 299_000L, isLocal = true),
            allowLocalMatch = true
        )
        assertNull(rejectedLive)

        val rejectedUnknownArtist = reporter.resolveReportableSongId(
            NeteaseScrobbleTrack(songId = null, title = "晴天", artist = "Unknown Artist", durationMs = 269_000L, isLocal = true),
            allowLocalMatch = true
        )
        assertNull(rejectedUnknownArtist)

        val disabledLocal = reporter.resolveReportableSongId(
            NeteaseScrobbleTrack(songId = null, title = "稻香", artist = "周杰伦", durationMs = 223_000L, isLocal = true),
            allowLocalMatch = false
        )
        assertNull(disabledLocal)

        val matched = reporter.resolveReportableSongId(
            NeteaseScrobbleTrack(songId = null, title = "稻香", artist = "周杰伦", durationMs = 223_500L, isLocal = true),
            allowLocalMatch = true
        )
        assertEquals(2002L, matched)

        // Second query hits cache cleanly
        val cachedMatch = reporter.resolveReportableSongId(
            NeteaseScrobbleTrack(songId = null, title = "稻香", artist = "周杰伦", durationMs = 223_500L, isLocal = true),
            allowLocalMatch = true
        )
        assertEquals(2002L, cachedMatch)
        verify(client, times(1)).searchSongsCancellable("稻香 周杰伦", 10, 0, 1, true)
    }

    @Test
    fun `reporter posts startplay and play weblog payloads when logged in`(): Unit = runBlocking {
        val client = mock(NeteaseClient::class.java)
        `when`(client.hasLogin()).thenReturn(true)
        val captor = ArgumentCaptor.forClass(String::class.java)
        `when`(client.reportWeblogCancellable(captor.capture())).thenReturn("""{"code":200}""")

        val reporter = NeteaseWeblogReporter(client)
        assertTrue(reporter.sendStartPlay(186016L))
        assertFalse(reporter.sendPlay(186016L, playedMs = 15_000L, durationMs = 269_000L, NeteaseWeblogEndReason.UI))
        assertTrue(reporter.sendPlay(186016L, playedMs = 45_200L, durationMs = 269_000L, NeteaseWeblogEndReason.PLAY_END))

        val payloads = captor.allValues
        assertEquals(2, payloads.size)

        val startObj = JSONArray(payloads[0]).getJSONObject(0)
        assertEquals("startplay", startObj.getString("action"))
        assertEquals(186016L, startObj.getJSONObject("json").getLong("id"))

        val playObj = JSONArray(payloads[1]).getJSONObject(0)
        assertEquals("play", playObj.getString("action"))
        assertEquals(186016L, playObj.getJSONObject("json").getLong("id"))
        assertEquals(45L, playObj.getJSONObject("json").getLong("time"))
        assertEquals("playend", playObj.getJSONObject("json").getString("end"))
    }
}
