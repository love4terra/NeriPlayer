package moe.ouom.neriplayer.ui.screen.tab.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicHotVideo
import moe.ouom.neriplayer.platform.bilibili.api.image.buildBiliThumbnailUrl
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliExploreSection
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliExploreTag
import moe.ouom.neriplayer.ui.viewmodel.tab.biliVideoStableKey
import moe.ouom.neriplayer.util.format.formatDurationSec
import moe.ouom.neriplayer.util.format.formatPlayCount
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

internal data class BiliBrowseState(
    val selectedSection: String = BiliExploreSection.MUSIC.key,
    val selectedTag: String,
    val videos: List<BiliMusicHotVideo>,
    val loading: Boolean,
    val loadingMore: Boolean,
    val refreshing: Boolean,
    val hasMore: Boolean,
    val personalized: Boolean,
    val loginRequired: Boolean = false,
    val error: String?
)

internal data class BiliExploreTagOption(
    val key: String,
    val label: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BiliExploreContent(
    browse: BiliBrowseState,
    tags: List<BiliExploreTagOption>,
    offlineMode: Boolean,
    isTabletLayout: Boolean,
    miniPlayerHeight: Dp,
    gridState: LazyGridState,
    tagChipSelectedAlpha: Float,
    tagChipUnselectedAlpha: Float,
    tagChipBorderAlpha: Float,
    onSectionSelected: (String) -> Unit = {},
    onTagSelected: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onVideoClick: (Int) -> Unit
) {
    val gridMinCellSize = if (isTabletLayout) 180.dp else 156.dp
    val gridSpacing = if (isTabletLayout) 16.dp else 12.dp
    val tagListState = rememberLazyListState()
    val showMusicSubzones = browse.selectedSection == BiliExploreSection.MUSIC.key
    val primarySections = listOf(
        BiliExploreTagOption(
            key = BiliExploreSection.DYNAMIC.key,
            label = stringResource(AppR.string.bili_section_dynamic)
        ),
        BiliExploreTagOption(
            key = BiliExploreSection.MUSIC.key,
            label = stringResource(AppR.string.bili_section_music)
        )
    )
    val showTagStartFade by remember { derivedStateOf { tagListState.canScrollBackward } }
    val showTagEndFade by remember { derivedStateOf { tagListState.canScrollForward } }
    val shouldLoadMore by remember(
        gridState,
        browse.videos.size,
        browse.hasMore,
        browse.loading,
        browse.loadingMore,
        browse.refreshing
    ) {
        derivedStateOf {
            if (!browse.hasMore || browse.loading || browse.loadingMore || browse.refreshing || browse.videos.isEmpty()) {
                false
            } else {
                val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
                lastVisible >= browse.videos.size - 4
            }
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            onLoadMore()
        }
    }

    PullToRefreshBox(
        isRefreshing = browse.refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(gridMinCellSize),
            verticalArrangement = Arrangement.spacedBy(gridSpacing),
            horizontalArrangement = Arrangement.spacedBy(gridSpacing),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 16.dp + miniPlayerHeight
            ),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            primarySections.forEach { section ->
                                val selected = browse.selectedSection == section.key
                                ExploreTagChip(
                                    label = section.label,
                                    selected = selected,
                                    onClick = { if (!selected) onSectionSelected(section.key) },
                                    selectedAlpha = tagChipSelectedAlpha,
                                    unselectedAlpha = tagChipUnselectedAlpha,
                                    borderAlpha = tagChipBorderAlpha
                                )
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        HapticIconButton(
                            onClick = onRefresh,
                            enabled = !browse.loading && !browse.refreshing
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(AppR.string.explore_bili_refresh)
                            )
                        }
                    }
                    if (showMusicSubzones) {
                        Spacer(Modifier.height(8.dp))
                        LazyRow(
                            state = tagListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .exploreHorizontalEdgeFade(
                                    showStartFade = showTagStartFade,
                                    showEndFade = showTagEndFade
                                ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(tags) { _, tag ->
                                val selected = browse.selectedTag == tag.key
                                ExploreTagChip(
                                    label = tag.label,
                                    selected = selected,
                                    onClick = { if (!selected) onTagSelected(tag.key) },
                                    selectedAlpha = tagChipSelectedAlpha,
                                    unselectedAlpha = tagChipUnselectedAlpha,
                                    borderAlpha = tagChipBorderAlpha
                                )
                            }
                        }
                    }
                    when {
                        !showMusicSubzones && !browse.loginRequired -> {
                            Text(
                                text = stringResource(AppR.string.explore_bili_dynamic_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp, start = 2.dp)
                            )
                        }
                        showMusicSubzones && browse.selectedTag == BiliExploreTag.RECOMMEND.key -> {
                            Text(
                                text = stringResource(
                                    if (browse.personalized) {
                                        AppR.string.explore_bili_personalized_hint
                                    } else {
                                        AppR.string.explore_bili_default_hint
                                    }
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp, start = 2.dp)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (browse.loading && !browse.refreshing) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            if (browse.videos.isNotEmpty()) {
                itemsIndexed(
                    items = browse.videos,
                    key = { index, video ->
                        biliVideoStableKey(video).ifBlank { "bili-explore-$index" }
                    }
                ) { index, video ->
                    BiliExploreVideoCard(
                        video = video,
                        offlineMode = offlineMode,
                        onClick = { onVideoClick(index) }
                    )
                }
                if (browse.loadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        }
                    }
                }
            } else if (browse.loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (browse.loginRequired) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(AppR.string.explore_bili_dynamic_login_required),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HapticTextButton(onClick = onRefresh) {
                            Text(text = stringResource(CoreCommonR.string.recommend_refresh))
                        }
                    }
                }
            } else if (browse.error != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = browse.error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        HapticTextButton(onClick = onRefresh) {
                            Text(text = stringResource(CoreCommonR.string.recommend_refresh))
                        }
                    }
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(CoreCommonR.string.home_bili_music_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HapticTextButton(onClick = onRefresh) {
                            Text(text = stringResource(CoreCommonR.string.recommend_refresh))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BiliExploreVideoCard(
    video: BiliMusicHotVideo,
    offlineMode: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val thumbnailUrl = remember(video.coverUrl) {
        buildBiliThumbnailUrl(imageUrl = video.coverUrl, width = 384, height = 216)
    }
    val coverRequest = remember(context, thumbnailUrl, offlineMode) {
        offlineCachedImageRequest(
            context = context,
            data = thumbnailUrl,
            sizePx = 384,
            allowHardware = false,
            offlineMode = offlineMode
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(neriRoundedShape(if (NeriDesign.isClean) 14.dp else 12.dp))
            .clickable(enabled = video.isPlayable, onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(neriRoundedShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = coverRequest,
                    contentDescription = video.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (video.durationSec > 0) {
                Text(
                    text = formatDurationSec(video.durationSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                            neriRoundedShape(4.dp)
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = video.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = video.uploader,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val meta = listOfNotNull(
            video.tname.takeIf { it.isNotBlank() },
            video.viewCount.takeIf { it > 0L }?.let { formatPlayCount(context, it) }
        ).joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
