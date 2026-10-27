package io.github.solitaire.spwlyrics.storage

import io.github.solitaire.spwlyrics.domain.LyricLine
import io.github.solitaire.spwlyrics.domain.LyricsDocument
import io.github.solitaire.spwlyrics.domain.LyricsFormat
import io.github.solitaire.spwlyrics.domain.LyricsSource
import io.github.solitaire.spwlyrics.domain.TrackQuery
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileLyricsCacheTest {
    private val query = TrackQuery("Da Capo", listOf("崩坏3"), "Da Capo")

    @Test
    fun presenceCheckFollowsCachedFilesWithoutDecoding() {
        val root = Files.createTempDirectory("spw-lyrics-cache")
        try {
            val cache = FileLyricsCache(root)
            assertFalse(cache.hasLyrics(query))

            cache.putLyrics(query, CachedLyrics(document(), savedAtEpochMs = System.currentTimeMillis()))
            assertTrue(cache.hasLyrics(query))
            assertTrue(FileLyricsCache(root).hasLyrics(query))
        } finally {
            root.toFile().deleteRecursively()
        }
    }


    @Test
    fun maintenancePreservesCurrentTrackAndManualOverride() {
        val root = Files.createTempDirectory("spw-lyrics-cache-prune")
        try {
            val cache = FileLyricsCache(root)
            val older = TrackQuery("Older", listOf("Artist"), "Album")
            val newer = TrackQuery("Newer", listOf("Artist"), "Album")
            val extra = TrackQuery("Extra", listOf("Artist"), "Album")
            listOf(older, newer, extra).forEach { query ->
                cache.putLyrics(query, CachedLyrics(document(), savedAtEpochMs = System.currentTimeMillis()))
            }
            cache.putOverride(older, ManualOverride(local = true))

            val result = cache.prune(maxBytes = 1L, protect = newer)
            assertEquals(1, result.deletedFiles)
            assertTrue(cache.hasLyrics(newer))
            assertTrue(cache.hasLyrics(older))
            assertFalse(cache.hasLyrics(extra))
            assertNotNull(cache.getOverride(older))
            assertEquals(2, Files.list(root.resolve("歌词")).use { it.count() })

            val cleared = cache.clearCachedLyrics()
            assertEquals(2, cleared.deletedFiles)
            assertFalse(cache.hasLyrics(newer))
            assertNotNull(cache.getOverride(older))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun document() = LyricsDocument(
        source = LyricsSource.AMLL,
        format = LyricsFormat.LRC,
        lines = listOf(LyricLine(startMs = 0, endMs = 1_000, text = "test")),
    )
}
