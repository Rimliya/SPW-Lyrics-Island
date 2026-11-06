package io.github.solitaire.spwisland.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackTimelineStaleLyricsTest {
    @Test
    fun rejectsRowsFromPreviousLyricsSourceAfterSnapshot() {
        val timeline = PlaybackTimeline { 0L }
        timeline.trackChanged(Track("Track", "Artist", "D:\\music\\track.flac"))
        val current = LyricLine(0, 1_000, "new source", translation = null, words = emptyList())
        timeline.lyricsChanged(listOf(current))

        timeline.lineChanged(LyricLine(0, 1_000, "old source", translation = null, words = emptyList()))

        assertEquals("new source", timeline.snapshot().line?.text)
        assertNull(timeline.snapshot().lyrics.firstOrNull { it.text == "old source" })
    }
}
