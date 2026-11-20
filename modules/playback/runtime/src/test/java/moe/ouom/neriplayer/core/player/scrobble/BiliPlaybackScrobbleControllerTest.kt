package moe.ouom.neriplayer.core.player.scrobble

import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.scrobble.BiliPlaybackHistoryReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class BiliPlaybackScrobbleControllerTest {

    private data class RecordedHeartbeat(
        val aid: Long,
        val bvid: String,
        val cid: Long,
        val playedTimeSec: Long,
        val playType: Int,
        val startTsSec: Long
    )

    private suspend fun awaitEventCount(
        events: List<RecordedHeartbeat>,
        expectedCount: Int,
        timeoutMs: Long = 5_000L
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (events.size < expectedCount && System.nanoTime() < deadline) {
            delay(10)
        }
        assertEquals(expectedCount, events.size)
    }

    private fun biliSong(
        aid: Long = 114514L,
        bvid: String = "BV1ZVdJYcEjF",
        cid: Long? = 223344L
    ): SongItem = SongItem(
        id = aid,
        name = "B站测试曲目",
        artist = "UP主",
        album = if (cid != null) "Bilibili|$cid|$bvid" else "Bilibili|$bvid",
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null,
        channelId = "bilibili",
        audioId = aid.toString(),
        subAudioId = cid?.toString()
    )

    @Test
    fun `reports start heartbeat, pause progress, and completion heartbeat`(): Unit = runBlocking {
        val client = mock(BiliClient::class.java)
        `when`(client.hasLoginSession()).thenReturn(true)
        val events = Collections.synchronizedList(mutableListOf<RecordedHeartbeat>())
        `when`(
            client.reportPlaybackHeartbeat(
                anyLong(),
                anyString(),
                anyLong(),
                anyLong(),
                anyInt(),
                anyLong()
            )
        ).thenAnswer { invocation ->
            events += RecordedHeartbeat(
                aid = invocation.getArgument(0),
                bvid = invocation.getArgument(1),
                cid = invocation.getArgument(2),
                playedTimeSec = invocation.getArgument(3),
                playType = invocation.getArgument(4),
                startTsSec = invocation.getArgument(5)
            )
            true
        }

        var nowMs = 100_000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = BiliPlaybackScrobbleController(
                ioScopeProvider = { scope },
                isScrobbleEnabled = { true },
                reporter = BiliPlaybackHistoryReporter(client),
                clockMs = { nowMs },
                startPlayDelayMs = 0L,
                heartbeatIntervalMs = 0L
            )

            controller.onSongChanged(biliSong())
            controller.onPlayingChanged(true)
            awaitEventCount(events, 1)

            assertEquals(BiliPlaybackHistoryReporter.PLAY_TYPE_START, events[0].playType)
            assertEquals(223344L, events[0].cid)
            assertEquals(100L, events[0].startTsSec)

            controller.onProgress(42_000L)
            nowMs += 20_000L
            controller.onPlayingChanged(false)
            awaitEventCount(events, 2)

            assertEquals(BiliPlaybackHistoryReporter.PLAY_TYPE_PAUSE, events[1].playType)
            assertEquals(42L, events[1].playedTimeSec)

            controller.onPlayingChanged(true)
            controller.onTrackEnded()
            awaitEventCount(events, 3)

            assertEquals(BiliPlaybackHistoryReporter.PLAY_TYPE_END, events[2].playType)
            assertEquals(-1L, events[2].playedTimeSec)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `defers start heartbeat until onTrackResolved delivers active part cid`(): Unit = runBlocking {
        val client = mock(BiliClient::class.java)
        `when`(client.hasLoginSession()).thenReturn(true)
        val events = Collections.synchronizedList(mutableListOf<RecordedHeartbeat>())
        `when`(
            client.reportPlaybackHeartbeat(
                anyLong(),
                anyString(),
                anyLong(),
                anyLong(),
                anyInt(),
                anyLong()
            )
        ).thenAnswer { invocation ->
            events += RecordedHeartbeat(
                aid = invocation.getArgument(0),
                bvid = invocation.getArgument(1),
                cid = invocation.getArgument(2),
                playedTimeSec = invocation.getArgument(3),
                playType = invocation.getArgument(4),
                startTsSec = invocation.getArgument(5)
            )
            true
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val songWithoutCid = SongItem(
                id = 5566L,
                name = "多P视频",
                artist = "UP主",
                album = "Bilibili",
                albumId = 0L,
                durationMs = 120_000L,
                coverUrl = null,
                channelId = "bilibili",
                audioId = "5566"
            )
            val controller = BiliPlaybackScrobbleController(
                ioScopeProvider = { scope },
                isScrobbleEnabled = { true },
                reporter = BiliPlaybackHistoryReporter(client),
                startPlayDelayMs = 0L,
                heartbeatIntervalMs = 0L
            )

            controller.onSongChanged(songWithoutCid)
            controller.onPlayingChanged(true)
            assertTrue(events.isEmpty())

            controller.onTrackResolved(
                song = songWithoutCid,
                aid = 5566L,
                bvid = "BV1ZVdJYcEjF",
                cid = 889900L,
                durationSec = 120
            )
            awaitEventCount(events, 1)

            assertEquals(889900L, events[0].cid)
            assertEquals(BiliPlaybackHistoryReporter.PLAY_TYPE_START, events[0].playType)
        } finally {
            scope.cancel()
        }
    }
}
