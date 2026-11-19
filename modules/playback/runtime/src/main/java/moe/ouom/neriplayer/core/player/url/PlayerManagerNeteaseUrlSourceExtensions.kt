package moe.ouom.neriplayer.core.player.url

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.quality.effectiveNeteaseQuality
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.playback.deriveCodecLabel
import moe.ouom.neriplayer.platform.netease.api.playback.parser.NeteasePlaybackResponseParser

/** 网易云官方/GD Music 音源解析辅助，避免 [PlayerManagerUrlExtensions] 超出单文件行数预算 */
internal data class OfficialNeteaseUrlAttempt(
    val fullSuccess: SongUrlResult.Success? = null,
    val previewFallback: SongUrlResult.Success? = null,
    val lastFailureReason: NeteasePlaybackResponseParser.FailureReason? = null,
    val requiresLogin: Boolean = false
)

internal fun PlayerManager.fetchOfficialNeteaseUrlAttempt(
    song: SongItem,
    effectiveQuality: String
): OfficialNeteaseUrlAttempt {
    val qualityCandidates = buildNeteaseQualityCandidates(effectiveQuality)
    var previewFallback: SongUrlResult.Success? = null
    var lastFailureReason: NeteasePlaybackResponseParser.FailureReason? = null
    var requiresLogin = false

    for ((index, quality) in qualityCandidates.withIndex()) {
        val resp = runCatching {
            neteaseClient.getSongDownloadUrl(song.id, level = quality)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            NPLogger.w("NERI-PlayerManager", "Official NetEase URL failed id=${song.id}, level=$quality: ${error.message}")
            lastFailureReason = NeteasePlaybackResponseParser.FailureReason.UNKNOWN
            continue
        }
        NPLogger.d("NERI-PlayerManager", "id=${song.id}, level=$quality, resp=$resp")

        when (val parsed = NeteasePlaybackResponseParser.parsePlayback(resp, song.durationMs)) {
            is NeteasePlaybackResponseParser.PlaybackResult.RequiresLogin -> {
                requiresLogin = true
                if (shouldRetryNeteaseWithLowerQualityAfterLogin(
                        qualityIndex = index,
                        lastQualityIndex = qualityCandidates.lastIndex
                    )
                ) {
                    NPLogger.w(
                        "NERI-PlayerManager",
                        "当前音质需要登录，继续尝试更低音质: id=${song.id}, level=$quality"
                    )
                    continue
                }
                break
            }

            is NeteasePlaybackResponseParser.PlaybackResult.Success -> {
                val success = buildNeteaseSuccessResult(
                    parsed = parsed,
                    resolvedQualityKey = quality,
                    fallbackDurationMs = song.durationMs,
                    getLocalizedString = { getLocalizedString(it) }
                ).let { result ->
                    if (parsed.notice == NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP) {
                        result.copy(
                            cacheKeyOverride = buildNeteasePreviewCacheKey(
                                songId = song.id,
                                preferredQuality = quality
                            )
                        )
                    } else {
                        result
                    }
                }
                if (parsed.notice != NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP) {
                    if (quality != effectiveQuality) {
                        NPLogger.w(
                            "NERI-PlayerManager",
                            "当前音质不可用，已自动降级: id=${song.id}, preferred=$effectiveQuality, resolved=$quality"
                        )
                    }
                    return OfficialNeteaseUrlAttempt(fullSuccess = success)
                }

                previewFallback = success
                if (index < qualityCandidates.lastIndex) {
                    NPLogger.w(
                        "NERI-PlayerManager",
                        "当前音质仅返回试听片段，继续尝试更低音质: id=${song.id}, level=$quality"
                    )
                    continue
                }
            }

            is NeteasePlaybackResponseParser.PlaybackResult.Failure -> {
                lastFailureReason = parsed.reason
                if (index < qualityCandidates.lastIndex &&
                    shouldRetryNeteaseWithLowerQuality(parsed.reason)
                ) {
                    NPLogger.w(
                        "NERI-PlayerManager",
                        "当前音质不可播放，继续尝试更低音质: id=${song.id}, level=$quality, reason=${parsed.reason}"
                    )
                    continue
                }
                break
            }
        }
    }

    return OfficialNeteaseUrlAttempt(
        previewFallback = previewFallback,
        lastFailureReason = lastFailureReason,
        requiresLogin = requiresLogin
    )
}

internal suspend fun PlayerManager.tryResolveGdMusicNeteaseUrl(
    song: SongItem,
    effectiveQuality: String,
    isPrefetch: Boolean = false
): SongUrlResult.Success? {
    val client = gdMusicClient ?: return null
    val resolved = client.resolveNeteaseUrl(
        songId = song.id,
        title = song.name,
        artist = song.artist,
        durationMs = song.durationMs,
        preferredQuality = effectiveQuality,
        allowJooxFallback = true,
        isPrefetch = isPrefetch
    ) ?: return null

    val lowerUrl = resolved.url.lowercase()
    val mimeType = when {
        ".flac" in lowerUrl || resolved.qualityLevel == "lossless" -> "audio/flac"
        ".m4a" in lowerUrl || ".mp4" in lowerUrl -> "audio/mp4"
        ".ogg" in lowerUrl -> "audio/ogg"
        else -> "audio/mpeg"
    }
    NPLogger.d(
        "NERI-PlayerManager",
        "GD Music解析成功: id=${song.id}, source=${resolved.source}, br=${resolved.actualBitrate}, level=${resolved.qualityLevel}"
    )
    return SongUrlResult.Success(
        url = resolved.url,
        mimeType = mimeType,
        expectedContentLength = resolved.sizeBytes.takeIf { it > 0L },
        audioInfo = PlaybackAudioInfo(
            source = PlaybackAudioSource.NETEASE,
            qualityKey = resolved.qualityLevel,
            qualityLabel = qualityLabelForNetease(resolved.qualityLevel) { getLocalizedString(it) },
            qualityOptions = buildNeteaseQualityOptions { getLocalizedString(it) },
            codecLabel = deriveCodecLabel(mimeType),
            mimeType = mimeType,
            bitrateKbps = resolved.actualBitrate.takeIf { it > 0 }
        )
    )
}

internal suspend fun PlayerManager.resolveNeteaseListenTogetherGdMusicFallback(
    song: SongItem
): String? {
    if (!neteasePlaybackSourcePreference.usesGdMusic()) return null
    val preferredQuality = effectiveNeteaseQuality()
    val resolved = runCatching {
        gdMusicClient?.resolveNeteaseUrl(
            songId = song.id,
            title = song.name,
            artist = song.artist,
            durationMs = song.durationMs,
            preferredQuality = preferredQuality,
            allowJooxFallback = true,
            isPrefetch = false
        )
    }.getOrNull() ?: return null
    if (!isDirectStreamUrl(resolved.url)) return null
    return decorateListenTogetherStreamUrl(
        streamUrl = resolved.url,
        source = PlaybackAudioSource.NETEASE,
        qualityKey = resolved.qualityLevel
    )
}
