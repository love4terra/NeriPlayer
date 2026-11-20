package moe.ouom.neriplayer.platform.bilibili.api.parser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliMusicAndExploreParsersTest {

    @Test
    fun `parsePopularVideosAsRankSongs maps valid items and upgrades covers`() {
        val data = JSONObject(
            """
            {
              "list": [
                {
                  "aid": 114300739918083,
                  "bvid": "BV1ZVdJYcEjF",
                  "cid": 998877,
                  "title": "综合热门音乐",
                  "pic": "http://i0.hdslb.com/bfs/archive/pop.jpg",
                  "duration": 215,
                  "owner": {"mid": 1001, "name": "音乐UP"},
                  "stat": {"view": 543210},
                  "rcmd_reason": {"content": "百万播放"},
                  "tnamev2": "原创音乐"
                },
                {
                  "aid": 0,
                  "bvid": "",
                  "title": "无效条目"
                }
              ]
            }
            """.trimIndent()
        )

        val songs = parsePopularVideosAsRankSongs(data)

        assertEquals(1, songs.size)
        val first = songs.single()
        assertEquals(1, first.rank)
        assertEquals("综合热门音乐", first.title)
        assertEquals("音乐UP", first.singer)
        assertEquals("百万播放", first.album)
        assertEquals("https://i0.hdslb.com/bfs/archive/pop.jpg", first.videoCoverUrl)
        assertEquals(114300739918083L, first.creationAid)
        assertEquals("BV1ZVdJYcEjF", first.creationBvid)
        assertEquals(998877L, first.creationCid)
        assertEquals(215, first.creationDurationSec)
        assertEquals(543210L, first.heat)
        assertTrue(first.canListen)
    }

    @Test
    fun `parseRegionFeedVideos skips non av entries and parses archive metadata`() {
        val data = JSONObject(
            """
            {
              "archives": [
                {
                  "aid": 101,
                  "bvid": "BV1xx411c7mD",
                  "cid": 202,
                  "goto": "av",
                  "title": "分区推荐曲目",
                  "cover": "//i1.hdslb.com/bfs/archive/feed.jpg",
                  "duration": 180,
                  "pubdate": 1700000000,
                  "author": {"mid": 303, "name": "作者"},
                  "stat": {"view": 8800, "danmaku": 45},
                  "rec_reason": "翻唱热推"
                },
                {
                  "aid": 999,
                  "bvid": "BV1xx411c7mZ",
                  "goto": "live",
                  "title": "直播卡片"
                }
              ]
            }
            """.trimIndent()
        )

        val videos = parseRegionFeedVideos(data)

        assertEquals(1, videos.size)
        val item = videos.single()
        assertEquals(101L, item.aid)
        assertEquals("BV1xx411c7mD", item.bvid)
        assertEquals(202L, item.cid)
        assertEquals("https://i1.hdslb.com/bfs/archive/feed.jpg", item.coverUrl)
        assertEquals(180, item.durationSec)
        assertEquals("作者", item.uploader)
        assertEquals(303L, item.uploaderMid)
        assertEquals(8800L, item.viewCount)
        assertEquals("翻唱热推", item.tname)
    }

    @Test
    fun `parseRegionRankingVideos parses string aid and formatted durations`() {
        val array = JSONArray(
            """
            [
              {
                "aid": "114300739918083",
                "bvid": "BV1ZVdJYcEjF",
                "typename": "原创音乐",
                "title": "你所期盼的春天",
                "play": 987061,
                "video_review": 2013,
                "mid": 3546800493431586,
                "author": "在虚无中永存",
                "pic": "http://i0.hdslb.com/bfs/archive/rank.jpg",
                "duration": "1:33"
              },
              {
                "aid": 55,
                "bvid": "BV1aa411c7mD",
                "title": "长时合集",
                "author": "合集UP",
                "pic": "//i0.hdslb.com/bfs/archive/long.jpg",
                "duration": "1:02:03"
              }
            ]
            """.trimIndent()
        )

        val videos = parseRegionRankingVideos(array)

        assertEquals(2, videos.size)
        assertEquals(114300739918083L, videos[0].aid)
        assertEquals(93, videos[0].durationSec)
        assertEquals("原创音乐", videos[0].tname)
        assertEquals("https://i0.hdslb.com/bfs/archive/rank.jpg", videos[0].coverUrl)
        assertEquals(3723, videos[1].durationSec)
        assertEquals("https://i0.hdslb.com/bfs/archive/long.jpg", videos[1].coverUrl)
    }
}
