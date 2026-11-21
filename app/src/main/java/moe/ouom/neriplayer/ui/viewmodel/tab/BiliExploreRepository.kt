package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.common.coroutines.runCatchingNonCancellation
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicHotVideo
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.buildBiliSongAlbum

const val DEFAULT_BILI_EXPLORE_SECTION = "music"
const val DEFAULT_BILI_EXPLORE_TAG = "recommend"

internal enum class BiliExploreSection(val key: String) {
    DYNAMIC("dynamic"),
    MUSIC(DEFAULT_BILI_EXPLORE_SECTION);

    companion object {
        fun fromKey(key: String?): BiliExploreSection =
            entries.firstOrNull { it.key == key } ?: MUSIC
    }
}

internal enum class BiliExploreTag(
    val key: String,
    val rid: Int?,
    val fallbackTname: String = ""
) {
    RECOMMEND(DEFAULT_BILI_EXPLORE_TAG, null),
    ORIGINAL("original", 28, "原创音乐"),
    COVER("cover", 31, "翻唱"),
    VOCALOID("vocaloid", 30, "VOCALOID"),
    PERFORM("perform", 59, "演奏"),
    LIVE("live", 29, "音乐现场"),
    MV("mv", 193, "MV"),
    GENERAL("general", 130, "音乐综合"),
    COMMENTARY("commentary", 243, "乐评盘点");

    companion object {
        fun fromKey(key: String?): BiliExploreTag =
            entries.firstOrNull { it.key == key } ?: RECOMMEND
    }
}

internal data class BiliExploreFetchResult(
    val videos: List<BiliMusicHotVideo>,
    val songs: List<SongItem>,
    val hasMore: Boolean,
    val personalized: Boolean,
    val nextOffset: String = "",
    val requiresLogin: Boolean = false
)

internal suspend fun loadBiliExplorePage(
    client: BiliClient,
    tag: BiliExploreTag,
    page: Int,
    section: BiliExploreSection = BiliExploreSection.MUSIC,
    offset: String = ""
): BiliExploreFetchResult {
    if (section == BiliExploreSection.DYNAMIC) {
        val dynamicPage = client.getDynamicVideoFeed(offset = offset)
        if (dynamicPage.requiresLogin) {
            return BiliExploreFetchResult(
                videos = emptyList(),
                songs = emptyList(),
                hasMore = false,
                personalized = false,
                requiresLogin = true
            )
        }
        val unique = mergeUniqueBiliVideos(emptyList(), dynamicPage.videos)
        return BiliExploreFetchResult(
            videos = unique,
            songs = unique.map { it.toExploreSongItem() },
            hasMore = dynamicPage.hasMore && dynamicPage.nextOffset.isNotBlank(),
            personalized = true,
            nextOffset = dynamicPage.nextOffset
        )
    }

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
            val safePage = page.coerceAtLeast(1)
            val newlistPage = client.getRegionNewlistRankVideos(
                rid = rid,
                page = safePage,
                fallbackTname = tag.fallbackTname
            )
            val rawVideos = if (safePage == 1 && newlistPage.videos.isEmpty()) {
                runCatchingNonCancellation {
                    client.getRegionRankingVideos(rid = rid, day = 7, original = 0)
                }.getOrDefault(emptyList())
            } else {
                newlistPage.videos
            }
            val unique = mergeUniqueBiliVideos(emptyList(), rawVideos)
            BiliExploreFetchResult(
                videos = unique,
                songs = unique.map { it.toExploreSongItem() },
                hasMore = newlistPage.videos.isNotEmpty() && newlistPage.hasMore,
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
