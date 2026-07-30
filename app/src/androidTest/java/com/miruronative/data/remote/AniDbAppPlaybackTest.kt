package com.miruronative.data.remote

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.cronet.CronetUtil
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.miruronative.data.model.Media
import com.miruronative.data.model.MediaTitle
import com.miruronative.data.model.StreamItem
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.chromium.net.CronetEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Live device coverage for AniDB App resolution and the production Cronet playback transport. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class AniDbAppPlaybackTest {
    private val provider = AniDbAppProvider(
        client = OkHttpClient.Builder()
            .followRedirects(true)
            .callTimeout(Duration.ofSeconds(30))
            .build(),
        json = Json { ignoreUnknownKeys = true },
    )

    @Test
    fun deathNoteSubAndDubResolveAndBufferThroughCronet() = verifyFixture(DEATH_NOTE)

    @Test
    fun jujutsuKaisenSubResolvesAndBuffersThroughCronet() = verifyFixture(JUJUTSU_KAISEN)

    @Test
    fun frierenSubResolvesAndBuffersThroughCronet() = verifyFixture(FRIEREN)

    private fun verifyFixture(fixture: Fixture) {
        val availability = provider.episodeAvailability(fixture.media, fixture.expectedEpisodes)
        assertTrue("${fixture.name}: episode 1 missing from SUB catalog", 1 in availability.sub)
        val sub = provider.sources(fixture.media, "sub", 1).streams.single()
        assertTrue("${fixture.name}: unexpected HLS host ${sub.url}", Uri.parse(sub.url).host == "hls.anidb.app")
        bufferWithCronet(sub)

        if (fixture.verifyDub) {
            assertTrue("${fixture.name}: episode 1 missing from DUB catalog", 1 in availability.dub)
            val dub = provider.sources(fixture.media, "dub", 1).streams.single()
            assertTrue("${fixture.name}: unexpected dub HLS host ${dub.url}", Uri.parse(dub.url).host == "hls.anidb.app")
            assertTrue("${fixture.name}: SUB and DUB resolved to the same encode", dub.url != sub.url)
            bufferWithCronet(dub)
        }
    }

    private fun bufferWithCronet(stream: StreamItem) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val completed = CountDownLatch(1)
        var player: ExoPlayer? = null
        var engine: CronetEngine? = null
        var error: String? = null

        instrumentation.runOnMainSync {
            val cronet = requireNotNull(CronetUtil.buildCronetEngine(context, null, true)) {
                "Cronet is unavailable on this device"
            }
            engine = cronet
            val referer = requireNotNull(stream.referer)
            val refererUri = Uri.parse(referer)
            val origin = "${refererUri.scheme}://${refererUri.host}"
            val http = CronetDataSource.Factory(cronet, Runnable::run)
                .setUserAgent(PLAYBACK_USER_AGENT)
                .setDefaultRequestProperties(mapOf("Referer" to referer, "Origin" to origin))
            val exo = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(http))
                .build()
            player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) completed.countDown()
                }

                override fun onPlayerError(playbackError: PlaybackException) {
                    error = "${playbackError.errorCodeName}: ${playbackError.message}"
                    completed.countDown()
                }
            })
            exo.setMediaItem(MediaItem.fromUri(stream.url))
            exo.prepare()
        }

        val reachedReady = completed.await(45, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { player?.release() }
        runCatching { engine?.shutdown() }
        check(reachedReady && error == null) {
            "Cronet playback did not reach READY; error=$error urlHost=${Uri.parse(stream.url).host}"
        }
    }

    private data class Fixture(
        val name: String,
        val media: Media,
        val expectedEpisodes: Int,
        val verifyDub: Boolean = false,
    )

    companion object {
        private const val PLAYBACK_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/137 Mobile Safari/537.36"

        private val DEATH_NOTE = Fixture(
            name = "Death Note",
            media = Media(
                id = 1_535,
                idMal = 1_535,
                title = MediaTitle(english = "Death Note", romaji = "Death Note"),
                episodes = 37,
                status = "FINISHED",
            ),
            expectedEpisodes = 37,
            verifyDub = true,
        )
        private val JUJUTSU_KAISEN = Fixture(
            name = "Jujutsu Kaisen",
            media = Media(
                id = 113_415,
                idMal = 40_748,
                title = MediaTitle(english = "Jujutsu Kaisen", romaji = "Jujutsu Kaisen"),
                episodes = 24,
                status = "FINISHED",
            ),
            expectedEpisodes = 24,
        )
        private val FRIEREN = Fixture(
            name = "Frieren: Beyond Journey's End",
            media = Media(
                id = 154_587,
                idMal = 52_991,
                title = MediaTitle(
                    english = "Frieren: Beyond Journey's End",
                    romaji = "Sousou no Frieren",
                ),
                episodes = 28,
                status = "FINISHED",
            ),
            expectedEpisodes = 28,
        )
    }
}
