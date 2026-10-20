package dev.gaboron.spwlyrics.application

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Read-only counters for cache, provider and prefetch work. */
internal object LyricsMetrics {
    private const val LOG_INTERVAL_NS = 30_000_000_000L
    private val cacheHits = AtomicLong()
    private val cacheMisses = AtomicLong()
    private val providerRequests = AtomicLong()
    private val providerFailures = AtomicLong()
    private val onlineResults = AtomicLong()
    private val prefetchAttempts = AtomicLong()
    private val prefetchSuccesses = AtomicLong()
    private val prefetchSkips = AtomicLong()
    private val batchSkips = AtomicLong()
    private var windowStart = System.nanoTime()
    @Volatile private var sink: ((String) -> Unit)? = null

    fun configureSink(value: (String) -> Unit) {
        sink = value
    }

    fun cacheHit() = cacheHits.incrementAndGet()
    fun cacheMiss() = cacheMisses.incrementAndGet()
    fun providerRequest() = providerRequests.incrementAndGet()
    fun providerFailure() = providerFailures.incrementAndGet()
    fun onlineResult() = onlineResults.incrementAndGet()
    fun prefetchAttempt() = prefetchAttempts.incrementAndGet()
    fun prefetchSuccess() = prefetchSuccesses.incrementAndGet()
    fun prefetchSkip() = prefetchSkips.incrementAndGet()
    fun batchSkip() = batchSkips.incrementAndGet()

    @Synchronized
    fun maybeLog(now: Long = System.nanoTime()) {
        if (now - windowStart < LOG_INTERVAL_NS) return
        val hits = cacheHits.getAndSet(0L)
        val misses = cacheMisses.getAndSet(0L)
        val providers = providerRequests.getAndSet(0L)
        val failures = providerFailures.getAndSet(0L)
        val online = onlineResults.getAndSet(0L)
        val prefetch = prefetchAttempts.getAndSet(0L)
        val prefetchOk = prefetchSuccesses.getAndSet(0L)
        val prefetchSkipped = prefetchSkips.getAndSet(0L)
        val batchSkipped = batchSkips.getAndSet(0L)
        windowStart = now
        if (hits + misses + providers + online + prefetch + batchSkipped == 0L) return
        val totalCache = hits + misses
        val hitRate = if (totalCache == 0L) 0.0 else hits * 100.0 / totalCache
        val line = String.format(Locale.ROOT,
            "[SPW Lyrics] cacheHit=%d cacheMiss=%d hitRate=%.1f%% providers=%d providerFail=%d " +
                "online=%d prefetch=%d prefetchOk=%d prefetchSkip=%d batchSkip=%d",
            hits, misses, hitRate, providers, failures, online, prefetch, prefetchOk, prefetchSkipped, batchSkipped)
        val target = sink
        if (target != null) target(line) else println(line)
    }
}
