package io.github.solitaire.spwlyrics.application

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Writes one compact search summary per interval; no per-request file I/O. */
internal class LyricsPerformanceMonitor(
    private val file: Path,
    private val snapshot: () -> ProviderSearchCache.Snapshot,
    private val intervalSeconds: Long = 15,
) : AutoCloseable {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "spw-lyrics-performance").apply { isDaemon = true }
    }
    private var previous = ProviderSearchCache.Snapshot(0, 0, 0, 0, 0)

    fun start() {
        executor.scheduleWithFixedDelay(::sampleSafely, intervalSeconds, intervalSeconds, TimeUnit.SECONDS)
    }

    internal fun sample() {
        val current = snapshot()
        val deltaHits = (current.hits - previous.hits).coerceAtLeast(0)
        val deltaMisses = (current.misses - previous.misses).coerceAtLeast(0)
        val deltaNegative = (current.negativeHits - previous.negativeHits).coerceAtLeast(0)
        val deltaJoined = (current.joinedRequests - previous.joinedRequests).coerceAtLeast(0)
        val deltaFailures = (current.providerFailures - previous.providerFailures).coerceAtLeast(0)
        previous = current
        val total = deltaHits + deltaMisses
        val hitRate = if (total == 0L) 0.0 else deltaHits * 100.0 / total
        val line = "[SPW Lyrics] searchHit=$deltaHits searchMiss=$deltaMisses hitRate=${"%.1f".format(hitRate)}% " +
            "negativeHit=$deltaNegative joined=$deltaJoined providerFail=$deltaFailures\n"
        Files.createDirectories(file.parent)
        if (runCatching { Files.size(file) }.getOrDefault(0L) > MAX_LOG_BYTES) {
            runCatching { Files.move(file, file.resolveSibling("${file.fileName}.old"),
                StandardCopyOption.REPLACE_EXISTING) }
        }
        Files.writeString(file, "${Instant.now()} $line", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun sampleSafely() {
        runCatching { sample() }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private companion object {
        const val MAX_LOG_BYTES = 1_000_000L
    }
}