package moe.ouom.neriplayer.platform.netease.api.client

import moe.ouom.neriplayer.platform.netease.api.crypto.NeteaseCrypto
import moe.ouom.neriplayer.platform.netease.api.request.buildNeteaseCloudParams
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class NeteaseClientTest {

    @Test
    fun mergeRequestCookies_keepsRuntimeSessionCookies() {
        val cookies = mergeNeteaseRequestCookies(
            persistedCookies = linkedMapOf(
                "MUSIC_U" to "persisted-session",
                "__csrf" to "persisted-csrf",
                "NMTID" to "persisted-context"
            ),
            runtimeCookies = linkedMapOf(
                "MUSIC_U" to "runtime-session",
                "NMTID" to "runtime-context"
            ),
            requestContextCookies = linkedMapOf(
                "__remember_me" to "true",
                "NMTID" to "generated-context"
            )
        )

        assertEquals("runtime-session", cookies["MUSIC_U"])
        assertEquals("persisted-csrf", cookies["__csrf"])
        assertEquals("runtime-context", cookies["NMTID"])
        assertEquals("true", cookies["__remember_me"])
        assertEquals("pc", cookies["os"])
        assertEquals("8.10.35", cookies["appver"])
    }

    @Test
    fun setPersistedCookies_dropsThePreviousLoginSession() {
        val client = NeteaseClient { error("Comment token is outside this test") }

        client.setPersistedCookies(mapOf("MUSIC_U" to "previous-session"))
        client.setPersistedCookies(emptyMap())

        assertFalse(client.getCookies().containsKey("MUSIC_U"))
    }

    @Test
    fun sessionStore_keepsLateCookiesInTheOriginalAccountSession() {
        val store = NeteaseRequestSessionStore()
        val accountA = store.setPersistedCookies(mapOf("MUSIC_U" to "account-a"))
        val accountB = store.setPersistedCookies(mapOf("MUSIC_U" to "account-b"))
        val requestUrl = "https://music.163.com/".toHttpUrl()

        accountA.cookieJar.saveFromResponse(
            requestUrl,
            listOf(newCookie("__csrf", "late-account-a"))
        )

        assertNotSame(accountA, accountB)
        assertEquals("late-account-a", accountA.requestCookiesForUrl(requestUrl)["__csrf"])
        assertFalse(accountB.requestCookiesForUrl(requestUrl).containsKey("__csrf"))
        assertFalse(store.currentSession().requestCookiesForUrl(requestUrl).containsKey("__csrf"))
    }

    @Test
    fun sessionStore_preservesSessionWhenOnlyRuntimeCookiesChange() {
        val store = NeteaseRequestSessionStore()
        val original = store.setPersistedCookies(
            mapOf("MUSIC_U" to "account-a", "__csrf" to "old-session")
        )
        val replacement = store.setPersistedCookies(
            mapOf("MUSIC_U" to "account-a", "__csrf" to "new-session")
        )

        assertEquals(original, replacement)
        assertEquals("new-session", replacement.requestCookiesForUrl(
            "https://music.163.com/".toHttpUrl()
        )["__csrf"])
    }

    @Test
    fun sessionStore_doesNotRestoreAnOlderAccountSnapshotAfterSwitching() {
        val store = NeteaseRequestSessionStore()
        val accountA = mapOf("MUSIC_U" to "account-a", "__csrf" to "a-session")
        val accountB = mapOf("MUSIC_U" to "account-b", "__csrf" to "b-session")

        val firstAccountSession = store.setPersistedCookies(accountA)
        val secondAccountSession = store.setPersistedCookies(accountB)
        val restoredAccountSession = store.setPersistedCookies(accountA)

        assertNotSame(firstAccountSession, secondAccountSession)
        assertNotSame(secondAccountSession, restoredAccountSession)
        assertEquals("account-a", restoredAccountSession.requestCookiesForUrl(
            "https://music.163.com/".toHttpUrl()
        )["MUSIC_U"])
    }

    @Test
    fun requestCookies_includePersonalizationContextBeforeFirstResponse() {
        val client = NeteaseClient { error("Comment token is outside this test") }
        client.setPersistedCookies(mapOf("MUSIC_U" to "login-session"))

        val cookies = client.getNeteaseRequestCookies()

        assertEquals("login-session", cookies["MUSIC_U"])
        assertEquals("true", cookies["__remember_me"])
        assertTrueHexSessionCookie(cookies["_ntes_nuid"])
        assertTrueHexSessionCookie(cookies["NMTID"])
    }

    @Test
    fun musicUOnlyLogin_requiresWeapiSessionPreheat() {
        assertTrue(
            shouldPreheatNeteaseWeapiSession(
                persistedCookies = mapOf("MUSIC_U" to "login-session"),
                requestCookies = emptyMap(),
                usePersistedCookies = true
            )
        )
        assertFalse(
            shouldPreheatNeteaseWeapiSession(
                persistedCookies = mapOf("MUSIC_U" to "login-session"),
                requestCookies = mapOf("__csrf" to "csrf-token"),
                usePersistedCookies = true
            )
        )
        assertFalse(
            shouldPreheatNeteaseWeapiSession(
                persistedCookies = mapOf("MUSIC_U" to "login-session"),
                requestCookies = emptyMap(),
                usePersistedCookies = false
            )
        )
    }

    @Test
    fun radarPlaylistMetadataParams_includeMgcContextAndMetadataLimit() {
        val params = buildNeteaseRadarPlaylistMetadataParams(5_327_906_368L)

        assertEquals("5327906368", params["id"])
        assertEquals("1", params["n"])
        assertEquals("0", params["s"])
        assertEquals("MGC", params["uiPlaylistType"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun radarPlaylistMetadataParams_rejectNonPositivePlaylistId() {
        buildNeteaseRadarPlaylistMetadataParams(0L)
    }

    @Test
    fun cloudParams_encodeOffsetAndLimit() {
        val params = buildNeteaseCloudParams(offset = 200, limit = 500)

        assertEquals("200", params["offset"])
        assertEquals("500", params["limit"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun cloudParams_rejectNegativeOffset() {
        buildNeteaseCloudParams(offset = -1, limit = 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cloudParams_rejectUnsupportedLimit() {
        buildNeteaseCloudParams(offset = 0, limit = 1001)
    }

    @Test
    fun buildWeblogRequest_usesClientlogEndpointHeadersAndEapiEncryptedHeader() {
        val client = NeteaseClient { error("Comment token is outside this test") }
        client.setPersistedCookies(
            mapOf(
                "MUSIC_U" to "login-music-u",
                "__csrf" to "csrf-token",
                "os" to "pc",
                "appver" to "8.10.35"
            )
        )
        val logsJson = """[{"action":"startplay","json":{"id":186016,"type":"song","mainsite":"1","mainsiteWeb":"1","content":"id=0"}}]"""
        val request = client.buildWeblogRequest(logsJson, nowMs = 1_700_000_000_123L)

        assertEquals("https://clientlog.music.163.com/eapi/feedback/weblog", request.url.toString())
        assertEquals("https://music.163.com", request.header("Origin"))
        assertEquals("https://music.163.com/", request.header("Referer"))
        assertEquals(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36",
            request.header("User-Agent")
        )
        val cookieHeader = request.header("Cookie").orEmpty()
        assertTrue(cookieHeader.contains("MUSIC_U=login-music-u"))
        assertTrue(cookieHeader.contains("__csrf=csrf-token"))
        assertTrue(cookieHeader.contains("os=osx"))
        assertTrue(cookieHeader.contains("appver=3.1.10.5100"))
        assertFalse(cookieHeader.contains("appver=8.10.35"))

        val form = request.body as FormBody
        assertEquals("params", form.name(0))
        val plain = decryptEapiParams(form.value(0))
        val parts = plain.split("-36cd479b6b5-")
        assertEquals(3, parts.size)
        assertEquals("/api/feedback/weblog", parts[0])
        assertEquals(
            NeteaseCrypto.md5Hex("nobody/api/feedback/webloguse${parts[1]}md5forencrypt"),
            parts[2]
        )

        val payloadJson = JSONObject(parts[1])
        assertEquals(logsJson, payloadJson.getString("logs"))
        assertFalse(payloadJson.getBoolean("e_r"))
        val encryptedHeader = payloadJson.getJSONObject("header")
        assertEquals("login-music-u", encryptedHeader.getString("MUSIC_U"))
        assertEquals("csrf-token", encryptedHeader.getString("__csrf"))
        assertEquals("osx", encryptedHeader.getString("os"))
        assertEquals("3.1.10.5100", encryptedHeader.getString("appver"))
        assertEquals("15.5", encryptedHeader.getString("osver"))
        assertEquals(52, encryptedHeader.getString("deviceId").length)
        assertTrue(encryptedHeader.getString("requestId").startsWith("1700000000123_"))
    }

    private fun decryptEapiParams(hexCipher: String): String {
        val bytes = hexCipher.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec("e82ckenh8dichen8".toByteArray(StandardCharsets.UTF_8), "AES")
        )
        return String(cipher.doFinal(bytes), StandardCharsets.UTF_8)
    }

    private fun assertTrueHexSessionCookie(value: String?) {
        assertEquals(32, value?.length)
        assertTrue(value?.all { it in '0'..'9' || it in 'a'..'f' } == true)
    }

    private fun newCookie(name: String, value: String): Cookie {
        return Cookie.Builder()
            .name(name)
            .value(value)
            .domain("music.163.com")
            .path("/")
            .build()
    }
}
