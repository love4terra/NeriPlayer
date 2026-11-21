package moe.ouom.neriplayer.ui.screen.tab.home

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.theme.NeriDesign

/** LargeTopAppBar 自带的标题水平内边距，首页大标题据此补齐到卡片左边缘 */
internal val TopAppBarTitleInset = 16.dp

/** 分区标题图标与网格卡片左边缘对齐 */
@Composable
internal fun SectionHeader(
    icon: ImageVector,
    title: String,
    onRefresh: (() -> Unit)? = null,
    refreshing: Boolean = false,
    refreshEnabled: Boolean = true
) {
    if (NeriDesign.isClean) {
        // clean style carries hierarchy by weight alone; the accent stays reserved for actions
        if (onRefresh == null) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 18.dp, bottom = 10.dp)
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 10.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                SectionRefreshButton(
                    onRefresh = onRefresh,
                    refreshing = refreshing,
                    enabled = refreshEnabled
                )
            }
        }
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f)
        )
        if (onRefresh != null) {
            SectionRefreshButton(
                onRefresh = onRefresh,
                refreshing = refreshing,
                enabled = refreshEnabled
            )
        }
    }
}

@Composable
internal fun SectionRefreshButton(
    onRefresh: () -> Unit,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    HapticIconButton(
        onClick = onRefresh,
        enabled = enabled && !refreshing,
        modifier = modifier.size(32.dp)
    ) {
        if (refreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = stringResource(CoreCommonR.string.recommend_refresh),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
