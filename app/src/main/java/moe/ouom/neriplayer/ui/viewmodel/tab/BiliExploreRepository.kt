package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.common.coroutines.runCatchingNonCancellation
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicHotVideo
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.buildBiliSongAlbum

const val DEFAULT_BILI_EXPLORE_TAG = "recommend"

internal enum class BiliExploreTag(val key: String, val rid: Int?) {
    RECOMMEND(DEFAULT_BILI_EXPLORE_TAG, null),
    ORIGINAL("original", 28),
    COVER("cover", 31),
    VOCALOID("vocaloid", 30),
    PERFORM("perform", 59),
    LIVE("live", 29),
    MV("mv", 193),
    GENERAL("general", 130),
    COMMENTARY("commentary", 243);

    companion object {
        fun fromKey(key: String?): BiliExploreTag =
            entries.firstOrNull { it.key == key } ?: RECOMMEND
    }
}

internal data class BiliExploreFetchResult(
    val videos: List<BiliMusicHotVideo>,
    val songs: List<SongItem>,
    val hasMore: Boolean,
    val personalized: Boolean
)

internal suspend fun loadBiliExplorePage(
    client: BiliClient,
    tag: BiliExploreTag,
    page: Int
): BiliExploreFetchResult {
    return when (tag) {
        BiliExploreTag.RECOMMEND -> {
            val personalized = client.hasLoginSession()
            val feedVideos = runCatchingNonCancellation {
                client.getMusicRegionFeedVideos(displayId = page.coerceAtLeast(1))
            }.getOrNull().orEmpty()
            val videos = if (feedVideos.isNotEmpty() || page > 1) {
                feedVideos
            } else {
                // 分区推荐流偶发受风控或空页时，回退到移动端音乐热门榜兜底
                client.getMusicHotRankVideos()
            }
            val unique = mergeUniqueBiliVideos(emptyList(), videos)
            BiliExploreFetchResult(
                videos = unique,
                songs = unique.map { it.toExploreSongItem() },
                hasMore = feedVideos.isNotEmpty() && page < MAX_RECOMMEND_PAGES,
                personalized = personalized
            )
        }

        else -> {
            val rid = tag.rid ?: 3
            val rankingVideos = client.getRegionRankingVideos(rid = rid, day = 7, original = 0)
            val combined = if (rankingVideos.size < SUBZONE_MIN_TARGET_COUNT) {
                val originalVideos = runCatchingNonCancellation {
                    client.getRegionRankingVideos(rid = rid, day = 7, original = 1)
                }.getOrDefault(emptyList())
                mergeUniqueBiliVideos(rankingVideos, originalVideos)
            } else {
                mergeUniqueBiliVideos(emptyList(), rankingVideos)
            }
            BiliExploreFetchResult(
                videos = combined,
                songs = combined.map { it.toExploreSongItem() },
                hasMore = false,
                personalized = client.hasLoginSession()
            )
        }
    }
}

internal fun mergeUniqueBiliVideos(
    existing: List<BiliMusicHotVideo>,
    incoming: List<BiliMusicHotVideo>
): List<BiliMusicHotVideo> {
    if (existing.isEmpty() && incoming.isEmpty()) return emptyList()
    val seen = HashSet<String>(existing.size + incoming.size)
    val merged = ArrayList<BiliMusicHotVideo>(existing.size + incoming.size)
    fun appendIfNew(video: BiliMusicHotVideo) {
        val identity = biliVideoStableKey(video)
        if (identity.isNotBlank() && seen.add(identity)) {
            merged += video
        }
    }
    existing.forEach(::appendIfNew)
    incoming.forEach(::appendIfNew)
    return merged
}

internal fun biliVideoStableKey(video: BiliMusicHotVideo): String =
    video.bvid.trim().ifBlank { video.aid.takeIf { it > 0L }?.toString().orEmpty() }

internal fun BiliMusicHotVideo.toExploreSongItem(): SongItem =
    SongItem(
        id = aid,
        name = title,
        artist = uploader.ifBlank { "bilibili" },
        // 不预先写入 cid，点击时走统一分 P 探测：单 P 直接播，多 P 弹选集
        album = buildBiliSongAlbum(bvid = bvid),
        albumId = 0L,
        durationMs = durationSec.coerceAtLeast(0) * 1_000L,
        coverUrl = coverUrl.takeIf { it.isNotBlank() },
        channelId = "bilibili",
        audioId = aid.toString()
    )

private const val MAX_RECOMMEND_PAGES = 20
private const val SUBZONE_MIN_TARGET_COUNT = 12
