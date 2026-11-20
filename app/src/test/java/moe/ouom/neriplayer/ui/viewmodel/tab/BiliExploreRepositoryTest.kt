package moe.ouom.neriplayer.ui.viewmodel.tab

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicHotVideo
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
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
    fun `loadBiliExplorePage merges unique original items for music subzones with few entries`() = runTest {
        val client = mock(BiliClient::class.java)
        `when`(client.hasLoginSession()).thenReturn(false)
        `when`(client.getRegionRankingVideos(rid = 28, day = 7, original = 0)).thenReturn(
            listOf(sampleVideo(1L, "BV1a"), sampleVideo(2L, "BV1b"))
        )
        `when`(client.getRegionRankingVideos(rid = 28, day = 7, original = 1)).thenReturn(
            listOf(sampleVideo(2L, "BV1b"), sampleVideo(3L, "BV1c"))
        )

        val result = loadBiliExplorePage(client, BiliExploreTag.ORIGINAL, page = 1)

        assertEquals(listOf("BV1a", "BV1b", "BV1c"), result.videos.map { it.bvid })
        assertFalse(result.hasMore)
        verify(client).getRegionRankingVideos(rid = 28, day = 7, original = 0)
        verify(client).getRegionRankingVideos(rid = 28, day = 7, original = 1)
    }
}
