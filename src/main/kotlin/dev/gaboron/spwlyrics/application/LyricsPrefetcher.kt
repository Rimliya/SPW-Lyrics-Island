package dev.gaboron.spwlyrics.application

import dev.gaboron.spwlyrics.domain.TrackQuery
import dev.gaboron.spwlyrics.domain.LyricsDocument
import dev.gaboron.spwlyrics.domain.InstrumentalLyrics
import dev.gaboron.spwlyrics.storage.CachedLyrics
import dev.gaboron.spwlyrics.storage.LyricsCache
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * Warms the cache for the track that the host will play next.
 *
 * The Workshop API does not expose the play queue. [nextTrack] is therefore best effort: a miss or
 * a wrong guess only leaves a normal cache miss when the song actually starts.
 */
class LyricsPrefetcher(
    private val cache: LyricsCache,
    private val nextTrack: (TrackQuery) -> TrackQuery?,
    private val resolve: (TrackQuery) -> CachedLyrics?,
    private val enabled: () -> Boolean,
    private val policy: () -> AutomaticReplacementPolicy,
    private val hasLocalLyrics: (TrackQuery) -> Boolean = { false },
    private val localLyrics: ((TrackQuery) -> LyricsDocument?)? = null,
    private val delayMillis: Long = 0,
) : AutoCloseable {
    private val executor: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "spw-lyrics-prefetch").apply { isDaemon = true }
    }
    private val generation = AtomicInteger()
    private val lock = Any()
    private var lastCurrentKey: String? = null
    private var task: Future<*>? = null

    fun onTrackLoaded(current: TrackQuery) {
        if (!enabled() || policy() == AutomaticReplacementPolicy.MANUAL_ONLY) {
            return
        }
        synchronized(lock) {
            if (lastCurrentKey == current.key) return
            lastCurrentKey = current.key
            val token = generation.incrementAndGet()
            task?.cancel(true)
            task = executor.submit(Runnable {
                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis)
                    } catch (_: InterruptedException) {
                        return@Runnable
                    }
                }
                prefetch(current, token)
            })
        }
    }

    /** Synchronous core, also used by tests. */
    fun prefetchNow(current: TrackQuery): Boolean = prefetch(current, generation.get())

    private fun prefetch(current: TrackQuery, token: Int): Boolean {
        if (!enabled() || policy() == AutomaticReplacementPolicy.MANUAL_ONLY) {
            return false
        }
        val next = runCatching { nextTrack(current) }.getOrNull() ?: return false
        if (next.key == current.key || next.path.isBlank()) return false
        val local = runCatching { localLyrics?.invoke(next) }.getOrNull()
        if (local != null && InstrumentalLyrics.isPlaceholder(local)) {
            return false
        }
        if (policy() == AutomaticReplacementPolicy.WHEN_LOCAL_MISSING &&
            (local != null || runCatching { hasLocalLyrics(next) }.getOrDefault(false))) {
            return false
        }
        if (cache.hasLyrics(next) || cache.getOverride(next) != null) {
            return false
        }
        val cached = runCatching { resolve(next) }.getOrNull() ?: return false
        if (!enabled() || generation.get() != token || Thread.currentThread().isInterrupted) return false
        if (cache.hasLyrics(next) || cache.getOverride(next) != null) return false
        cache.putLyrics(next, cached)
        return true
    }

    override fun close() {
        synchronized(lock) {
            generation.incrementAndGet()
            task?.cancel(true)
            task = null
            lastCurrentKey = null
        }
        executor.shutdownNow()
    }
}
