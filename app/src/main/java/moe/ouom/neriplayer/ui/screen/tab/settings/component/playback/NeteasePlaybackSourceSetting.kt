package moe.ouom.neriplayer.ui.screen.tab.settings.component.playback

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.netease.playback.NeteaseAudioSourcePreference
import moe.ouom.neriplayer.data.model.netease.playback.NeteaseDownloadSourcePreference
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.ksp.annotations.AutoSettingSpec
import moe.ouom.neriplayer.ui.screen.tab.settings.component.AutoSettingSpecListItem
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

private data class NeteaseSourceChoiceOption<T>(
    val value: T,
    val title: String,
    val subtitle: String
)

@Composable
internal fun NeteasePlaybackSourceSetting(
    repository: SettingsRepository,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val source by repository.neteasePlaybackSourceFlow.collectAsState(
        initial = NeteaseAudioSourcePreference.GD_MUSIC_FIRST
    )
    val options = listOf(
        audioSourceChoiceOption(NeteaseAudioSourcePreference.GD_MUSIC_FIRST),
        audioSourceChoiceOption(NeteaseAudioSourcePreference.OFFICIAL_FIRST),
        audioSourceChoiceOption(NeteaseAudioSourcePreference.GD_MUSIC_ONLY),
        audioSourceChoiceOption(NeteaseAudioSourcePreference.OFFICIAL_ONLY)
    )
    NeteaseSourceChoiceSettingItem(
        setting = AutoSettingsSchema.playback.neteasePlaybackSource,
        dialogTitle = stringResource(CoreCommonR.string.settings_netease_playback_source),
        currentValue = source,
        options = options,
        onSelect = { selected -> scope.launch { repository.setNeteasePlaybackSource(selected) } },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
}

@Composable
internal fun NeteaseDownloadSourceSetting(
    repository: SettingsRepository,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val source by repository.neteaseDownloadSourceFlow.collectAsState(
        initial = NeteaseDownloadSourcePreference.FOLLOW_PLAYBACK
    )
    val options = listOf(
        NeteaseSourceChoiceOption(
            value = NeteaseDownloadSourcePreference.FOLLOW_PLAYBACK,
            title = stringResource(CoreCommonR.string.settings_netease_download_source_follow_playback),
            subtitle = stringResource(CoreCommonR.string.settings_netease_download_source_follow_playback_desc)
        ),
        downloadSourceChoiceOption(
            NeteaseDownloadSourcePreference.GD_MUSIC_FIRST,
            NeteaseAudioSourcePreference.GD_MUSIC_FIRST
        ),
        downloadSourceChoiceOption(
            NeteaseDownloadSourcePreference.OFFICIAL_FIRST,
            NeteaseAudioSourcePreference.OFFICIAL_FIRST
        ),
        downloadSourceChoiceOption(
            NeteaseDownloadSourcePreference.GD_MUSIC_ONLY,
            NeteaseAudioSourcePreference.GD_MUSIC_ONLY
        ),
        downloadSourceChoiceOption(
            NeteaseDownloadSourcePreference.OFFICIAL_ONLY,
            NeteaseAudioSourcePreference.OFFICIAL_ONLY
        )
    )
    NeteaseSourceChoiceSettingItem(
        setting = AutoSettingsSchema.download.downloadNeteaseSource,
        dialogTitle = stringResource(CoreCommonR.string.settings_netease_download_source),
        currentValue = source,
        options = options,
        onSelect = { selected -> scope.launch { repository.setNeteaseDownloadSource(selected) } },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
}

@Composable
private fun <T> NeteaseSourceChoiceSettingItem(
    setting: AutoSettingSpec<String>,
    dialogTitle: String,
    currentValue: T,
    options: List<NeteaseSourceChoiceOption<T>>,
    onSelect: (T) -> Unit,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    var showDialog by remember { mutableStateOf(false) }
    val selectedOption = options.firstOrNull { it.value == currentValue } ?: options.first()

    AutoSettingSpecListItem(
        setting = setting,
        supportingContent = { Text(selectedOption.title) },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished,
        onClick = { showDialog = true }
    )

    if (!showDialog) return

    MiuixSettingsDialog(
        onDismissRequest = { showDialog = false },
        title = { Text(dialogTitle) },
        text = {
            Column {
                options.forEach { option ->
                    MiuixSettingsChoiceRow(
                        title = option.title,
                        subtitle = option.subtitle,
                        selected = option.value == currentValue,
                        onClick = {
                            onSelect(option.value)
                            showDialog = false
                        }
                    )
                }
            }
        },
        confirmButton = {
            MiuixSettingsTextButton(
                onClick = { showDialog = false },
                text = { Text(stringResource(CoreCommonR.string.action_close)) }
            )
        }
    )
}

@Composable
private fun audioSourceChoiceOption(
    source: NeteaseAudioSourcePreference
): NeteaseSourceChoiceOption<NeteaseAudioSourcePreference> =
    NeteaseSourceChoiceOption(
        value = source,
        title = neteaseSourceLabel(source),
        subtitle = neteaseSourceDescription(source)
    )

@Composable
private fun downloadSourceChoiceOption(
    downloadPref: NeteaseDownloadSourcePreference,
    audioPref: NeteaseAudioSourcePreference
): NeteaseSourceChoiceOption<NeteaseDownloadSourcePreference> =
    NeteaseSourceChoiceOption(
        value = downloadPref,
        title = neteaseSourceLabel(audioPref),
        subtitle = neteaseSourceDescription(audioPref)
    )

@Composable
private fun neteaseSourceLabel(source: NeteaseAudioSourcePreference): String = stringResource(
    when (source) {
        NeteaseAudioSourcePreference.GD_MUSIC_FIRST -> CoreCommonR.string.settings_netease_playback_source_gd_first
        NeteaseAudioSourcePreference.OFFICIAL_FIRST -> CoreCommonR.string.settings_netease_playback_source_official_first
        NeteaseAudioSourcePreference.GD_MUSIC_ONLY -> CoreCommonR.string.settings_netease_playback_source_gd_only
        NeteaseAudioSourcePreference.OFFICIAL_ONLY -> CoreCommonR.string.settings_netease_playback_source_official_only
    }
)

@Composable
private fun neteaseSourceDescription(source: NeteaseAudioSourcePreference): String = stringResource(
    when (source) {
        NeteaseAudioSourcePreference.GD_MUSIC_FIRST -> CoreCommonR.string.settings_netease_playback_source_gd_first_desc
        NeteaseAudioSourcePreference.OFFICIAL_FIRST -> CoreCommonR.string.settings_netease_playback_source_official_first_desc
        NeteaseAudioSourcePreference.GD_MUSIC_ONLY -> CoreCommonR.string.settings_netease_playback_source_gd_only_desc
        NeteaseAudioSourcePreference.OFFICIAL_ONLY -> CoreCommonR.string.settings_netease_playback_source_official_only_desc
    }
)
