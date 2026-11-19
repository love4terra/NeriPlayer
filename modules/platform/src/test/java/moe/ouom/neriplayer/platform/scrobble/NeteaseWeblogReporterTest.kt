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
import org.mockito.ArgumentMatchers.anyString
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
    fun `reporter posts startplay and play weblog payloads when logged in and treats empty 200 body as success`(): Unit = runBlocking {
        val client = mock(NeteaseClient::class.java)
        `when`(client.hasLogin()).thenReturn(true)
        val payloads = mutableListOf<String>()
        var callCount = 0
        `when`(client.reportWeblogCancellable(anyString())).thenAnswer { invocation ->
            payloads += invocation.getArgument<String>(0)
            callCount++
            if (callCount == 1) """{"code":200}""" else ""
        }

        val reporter = NeteaseWeblogReporter(client)
        assertTrue(reporter.sendStartPlay(186016L))
        assertFalse(reporter.sendPlay(186016L, playedMs = 15_000L, durationMs = 269_000L, NeteaseWeblogEndReason.UI))
        assertTrue(reporter.sendPlay(186016L, playedMs = 45_200L, durationMs = 269_000L, NeteaseWeblogEndReason.PLAY_END))

        assertEquals(2, payloads.size)

        val startObj = JSONArray(payloads[0]).getJSONObject(0)
        assertEquals("startplay", startObj.getString("action"))
        val startJson = startObj.getJSONObject("json")
        assertEquals(186016L, startJson.getLong("id"))
        assertEquals("song", startJson.getString("type"))
        assertEquals("1", startJson.getString("mainsite"))
        assertEquals("1", startJson.getString("mainsiteWeb"))
        assertEquals("id=0", startJson.getString("content"))

        val playObj = JSONArray(payloads[1]).getJSONObject(0)
        assertEquals("play", playObj.getString("action"))
        val playJson = playObj.getJSONObject("json")
        assertEquals(186016L, playJson.getLong("id"))
        assertEquals(45L, playJson.getLong("time"))
        assertEquals("playend", playJson.getString("end"))
        assertEquals("0", playJson.getString("sourceId"))
        assertEquals("list", playJson.getString("source"))
        assertEquals("1", playJson.getString("mainsite"))
        assertEquals("1", playJson.getString("mainsiteWeb"))
        assertEquals("id=0", playJson.getString("content"))
    }

    @Test
    fun `local song matcher enforces fixed duration and passes partial overlap across title artist and album`(): Unit = runBlocking {
        val client = mock(NeteaseClient::class.java)
        `when`(client.hasLogin()).thenReturn(true)
        `when`(client.searchSongsCancellable("Blinding Lights The Weeknd", 10, 0, 1, true)).thenReturn(
            """{"code":200,"result":{"songs":[
                {"id":3001,"name":"Blinding Lights","ar":[{"name":"The Weeknd"},{"name":"ROSALÍA"}],"al":{"name":"After Hours"},"dt":200000}
            ]}}"""
        )
        `when`(client.searchSongsCancellable("サクラ いきものがかり", 10, 0, 1, true)).thenReturn(
            """{"code":200,"result":{"songs":[
                {"id":3002,"name":"さくら","ar":[{"name":"いきものがかり"}],"al":{"name":"My song Your song"},"dt":350000}
            ]}}"""
        )
        `when`(client.searchSongsCancellable("Hikaru Nara Goose house", 10, 0, 1, true)).thenReturn(
            """{"code":200,"result":{"songs":[]}}"""
        )
        `when`(client.searchSongsCancellable("Milk Goose house", 20, 0, 1, true)).thenReturn(
            """{"code":200,"result":{"songs":[
                {"id":3003,"name":"光るなら","ar":[{"name":"Goose house"}],"al":{"name":"Milk"},"dt":253000}
            ]}}"""
        )
        `when`(client.searchSongsCancellable("Unravel TK from Ling tosite sigure", 10, 0, 1, true)).thenReturn(
            """{"code":200,"result":{"songs":[]}}"""
        )
        `when`(client.searchSongsCancellable("Fantastic Magic TK from Ling tosite sigure", 20, 0, 1, true)).thenReturn(
            """{"code":200,"result":{"songs":[]}}"""
        )
        `when`(client.searchSongsCancellable("Unravel Fantastic Magic", 20, 0, 1, true)).thenReturn(
            """{"code":200,"result":{"songs":[
                {"id":3004,"name":"unravel","ar":[{"name":"TK from 凛として時雨"}],"al":{"name":"Fantastic Magic"},"dt":238000}
            ]}}"""
        )
        `when`(client.searchSongsCancellable("偏离时长 周杰伦", 10, 0, 1, true)).thenReturn(
            """{"code":200,"result":{"songs":[
                {"id":3005,"name":"偏离时长","ar":[{"name":"周杰伦"}],"al":{"name":"叶惠美"},"dt":210000}
            ]}}"""
        )

        val reporter = NeteaseWeblogReporter(client)

        assertEquals(
            3001L,
            reporter.resolveReportableSongId(
                NeteaseScrobbleTrack(
                    songId = null,
                    title = "Blinding Lights feat. Rosalia",
                    artist = "The Weeknd",
                    durationMs = 201_200L,
                    isLocal = true
                ),
                allowLocalMatch = true
            )
        )

        assertEquals(
            3002L,
            reporter.resolveReportableSongId(
                NeteaseScrobbleTrack(
                    songId = null,
                    title = "サクラ",
                    artist = "いきものがかり",
                    durationMs = 350_500L,
                    isLocal = true
                ),
                allowLocalMatch = true
            )
        )

        // English title vs Japanese title passes via artist + album + fixed duration
        assertEquals(
            3003L,
            reporter.resolveReportableSongId(
                NeteaseScrobbleTrack(
                    songId = null,
                    title = "Hikaru Nara",
                    artist = "Goose house",
                    album = "Milk (Special Edition)",
                    durationMs = 253_800L,
                    isLocal = true
                ),
                allowLocalMatch = true
            )
        )

        // Different artist spelling passes via title + album + fixed duration
        assertEquals(
            3004L,
            reporter.resolveReportableSongId(
                NeteaseScrobbleTrack(
                    songId = null,
                    title = "Unravel",
                    artist = "TK from Ling tosite sigure",
                    album = "Fantastic Magic",
                    durationMs = 238_000L,
                    isLocal = true
                ),
                allowLocalMatch = true
            )
        )

        // Duration difference > 3s is strictly rejected
        assertNull(
            reporter.resolveReportableSongId(
                NeteaseScrobbleTrack(
                    songId = null,
                    title = "偏离时长",
                    artist = "周杰伦",
                    album = "叶惠美",
                    durationMs = 216_000L,
                    isLocal = true
                ),
                allowLocalMatch = true
            )
        )

        // Unknown duration is strictly rejected
        assertNull(
            reporter.resolveReportableSongId(
                NeteaseScrobbleTrack(
                    songId = null,
                    title = "Unravel",
                    artist = "TK from 凛として時雨",
                    album = "Fantastic Magic",
                    durationMs = 0L,
                    isLocal = true
                ),
                allowLocalMatch = true
            )
        )
    }
}
