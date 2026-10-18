package dev.gaboron.spwlyrics.storage

import dev.gaboron.spwlyrics.domain.LyricLine
import dev.gaboron.spwlyrics.domain.LyricsDocument
import dev.gaboron.spwlyrics.domain.LyricsFormat
import dev.gaboron.spwlyrics.domain.LyricsSource
import dev.gaboron.spwlyrics.domain.TrackQuery
import java.nio.file.Files
import kotlin.test.Test
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

    private fun document() = LyricsDocument(
        source = LyricsSource.AMLL,
        format = LyricsFormat.LRC,
        lines = listOf(LyricLine(startMs = 0, endMs = 1_000, text = "test")),
    )
}
