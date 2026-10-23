package io.github.solitaire.spwlyrics.application

import io.github.solitaire.spwlyrics.domain.LyricsCandidate
import io.github.solitaire.spwlyrics.domain.TrackQuery
import io.github.solitaire.spwlyrics.provider.LyricsProvider
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Deduplicates automatic provider searches and keeps short positive/negative result windows. */
internal class ProviderSearchCache(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val positiveTtlMillis: Long = 10 * 60_000L,
    private val negativeTtlMillis: Long = 2 * 60_000L,
    private val maxEntries: Int = 256,
) {
    private data class Key(
        val source: String,
        val queryKey: String,
        val keywords: String,
        val limit: Int,
    )

    private data class Entry(val results: List<LyricsCandidate>, val expiresAtMillis: Long)

    private val lock = Any()
    private val entries = object : LinkedHashMap<Key, Entry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean = size > maxEntries
    }
    private val inFlight = ConcurrentHashMap<Key, CompletableFuture<List<LyricsCandidate>>>()
    private val hits = AtomicLong()
    private val misses = AtomicLong()
    private val negativeHits = AtomicLong()
    private val joinedRequests = AtomicLong()
    private val providerFailures = AtomicLong()

    fun getOrLoad(provider: LyricsProvider, query: TrackQuery, keywords: String, limit: Int): List<LyricsCandidate> {
        val key = Key(provider.source.name, query.key, keywords.trim().lowercase(), limit)
        cached(key)?.let {
            hits.incrementAndGet()
            if (it.isEmpty()) negativeHits.incrementAndGet()
            return it
        }
        misses.incrementAndGet()
        val promise = CompletableFuture<List<LyricsCandidate>>()
        val existing = inFlight.putIfAbsent(key, promise)
        if (existing != null) {
            joinedRequests.incrementAndGet()
            return runCatching { existing.get().toList() }.getOrDefault(emptyList())
        }
        return try {
            val loaded = runCatching { provider.search(query, keywords, limit) }
                .onFailure { providerFailures.incrementAndGet() }
                .getOrDefault(emptyList())
                .toList()
            store(key, loaded)
            promise.complete(loaded)
            loaded
        } catch (error: Throwable) {
            promise.completeExceptionally(error)
            throw error
        } finally {
            inFlight.remove(key, promise)
        }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
        inFlight.clear()
    }

    fun snapshot(): Snapshot = Snapshot(
        hits = hits.get(),
        misses = misses.get(),
        negativeHits = negativeHits.get(),
        joinedRequests = joinedRequests.get(),
        providerFailures = providerFailures.get(),
    )

    private fun cached(key: Key): List<LyricsCandidate>? = synchronized(lock) {
        val entry = entries[key] ?: return@synchronized null
        if (entry.expiresAtMillis <= nowMillis()) {
            entries.remove(key)
            null
        } else entry.results
    }

    private fun store(key: Key, results: List<LyricsCandidate>) {
        val ttl = if (results.isEmpty()) negativeTtlMillis else positiveTtlMillis
        synchronized(lock) { entries[key] = Entry(results, nowMillis() + ttl) }
    }

    data class Snapshot(
        val hits: Long,
        val misses: Long,
        val negativeHits: Long,
        val joinedRequests: Long,
        val providerFailures: Long,
    )
}