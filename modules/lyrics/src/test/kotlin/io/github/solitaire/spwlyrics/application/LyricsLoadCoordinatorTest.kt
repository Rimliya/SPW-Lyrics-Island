package io.github.solitaire.spwlyrics.application

import io.github.solitaire.spwlyrics.domain.*
import io.github.solitaire.spwlyrics.storage.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class LyricsLoadCoordinatorTest {
    @TempDir lateinit var directory: Path

    @Test fun lateCacheLookupCannotReturnPreviousTrackLyrics() {
        val first = TrackQuery("A", listOf("artist"), "album", path = "D:/A.flac")
        val second = first.copy(title = "B", path = "D:/B.flac")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fileCache = FileLyricsCache(directory)
        val cache = object : LyricsCache by fileCache {
            override fun getSelection(query: TrackQuery): ManualOverride? {
                if (query == first) {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return null
            }
        }
        val worker = Executors.newSingleThreadExecutor()
        val coordinator = LyricsLoadCoordinator(cache, LyricsResolver(emptyList()), object : LyricsRefreshBridge {
            override fun reloadCurrentLyrics() = true
        })
        try {
            val old = worker.submit<String?> {
                coordinator.onLoad(first, LyricsLoadPhase.BEFORE_LOCAL, AutomaticReplacementPolicy.MANUAL_ONLY,
                    localTtml = { document("A") })
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertNotNull(coordinator.onLoad(second, LyricsLoadPhase.BEFORE_LOCAL,
                AutomaticReplacementPolicy.MANUAL_ONLY, localTtml = { document("B") }))
            release.countDown()
            assertNull(old.get(5, TimeUnit.SECONDS))
            assertEquals(second, coordinator.currentQuery())
        } finally {
            release.countDown()
            coordinator.close()
            worker.shutdownNow()
        }
    }

    @Test fun staleExternalSelectionCannotChangeReplayedTrack() {
        val first = TrackQuery("A", listOf("artist"), "album", playbackProvider = "test",
            playbackUri = "urn:test:A", playbackSessionId = "session", playbackTrackRevision = 1)
        val replay = first.copy(playbackTrackRevision = 3)
        LyricsLoadCoordinator(FileLyricsCache(directory), LyricsResolver(emptyList()), object : LyricsRefreshBridge {
            override fun reloadCurrentLyrics() = true
        }).use { coordinator ->
            coordinator.onLoad(first, LyricsLoadPhase.BEFORE_LOCAL, AutomaticReplacementPolicy.MANUAL_ONLY)
            coordinator.onLoad(replay, LyricsLoadPhase.BEFORE_LOCAL, AutomaticReplacementPolicy.MANUAL_ONLY)
            assertFalse(coordinator.useLocal(first))
            assertTrue(coordinator.useLocal(replay))
        }
    }

    private fun document(text: String) = LyricsDocument(LyricsSource.LOCAL, LyricsFormat.LRC,
        listOf(LyricLine(startMs = 0, endMs = 1_000, text = text)))
}
