package io.github.solitaire.spwlyrics.application

import io.github.solitaire.spwlyrics.domain.LyricsCandidate
import io.github.solitaire.spwlyrics.domain.LyricsDocument
import io.github.solitaire.spwlyrics.domain.LyricsSource
import io.github.solitaire.spwlyrics.domain.TrackQuery
import io.github.solitaire.spwlyrics.provider.LyricsProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderSearchCacheTest {
    private val query = TrackQuery("Track", listOf("Artist"), "Album", durationMs = 180_000)
    private val candidate = LyricsCandidate(LyricsSource.QQ, "remote", "Track", listOf("Artist"), "Album", 180_000)

    @Test
    fun cachesPositiveAndNegativeResultsWithDifferentTtls() {
        var now = 1_000L
        val cache = ProviderSearchCache({ now }, positiveTtlMillis = 100, negativeTtlMillis = 20)
        val positiveProvider = FakeProvider { _, _, _ -> listOf(candidate) }
        val negativeProvider = FakeProvider(LyricsSource.KUGOU) { _, _, _ -> emptyList() }

        assertEquals(listOf(candidate), cache.getOrLoad(positiveProvider, query, "Track Artist", 20))
        assertEquals(listOf(candidate), cache.getOrLoad(positiveProvider, query, "track artist", 20))
        assertEquals(1, positiveProvider.calls.get())

        assertEquals(emptyList<LyricsCandidate>(), cache.getOrLoad(negativeProvider, query, "Track Artist", 20))
        assertEquals(emptyList<LyricsCandidate>(), cache.getOrLoad(negativeProvider, query, "Track Artist", 20))
        assertEquals(1, negativeProvider.calls.get())

        now += 25
        assertEquals(emptyList<LyricsCandidate>(), cache.getOrLoad(negativeProvider, query, "Track Artist", 20))
        assertEquals(2, negativeProvider.calls.get())

        now += 100
        assertEquals(listOf(candidate), cache.getOrLoad(positiveProvider, query, "Track Artist", 20))
        assertEquals(2, positiveProvider.calls.get())
    }

    @Test
    fun sharesInFlightRequests() {
        val cache = ProviderSearchCache()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val provider = FakeProvider { _, _, _ ->
            started.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            listOf(candidate)
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<List<LyricsCandidate>> { cache.getOrLoad(provider, query, "Track Artist", 20) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val second = executor.submit<List<LyricsCandidate>> { cache.getOrLoad(provider, query, "Track Artist", 20) }
            val joinDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (cache.snapshot().joinedRequests == 0L && System.nanoTime() < joinDeadline) Thread.sleep(5)
            release.countDown()
            assertEquals(listOf(candidate), first.get(5, TimeUnit.SECONDS))
            assertEquals(listOf(candidate), second.get(5, TimeUnit.SECONDS))
            assertEquals(1, provider.calls.get())
            assertEquals(1, cache.snapshot().joinedRequests)
        } finally {
            executor.shutdownNow()
        }
    }

    private class FakeProvider(
        override val source: LyricsSource = LyricsSource.QQ,
        private val loader: (TrackQuery, String, Int) -> List<LyricsCandidate>,
    ) : LyricsProvider {
        val calls = AtomicInteger()
        override fun search(query: TrackQuery, keywords: String, limit: Int): List<LyricsCandidate> {
            calls.incrementAndGet()
            return loader(query, keywords, limit)
        }
        override fun fetch(candidate: LyricsCandidate): LyricsDocument? = null
    }
}