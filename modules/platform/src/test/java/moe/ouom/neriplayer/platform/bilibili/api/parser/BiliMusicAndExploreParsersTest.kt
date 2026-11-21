package moe.ouom.neriplayer.platform.bilibili.api.parser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `parseRegionNewlistRankVideos maps items and derives hasMore from numPages`() {
        val data = JSONObject(
            """
            {
              "numPages": 3,
              "result": [
                {
                  "id": 223344,
                  "bvid": "BV1xx411c7mD",
                  "title": "翻唱排行第一页",
                  "pic": "//i0.hdslb.com/bfs/archive/newlist.jpg",
                  "duration": 205,
                  "author": "翻唱UP",
                  "mid": 9001,
                  "play": "1.2万",
                  "video_review": 321,
                  "senddate": 1760000123
                },
                {
                  "id": 223345,
                  "bvid": "",
                  "typename": "翻唱",
                  "title": "第二条",
                  "pic": "http://i1.hdslb.com/bfs/archive/two.jpg",
                  "duration": "2:05",
                  "author": "UP2",
                  "mid": 9002,
                  "play": 880,
                  "danmaku": "42",
                  "pubdate": 1760000456
                }
              ]
            }
            """.trimIndent()
        )

        val page1 = parseRegionNewlistRankVideos(data, currentPage = 1, fallbackTname = "音乐")
        assertTrue(page1.hasMore)
        assertEquals(2, page1.videos.size)
        assertEquals(223344L, page1.videos[0].aid)
        assertEquals("https://i0.hdslb.com/bfs/archive/newlist.jpg", page1.videos[0].coverUrl)
        assertEquals(12_000L, page1.videos[0].viewCount)
        assertEquals(321L, page1.videos[0].danmakuCount)
        assertEquals(1760000123L, page1.videos[0].pubdateSec)
        assertEquals("音乐", page1.videos[0].tname)
        assertEquals(125, page1.videos[1].durationSec)
        assertEquals(42L, page1.videos[1].danmakuCount)
        assertEquals("翻唱", page1.videos[1].tname)

        val lastPage = parseRegionNewlistRankVideos(data, currentPage = 3, fallbackTname = "音乐")
        assertFalse(lastPage.hasMore)
    }

    @Test
    fun `parseDynamicVideoFeed maps direct and forwarded archives and skips non-video items`() {
        val data = JSONObject(
            """
            {
              "has_more": true,
              "offset": "987654321",
              "items": [
                {
                  "type": "DYNAMIC_TYPE_AV",
                  "visible": true,
                  "modules": {
                    "module_author": {"mid": 11, "name": "作者甲", "pub_ts": 1760001000},
                    "module_dynamic": {
                      "major": {
                        "archive": {
                          "aid": "1001",
                          "bvid": "BV1xx411c7mD",
                          "title": "关注动态视频",
                          "cover": "http://i0.hdslb.com/bfs/archive/dyn1.jpg",
                          "duration_text": "03:45",
                          "badge": {"text": "投稿视频"},
                          "stat": {"play": "2.5万", "danmaku": "1.1万"}
                        }
                      }
                    }
                  }
                },
                {
                  "type": "DYNAMIC_TYPE_FORWARD",
                  "visible": true,
                  "orig": {
                    "modules": {
                      "module_author": {"mid": 22, "name": "原作者", "pub_ts": 1760002000},
                      "module_dynamic": {
                        "major": {
                          "archive": {
                            "aid": "1002",
                            "bvid": "BV1aa411c7mD",
                            "title": "转发的原视频",
                            "cover": "//i1.hdslb.com/bfs/archive/dyn2.jpg",
                            "duration_text": "1:00:05",
                            "stat": {"play": "1,234", "danmaku": "-"}
                          }
                        }
                      }
                    }
                  }
                },
                {
                  "type": "DYNAMIC_TYPE_WORD",
                  "visible": true,
                  "modules": {
                    "module_author": {"mid": 33, "name": "纯文字"},
                    "module_dynamic": {"major": null}
                  }
                },
                {
                  "type": "DYNAMIC_TYPE_AV",
                  "visible": false,
                  "modules": {
                    "module_author": {"mid": 44, "name": "不可见"},
                    "module_dynamic": {
                      "major": {
                        "archive": {
                          "aid": "1003",
                          "bvid": "BV1zz411c7mD",
                          "title": "隐藏视频"
                        }
                      }
                    }
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val page = parseDynamicVideoFeed(data)

        assertTrue(page.hasMore)
        assertEquals("987654321", page.nextOffset)
        assertEquals(2, page.videos.size)
        assertEquals(1001L, page.videos[0].aid)
        assertEquals(225, page.videos[0].durationSec)
        assertEquals(25_000L, page.videos[0].viewCount)
        assertEquals(11_000L, page.videos[0].danmakuCount)
        assertEquals("投稿视频", page.videos[0].tname)
        assertEquals(1002L, page.videos[1].aid)
        assertEquals("原作者", page.videos[1].uploader)
        assertEquals(3605, page.videos[1].durationSec)
        assertEquals(1234L, page.videos[1].viewCount)
        assertEquals(0L, page.videos[1].danmakuCount)
    }

    @Test
    fun `parseBiliFlexibleCount handles chinese units commas and dashes`() {
        assertEquals(0L, parseBiliFlexibleCount(null))
        assertEquals(0L, parseBiliFlexibleCount("-"))
        assertEquals(1234L, parseBiliFlexibleCount("1,234"))
        assertEquals(12_500L, parseBiliFlexibleCount("1.25万"))
        assertEquals(230_000_000L, parseBiliFlexibleCount("2.3亿"))
        assertEquals(999L, parseBiliFlexibleCount(999L))
    }
}
