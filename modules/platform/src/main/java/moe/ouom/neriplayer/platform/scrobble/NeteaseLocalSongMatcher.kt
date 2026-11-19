package moe.ouom.neriplayer.platform.scrobble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.platform.gdmusic.GdMusicTextMatcher
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * 本地歌曲到网易云曲库的匹配器：
 * 1. 时长定死（双方均需有有效时长，且差值 <= 3s）
 * 2. 标题、歌手、专辑名按部分重合（支持 feat 去噪、日文平片假名/新旧字体归一化、别名/译名）计分，
 *    至少两项重合且版本标签不冲突时判定通过
 */
class NeteaseLocalSongMatcher(
    private val neteaseClient: NeteaseClient
) {
    companion object {
        private const val TAG = "NERI-NeteaseLocalMatcher"
        private const val MAX_CACHE_ENTRIES = 512
        private const val MAX_DURATION_DIFF_MS = 3_000L
        private const val PRIMARY_SEARCH_LIMIT = 10
        private const val FALLBACK_SEARCH_LIMIT = 20
        private const val CACHE_MISS_ID = -1L

        private val featSuffixRegex = Regex(
            """(?i)(?:\s*[-–—]\s*|\s+)(?:feat\.|ft\.|featuring\b|(?:feat|ft)\s+)\s*\S.*$"""
        )
        private val bracketPairRegex = Regex(
            """[\(\（\[【\<《]([^\)\）\]】\>》]+)[\)\）\]】\>》]"""
        )
        private val segmentSplitRegex = Regex("""\s+(?:/|\||-|–|—)\s+""")
        private val punctuationRegex = Regex("""[^\p{L}\p{N}\s]+""")
        private val whitespaceRegex = Regex("""\s+""")

        private val shinjitaiToSimp = mapOf(
            '桜' to '樱', '櫻' to '樱', '響' to '响', '駅' to '驿', '夢' to '梦',
            '恋' to '恋', '剣' to '剑', '竜' to '龙', '黒' to '黑', '歩' to '步',
            '歳' to '岁', '昼' to '昼', '涙' to '泪', '声' to '声', '聴' to '听',
            '飛' to '飞', '葉' to '叶', '実' to '实', '広' to '广', '気' to '气',
            '絵' to '绘', '鉄' to '铁', '門' to '门', '問' to '问', '間' to '间',
            '時' to '时', '東' to '东', '風' to '风', '雲' to '云', '楽' to '乐',
            '歓' to '欢', '転' to '转', '伝' to '传', '戦' to '战', '緑' to '绿',
            '縁' to '缘', '続' to '续', '読' to '读', '語' to '语', '話' to '话',
            '詩' to '诗', '変' to '变', '応' to '应', '対' to '对', '観' to '观',
            '辺' to '边'
        )

        private val unknownArtistTokens = setOf(
            "",
            "unknown",
            "unknown artist",
            "<unknown>",
            "未知歌手",
            "未知艺术家",
            "群星",
            "various artists"
        )

        private val unknownAlbumTokens = setOf(
            "",
            "__local_files__",
            "本地文件",
            "local files",
            "unknown",
            "unknown album",
            "<unknown>",
            "未知专辑"
        )

        private val tokenStopWords = setOf(
            "the", "a", "an", "of", "and", "or", "in", "on", "at", "to",
            "for", "by", "from", "with", "feat", "ft", "featuring", "ver",
            "version", "mix", "edit", "vol", "pt"
        )
    }

    private enum class MatchStrength {
        NONE,
        PARTIAL,
        EXACT
    }

    private data class MatchTarget(
        val rawTitle: String,
        val cleanTitle: String,
        val cleanAlbum: String,
        val artists: List<String>,
        val durationMs: Long,
        val titleVariants: List<String>,
        val artistVariants: List<String>,
        val albumVariants: List<String>
    )

    private data class MatchCandidate(
        val id: Long,
        val primaryTitle: String,
        val durationMs: Long,
        val titleVariants: List<String>,
        val artistVariants: List<String>,
        val albumVariants: List<String>
    )

    private val lock = Any()
    private val matchCache = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    suspend fun matchLocalTrack(track: NeteaseScrobbleTrack): Long? = withContext(Dispatchers.IO) {
        if (track.durationMs <= 0L) {
            return@withContext null
        }

        val target = buildMatchTarget(track) ?: return@withContext null
        val cacheKey = buildCacheKey(target)
        synchronized(lock) {
            val cached = matchCache[cacheKey]
            if (cached != null) {
                return@withContext if (cached == CACHE_MISS_ID) null else cached
            }
        }

        val matchedId = try {
            verifyMatchedId(track, target) ?: searchMatch(target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            NPLogger.w(TAG, "本地歌曲匹配网易云失败: title=${target.rawTitle}", e)
            return@withContext null
        }

        synchronized(lock) {
            matchCache[cacheKey] = matchedId ?: CACHE_MISS_ID
        }
        matchedId
    }

    private fun buildMatchTarget(track: NeteaseScrobbleTrack): MatchTarget? {
        val rawTitle = track.title.trim()
        val artists = GdMusicTextMatcher.splitArtists(track.artist)
            .filterNot { isUnknownToken(it, unknownArtistTokens) }
        val cleanAlbum = track.album.trim()
            .takeUnless { isUnknownToken(it, unknownAlbumTokens) }
            .orEmpty()

        val titleVariants = extractTitleVariants(rawTitle)
        val artistVariants = extractArtistVariants(artists)
        val albumVariants = extractAlbumVariants(cleanAlbum)

        val availableDimensions = (if (titleVariants.isNotEmpty()) 1 else 0) +
            (if (artistVariants.isNotEmpty()) 1 else 0) +
            (if (albumVariants.isNotEmpty()) 1 else 0)
        if (availableDimensions < 2) {
            return null
        }

        val cleanTitle = stripTitleDecorations(rawTitle).ifBlank { rawTitle }
        return MatchTarget(
            rawTitle = rawTitle,
            cleanTitle = cleanTitle,
            cleanAlbum = stripTitleDecorations(cleanAlbum).ifBlank { cleanAlbum },
            artists = artists,
            durationMs = track.durationMs,
            titleVariants = titleVariants,
            artistVariants = artistVariants,
            albumVariants = albumVariants
        )
    }

    private suspend fun verifyMatchedId(
        track: NeteaseScrobbleTrack,
        target: MatchTarget
    ): Long? {
        if (track.matchedLyricSource != MusicPlatform.CLOUD_MUSIC) return null
        val matchedId = track.matchedSongId?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val detailJson = neteaseClient.getSongDetailCancellable(listOf(matchedId))
        val candidates = parseDetailSongs(detailJson)
        return findBestCandidate(target, candidates)
    }

    private suspend fun searchMatch(target: MatchTarget): Long? {
        val primaryArtist = target.artists.firstOrNull().orEmpty()
        val queries = linkedSetOf<Pair<String, Int>>()
        if (target.cleanTitle.isNotBlank() && primaryArtist.isNotBlank()) {
            queries += "${target.cleanTitle} $primaryArtist" to PRIMARY_SEARCH_LIMIT
        }
        if (target.cleanAlbum.isNotBlank() && primaryArtist.isNotBlank()) {
            queries += "${target.cleanAlbum} $primaryArtist" to FALLBACK_SEARCH_LIMIT
        }
        if (target.cleanTitle.isNotBlank() && target.cleanAlbum.isNotBlank()) {
            queries += "${target.cleanTitle} ${target.cleanAlbum}" to FALLBACK_SEARCH_LIMIT
        }
        if (target.cleanTitle.isNotBlank()) {
            val limit = if (queries.isEmpty()) PRIMARY_SEARCH_LIMIT else FALLBACK_SEARCH_LIMIT
            queries += target.cleanTitle to limit
        }

        for ((keyword, limit) in queries) {
            val trimmed = keyword.trim()
            if (trimmed.isEmpty()) continue
            val rawSearch = neteaseClient.searchSongsCancellable(
                keyword = trimmed,
                limit = limit,
                offset = 0,
                type = 1,
                usePersistedCookies = true
            )
            val candidates = parseSearchSongs(rawSearch)
            if (candidates.isEmpty()) continue
            findBestCandidate(target, candidates)?.let { return it }
        }
        return null
    }

    private fun findBestCandidate(
        target: MatchTarget,
        candidates: List<MatchCandidate>
    ): Long? {
        return candidates
            .asSequence()
            .mapNotNull { candidate -> scoreCandidate(target, candidate)?.let { candidate.id to it } }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun scoreCandidate(target: MatchTarget, candidate: MatchCandidate): Int? {
        if (target.durationMs <= 0L || candidate.durationMs <= 0L) return null
        val durationDiffMs = abs(target.durationMs - candidate.durationMs)
        if (durationDiffMs > MAX_DURATION_DIFF_MS) return null

        val targetTags = GdMusicTextMatcher.extractVersionTags(target.rawTitle)
        val candTags = GdMusicTextMatcher.extractVersionTags(candidate.primaryTitle)
        if (targetTags != candTags) return null

        val titleStrength = overlapStrength(target.titleVariants, candidate.titleVariants)
        val artistStrength = overlapStrength(target.artistVariants, candidate.artistVariants)
        val albumStrength = overlapStrength(target.albumVariants, candidate.albumVariants)

        val matchedDimensions = (if (titleStrength != MatchStrength.NONE) 1 else 0) +
            (if (artistStrength != MatchStrength.NONE) 1 else 0) +
            (if (albumStrength != MatchStrength.NONE) 1 else 0)
        if (matchedDimensions < 2) return null

        val titleBonus = strengthBonus(titleStrength, exact = 50, partial = 30)
        val artistBonus = strengthBonus(artistStrength, exact = 35, partial = 20)
        val albumBonus = strengthBonus(albumStrength, exact = 35, partial = 20)
        val durationBonus = ((MAX_DURATION_DIFF_MS - durationDiffMs) / 100L).toInt()
        return matchedDimensions * 100 + titleBonus + artistBonus + albumBonus + durationBonus
    }

    private fun strengthBonus(strength: MatchStrength, exact: Int, partial: Int): Int = when (strength) {
        MatchStrength.EXACT -> exact
        MatchStrength.PARTIAL -> partial
        MatchStrength.NONE -> 0
    }

    private fun overlapStrength(expectedVars: List<String>, candidateVars: List<String>): MatchStrength {
        if (expectedVars.isEmpty() || candidateVars.isEmpty()) return MatchStrength.NONE
        var best = MatchStrength.NONE
        for (expected in expectedVars) {
            val expNorm = normalizePhoneticText(expected)
            val expCompact = expNorm.replace(" ", "")
            if (expCompact.isEmpty()) continue
            val expTokens = meaningfulTokens(expNorm)

            for (candidate in candidateVars) {
                val candNorm = normalizePhoneticText(candidate)
                val candCompact = candNorm.replace(" ", "")
                if (candCompact.isEmpty()) continue
                if (expCompact == candCompact) return MatchStrength.EXACT

                val minLen = min(expCompact.length, candCompact.length)
                if (minLen >= 2 && (expCompact.contains(candCompact) || candCompact.contains(expCompact))) {
                    best = MatchStrength.PARTIAL
                    continue
                }

                val candTokens = meaningfulTokens(candNorm)
                if (expTokens.isNotEmpty() && candTokens.isNotEmpty()) {
                    val sharedCount = expTokens.count { it in candTokens }
                    if (sharedCount > 0 && sharedCount * 2 >= min(expTokens.size, candTokens.size)) {
                        best = MatchStrength.PARTIAL
                    }
                }
            }
        }
        return best
    }

    private fun meaningfulTokens(normalized: String): Set<String> {
        if (normalized.isBlank()) return emptySet()
        return normalized.split(' ')
            .asSequence()
            .filter { it.length >= 2 && it !in tokenStopWords }
            .toSet()
    }

    private fun extractTitleVariants(rawTitle: String): List<String> {
        if (rawTitle.isBlank()) return emptyList()
        return buildList {
            add(rawTitle)
            val withoutBrackets = GdMusicTextMatcher.stripParentheticals(rawTitle)
            if (withoutBrackets.isNotBlank()) add(withoutBrackets)
            val withoutFeat = stripTitleDecorations(rawTitle)
            if (withoutFeat.isNotBlank()) add(withoutFeat)
            addBracketVariants(rawTitle, skipPureVersionTag = true)
            segmentSplitRegex.split(withoutBrackets.ifBlank { rawTitle })
                .map { stripTitleDecorations(it) }
                .filter { it.length >= 2 && GdMusicTextMatcher.extractVersionTags(it).isEmpty() }
                .forEach(::add)
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun extractArtistVariants(artists: List<String>): List<String> {
        if (artists.isEmpty()) return emptyList()
        return buildList {
            artists.forEach { artist ->
                add(artist)
                val withoutBrackets = GdMusicTextMatcher.stripParentheticals(artist)
                if (withoutBrackets.isNotBlank()) add(withoutBrackets)
                addBracketVariants(artist, skipPureVersionTag = false)
            }
        }.map { it.trim() }.filter { it.isNotEmpty() && !isUnknownToken(it, unknownArtistTokens) }.distinct()
    }

    private fun extractAlbumVariants(rawAlbum: String): List<String> {
        if (rawAlbum.isBlank() || isUnknownToken(rawAlbum, unknownAlbumTokens)) return emptyList()
        return buildList {
            add(rawAlbum)
            val withoutBrackets = GdMusicTextMatcher.stripParentheticals(rawAlbum)
            if (withoutBrackets.isNotBlank()) add(withoutBrackets)
            addBracketVariants(rawAlbum, skipPureVersionTag = false)
            segmentSplitRegex.split(withoutBrackets.ifBlank { rawAlbum })
                .map { it.trim() }
                .filter { it.length >= 2 }
                .forEach(::add)
        }.map { it.trim() }.filter { it.isNotEmpty() && !isUnknownToken(it, unknownAlbumTokens) }.distinct()
    }

    private fun MutableList<String>.addBracketVariants(raw: String, skipPureVersionTag: Boolean) {
        bracketPairRegex.findAll(raw).forEach { match ->
            val inner = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (inner.length < 2) return@forEach
            val strippedInner = stripTitleDecorations(inner)
            if (skipPureVersionTag && GdMusicTextMatcher.extractVersionTags(inner).isNotEmpty()) {
                return@forEach
            }
            if (strippedInner.length >= 2) {
                add(strippedInner)
            }
        }
    }

    private fun stripTitleDecorations(raw: String): String {
        val noParens = GdMusicTextMatcher.stripParentheticals(raw)
        val noFeat = featSuffixRegex.replace(noParens, "").trim()
        return noFeat.trimEnd('-', '/', '–', '—', ' ')
    }

    private fun normalizePhoneticText(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val simplified = GdMusicTextMatcher.t2s(decomposed)
        val folded = StringBuilder(simplified.length)
        for (ch in simplified) {
            val mapped = shinjitaiToSimp[ch] ?: ch
            folded.append(foldJapaneseKana(mapped))
        }
        val lower = folded.toString().lowercase(Locale.ROOT)
        val noPunct = punctuationRegex.replace(lower, " ")
        return whitespaceRegex.replace(noPunct, " ").trim()
    }

    private fun foldJapaneseKana(ch: Char): Char = when (ch) {
        in '\u30A1'..'\u30F6' -> (ch.code - 0x60).toChar()
        'ヵ', 'ヶ' -> 'か'
        'ヴ' -> 'う'
        else -> ch
    }

    private fun isUnknownToken(value: String, tokens: Set<String>): Boolean {
        val trimmed = value.trim().lowercase(Locale.ROOT)
        return trimmed in tokens || GdMusicTextMatcher.compactText(value) in tokens
    }

    private fun parseDetailSongs(rawJson: String?): List<MatchCandidate> {
        if (rawJson.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
        return parseSongArray(root.optJSONArray("songs"))
    }

    private fun parseSearchSongs(rawJson: String?): List<MatchCandidate> {
        if (rawJson.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
        val songs = root.optJSONObject("result")?.optJSONArray("songs")
        return parseSongArray(songs)
    }

    private fun parseSongArray(array: JSONArray?): List<MatchCandidate> {
        if (array == null) return emptyList()
        val list = ArrayList<MatchCandidate>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optLong("id", 0L).takeIf { it > 0L } ?: continue
            val name = obj.optString("name", "").trim().takeIf { it.isNotEmpty() } ?: continue
            val durationMs = obj.optLong("dt", 0L).takeIf { it > 0L }
                ?: obj.optLong("duration", 0L).coerceAtLeast(0L)
            val titleVariants = buildList {
                addAll(extractTitleVariants(name))
                extractStringArray(obj.optJSONArray("alia")).forEach { addAll(extractTitleVariants(it)) }
                extractStringArray(obj.optJSONArray("tns")).forEach { addAll(extractTitleVariants(it)) }
            }.distinct()
            val artistVariants = extractArtists(obj)
            val albumVariants = extractAlbumFromJson(obj)
            list.add(
                MatchCandidate(
                    id = id,
                    primaryTitle = name,
                    durationMs = durationMs,
                    titleVariants = titleVariants,
                    artistVariants = artistVariants,
                    albumVariants = albumVariants
                )
            )
        }
        return list
    }

    private fun extractArtists(songObj: JSONObject): List<String> {
        val arArray = songObj.optJSONArray("ar") ?: songObj.optJSONArray("artists") ?: return emptyList()
        val names = buildList {
            for (i in 0 until arArray.length()) {
                val artistObj = arArray.optJSONObject(i) ?: continue
                artistObj.optString("name", "").trim().takeIf { it.isNotEmpty() }?.let(::add)
                extractStringArray(artistObj.optJSONArray("alias")).forEach(::add)
                extractStringArray(artistObj.optJSONArray("alia")).forEach(::add)
                extractStringArray(artistObj.optJSONArray("tns")).forEach(::add)
                extractStringArray(artistObj.optJSONArray("trans")).forEach(::add)
            }
        }
        return extractArtistVariants(names)
    }

    private fun extractAlbumFromJson(songObj: JSONObject): List<String> {
        val albumObj = songObj.optJSONObject("al") ?: songObj.optJSONObject("album") ?: return emptyList()
        return buildList {
            albumObj.optString("name", "").trim().takeIf { it.isNotEmpty() }?.let {
                addAll(extractAlbumVariants(it))
            }
            extractStringArray(albumObj.optJSONArray("tns")).forEach { addAll(extractAlbumVariants(it)) }
            extractStringArray(albumObj.optJSONArray("alia")).forEach { addAll(extractAlbumVariants(it)) }
            extractStringArray(albumObj.optJSONArray("alias")).forEach { addAll(extractAlbumVariants(it)) }
        }.distinct()
    }

    private fun extractStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
    }

    private fun buildCacheKey(target: MatchTarget): String {
        val roundedDurationSec = target.durationMs / 1000L
        return buildString {
            append(normalizePhoneticText(target.rawTitle).replace(" ", ""))
            append('|')
            append(target.artists.joinToString(",") { normalizePhoneticText(it).replace(" ", "") })
            append('|')
            append(normalizePhoneticText(target.cleanAlbum).replace(" ", ""))
            append('|')
            append(roundedDurationSec)
        }
    }
}
