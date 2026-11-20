package moe.ouom.neriplayer.platform.scrobble

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliTestHttp
import moe.ouom.neriplayer.platform.bilibili.api.client.json
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliPlaybackHistoryReporterTest {

    @Test
    fun `reportPlaybackHeartbeat posts expected form fields and cookies when signed in`() = runTest {
        val http = BiliTestHttp { request ->
            if (request.url.encodedPath == "/x/click-interface/web/heartbeat") {
                json("""{"code":0,"message":"0","ttl":1}""")
            } else {
                null
            }
        }
        try {
            val client = BiliClient(
                cookieRepo = {
                    mapOf(
                        "SESSDATA" to "sess-token",
                        "bili_jct" to "csrf-token",
                        "DedeUserID" to "42"
                    )
                },
                client = http.client
            )
            val reporter = BiliPlaybackHistoryReporter(client)

            val ok = reporter.reportHeartbeat(
                track = BiliScrobbleTrack(
                    aid = 12345L,
                    bvid = "BV1ZVdJYcEjF",
                    cid = 67890L,
                    durationSec = 240
                ),
                playedTimeSec = 15L,
                playType = BiliPlaybackHistoryReporter.PLAY_TYPE_START,
                startTsSec = 1700000000L
            )

            assertTrue(ok)
            val request = http.requestsTo("/x/click-interface/web/heartbeat").single()
            assertEquals("POST", request.method)
            assertEquals("https://www.bilibili.com/video/BV1ZVdJYcEjF", request.header("Referer"))
            val cookie = request.header("Cookie").orEmpty()
            assertTrue(cookie.contains("SESSDATA=sess-token"))
            assertTrue(cookie.contains("bili_jct=csrf-token"))

            val bodyText = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val params = bodyText.split('&').associate { pair ->
                val idx = pair.indexOf('=')
                pair.substring(0, idx) to pair.substring(idx + 1)
            }
            assertEquals("12345", params["aid"])
            assertEquals("BV1ZVdJYcEjF", params["bvid"])
            assertEquals("67890", params["cid"])
            assertEquals("15", params["played_time"])
            assertEquals("1", params["play_type"])
            assertEquals("1700000000", params["start_ts"])
            assertEquals("csrf-token", params["csrf"])
            assertEquals("42", params["mid"])
        } finally {
            http.close()
        }
    }

    @Test
    fun `reportPlaybackHeartbeat skips network request when logged out or missing csrf`() = runTest {
        val http = BiliTestHttp { error("unexpected network call") }
        try {
            val loggedOutClient = BiliClient(cookieRepo = { emptyMap() }, client = http.client)
            val missingCsrfClient = BiliClient(
                cookieRepo = { mapOf("SESSDATA" to "sess-only") },
                client = http.client
            )

            assertFalse(
                BiliPlaybackHistoryReporter(loggedOutClient).reportHeartbeat(
                    BiliScrobbleTrack(123L, "BV1ZVdJYcEjF", 456L, 120),
                    playedTimeSec = 0L,
                    playType = BiliPlaybackHistoryReporter.PLAY_TYPE_START,
                    startTsSec = 100L
                )
            )
            assertFalse(
                BiliPlaybackHistoryReporter(missingCsrfClient).reportHeartbeat(
                    BiliScrobbleTrack(123L, "BV1ZVdJYcEjF", 456L, 120),
                    playedTimeSec = 0L,
                    playType = BiliPlaybackHistoryReporter.PLAY_TYPE_START,
                    startTsSec = 100L
                )
            )
            assertTrue(http.requests.isEmpty())
        } finally {
            http.close()
        }
    }
}
