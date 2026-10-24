package io.github.solitaire.spwlyrics.application

import io.github.solitaire.spwlyrics.domain.LyricLine
import io.github.solitaire.spwlyrics.domain.LyricsDocument
import io.github.solitaire.spwlyrics.domain.LyricsFormat
import io.github.solitaire.spwlyrics.domain.LyricsSource
import io.github.solitaire.spwlyrics.domain.TrackQuery
import io.github.solitaire.spwlyrics.storage.CachedLyrics
import io.github.solitaire.spwlyrics.storage.LyricsCache
import io.github.solitaire.spwlyrics.storage.ManualOverride
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsPrefetcherTest {
    private val current = query("Current")
    private val next = query("Next")

    @Test
    fun cachesTheNextTrack() {
        val cache = MemoryCache()
        val prefetcher = prefetcher(cache)

        assertTrue(prefetcher.prefetchNow(current))
        assertTrue(cache.hasLyrics(next))
    }

    @Test
    fun skipsWhenDisabled() {
        val cache = MemoryCache()
        val prefetcher = prefetcher(cache, enabled = { false })

        assertFalse(prefetcher.prefetchNow(current))
        assertFalse(cache.hasLyrics(next))
    }

    @Test
    fun skipsManualOnlyPolicy() {
        val cache = MemoryCache()
        val prefetcher = prefetcher(cache, policy = { AutomaticReplacementPolicy.MANUAL_ONLY })

        assertFalse(prefetcher.prefetchNow(current))
        assertFalse(cache.hasLyrics(next))
    }

    @Test
    fun skipsInstrumentalTitleMarkers() {
        val cache = MemoryCache()
        var resolved = false
        val prefetcher = prefetcher(
            cache,
            nextTrack = { query("烟火焚 (inst.)") },
            resolve = { resolved = true; cached() },
        )

        assertFalse(prefetcher.prefetchNow(current))
        assertFalse(resolved)
        assertFalse(cache.hasLyrics(query("烟火焚 (inst.)")))
    }
    @Test
    fun skipsExistingCacheAndOverrides() {
        val cache = MemoryCache()
        cache.putLyrics(next, cached())
        var resolved = false
        val prefetcher = prefetcher(cache, resolve = { resolved = true; cached() })

        assertFalse(prefetcher.prefetchNow(current))
        assertFalse(resolved)

        val other = MemoryCache()
        other.putOverride(next, ManualOverride(local = true))
        val overridden = prefetcher(other)
        assertFalse(overridden.prefetchNow(current))
        assertFalse(other.hasLyrics(next))
    }

    @Test
    fun respectsLocalLyricsWhenPolicyNeedsMissingLocal() {
        val cache = MemoryCache()
        var resolved = false
        val prefetcher = prefetcher(
            cache,
            policy = { AutomaticReplacementPolicy.WHEN_LOCAL_MISSING },
            hasLocalLyrics = { true },
            resolve = { resolved = true; cached() },
        )

        assertFalse(prefetcher.prefetchNow(current))
        assertFalse(resolved)
    }

    @Test
    fun skipsMissingOrSameTrack() {
        val cache = MemoryCache()
        val missing = prefetcher(cache, nextTrack = { null })
        assertFalse(missing.prefetchNow(current))

        val same = prefetcher(cache, nextTrack = { current })
        assertFalse(same.prefetchNow(current))
    }

    @Test
    fun skipsLocalInstrumentalPlaceholder() {
        val cache = MemoryCache()
        var resolved = false
        val prefetcher = prefetcher(
            cache,
            localLyrics = { placeholder() },
            resolve = { resolved = true; cached() },
        )

        assertFalse(prefetcher.prefetchNow(current))
        assertFalse(resolved)
    }

    @Test
    fun refreshesStaleCacheAfterSelectionChanges() {
        val cache = MemoryCache()
        cache.putLyrics(next, cached(selectionKey = "old-selection"))
        var resolved = false
        val prefetcher = prefetcher(
            cache,
            selectionKey = { "new-selection" },
            resolve = { resolved = true; cached(selectionKey = "new-selection") },
        )

        assertTrue(prefetcher.prefetchNow(current))
        assertTrue(resolved)
        assertEquals("new-selection", cache.getLyrics(next)?.selectionKey)
    }
    private fun prefetcher(
        cache: LyricsCache,
        nextTrack: (TrackQuery) -> TrackQuery? = { next },
        resolve: (TrackQuery) -> CachedLyrics? = { cached() },
        enabled: () -> Boolean = { true },
        policy: () -> AutomaticReplacementPolicy = { AutomaticReplacementPolicy.ALWAYS },
        hasLocalLyrics: (TrackQuery) -> Boolean = { false },
        localLyrics: ((TrackQuery) -> LyricsDocument?)? = null,
        selectionKey: () -> String = { "" },
    ) = LyricsPrefetcher(
        cache = cache,
        nextTrack = nextTrack,
        resolve = resolve,
        enabled = enabled,
        policy = policy,
        hasLocalLyrics = hasLocalLyrics,
        localLyrics = localLyrics,
        selectionKey = selectionKey,
    )

    private fun query(title: String) = TrackQuery(
        title = title,
        artists = listOf("Artist"),
        album = "Album",
        path = "/music/$title.flac",
        durationMs = 180_000,
    )

    private fun cached(selectionKey: String = "test-selection") = CachedLyrics(document(), savedAtEpochMs = System.currentTimeMillis(), selectionKey = selectionKey)

    private fun document() = LyricsDocument(
        source = LyricsSource.AMLL,
        format = LyricsFormat.LRC,
        lines = listOf(LyricLine(startMs = 0, endMs = 1_000, text = "test")),
    )

    private fun placeholder() = LyricsDocument(
        source = LyricsSource.LOCAL,
        format = LyricsFormat.PLAIN,
        lines = listOf(LyricLine(text = "纯音乐，请欣赏")),
    )

    private class MemoryCache : LyricsCache {
        private val lyrics = mutableMapOf<String, CachedLyrics>()
        private val overrides = mutableMapOf<String, ManualOverride>()

        override fun getLyrics(query: TrackQuery): CachedLyrics? = lyrics[query.key]
        override fun hasLyrics(query: TrackQuery): Boolean = lyrics.containsKey(query.key)
        override fun putLyrics(query: TrackQuery, lyrics: CachedLyrics) {
            this.lyrics[query.key] = lyrics
        }

        override fun removeLyrics(query: TrackQuery) {
            lyrics.remove(query.key)
        }

        override fun getOverride(query: TrackQuery): ManualOverride? = overrides[query.key]
        override fun putOverride(query: TrackQuery, override: ManualOverride) {
            overrides[query.key] = override
        }

        override fun removeOverride(query: TrackQuery) {
            overrides.remove(query.key)
        }
    }
}
