package moe.ouom.neriplayer.platform.bilibili.api.client

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

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.bilibili.music.BiliPagedVideoList
import moe.ouom.neriplayer.platform.bilibili.api.auth.BiliCookieSession
import moe.ouom.neriplayer.platform.bilibili.api.auth.BiliCookieSource
import moe.ouom.neriplayer.platform.bilibili.api.http.BILI_WEB_REFERER
import moe.ouom.neriplayer.platform.bilibili.api.http.BILI_WEB_USER_AGENT
import moe.ouom.neriplayer.platform.bilibili.api.http.biliCookie
import moe.ouom.neriplayer.platform.bilibili.api.http.executeBiliOrThrow
import moe.ouom.neriplayer.platform.bilibili.api.http.toBiliCookieHeader
import moe.ouom.neriplayer.platform.bilibili.api.parser.parseDynamicVideoFeed
import moe.ouom.neriplayer.platform.bilibili.api.parser.parseRegionNewlistRankVideos
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Bilibili 探索页扩展接口（关注视频动态与子分区时间窗口分页排行） */
internal class BiliExploreApi(
    private val cookieRepo: BiliCookieSource,
    private val cookieSession: BiliCookieSession,
    private val http: OkHttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    suspend fun getRegionNewlistRankVideos(
        rid: Int,
        page: Int = 1,
        pageSize: Int = DEFAULT_NEWLIST_PAGE_SIZE,
        order: String = "click",
        fallbackTname: String = ""
    ): BiliPagedVideoList = withContext(Dispatchers.IO) {
        val safePage = page.coerceAtLeast(1)
        val (timeFrom, timeTo) = buildRecentDateWindow(nowMs())
        val url = NEWLIST_RANK_URL.toHttpUrl().newBuilder()
            .addQueryParameter("main_ver", "v3")
            .addQueryParameter("search_type", "video")
            .addQueryParameter("view_type", "hot_rank")
            .addQueryParameter("order", order.ifBlank { "click" })
            .addQueryParameter("copy_right", "-1")
            .addQueryParameter("cate_id", rid.toString())
            .addQueryParameter("page", safePage.toString())
            .addQueryParameter("pagesize", pageSize.coerceIn(1, 50).toString())
            .addQueryParameter("time_from", timeFrom)
            .addQueryParameter("time_to", timeTo)
            .build()
        val cookieHeader = cookieSession.effectiveCookies().toBiliCookieHeader()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BILI_WEB_USER_AGENT)
            .header("Referer", BILI_WEB_REFERER)
            .header("Origin", BILI_WEB_REFERER)
            .biliCookie(cookieHeader)
            .get()
            .build()
        val body = http.newCall(request).executeBiliOrThrow().use { it.body.string() }
        val response = JSONObject(body)
        val code = response.optInt("code", -1)
        if (code != 0) {
            throw IOException("Bilibili newlist_rank failed: code=$code msg=${response.optString("message")}")
        }
        val data = response.optJSONObject("data") ?: JSONObject()
        parseRegionNewlistRankVideos(data, currentPage = safePage, fallbackTname = fallbackTname)
    }

    suspend fun getDynamicVideoFeed(offset: String = ""): BiliPagedVideoList = withContext(Dispatchers.IO) {
        val storedCookies = cookieRepo.getCookiesOnce()
        if (storedCookies["SESSDATA"].isNullOrBlank()) {
            return@withContext BiliPagedVideoList(
                videos = emptyList(),
                hasMore = false,
                requiresLogin = true
            )
        }
        val webCookies = cookieSession.effectiveWebFeedCookies()
        val builder = WEB_DYNAMIC_FEED_URL.toHttpUrl().newBuilder()
            .addQueryParameter("type", "video")
            .addQueryParameter("timezone_offset", "-480")
            .addQueryParameter("platform", "web")
            .addQueryParameter("features", "itemOpusStyle")
        val trimmedOffset = offset.trim()
        if (trimmedOffset.isNotEmpty()) {
            builder.addQueryParameter("offset", trimmedOffset)
        }
        val request = Request.Builder()
            .url(builder.build())
            .header("User-Agent", BILI_WEB_USER_AGENT)
            .header("Referer", DYNAMIC_REFERER)
            .header("Origin", DYNAMIC_ORIGIN)
            .biliCookie(webCookies.toBiliCookieHeader())
            .get()
            .build()
        val body = http.newCall(request).executeBiliOrThrow().use { it.body.string() }
        val response = JSONObject(body)
        val code = response.optInt("code", -1)
        if (code == BILI_CODE_NOT_LOGGED_IN) {
            return@withContext BiliPagedVideoList(
                videos = emptyList(),
                hasMore = false,
                requiresLogin = true
            )
        }
        if (code != 0) {
            throw IOException("Bilibili web-dynamic failed: code=$code msg=${response.optString("message")}")
        }
        val data = response.optJSONObject("data") ?: JSONObject()
        parseDynamicVideoFeed(data)
    }

    private fun buildRecentDateWindow(nowMillis: Long): Pair<String, String> {
        val tz = TimeZone.getTimeZone("Asia/Shanghai")
        val formatter = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = tz
        }
        val calendar = Calendar.getInstance(tz, Locale.US).apply {
            timeInMillis = nowMillis
        }
        val timeTo = formatter.format(calendar.time)
        calendar.add(Calendar.DAY_OF_MONTH, -WINDOW_DAYS)
        val timeFrom = formatter.format(calendar.time)
        return timeFrom to timeTo
    }

    private companion object {
        const val NEWLIST_RANK_URL = "https://api.bilibili.com/x/web-interface/newlist_rank"
        const val WEB_DYNAMIC_FEED_URL = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all"
        const val DYNAMIC_ORIGIN = "https://t.bilibili.com"
        const val DYNAMIC_REFERER = "https://t.bilibili.com/"
        const val DEFAULT_NEWLIST_PAGE_SIZE = 20
        const val WINDOW_DAYS = 7
        const val BILI_CODE_NOT_LOGGED_IN = -101
    }
}
