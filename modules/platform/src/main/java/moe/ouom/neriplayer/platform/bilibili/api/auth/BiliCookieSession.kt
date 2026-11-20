package moe.ouom.neriplayer.platform.bilibili.api.auth

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.platform.bilibili.api.http.BILI_FINGERPRINT_URL
import moe.ouom.neriplayer.platform.bilibili.api.http.BILI_FINGERPRINT_USER_AGENT
import moe.ouom.neriplayer.platform.bilibili.api.http.executeBiliOrThrow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal class BiliCookieSession(
    private val cookieRepo: BiliCookieSource,
    private val http: OkHttpClient
) {
    private val anonymousCookieMutex = Mutex()

    @Volatile
    private var cachedAnonymousCookies: Map<String, String>? = null

    @Volatile
    private var anonymousCookiesCachedAt = 0L

    suspend fun effectiveCookies(): Map<String, String> {
        val stored = cookieRepo.getCookiesOnce()
        return if (stored.isNotEmpty()) stored else ensureAnonymousCookies(requireWebNut = false)
    }

    /**
     * Web 首页分区 Feed 等接口要求同时携带 `buvid3` 与 `b_nut`。
     * 二维码登录通常仅持久化了鉴权 Cookie，这里把缺失的站点指纹补在底层，保留用户登录态。
     */
    suspend fun effectiveWebFeedCookies(): Map<String, String> {
        val stored = cookieRepo.getCookiesOnce()
        if (!stored["buvid3"].isNullOrBlank() && !stored["b_nut"].isNullOrBlank()) {
            return stored
        }
        val webCookies = ensureAnonymousCookies(requireWebNut = true)
        if (stored.isEmpty()) return webCookies
        return LinkedHashMap<String, String>(webCookies.size + stored.size).apply {
            putAll(webCookies)
            putAll(stored)
        }
    }

    private suspend fun ensureAnonymousCookies(requireWebNut: Boolean): Map<String, String> {
        val now = System.currentTimeMillis()
        freshAnonymousCookies(now, requireWebNut)?.let { return it }
        return anonymousCookieMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            freshAnonymousCookies(lockedNow, requireWebNut)?.let { return@withLock it }
            val current = cachedAnonymousCookies.orEmpty()
            val fetched = if (requireWebNut) {
                fetchWebFeedAnonymousCookies(current)
            } else {
                fetchAnonymousCookies()
            }
            val merged = LinkedHashMap<String, String>(current.size + fetched.size).apply {
                putAll(current)
                putAll(fetched)
            }
            cachedAnonymousCookies = merged
            anonymousCookiesCachedAt = lockedNow
            merged
        }
    }

    private fun freshAnonymousCookies(now: Long, requireWebNut: Boolean): Map<String, String>? {
        val cached = cachedAnonymousCookies?.takeIf { now - anonymousCookiesCachedAt < ANONYMOUS_COOKIE_CACHE_MS }
            ?: return null
        if (requireWebNut && (cached["buvid3"].isNullOrBlank() || cached["b_nut"].isNullOrBlank())) {
            return null
        }
        return cached
    }

    private suspend fun fetchWebFeedAnonymousCookies(existing: Map<String, String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, String>(existing)
            val request = Request.Builder()
                .url(BILI_WEB_HOME_URL)
                .header("User-Agent", BILI_FINGERPRINT_USER_AGENT)
                .get()
                .build()
            try {
                http.newCall(request).executeBiliOrThrow().use { response ->
                    collectSetCookiePairs(response.headers("Set-Cookie"), out)
                    response.priorResponse?.let { prior ->
                        collectSetCookiePairs(prior.headers("Set-Cookie"), out)
                    }
                }
            } catch (_: IOException) {
            }
            if (out["buvid3"].isNullOrBlank()) {
                out.putAll(fetchAnonymousCookies())
            }
            out
        }

    private fun collectSetCookiePairs(headers: List<String>, target: MutableMap<String, String>) {
        headers.forEach { raw ->
            val pair = raw.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val key = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1).trim()
                if (key.isNotBlank() && value.isNotBlank()) {
                    target[key] = value
                }
            }
        }
    }

    private suspend fun fetchAnonymousCookies(): Map<String, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(BILI_FINGERPRINT_URL)
            .header("User-Agent", BILI_FINGERPRINT_USER_AGENT)
            .get()
            .build()
        val data = http.newCall(request).executeBiliOrThrow().use { response ->
            JSONObject(response.body.string()).optJSONObject("data") ?: JSONObject()
        }
        listOf(
            "buvid3" to data.optString("b_3", data.optString("buvid3", "")),
            "buvid4" to data.optString("b_4", data.optString("buvid4", "")),
            "buvid_fp" to data.optString("buvid_fp", ""),
            "buvid_fp_plain" to data.optString("buvid_fp_plain", ""),
            "b_lsid" to data.optString("b_lsid", "")
        ).filter { (_, value) -> value.isNotBlank() }.toMap()
    }

    private companion object {
        const val ANONYMOUS_COOKIE_CACHE_MS = 60 * 60 * 1000L
        const val BILI_WEB_HOME_URL = "https://www.bilibili.com/"
    }
}
