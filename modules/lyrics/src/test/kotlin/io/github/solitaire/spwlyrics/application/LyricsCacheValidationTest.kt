package io.github.solitaire.spwlyrics.application

import io.github.solitaire.spwlyrics.domain.*
import io.github.solitaire.spwlyrics.storage.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class LyricsCacheValidationTest {
    @TempDir lateinit var directory: Path
    private val query = TrackQuery("Track", listOf("Artist"), "Album", path = "D:/track.flac")
    private val text = listOf("amber valleys awaken", "silver rivers wander", "crimson morning glows",
        "quiet forests whisper", "golden lanterns shimmer", "winter oceans sleep")
    private val unrelated = listOf("satellite engines ignite", "velvet trousers vanish", "purple bicycles tumble",
        "electric deserts sparkle", "oaken cabinets collapse", "distant comets revolve")

    @Test fun changedLocalTextRejectsPreviouslyValidatedAutomaticCache() {
        LyricsResolver(emptyList()).use { resolver ->
            val original = document(text)
            val cached = cached(resolver, original).copy(validation = LyricsCacheValidation.current(original, LyricsSimilarity.Level.STANDARD))
            assertSame(cached, resolver.revalidateCached(cached, document(text), LyricsSimilarity.Level.STANDARD))
            assertNull(resolver.revalidateCached(cached, document(unrelated), LyricsSimilarity.Level.STANDARD))
        }
    }

    @Test fun stricterSimilarityRechecksAnExistingStandardMatch() {
        LyricsResolver(emptyList()).use { resolver ->
            val local = document(text)
            val candidate = document(text.take(2) + unrelated.take(4))
            val coverage = LyricsSimilarity.compare(local, candidate).coverage
            assertTrue(coverage >= .25 && coverage < .4, "Fixture coverage was $coverage")
            val cached = cached(resolver, candidate).copy(validation = LyricsCacheValidation.current(local, LyricsSimilarity.Level.STANDARD))
            assertSame(cached, resolver.revalidateCached(cached, local, LyricsSimilarity.Level.STANDARD))
            assertNull(resolver.revalidateCached(cached, local, LyricsSimilarity.Level.STRICT))
        }
    }

    @Test fun legacyEntryIsStampedAfterSuccessfulValidationWithoutExtendingTtl() {
        LyricsResolver(emptyList()).use { resolver ->
            val local = document(text)
            val legacy = cached(resolver, local)
            val updated = assertNotNull(resolver.revalidateCached(legacy, local, LyricsSimilarity.Level.STRICT))
            assertEquals(LyricsCacheValidation.current(local, LyricsSimilarity.Level.STRICT), updated.validation)
            assertEquals(legacy.savedAtEpochMs, updated.savedAtEpochMs)
            val cache = FileLyricsCache(directory)
            cache.putLyrics(query, updated)
            assertEquals(updated.validation, FileLyricsCache(directory).getLyrics(query)?.validation)
        }
    }

    @Test fun playbackDoesNotReturnAnInvalidAutomaticCache() {
        val resolver = LyricsResolver(emptyList())
        val cache = FileLyricsCache(directory)
        cache.putLyrics(query, cached(resolver, document(text)))
        LyricsLoadCoordinator(cache, resolver, refresh(), referenceLyrics = { document(unrelated) }).use { coordinator ->
            assertNull(coordinator.onLoad(query, LyricsLoadPhase.BEFORE_LOCAL, AutomaticReplacementPolicy.ALWAYS))
        }
    }

    @Test fun manualSelectionSurvivesValidationSettingChanges() {
        val resolver = LyricsResolver(emptyList())
        val cache = FileLyricsCache(directory)
        val candidate = LyricsCandidate(LyricsSource.QQ, "manual", query.title, query.artists, query.album)
        cache.putOverride(query, ManualOverride(false, candidate = candidate))
        cache.putLyrics(query, cached(resolver, document(text)))
        LyricsLoadCoordinator(cache, resolver, refresh(), referenceLyrics = { error("Manual cache should not load a reference") },
            similarityLevel = { LyricsSimilarity.Level.STRICT }).use { coordinator ->
            assertNotNull(coordinator.onLoad(query, LyricsLoadPhase.BEFORE_LOCAL, AutomaticReplacementPolicy.ALWAYS))
            assertEquals(candidate, cache.getSelection(query)?.candidate)
        }
    }

    @Test fun batchRechecksCachedTracksOnItsWorker() {
        val resolver = LyricsResolver(emptyList())
        val cache = FileLyricsCache(directory)
        cache.putLyrics(query, cached(resolver, document(text)))
        LyricsBatchProcessor({ listOf(query) }, cache, resolver, localLyrics = { document(unrelated) }).use { batch ->
            batch.start(includeCached = false)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (batch.snapshot().state != LyricsBatchState.COMPLETED && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(LyricsBatchItemState.FAILED, batch.snapshot().items.single().state)
        }
        resolver.close()
    }

    @Test fun validationStampCannotOverwriteANewerManualChoice() {
        LyricsResolver(emptyList()).use { resolver ->
            val cache = FileLyricsCache(directory)
            val old = cached(resolver, document(text))
            cache.putLyrics(query, old)
            val manual = old.copy(document = document(unrelated))
            cache.putOverride(query, ManualOverride(false,
                candidate = LyricsCandidate(LyricsSource.QQ, "chosen", query.title, query.artists, query.album)))
            cache.putLyrics(query, manual)
            assertFalse(cache.updateValidation(query, old, LyricsCacheValidation.current(document(text), LyricsSimilarity.Level.STANDARD)))
            assertEquals(manual.document, cache.getLyrics(query)?.document)
        }
    }

    private fun cached(resolver: LyricsResolver, document: LyricsDocument) = CachedLyrics(document,
        savedAtEpochMs = System.currentTimeMillis(), selectionKey = resolver.selectionKey())
    private fun document(lines: List<String>) = LyricsDocument(LyricsSource.LOCAL, LyricsFormat.LRC,
        lines.mapIndexed { index, line -> LyricLine(index * 1_000L, (index + 1) * 1_000L, line) })
    private fun refresh() = object : LyricsRefreshBridge { override fun reloadCurrentLyrics() = true }
}
