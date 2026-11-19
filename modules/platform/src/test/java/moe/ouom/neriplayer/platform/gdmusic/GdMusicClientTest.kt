package moe.ouom.neriplayer.platform.gdmusic

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.platform.gdmusic.GdMusicRateLimiter.RequestTier
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GdMusicClientTest {

    private fun jsonResponse(request: Request, code: Int, body: String, retryAfter: String? = null): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "ERR")
            .body(body.toResponseBody("application/json".toMediaType()))
        if (retryAfter != null) {
            builder.header("Retry-After", retryAfter)
        }
        return builder.build()
    }

    @Test
    fun `direct netease request attaches gdstudio headers and upgrades netease cdn url to https`() = runTest {
        val captured = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            captured += request
            jsonResponse(
                request,
                200,
                """{"url":"http://m701.music.126.net/2026/track.mp3","br":320,"size":10485760}"""
            )
        }.build()

        val gdClient = GdMusicClient(client)
        val resolved = gdClient.resolveNeteaseUrl(
            songId = 186016L,
            title = "晴天",
            artist = "周杰伦",
            durationMs = 269_000L,
            preferredQuality = "exhigh"
        )

        assertNotNull(resolved)
        assertEquals("https://m701.music.126.net/2026/track.mp3", resolved!!.url)
        assertEquals("netease", resolved.source)
        assertEquals(320, resolved.actualBitrate)
        assertEquals("exhigh", resolved.qualityLevel)
        assertEquals(GdMusicClient.NETEASE_MEDIA_REFERER, resolved.headers["Referer"])
        assertEquals(1, captured.size)
        assertEquals(GdMusicClient.DEFAULT_REFERER, captured[0].header("Referer"))
        assertEquals("netease", captured[0].url.queryParameter("source"))
        assertEquals("320", captured[0].url.queryParameter("br"))

        // Subsequent resolve within TTL hits memory cache without extra HTTP calls
        val cached = gdClient.resolveNeteaseUrl(
            songId = 186016L,
            title = "晴天",
            artist = "周杰伦",
            preferredQuality = "exhigh"
        )
        assertEquals(resolved, cached)
        assertEquals(1, captured.size)
    }

    @Test
    fun `cascades bitrates and falls back to joox match when direct netease url is unavailable`() = runTest {
        val requestedCalls = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val types = request.url.queryParameter("types")
            val source = request.url.queryParameter("source")
            val br = request.url.queryParameter("br")
            requestedCalls += "$types:$source:$br"
            val body = when {
                types == "url" && source == "netease" -> """{"url":""}"""
                types == "search" && source == "joox" ->
                    """[{"id":"jx-100","name":"晴天 (Live)","artist":["周杰伦"]},{"id":"jx-200","name":"晴天","artist":["周杰倫"]}]"""
                types == "url" && source == "joox" && request.url.queryParameter("id") == "jx-200" && br == "320" ->
                    """{"url":"https://joox-cdn.example.com/qingtian.mp3","br":320,"size":8888888}"""
                else -> """{"url":""}"""
            }
            jsonResponse(request, 200, body)
        }.build()

        val gdClient = GdMusicClient(client)
        val resolved = gdClient.resolveNeteaseUrl(
            songId = 186016L,
            title = "晴天",
            artist = "周杰伦",
            preferredQuality = "lossless",
            allowJooxFallback = true
        )

        assertNotNull(resolved)
        assertEquals("https://joox-cdn.example.com/qingtian.mp3", resolved!!.url)
        assertEquals("joox", resolved.source)
        assertTrue(resolved.headers.isEmpty())
        assertEquals(
            listOf(
                "url:netease:999",
                "url:netease:320",
                "url:netease:192",
                "search:joox:null",
                "url:joox:320"
            ),
            requestedCalls
        )
    }

    @Test
    fun `rate limiter enforces 429 cooldown prefetch ceiling and 5 minute window`() = runTest {
        var nowMs = 1_000_000L
        val limiter = GdMusicRateLimiter(clockMs = { nowMs })

        val client = OkHttpClient.Builder().addInterceptor { chain ->
            jsonResponse(chain.request(), 429, """{"error":"rate limit"}""", retryAfter = "20")
        }.build()
        val gdClient = GdMusicClient(client, rateLimiter = limiter)

        val blocked = gdClient.resolveNeteaseUrl(
            songId = 100L,
            title = "稻香",
            artist = "周杰伦",
            preferredQuality = "exhigh"
        )
        assertNull(blocked)
        assertTrue(limiter.isInCooldown())
        assertFalse(limiter.tryAcquireSlot(RequestTier.PRIMARY))

        // After 20 seconds cooldown expires
        nowMs += 20_000L
        assertFalse(limiter.isInCooldown())

        // Pre-429 call consumed 1 slot; remaining prefetch stops at 36 while PRIMARY still works up to 50
        val remainingPrefetch = limiter.remainingWindowSlots(RequestTier.PREFETCH)
        assertEquals(GdMusicRateLimiter.PREFETCH_CEILING - 1, remainingPrefetch)
        repeat(remainingPrefetch) {
            assertTrue(limiter.tryAcquireSlot(RequestTier.PREFETCH))
        }
        assertFalse(limiter.tryAcquireSlot(RequestTier.PREFETCH))
        assertTrue(limiter.tryAcquireSlot(RequestTier.SECONDARY_FALLBACK))

        // After 5 minute window passes, slots reset
        nowMs += GdMusicRateLimiter.WINDOW_MS + 1L
        assertEquals(GdMusicRateLimiter.MAX_CALLS_PER_WINDOW, limiter.remainingWindowSlots(RequestTier.PRIMARY))
    }
}
