package moe.ouom.neriplayer.ui.viewmodel.tab

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicHotVideo
import moe.ouom.neriplayer.data.model.bilibili.music.BiliPagedVideoList
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class BiliExploreRepositoryTest {

    private fun sampleVideo(aid: Long, bvid: String, title: String = "Title $aid") = BiliMusicHotVideo(
        aid = aid,
        bvid = bvid,
        cid = aid + 10L,
        title = title,
        coverUrl = "https://example.com/$aid.jpg",
        durationSec = 180,
        uploader = "Uploader",
        uploaderMid = 99L,
        viewCount = 1000L,
        danmakuCount = 10L,
        pubdateSec = 1700000000L,
        tname = "原创音乐"
    )

    @Test
    fun `loadBiliExplorePage uses personalized region feed when signed in and falls back when empty`() = runTest {
        val client = mock(BiliClient::class.java)
        val feedVideo = sampleVideo(1L, "BV1a")
        `when`(client.hasLoginSession()).thenReturn(true)
        `when`(client.getMusicRegionFeedVideos(displayId = 1, requestCount = 20)).thenReturn(listOf(feedVideo))

        val result = loadBiliExplorePage(client, BiliExploreTag.RECOMMEND, page = 1)

        assertTrue(result.personalized)
        assertTrue(result.hasMore)
        assertEquals(listOf(feedVideo), result.videos)
        assertEquals("bilibili", result.songs.single().channelId)
        assertEquals("Bilibili||BV1a", result.songs.single().album)

        val fallbackVideo = sampleVideo(2L, "BV1b")
        `when`(client.hasLoginSession()).thenReturn(false)
        `when`(client.getMusicRegionFeedVideos(displayId = 1, requestCount = 20)).thenReturn(emptyList())
        `when`(client.getMusicHotRankVideos()).thenReturn(listOf(fallbackVideo))

        val fallbackResult = loadBiliExplorePage(client, BiliExploreTag.RECOMMEND, page = 1)
        assertFalse(fallbackResult.personalized)
        assertFalse(fallbackResult.hasMore)
        assertEquals(listOf(fallbackVideo), fallbackResult.videos)
    }

    @Test
    fun `loadBiliExplorePage uses dynamic child feed for music subzones with cursor pagination and falls back when empty`() = runTest {
        val client = mock(BiliClient::class.java)
        `when`(client.hasLoginSession()).thenReturn(false)
        `when`(
            client.getRegionDynamicChildVideos(
                rid = 28,
                cursor = "",
                fallbackTname = "原创音乐"
            )
        ).thenReturn(
            BiliPagedVideoList(
                videos = listOf(sampleVideo(1L, "BV1a"), sampleVideo(2L, "BV1b")),
                hasMore = true,
                nextOffset = "cbottom_1"
            )
        )
        `when`(
            client.getRegionDynamicChildVideos(
                rid = 28,
                cursor = "cbottom_1",
                fallbackTname = "原创音乐"
            )
        ).thenReturn(
            BiliPagedVideoList(
                videos = listOf(sampleVideo(2L, "BV1b"), sampleVideo(3L, "BV1c")),
                hasMore = true,
                nextOffset = "cbottom_2"
            )
        )
        `when`(
            client.getRegionDynamicChildVideos(
                rid = 28,
                cursor = "cbottom_2",
                fallbackTname = "原创音乐"
            )
        ).thenReturn(
            BiliPagedVideoList(
                videos = listOf(sampleVideo(4L, "BV1d")),
                hasMore = false,
                nextOffset = ""
            )
        )

        val initialResult = loadBiliExplorePage(
            client = client,
            tag = BiliExploreTag.ORIGINAL,
            page = 1,
            section = BiliExploreSection.MUSIC,
            offset = ""
        )

        assertEquals(listOf("BV1a", "BV1b", "BV1c"), initialResult.videos.map { it.bvid })
        assertTrue(initialResult.hasMore)
        assertEquals("cbottom_2", initialResult.nextOffset)

        val nextResult = loadBiliExplorePage(
            client = client,
            tag = BiliExploreTag.ORIGINAL,
            page = 2,
            section = BiliExploreSection.MUSIC,
            offset = "cbottom_2"
        )
        assertEquals(listOf("BV1d"), nextResult.videos.map { it.bvid })
        assertFalse(nextResult.hasMore)

        `when`(
            client.getRegionDynamicChildVideos(
                rid = 28,
                cursor = "",
                fallbackTname = "原创音乐"
            )
        ).thenReturn(BiliPagedVideoList(videos = emptyList(), hasMore = false))
        `when`(client.getRegionRankingVideos(rid = 28, day = 7, original = 0)).thenReturn(
            listOf(sampleVideo(5L, "BV1e"))
        )

        val fallbackResult = loadBiliExplorePage(
            client = client,
            tag = BiliExploreTag.ORIGINAL,
            page = 1,
            section = BiliExploreSection.MUSIC
        )
        assertEquals(listOf("BV1e"), fallbackResult.videos.map { it.bvid })
        assertFalse(fallbackResult.hasMore)
    }

    @Test
    fun `loadBiliExplorePage handles dynamic feed pagination and login required state`() = runTest {
        val client = mock(BiliClient::class.java)
        `when`(client.getDynamicVideoFeed(offset = "")).thenReturn(
            BiliPagedVideoList(
                videos = emptyList(),
                hasMore = false,
                requiresLogin = true
            )
        )

        val loggedOutResult = loadBiliExplorePage(
            client = client,
            tag = BiliExploreTag.RECOMMEND,
            page = 1,
            section = BiliExploreSection.DYNAMIC
        )
        assertTrue(loggedOutResult.requiresLogin)
        assertFalse(loggedOutResult.hasMore)
        assertTrue(loggedOutResult.videos.isEmpty())

        val dynamicVideo = sampleVideo(9L, "BV1dyn")
        `when`(client.getDynamicVideoFeed(offset = "cursor_1")).thenReturn(
            BiliPagedVideoList(
                videos = listOf(dynamicVideo),
                hasMore = true,
                nextOffset = "cursor_2",
                requiresLogin = false
            )
        )

        val dynamicPage = loadBiliExplorePage(
            client = client,
            tag = BiliExploreTag.RECOMMEND,
            page = 2,
            section = BiliExploreSection.DYNAMIC,
            offset = "cursor_1"
        )
        assertFalse(dynamicPage.requiresLogin)
        assertTrue(dynamicPage.hasMore)
        assertEquals("cursor_2", dynamicPage.nextOffset)
        assertEquals(listOf("BV1dyn"), dynamicPage.videos.map { it.bvid })
    }
}
