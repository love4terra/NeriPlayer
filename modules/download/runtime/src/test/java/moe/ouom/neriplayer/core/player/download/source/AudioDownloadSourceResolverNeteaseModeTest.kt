package moe.ouom.neriplayer.core.player.download.source

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.host.DownloadCredentials
import moe.ouom.neriplayer.core.download.host.DownloadEnvironment
import moe.ouom.neriplayer.core.download.host.DownloadHostBindings
import moe.ouom.neriplayer.core.download.host.DownloadHostRegistry
import moe.ouom.neriplayer.core.download.host.DownloadHosts
import moe.ouom.neriplayer.core.download.host.DownloadLyricServices
import moe.ouom.neriplayer.core.download.host.DownloadSourceServices
import moe.ouom.neriplayer.core.download.host.DownloadedPlaybackHost
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.netease.playback.NeteaseAudioSourcePreference
import moe.ouom.neriplayer.platform.gdmusic.GdMusicClient
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class AudioDownloadSourceResolverNeteaseModeTest {
    private val registry = DownloadHosts::class.java.getDeclaredField("registry")
        .apply { isAccessible = true }.get(null)
    private val installed = DownloadHostRegistry::class.java.getDeclaredField("installed")
        .apply { isAccessible = true }
    private var previousHosts: DownloadHostBindings? = null

    private val sources = mock(DownloadSourceServices::class.java)
    private val neteaseClient = mock(NeteaseClient::class.java)
    private val song = SongItem(
        id = 186016L,
        name = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        albumId = 100L,
        durationMs = 269_000L,
        coverUrl = null
    )

    @Before
    fun installHosts() {
        previousHosts = installed.get(registry) as DownloadHostBindings?
        `when`(sources.neteaseClient).thenReturn(neteaseClient)
        DownloadHosts.install(
            DownloadHostBindings(
                mock(DownloadEnvironment::class.java),
                sources,
                mock(DownloadLyricServices::class.java),
                mock(DownloadCredentials::class.java),
                mock(DownloadedPlaybackHost::class.java)
            )
        )
    }

    @After
    fun restoreHosts() {
        installed.set(registry, previousHosts)
    }

    private fun stubGdMusic(url: String?): GdMusicClient {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val body = if (url != null) {
                """{"url":"$url","br":320,"size":10240000}"""
            } else {
                """{"url":""}"""
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return GdMusicClient(http)
    }

    @Test
    fun `gd_music_first resolves gd url before hitting official netease`(): Unit = runBlocking {
        `when`(sources.neteaseDownloadSource()).thenReturn(NeteaseAudioSourcePreference.GD_MUSIC_FIRST)
        `when`(sources.gdMusicClient).thenReturn(stubGdMusic("https://m701.music.126.net/gd-first.mp3"))

        val resolved = AudioDownloadSourceResolver.resolveNetease(song, "exhigh")
        assertNotNull(resolved)
        assertEquals("https://m701.music.126.net/gd-first.mp3", resolved!!.url)
        verify(neteaseClient, never()).getSongDownloadUrl(186016L, "exhigh")
    }

    @Test
    fun `official_first uses gd fallback when official returns preview clip`(): Unit = runBlocking {
        `when`(sources.neteaseDownloadSource()).thenReturn(NeteaseAudioSourcePreference.OFFICIAL_FIRST)
        `when`(sources.gdMusicClient).thenReturn(stubGdMusic("https://m701.music.126.net/gd-fallback.mp3"))
        val previewJson = """{"code":200,"data":[{"id":186016,"url":"http://m7.music.126.net/preview.mp3","type":"mp3","size":400000,"freeTrialInfo":{"start":0,"end":30}}]}"""
        `when`(neteaseClient.getSongDownloadUrl(186016L, "exhigh")).thenReturn(previewJson)
        `when`(neteaseClient.getSongUrl(186016L, 320000)).thenReturn("""{"code":200,"data":[]}""")
        `when`(neteaseClient.getSongDownloadUrl(186016L, "standard")).thenReturn(previewJson)
        `when`(neteaseClient.getSongUrl(186016L, 128000)).thenReturn("""{"code":200,"data":[]}""")

        val resolved = AudioDownloadSourceResolver.resolveNetease(song, "exhigh")
        assertNotNull(resolved)
        assertEquals("https://m701.music.126.net/gd-fallback.mp3", resolved!!.url)
    }

    @Test
    fun `gd_music_only never calls official netease client when gd returns nothing`(): Unit = runBlocking {
        `when`(sources.neteaseDownloadSource()).thenReturn(NeteaseAudioSourcePreference.GD_MUSIC_ONLY)
        `when`(sources.gdMusicClient).thenReturn(stubGdMusic(null))

        val resolved = AudioDownloadSourceResolver.resolveNetease(song, "exhigh")
        assertNull(resolved)
        verify(neteaseClient, never()).getSongDownloadUrl(186016L, "exhigh")
    }

    @Test
    fun `official_only ignores gd music client cleanly`(): Unit = runBlocking {
        `when`(sources.neteaseDownloadSource()).thenReturn(NeteaseAudioSourcePreference.OFFICIAL_ONLY)
        `when`(sources.gdMusicClient).thenReturn(stubGdMusic("https://m701.music.126.net/should-not-use.mp3"))
        `when`(neteaseClient.getSongDownloadUrl(186016L, "exhigh")).thenReturn(
            """{"code":200,"data":[{"id":186016,"url":"http://m801.music.126.net/official.mp3","type":"mp3","size":9000000}]}"""
        )

        val resolved = AudioDownloadSourceResolver.resolveNetease(song, "exhigh")
        assertNotNull(resolved)
        assertEquals("https://m801.music.126.net/official.mp3", resolved!!.url)
    }
}
