package moe.ouom.neriplayer.platform.gdmusic

import kotlin.math.min

/**
 * GD Studio API 限流与短时缓存：
 * - 每 5 分钟窗口上限 50 次请求
 * - 预取请求限制在 36 次内，跨源回退等非重试调用限制在 40 次内，为主播放与重试保留配额
 * - 支持 HTTP 429 Retry-After 冷却期与 LRU/TTL URL、搜索结果缓存
 */
class GdMusicRateLimiter(
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val WINDOW_MS = 5 * 60 * 1000L
        const val MAX_CALLS_PER_WINDOW = 50
        const val RETRY_RESERVE_CEILING = 40
        const val PREFETCH_CEILING = 36
        const val URL_CACHE_TTL_MS = 4 * 60 * 1000L
        const val SEARCH_CACHE_TTL_MS = 10 * 60 * 1000L
        const val CACHE_MAX_ENTRIES = 200
        const val DEFAULT_COOLDOWN_MS = 15_000L
        const val MAX_COOLDOWN_MS = 120_000L
    }

    enum class RequestTier {
        PRIMARY_RETRY,
        PRIMARY,
        SECONDARY_FALLBACK,
        PREFETCH
    }

    private data class CacheEntry<T>(
        val value: T,
        val expiresAtMs: Long
    )

    private val lock = Any()
    private val callTimestamps = ArrayDeque<Long>()
    private var cooldownUntilMs = 0L

    private val urlCache = object : LinkedHashMap<String, CacheEntry<GdMusicResolvedUrl>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry<GdMusicResolvedUrl>>?): Boolean =
            size > CACHE_MAX_ENTRIES
    }

    private val jooxSearchCache =
        object : LinkedHashMap<String, CacheEntry<List<GdMusicSearchTrack>>>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CacheEntry<List<GdMusicSearchTrack>>>?
            ): Boolean = size > CACHE_MAX_ENTRIES
        }

    fun tryAcquireSlot(tier: RequestTier): Boolean {
        synchronized(lock) {
            val now = clockMs()
            if (now < cooldownUntilMs) return false
            pruneExpiredTimestampsLocked(now)
            val limit = when (tier) {
                RequestTier.PREFETCH -> PREFETCH_CEILING
                RequestTier.SECONDARY_FALLBACK -> RETRY_RESERVE_CEILING
                RequestTier.PRIMARY, RequestTier.PRIMARY_RETRY -> MAX_CALLS_PER_WINDOW
            }
            if (callTimestamps.size >= limit) return false
            callTimestamps.addLast(now)
            return true
        }
    }

    fun isInCooldown(): Boolean {
        synchronized(lock) {
            return clockMs() < cooldownUntilMs
        }
    }

    fun recordRateLimited(retryAfterSeconds: Long? = null) {
        synchronized(lock) {
            val now = clockMs()
            val delayMs = retryAfterSeconds
                ?.takeIf { it > 0L }
                ?.let { min(it * 1000L, MAX_COOLDOWN_MS) }
                ?: DEFAULT_COOLDOWN_MS
            cooldownUntilMs = maxOf(cooldownUntilMs, now + delayMs)
        }
    }

    fun getCachedUrl(key: String): GdMusicResolvedUrl? {
        synchronized(lock) {
            val entry = urlCache[key] ?: return null
            if (clockMs() >= entry.expiresAtMs) {
                urlCache.remove(key)
                return null
            }
            return entry.value
        }
    }

    fun putCachedUrl(key: String, value: GdMusicResolvedUrl) {
        synchronized(lock) {
            urlCache[key] = CacheEntry(value, clockMs() + URL_CACHE_TTL_MS)
        }
    }

    fun getCachedJooxSearch(key: String): List<GdMusicSearchTrack>? {
        synchronized(lock) {
            val entry = jooxSearchCache[key] ?: return null
            if (clockMs() >= entry.expiresAtMs) {
                jooxSearchCache.remove(key)
                return null
            }
            return entry.value
        }
    }

    fun putCachedJooxSearch(key: String, tracks: List<GdMusicSearchTrack>) {
        synchronized(lock) {
            jooxSearchCache[key] = CacheEntry(tracks, clockMs() + SEARCH_CACHE_TTL_MS)
        }
    }

    fun remainingWindowSlots(tier: RequestTier = RequestTier.PRIMARY): Int {
        synchronized(lock) {
            val now = clockMs()
            if (now < cooldownUntilMs) return 0
            pruneExpiredTimestampsLocked(now)
            val limit = when (tier) {
                RequestTier.PREFETCH -> PREFETCH_CEILING
                RequestTier.SECONDARY_FALLBACK -> RETRY_RESERVE_CEILING
                RequestTier.PRIMARY, RequestTier.PRIMARY_RETRY -> MAX_CALLS_PER_WINDOW
            }
            return (limit - callTimestamps.size).coerceAtLeast(0)
        }
    }

    private fun pruneExpiredTimestampsLocked(now: Long) {
        val cutoff = now - WINDOW_MS
        while (callTimestamps.isNotEmpty() && callTimestamps.first() <= cutoff) {
            callTimestamps.removeFirst()
        }
    }
}
