package moe.ouom.neriplayer.platform.gdmusic

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * 曲名 / 歌手 / 版本标记 / 时长匹配工具，供 GD Music 多源回退与本地歌曲网易云严格匹配共用。
 */
object GdMusicTextMatcher {

    enum class VersionTag {
        LIVE,
        ACOUSTIC,
        INSTRUMENTAL,
        REMIX,
        DJ,
        COVER,
        TV,
        DEMO,
        SLOWED,
        SPED_UP
    }

    data class SongMatchTarget(
        val title: String,
        val artists: List<String>,
        val durationMs: Long = 0L
    )

    data class SongMatchCandidate<T>(
        val item: T,
        val title: String,
        val artists: List<String>,
        val durationMs: Long = 0L
    )

    private val parentheticalRegex = Regex(
        """[\(\（\[【\<《][^\)\）\]】\>》]*[\)\）\]】\>》]"""
    )

    private val artistSplitRegex = Regex(
        """\s*(?:/|&|,|、|;|；|\+|×|\s+x\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+with\s+)\s*""",
        RegexOption.IGNORE_CASE
    )

    private val punctuationRegex = Regex("""[^\p{L}\p{N}\s]+""")
    private val whitespaceRegex = Regex("""\s+""")

    private val versionRules: List<Pair<VersionTag, Regex>> = listOf(
        VersionTag.LIVE to Regex("""\b(live|演唱会|音乐会|concert)\b|现场""", RegexOption.IGNORE_CASE),
        VersionTag.ACOUSTIC to Regex("""\b(acoustic|unplugged)\b|不插电|木吉他""", RegexOption.IGNORE_CASE),
        VersionTag.INSTRUMENTAL to Regex("""\b(inst|instrumental|karaoke|off vocal)\b|纯音乐|伴奏|消音""", RegexOption.IGNORE_CASE),
        VersionTag.REMIX to Regex("""\b(remix|mix|edit|vip|bootleg)\b|混音|重制""", RegexOption.IGNORE_CASE),
        VersionTag.DJ to Regex("""\b(dj)\b|串烧|慢摇""", RegexOption.IGNORE_CASE),
        VersionTag.COVER to Regex("""\b(cover)\b|翻唱""", RegexOption.IGNORE_CASE),
        VersionTag.TV to Regex("""\b(tv\s*size|tv\s*ver|short\s*ver)\b|tv版""", RegexOption.IGNORE_CASE),
        VersionTag.DEMO to Regex("""\b(demo)\b|小样""", RegexOption.IGNORE_CASE),
        VersionTag.SLOWED to Regex("""\b(slowed|reverb|0\.8x)\b|降速|慢放""", RegexOption.IGNORE_CASE),
        VersionTag.SPED_UP to Regex("""\b(sped\s*up|speed\s*up|nightcore|1\.2x)\b|加速|倍速""", RegexOption.IGNORE_CASE)
    )

    private const val TRAD_CHARS =
        "愛聲夢淚聽獨歡樂飛風雲葉實點對錯開關這個為從還沒別讓給過進發現覺記憶戀無盡處當經舊歲時間裏裡廣東臺華語雙親離傷溫餘韻絕專輯選創場純輕節電視劇頭題單數寫銀藍紅塵飄靜遙遠尋執約恆終邊緣靈擁牽歸見國粵陽萬長黃髮氣說話簡後擱淺龍捲園遊會麼爺戰殤麥車術傳詞薦總結調優編隱顯設認網絡頻與來們妳誰樣種幾線圓團號碼響鳴動變書頁燈滅燒熱鐵銅鏡門問聞閉馬鳥魚鳳讀識證謝請講論試貨買賣貝財貴費賭贈贊贏輪載輩輝轉轟辭農連違適遺邁鄉鄰醫鎮閃陰際隨難雜須領顏願類顧飯飲館驚體齊"

    private const val SIMP_CHARS =
        "爱声梦泪听独欢乐飞风云叶实点对错开关这个为从还没别让给过进发现觉记忆恋无尽处当经旧岁时间里里广东台华语双亲离伤温余韵绝专辑选创场纯轻节电视剧头题单数写银蓝红尘飘静遥远寻执约恒终边缘灵拥牵归见国粤阳万长黄发气说话简后搁浅龙卷园游会么爷战殇麦车术传词荐总结调优编隐显设认网络频与来们你谁样种几线圆团号码响鸣动变书页灯灭烧热铁铜镜门问闻闭马鸟鱼凤读识证谢请讲论试货买卖贝财贵费赌赠赞赢轮载辈辉转轰辞农连违适遗迈乡邻医镇闪阴际随难杂须领颜愿类顾饭饮馆惊体齐"

    private val tradToSimp: Map<Char, Char> = TRAD_CHARS.indices.associate { TRAD_CHARS[it] to SIMP_CHARS[it] }

    fun t2s(input: String): String {
        if (input.isEmpty()) return ""
        val sb = StringBuilder(input.length)
        for (ch in input) {
            sb.append(tradToSimp[ch] ?: ch)
        }
        return sb.toString()
    }

    fun normalizeText(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val simplified = t2s(decomposed).lowercase(Locale.ROOT)
        val noPunct = punctuationRegex.replace(simplified, " ")
        return whitespaceRegex.replace(noPunct, " ").trim()
    }

    fun compactText(raw: String?): String =
        normalizeText(raw).replace(" ", "")

    fun stripParentheticals(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var cleaned = raw.trim()
        while (true) {
            val next = parentheticalRegex.replace(cleaned, " ").trim()
            if (next == cleaned) break
            cleaned = next
        }
        return whitespaceRegex.replace(cleaned, " ").trim()
    }

    fun extractVersionTags(raw: String?): Set<VersionTag> {
        if (raw.isNullOrBlank()) return emptySet()
        val simplified = t2s(Normalizer.normalize(raw, Normalizer.Form.NFKC)).lowercase(Locale.ROOT)
        return versionRules.mapNotNullTo(linkedSetOf()) { (tag, regex) ->
            if (regex.containsMatchIn(simplified)) tag else null
        }
    }

    fun splitArtists(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return artistSplitRegex.split(raw.trim())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { compactText(it) }
    }

    fun artistsMatch(
        expectedArtists: List<String>,
        candidateArtists: List<String>
    ): Boolean {
        val expected = expectedArtists.map { compactText(it) }.filter { it.isNotEmpty() }
        val candidate = candidateArtists.map { compactText(it) }.filter { it.isNotEmpty() }
        if (expected.isEmpty()) return candidate.isEmpty()
        if (candidate.isEmpty()) return false
        return expected.any { exp ->
            candidate.any { cand ->
                cand == exp || (exp.length >= 2 && cand.length >= 2 && (cand.contains(exp) || exp.contains(cand)))
            }
        }
    }

    fun <T> findBestMatch(
        target: SongMatchTarget,
        candidates: List<SongMatchCandidate<T>>,
        maxDurationDiffMs: Long = 12_000L,
        requireArtistWhenPresent: Boolean = true
    ): T? {
        val scored = candidates.mapNotNull { candidate ->
            scoreCandidate(
                target = target,
                candidate = candidate,
                maxDurationDiffMs = maxDurationDiffMs,
                requireArtistWhenPresent = requireArtistWhenPresent
            )?.let { score -> candidate.item to score }
        }
        return scored.maxByOrNull { it.second }?.first
    }

    fun <T> scoreCandidate(
        target: SongMatchTarget,
        candidate: SongMatchCandidate<T>,
        maxDurationDiffMs: Long = 12_000L,
        requireArtistWhenPresent: Boolean = true
    ): Int? {
        val targetFull = compactText(target.title)
        val candFull = compactText(candidate.title)
        if (targetFull.isEmpty() || candFull.isEmpty()) return null

        val targetBase = compactText(stripParentheticals(target.title)).ifEmpty { targetFull }
        val candBase = compactText(stripParentheticals(candidate.title)).ifEmpty { candFull }

        val titleScore = when {
            targetFull == candFull -> 100
            targetBase == candBase -> 80
            else -> return null
        }

        val targetTags = extractVersionTags(target.title)
        val candTags = extractVersionTags(candidate.title)
        if (targetTags != candTags) return null

        if (target.durationMs > 0L && candidate.durationMs > 0L) {
            if (abs(target.durationMs - candidate.durationMs) > maxDurationDiffMs) return null
        }

        val targetArtists = target.artists.flatMap { splitArtists(it) }.ifEmpty { target.artists }
        val candArtists = candidate.artists.flatMap { splitArtists(it) }.ifEmpty { candidate.artists }
        if (requireArtistWhenPresent && targetArtists.isNotEmpty() && candArtists.isNotEmpty()) {
            if (!artistsMatch(targetArtists, candArtists)) return null
        } else if (requireArtistWhenPresent && targetArtists.isNotEmpty() && candArtists.isEmpty()) {
            return null
        }

        val artistBonus = if (artistsMatch(targetArtists, candArtists)) 30 else 0
        val durationBonus = if (target.durationMs > 0L && candidate.durationMs > 0L) {
            val diff = abs(target.durationMs - candidate.durationMs)
            (10 - (diff / 1000L).toInt()).coerceAtLeast(0)
        } else {
            0
        }
        return titleScore + artistBonus + durationBonus
    }
}
