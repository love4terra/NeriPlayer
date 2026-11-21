package moe.ouom.neriplayer.ui.viewmodel.tab

import android.content.Context
import android.content.res.Resources
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.i18n.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class HomeViewModelHelpersTest {

    private val resources: Resources = mock(Resources::class.java) { invocation ->
        if (invocation.method.name == "getString") invocation.arguments.joinToString("|") else null
    }
    private val context: Context = mock(Context::class.java) { invocation ->
        if (invocation.method.name == "getResources") resources else null
    }

    @Test
    fun `recommend falls back only for redirect and login codes`() {
        assertTrue(shouldFallbackRecommend(301))
        assertTrue(shouldFallbackRecommend(50000005))
        assertFalse(shouldFallbackRecommend(200))
    }

    @Test
    fun `retry returns the first successful attempt`() = runTest {
        var attempts = 0

        val result = fetchWithRetry("section", maxAttempts = 3) {
            attempts++
            if (attempts < 2) throw IOException("flaky")
            listOf("a", "b")
        }

        assertEquals(RetryLoadResult.Success(listOf("a", "b")), result)
        assertEquals(2, attempts)
    }

    @Test
    fun `retry reports the last failure after all attempts`() = runTest {
        var attempts = 0

        val result = fetchWithRetry<String>("section", maxAttempts = 2) {
            attempts++
            throw IOException("failure $attempts")
        }

        assertEquals(2, attempts)
        assertEquals("failure 2", (result as RetryLoadResult.Failure).throwable.message)
    }

    @Test
    fun `retry uses the default attempt budget and succeeds immediately`() = runTest {
        var attempts = 0

        val result = fetchWithRetry("section") {
            attempts++
            listOf(1)
        }

        assertEquals(RetryLoadResult.Success(listOf(1)), result)
        assertEquals(1, attempts)
    }

    @Test
    fun `retry rethrows cancellation and rejects an empty budget`() = runTest {
        var attempts = 0
        val cancellation = runCatching {
            fetchWithRetry<String>("section", maxAttempts = 3) {
                attempts++
                throw CancellationException("cancelled")
            }
        }.exceptionOrNull()
        val emptyBudget = runCatching {
            fetchWithRetry<String>("section", maxAttempts = 0) { emptyList() }
        }.exceptionOrNull()

        assertTrue(cancellation is CancellationException)
        assertEquals(1, attempts)
        assertTrue(emptyBudget is IllegalArgumentException)
    }

    @Test
    fun `home errors map network api and unknown failures`() {
        assertEquals(
            "${CoreCommonR.string.home_error_network}|offline",
            buildHomeErrorMessage(IOException("offline"), context)
        )
        assertEquals(
            "${CoreCommonR.string.home_error_network}|IOException",
            buildHomeErrorMessage(IOException(), context)
        )
        assertEquals(
            "${CoreCommonR.string.home_login_required}",
            buildHomeErrorMessage(ApiCodeException(50000005), context)
        )
        assertEquals(
            "${CoreCommonR.string.error_api_code}|405",
            buildHomeErrorMessage(ApiCodeException(405), context)
        )
        assertEquals(
            "${CoreCommonR.string.home_error_unknown}|IllegalStateException",
            buildHomeErrorMessage(IllegalStateException(), context)
        )
        assertEquals(
            "${CoreCommonR.string.home_error_unknown}|bad state",
            buildHomeErrorMessage(IllegalStateException("bad state"), context)
        )
    }

    @Test
    fun `daily recommend only passes afresh true on explicit manual refresh`() {
        assertFalse(shouldRequestDailyRecommendAfresh(NeteaseHomeSongSource.DAILY_RECOMMEND, false))
        assertTrue(shouldRequestDailyRecommendAfresh(NeteaseHomeSongSource.DAILY_RECOMMEND, true))
        assertFalse(shouldRequestDailyRecommendAfresh(NeteaseHomeSongSource.PERSONAL_RADAR, true))
        assertFalse(shouldRequestDailyRecommendAfresh(NeteaseHomeSongSource.PRIVATE_FM, true))
    }

    @Test
    fun `single section refresh updates only targeted source and preserves existing items on failure`() {
        val songA = SongItem(id = 1L, name = "A", artist = "Artist", album = "Album", albumId = 1L, durationMs = 1000L, coverUrl = null)
        val songB = SongItem(id = 2L, name = "B", artist = "Artist", album = "Album", albumId = 2L, durationMs = 1000L, coverUrl = null)
        val initial = listOf(
            HomeNeteaseSongSectionState(
                source = NeteaseHomeSongSource.TOP_SOARING,
                section = HomeSectionState(items = listOf(songA))
            ),
            HomeNeteaseSongSectionState(
                source = NeteaseHomeSongSource.TOP_HOT,
                section = HomeSectionState(items = listOf(songB))
            )
        )

        val loading = markSongSectionLoading(initial, NeteaseHomeSongSource.TOP_SOARING)
        assertTrue(loading[0].section.loading)
        assertEquals(listOf(songA), loading[0].section.items)
        assertFalse(loading[1].section.loading)

        val failedRefresh = mergeSingleSongSectionResult(
            loading,
            HomeNeteaseSongSectionState(
                source = NeteaseHomeSongSource.TOP_SOARING,
                section = HomeSectionState(error = UiText.Plain("timeout"))
            )
        )
        assertFalse(failedRefresh[0].section.loading)
        assertEquals(listOf(songA), failedRefresh[0].section.items)
        assertEquals(UiText.Plain("timeout"), failedRefresh[0].section.error)
        assertEquals(listOf(songB), failedRefresh[1].section.items)

        val playlistInitial = listOf(
            HomeNeteasePlaylistSectionState(
                source = NeteaseHomePlaylistSource.PERSONALIZED,
                section = HomeSectionState(items = listOf(PlaylistSummary(10L, "Old", "", 100L, 5)))
            )
        )
        val playlistLoading = markPlaylistSectionLoading(playlistInitial, NeteaseHomePlaylistSource.PERSONALIZED)
        val playlistSuccess = mergeSinglePlaylistSectionResult(
            playlistLoading,
            HomeNeteasePlaylistSectionState(
                source = NeteaseHomePlaylistSource.PERSONALIZED,
                section = HomeSectionState(items = listOf(PlaylistSummary(20L, "Fresh", "", 200L, 8)))
            )
        )
        assertFalse(playlistSuccess.single().section.loading)
        assertEquals(listOf(20L), playlistSuccess.single().section.items.map { it.id })
    }
}
