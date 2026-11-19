package moe.ouom.neriplayer.core.player.scrobble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.player.buildNeteaseScrobbleTrack
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.scrobble.NeteaseScrobbleTrack
import moe.ouom.neriplayer.platform.scrobble.NeteaseWeblogReporter
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Collections

class NeteasePlaybackScrobbleControllerTest {

    private suspend fun awaitPayloadCount(
        payloads: List<String>,
        expectedCount: Int,
        timeoutMs: Long = 5_000L
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (payloads.size < expectedCount && System.nanoTime() < deadline) {
            delay(10)
        }
        assertEquals(expectedCount, payloads.size)
    }

    private fun testSong(id: Long = 186016L, name: String = "晴天"): SongItem = SongItem(
        id = id,
        name = name,
        artist = "周杰伦",
        album = "叶惠美",
        albumId = 100L,
        durationMs = 269_000L,
        coverUrl = null
    )

    @Test
    fun `accumulates playing time across pauses and skips buffering before reporting on switch`(): Unit = runBlocking {
        val client = mock(NeteaseClient::class.java)
        `when`(client.hasLogin()).thenReturn(true)
        val payloads = Collections.synchronizedList(mutableListOf<String>())
        `when`(client.reportWeblogCancellable(org.mockito.ArgumentMatchers.anyString())).thenAnswer { invocation ->
            payloads += invocation.getArgument<String>(0)
            """{"code":200}"""
        }

        var nowMs = 1_000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = NeteasePlaybackScrobbleController(
                ioScopeProvider = { scope },
                isScrobbleEnabled = { true },
                isLocalScrobbleEnabled = { true },
                reporter = NeteaseWeblogReporter(client),
                resolveTrack = { song ->
                    NeteaseScrobbleTrack(
                        songId = song.id,
                        title = song.name,
                        artist = song.artist,
                        durationMs = song.durationMs,
                        isLocal = false
                    )
                },
                clockMs = { nowMs },
                startPlayDelayMs = 0L
            )

            controller.onSongChanged(testSong())
            controller.onEnginePlayingChanged(isPlaying = true, progressAdvanced = true)
            awaitPayloadCount(payloads, 1)

            // Play 20s then pause for 60s (pause duration must NOT count)
            nowMs += 20_000L
            controller.onPlayingChanged(false)
            nowMs += 60_000L

            // Resume and play another 15s (total 35s > 30s threshold)
            controller.onEnginePlayingChanged(isPlaying = true, progressAdvanced = true)
            nowMs += 15_000L

            controller.onSongChanged(testSong(id = 186017L, name = "七里香"))
            awaitPayloadCount(payloads, 2)

            assertEquals(2, payloads.size)
            val startObj = JSONArray(payloads[0]).getJSONObject(0)
            assertEquals("startplay", startObj.getString("action"))
            assertEquals(186016L, startObj.getJSONObject("json").getLong("id"))

            val playObj = JSONArray(payloads[1]).getJSONObject(0)
            assertEquals("play", playObj.getString("action"))
            assertEquals(186016L, playObj.getJSONObject("json").getLong("id"))
            assertEquals(35L, playObj.getJSONObject("json").getLong("time"))
            assertEquals("ui", playObj.getJSONObject("json").getString("end"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `reports playend on track completion and ignores switches under threshold`(): Unit = runBlocking {
        val client = mock(NeteaseClient::class.java)
        `when`(client.hasLogin()).thenReturn(true)
        val payloads = Collections.synchronizedList(mutableListOf<String>())
        `when`(client.reportWeblogCancellable(org.mockito.ArgumentMatchers.anyString())).thenAnswer { invocation ->
            payloads += invocation.getArgument<String>(0)
            """{"code":200}"""
        }

        var nowMs = 5_000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = NeteasePlaybackScrobbleController(
                ioScopeProvider = { scope },
                isScrobbleEnabled = { true },
                isLocalScrobbleEnabled = { true },
                reporter = NeteaseWeblogReporter(client),
                resolveTrack = { song ->
                    NeteaseScrobbleTrack(
                        songId = song.id,
                        title = song.name,
                        artist = song.artist,
                        durationMs = song.durationMs,
                        isLocal = false
                    )
                },
                clockMs = { nowMs },
                startPlayDelayMs = 0L
            )

            // Short skip (<30s) -> only startplay, no play log
            controller.onSongChanged(testSong(id = 1L, name = "A"))
            controller.onEnginePlayingChanged(isPlaying = true, progressAdvanced = true)
            awaitPayloadCount(payloads, 1)
            nowMs += 10_000L
            controller.onTrackEnded()
            delay(50)
            assertTrue(payloads.none { JSONArray(it).getJSONObject(0).getString("action") == "play" })

            // Full play (>30s) -> reports playend
            controller.onSongChanged(testSong(id = 2L, name = "B"))
            controller.onEnginePlayingChanged(isPlaying = true, progressAdvanced = true)
            awaitPayloadCount(payloads, 2)
            nowMs += 120_000L
            controller.onTrackEnded()
            awaitPayloadCount(payloads, 3)

            val lastPlay = JSONArray(payloads.last()).getJSONObject(0)
            assertEquals("play", lastPlay.getString("action"))
            assertEquals(120L, lastPlay.getJSONObject("json").getLong("time"))
            assertEquals("playend", lastPlay.getJSONObject("json").getString("end"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `initial playback starts tracking when onPlayingChanged(true) fires after pre-advance onIsPlayingChanged(true)`(): Unit = runBlocking {
        val client = mock(NeteaseClient::class.java)
        `when`(client.hasLogin()).thenReturn(true)
        val payloads = Collections.synchronizedList(mutableListOf<String>())
        `when`(client.reportWeblogCancellable(org.mockito.ArgumentMatchers.anyString())).thenAnswer { invocation ->
            payloads += invocation.getArgument<String>(0)
            """{"code":200}"""
        }

        var nowMs = 10_000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = NeteasePlaybackScrobbleController(
                ioScopeProvider = { scope },
                isScrobbleEnabled = { true },
                isLocalScrobbleEnabled = { true },
                reporter = NeteaseWeblogReporter(client),
                resolveTrack = { song ->
                    NeteaseScrobbleTrack(
                        songId = song.id,
                        title = song.name,
                        artist = song.artist,
                        durationMs = song.durationMs,
                        isLocal = false
                    )
                },
                clockMs = { nowMs },
                startPlayDelayMs = 0L
            )

            controller.onSongChanged(testSong(id = 186016L, name = "晴天"))
            // ExoPlayer fires onIsPlayingChanged(true) before progress advances
            controller.onEnginePlayingChanged(isPlaying = true, progressAdvanced = false)
            // First progress advance triggers onPlayingChanged(true)
            controller.onPlayingChanged(true)
            awaitPayloadCount(payloads, 1)

            nowMs += 40_000L
            controller.onTrackEnded()
            awaitPayloadCount(payloads, 2)

            assertEquals("startplay", JSONArray(payloads[0]).getJSONObject(0).getString("action"))
            val playObj = JSONArray(payloads[1]).getJSONObject(0)
            assertEquals("play", playObj.getString("action"))
            assertEquals(40L, playObj.getJSONObject("json").getLong("time"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `downloaded netease songs retain direct netease song id while pure local imports stay local`() {
        val downloadedNetease = SongItem(
            id = 2L,
            name = "晴天",
            artist = "周杰伦",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 269_000L,
            coverUrl = null,
            mediaUri = "file:///storage/emulated/0/Music/晴天.flac",
            localFilePath = "/storage/emulated/0/Music/晴天.flac",
            channelId = "netease",
            audioId = "186016",
            sourceStableKey = SongIdentity(186016L, "netease", null).stableKey()
        )
        val pureLocal = SongItem(
            id = 999L,
            name = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            albumId = 0L,
            durationMs = 269_000L,
            coverUrl = null,
            mediaUri = "file:///storage/emulated/0/Music/晴天.flac",
            localFilePath = "/storage/emulated/0/Music/晴天.flac",
            channelId = "local",
            audioId = "999"
        )

        val neteaseTrack = buildNeteaseScrobbleTrack(downloadedNetease, isLocalFile = true)!!
        assertFalse(neteaseTrack.isLocal)
        assertEquals(186016L, neteaseTrack.songId)
        assertEquals("", neteaseTrack.album)

        val localTrack = buildNeteaseScrobbleTrack(pureLocal, isLocalFile = true)!!
        assertTrue(localTrack.isLocal)
        assertNull(localTrack.songId)
        assertEquals("叶惠美", localTrack.album)
    }
}
