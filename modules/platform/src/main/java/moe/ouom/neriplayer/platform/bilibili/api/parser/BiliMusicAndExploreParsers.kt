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
import moe.ouom.neriplayer.data.model.bilibili.music.BiliPagedVideoList
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliVideoIdCodec
import kotlin.math.roundToLong
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

/** 解析 B 站子分区按时间窗口排行 (`x/web-interface/newlist_rank`) 分页响应 */
internal fun parseRegionNewlistRankVideos(
    data: JSONObject,
    currentPage: Int,
    fallbackTname: String = ""
): BiliPagedVideoList {
    val array = data.optJSONArray("result") ?: return BiliPagedVideoList(emptyList(), hasMore = false)
    val videos = ArrayList<BiliMusicHotVideo>(array.length())
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val aid = item.optLong("id", item.optLong("aid", 0L))
        val bvid = item.optString("bvid").trim().ifBlank {
            BiliVideoIdCodec.aidToBvid(aid)
        }
        val title = decodeBiliHtmlEntities(item.optString("title").trim())
        if (aid <= 0L || bvid.isBlank() || title.isBlank()) continue
        val tname = item.optString("typename").trim()
            .ifBlank { item.optString("tname").trim() }
            .ifBlank { fallbackTname }
        val sendDateSec = item.optLong("senddate", 0L)
        val pubDateSec = sendDateSec.takeIf { it > 0L } ?: item.optLong("pubdate", 0L)
        videos += BiliMusicHotVideo(
            aid = aid,
            bvid = bvid,
            cid = item.optLong("cid", 0L),
            title = title,
            coverUrl = normalizeBiliCoverUrl(item.optString("pic")),
            durationSec = parseFlexibleBiliDurationSec(item.opt("duration")),
            uploader = item.optString("author").trim(),
            uploaderMid = item.optLong("mid", 0L),
            viewCount = parseBiliFlexibleCount(item.opt("play")),
            danmakuCount = parseBiliFlexibleCount(item.opt("video_review") ?: item.opt("danmaku")),
            pubdateSec = pubDateSec,
            tname = tname
        )
    }
    val numPages = data.optInt("numPages", 0)
    val hasMore = videos.isNotEmpty() && currentPage.coerceAtLeast(1) < numPages
    return BiliPagedVideoList(videos = videos, hasMore = hasMore)
}

/** 解析 B 站网页端关注视频动态 (`x/polymer/web-dynamic/v1/feed/all?type=video`) 响应 */
internal fun parseDynamicVideoFeed(data: JSONObject): BiliPagedVideoList {
    val items = data.optJSONArray("items")
    val nextOffset = data.optString("offset").trim()
    val apiHasMore = data.optBoolean("has_more", false)
    if (items == null) {
        return BiliPagedVideoList(
            videos = emptyList(),
            hasMore = false,
            nextOffset = nextOffset
        )
    }
    val videos = ArrayList<BiliMusicHotVideo>(items.length())
    for (i in 0 until items.length()) {
        val rawItem = items.optJSONObject(i) ?: continue
        if (!rawItem.optBoolean("visible", true)) continue
        val cardNode = if (rawItem.optString("type") == "DYNAMIC_TYPE_FORWARD") {
            rawItem.optJSONObject("orig") ?: rawItem
        } else {
            rawItem
        }
        val modules = cardNode.optJSONObject("modules") ?: continue
        val author = modules.optJSONObject("module_author") ?: JSONObject()
        val major = modules.optJSONObject("module_dynamic")?.optJSONObject("major") ?: continue
        val archive = major.optJSONObject("archive") ?: continue
        val aid = archive.optString("aid").trim().toLongOrNull()
            ?: archive.optLong("aid", 0L)
        val bvid = archive.optString("bvid").trim().ifBlank {
            BiliVideoIdCodec.aidToBvid(aid)
        }
        val title = archive.optString("title").trim()
        if (aid <= 0L || bvid.isBlank() || title.isBlank()) continue
        val stat = archive.optJSONObject("stat") ?: JSONObject()
        val badgeText = archive.optJSONObject("badge")?.optString("text")?.trim().orEmpty()
        videos += BiliMusicHotVideo(
            aid = aid,
            bvid = bvid,
            cid = 0L,
            title = title,
            coverUrl = normalizeBiliCoverUrl(archive.optString("cover")),
            durationSec = parseFlexibleBiliDurationSec(archive.opt("duration_text")),
            uploader = author.optString("name").trim(),
            uploaderMid = author.optLong("mid", 0L),
            viewCount = parseBiliFlexibleCount(stat.opt("play")),
            danmakuCount = parseBiliFlexibleCount(stat.opt("danmaku")),
            pubdateSec = author.optLong("pub_ts", 0L),
            tname = badgeText
        )
    }
    return BiliPagedVideoList(
        videos = videos,
        hasMore = apiHasMore && nextOffset.isNotBlank(),
        nextOffset = nextOffset
    )
}

/** 解析 B 站移动端音乐子分区实时动态流 (`app.bilibili.com/x/v2/region/dynamic/child`) 响应 */
internal fun parseRegionDynamicChildVideos(
    data: JSONObject,
    fallbackTname: String = ""
): BiliPagedVideoList {
    val array = data.optJSONArray("new")
    val cbottomLong = data.optLong("cbottom", 0L)
    val nextCursor = if (cbottomLong > 0L) {
        cbottomLong.toString()
    } else {
        data.optString("cbottom").trim().takeIf { it.isNotEmpty() && it != "0" }.orEmpty()
    }
    if (array == null) {
        return BiliPagedVideoList(videos = emptyList(), hasMore = false)
    }
    val videos = ArrayList<BiliMusicHotVideo>(array.length())
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val goTo = item.optString("goto").trim()
        if (goTo.isNotBlank() && !goTo.equals("av", ignoreCase = true)) continue
        val aid = item.optString("param").trim().toLongOrNull()
            ?: item.optLong("aid", 0L)
        val bvid = item.optString("bvid").trim().ifBlank {
            BiliVideoIdCodec.aidToBvid(aid)
        }
        val title = decodeBiliHtmlEntities(item.optString("title").trim())
        if (aid <= 0L || bvid.isBlank() || title.isBlank()) continue
        val cover = normalizeBiliCoverUrl(
            item.optString("cover").ifBlank { item.optString("pic") }
        )
        val tname = item.optString("rname").trim()
            .ifBlank { item.optString("tname").trim() }
            .ifBlank { fallbackTname }
        videos += BiliMusicHotVideo(
            aid = aid,
            bvid = bvid,
            cid = item.optLong("cid", 0L),
            title = title,
            coverUrl = cover,
            durationSec = parseFlexibleBiliDurationSec(item.opt("duration")),
            uploader = item.optString("name").trim(),
            uploaderMid = item.optLong("mid", 0L),
            viewCount = parseBiliFlexibleCount(item.opt("play")),
            danmakuCount = parseBiliFlexibleCount(item.opt("danmaku")),
            pubdateSec = item.optLong("pubdate", 0L),
            tname = tname
        )
    }
    return BiliPagedVideoList(
        videos = videos,
        hasMore = videos.isNotEmpty() && nextCursor.isNotBlank(),
        nextOffset = nextCursor
    )
}

private fun decodeBiliHtmlEntities(text: String): String {
    if (!text.contains('&')) return text
    return text
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
}

internal fun parseBiliFlexibleCount(raw: Any?): Long {
    if (raw == null || raw == JSONObject.NULL) return 0L
    if (raw is Number) return raw.toLong().coerceAtLeast(0L)
    val text = raw.toString().trim().replace(",", "")
    if (text.isEmpty() || text == "-") return 0L
    text.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
    val multiplier = when {
        text.endsWith("万") -> 10_000.0
        text.endsWith("亿") -> 100_000_000.0
        else -> return text.toDoubleOrNull()?.roundToLong()?.coerceAtLeast(0L) ?: 0L
    }
    val numeric = text.dropLast(1).trim().toDoubleOrNull() ?: return 0L
    return (numeric * multiplier).roundToLong().coerceAtLeast(0L)
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
