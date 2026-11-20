package moe.ouom.neriplayer.platform.bilibili.api.parser

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 */

import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicHotVideo
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankSong
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliVideoIdCodec
import org.json.JSONArray
import org.json.JSONObject

/** 解析 B 站全站综合热门 (`x/web-interface/popular`) 到首页榜单条目模型 */
internal fun parsePopularVideosAsRankSongs(data: JSONObject): List<BiliMusicRankSong> {
    val list = data.optJSONArray("list") ?: return emptyList()
    val out = ArrayList<BiliMusicRankSong>(list.length())
    for (i in 0 until list.length()) {
        val item = list.optJSONObject(i) ?: continue
        val aid = item.optLong("aid", 0L)
        val bvid = item.optString("bvid").trim().ifBlank {
            BiliVideoIdCodec.aidToBvid(aid)
        }
        val title = item.optString("title").trim()
        if (aid <= 0L || bvid.isBlank() || title.isBlank()) continue
        val owner = item.optJSONObject("owner") ?: JSONObject()
        val stat = item.optJSONObject("stat") ?: JSONObject()
        val rcmdReason = item.optJSONObject("rcmd_reason")?.optString("content")?.trim().orEmpty()
        val tname = item.optString("tnamev2").trim().ifBlank { item.optString("tname").trim() }
        val cover = normalizeBiliCoverUrl(item.optString("pic"))
        val uploader = owner.optString("name").trim()
        val uploaderMid = owner.optLong("mid", 0L)
        out += BiliMusicRankSong(
            musicId = "popular-$aid",
            rank = out.size + 1,
            title = title,
            singer = uploader,
            album = rcmdReason.ifBlank { tname },
            coverUrl = cover,
            videoCoverUrl = cover,
            creationAid = aid,
            creationBvid = bvid,
            creationCid = item.optLong("cid", 0L),
            creationDurationSec = item.optInt("duration", 0).coerceAtLeast(0),
            uploader = uploader,
            uploaderMid = uploaderMid,
            heat = stat.optLong("view", 0L),
            canListen = true
        )
    }
    return out
}

/** 解析 B 站首页分区 Feed (`x/web-interface/region/feed/rcmd`) 响应 */
internal fun parseRegionFeedVideos(
    data: JSONObject,
    fallbackTname: String = ""
): List<BiliMusicHotVideo> {
    val archives = data.optJSONArray("archives") ?: return emptyList()
    val out = ArrayList<BiliMusicHotVideo>(archives.length())
    for (i in 0 until archives.length()) {
        val item = archives.optJSONObject(i) ?: continue
        val goTo = item.optString("goto").trim()
        if (goTo.isNotBlank() && !goTo.equals("av", ignoreCase = true)) continue
        val aid = item.optLong("aid", item.optLong("id", 0L))
        val bvid = item.optString("bvid").trim().ifBlank {
            BiliVideoIdCodec.aidToBvid(aid)
        }
        val title = item.optString("title").trim()
        if (aid <= 0L || bvid.isBlank() || title.isBlank()) continue
        val author = item.optJSONObject("author") ?: item.optJSONObject("owner") ?: JSONObject()
        val stat = item.optJSONObject("stat") ?: JSONObject()
        val cover = normalizeBiliCoverUrl(
            item.optString("cover").ifBlank { item.optString("pic") }
        )
        val recReason = item.optString("rec_reason").trim()
        val tname = item.optString("tnamev2").trim()
            .ifBlank { item.optString("tname").trim() }
            .ifBlank { recReason }
            .ifBlank { fallbackTname }
        out += BiliMusicHotVideo(
            aid = aid,
            bvid = bvid,
            cid = item.optLong("cid", 0L),
            title = title,
            coverUrl = cover,
            durationSec = parseFlexibleBiliDurationSec(item.opt("duration")),
            uploader = author.optString("name").trim(),
            uploaderMid = author.optLong("mid", 0L),
            viewCount = stat.optLong("view", 0L),
            danmakuCount = stat.optLong("danmaku", 0L),
            pubdateSec = item.optLong("pubdate", 0L),
            tname = tname
        )
    }
    return out
}

/** 解析 B 站子分区排行榜 (`x/web-interface/ranking/region`) 响应 */
internal fun parseRegionRankingVideos(
    array: JSONArray,
    fallbackTname: String = ""
): List<BiliMusicHotVideo> {
    val out = ArrayList<BiliMusicHotVideo>(array.length())
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val aid = item.optString("aid").trim().toLongOrNull()
            ?: item.optLong("aid", 0L)
        val bvid = item.optString("bvid").trim().ifBlank {
            BiliVideoIdCodec.aidToBvid(aid)
        }
        val title = item.optString("title").trim()
        if (aid <= 0L || bvid.isBlank() || title.isBlank()) continue
        val tname = item.optString("typename").trim()
            .ifBlank { item.optString("tname").trim() }
            .ifBlank { fallbackTname }
        out += BiliMusicHotVideo(
            aid = aid,
            bvid = bvid,
            cid = item.optLong("cid", 0L),
            title = title,
            coverUrl = normalizeBiliCoverUrl(item.optString("pic")),
            durationSec = parseFlexibleBiliDurationSec(item.opt("duration")),
            uploader = item.optString("author").trim(),
            uploaderMid = item.optLong("mid", 0L),
            viewCount = item.optLong("play", 0L),
            danmakuCount = item.optLong("video_review", item.optLong("danmaku", 0L)),
            pubdateSec = 0L,
            tname = tname
        )
    }
    return out
}

internal fun parseFlexibleBiliDurationSec(raw: Any?): Int {
    return when (raw) {
        is Number -> raw.toInt().coerceAtLeast(0)
        is String -> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return 0
            trimmed.toIntOrNull()?.let { return it.coerceAtLeast(0) }
            val parts = trimmed.split(':').mapNotNull { it.trim().toIntOrNull() }
            if (parts.isEmpty() || parts.size > 3) return 0
            var total = 0
            for (part in parts) {
                total = total * 60 + part.coerceAtLeast(0)
            }
            total
        }
        else -> 0
    }
}

private fun normalizeBiliCoverUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return ""
    return when {
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.startsWith("http://", ignoreCase = true) -> "https://${trimmed.substring(7)}"
        else -> trimmed
    }
}
