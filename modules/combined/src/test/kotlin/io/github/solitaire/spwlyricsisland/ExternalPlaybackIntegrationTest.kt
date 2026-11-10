package io.github.solitaire.spwlyricsisland

import dev.gaboron.spw.api.ExternalPlayback
import io.github.solitaire.spwlyrics.integration.ExternalPlaybackClient as LyricsClient
import io.github.solitaire.spwisland.host.ExternalPlaybackClient as IslandClient
import io.github.solitaire.spwlyrics.domain.TrackQuery
import java.util.UUID
import kotlin.test.*

class ExternalPlaybackIntegrationTest {
    @Test fun sharesLyricsAndRejectsOldIdentityAcrossAToBToA() {
        ExternalPlayback("test." + UUID.randomUUID()).use { source ->
            val lyrics = LyricsClient()
            val island = IslandClient()
            source.publish(track("A"), true, false, 1_000, 80, 1.0)
            source.setSourceLyrics(ExternalPlayback.Lyrics.lrc("[00:01.00]original", 100))
            val first = assertNotNull(lyrics.snapshot())
            assertEquals(first.identity, island.snapshot()?.identity)
            assertEquals("[00:01.00]original", lyrics.sourceLyrics(first)?.content)
            assertTrue(lyrics.submit(first, "lrc", "[00:01.00]selected", "[00:01.00]selected", 250))
            assertEquals("[00:01.00]selected", island.lyrics(assertNotNull(island.snapshot()))?.spl)
            assertTrue(lyrics.clear(first))
            assertEquals("[00:01.00]original", island.lyrics(assertNotNull(island.snapshot()))?.spl)
            source.publish(track("B"), true, false, 0, 80, 1.0)
            source.publish(track("A"), true, false, 0, 80, 1.0)
            val latest = assertNotNull(lyrics.snapshot())
            assertNotEquals(first.identity, latest.identity)
            assertFalse(lyrics.submit(first, "lrc", "stale", "stale", 0))
            assertFalse(island.control(assertNotNull(island.snapshot()).copy(trackRevision = first.trackRevision), "play"))
            assertNull(lyrics.sourceLyrics(latest))
        }
    }

    @Test fun sourceHandoffAndLeaseExpirationRestoreDiscovery() {
        ExternalPlayback("test.first." + UUID.randomUUID()).use { first ->
            first.publish(track("A"), true, false, 0, 100, 1.0)
            val client = LyricsClient()
            val firstTrack = assertNotNull(client.snapshot())
            ExternalPlayback("test.second." + UUID.randomUUID()).use { second ->
                second.publish(track("B"), true, false, 0, 100, 1.0)
                assertEquals("B", client.snapshot()?.title)
                second.publish(track("B"), false, false, 0, 100, 1.0)
                assertEquals(firstTrack.identity, client.snapshot()?.identity)
            }
            val field = ExternalPlayback::class.java.getDeclaredField("updatedAtNanos")
            field.isAccessible = true
            field.setLong(first, System.nanoTime() - ExternalPlayback.LEASE_NANOS - 1_000_000L)
            assertNull(client.snapshot())
            assertNull(IslandClient().snapshot())
            first.touch()
            assertNotEquals(firstTrack.identity, assertNotNull(client.snapshot()).identity)
            assertFalse(client.clear(firstTrack))
        }
    }

    @Test fun routesControlsToSelectedProviderAndRetainsPausedPosition() {
        val commands = mutableListOf<Pair<String, Long>>()
        ExternalPlayback("test.control." + UUID.randomUUID()) { _, command, value ->
            commands += command to value
            true
        }.use { source ->
            source.publish(track("A"), true, false, 1_234, 70, 2.0)
            val client = IslandClient()
            val current = assertNotNull(client.snapshot())
            assertEquals(1_234, current.currentPosition())
            assertTrue(client.seek(current, 2_000))
            assertTrue(client.volume(current, 95))
            assertEquals(listOf("seek" to 2_000L, "volume" to 95L), commands)
        }
    }

    @Test fun externalCacheIdentityIsStableAcrossMetadataAndIsolatedByProvider() {
        val local = TrackQuery("Song", listOf("Artist"), "Album", path = "D:/music/song.flac")
        val external = local.copy(path = "", playbackProvider = "one", playbackUri = "urn:test:1")
        assertNotEquals(local.key, external.key)
        assertEquals(external.key, external.copy(title = "Renamed", playbackTrackRevision = 4).key)
        assertNotEquals(external.key, external.copy(playbackProvider = "two").key)
    }

    private fun track(id: String) = ExternalPlayback.Track("urn:test:$id", id, "artist", "album", "", 180_000)
}
