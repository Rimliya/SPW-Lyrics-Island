package dev.gaboron.spwlyrics.application

import dev.gaboron.spwlyrics.domain.LyricLine
import dev.gaboron.spwlyrics.domain.LyricsDocument
import dev.gaboron.spwlyrics.domain.LyricsFormat
import dev.gaboron.spwlyrics.domain.LyricsSource
import dev.gaboron.spwlyrics.domain.TrackQuery
import dev.gaboron.spwlyrics.storage.CachedLyrics
import dev.gaboron.spwlyrics.storage.LyricsCache
import dev.gaboron.spwlyrics.storage.ManualOverride
import kotlin.test.Test
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

    private fun prefetcher(
        cache: LyricsCache,
        nextTrack: (TrackQuery) -> TrackQuery? = { next },
        resolve: (TrackQuery) -> CachedLyrics? = { cached() },
        enabled: () -> Boolean = { true },
        policy: () -> AutomaticReplacementPolicy = { AutomaticReplacementPolicy.ALWAYS },
        hasLocalLyrics: (TrackQuery) -> Boolean = { false },
    ) = LyricsPrefetcher(cache, nextTrack, resolve, enabled, policy, hasLocalLyrics)

    private fun query(title: String) = TrackQuery(
        title = title,
        artists = listOf("Artist"),
        album = "Album",
        path = "/music/$title.flac",
        durationMs = 180_000,
    )

    private fun cached() = CachedLyrics(document(), savedAtEpochMs = System.currentTimeMillis())

    private fun document() = LyricsDocument(
        source = LyricsSource.AMLL,
        format = LyricsFormat.LRC,
        lines = listOf(LyricLine(startMs = 0, endMs = 1_000, text = "test")),
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
