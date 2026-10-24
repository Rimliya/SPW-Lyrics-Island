package io.github.solitaire.spwlyrics.application

import io.github.solitaire.spwlyrics.domain.LyricLine
import io.github.solitaire.spwlyrics.domain.LyricsDocument
import io.github.solitaire.spwlyrics.domain.LyricsFormat
import io.github.solitaire.spwlyrics.domain.LyricsSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LyricsBatchProcessorTest {
    @Test
    fun skipsInstrumentalAndPolicyBlockedTracks() {
        val instrumental = document("纯音乐，请欣赏")
        val local = document("local lyric")

        assertEquals("纯音乐", batchSkipReason("Song", instrumental, AutomaticReplacementPolicy.ALWAYS)?.first)
        assertEquals("纯音乐", batchSkipReason("烟火焚 (inst.)", null, AutomaticReplacementPolicy.ALWAYS)?.first)
        assertEquals("仅手动选择", batchSkipReason("Song", null, AutomaticReplacementPolicy.MANUAL_ONLY)?.first)
        assertEquals("已有本地歌词", batchSkipReason("Song", local, AutomaticReplacementPolicy.WHEN_LOCAL_MISSING)?.first)
        assertNull(batchSkipReason("Song", local, AutomaticReplacementPolicy.ALWAYS))
        assertNull(batchSkipReason("Song", null, AutomaticReplacementPolicy.WHEN_LOCAL_MISSING))
    }

    private fun document(text: String) = LyricsDocument(
        source = LyricsSource.LOCAL,
        format = LyricsFormat.PLAIN,
        lines = listOf(LyricLine(text = text)),
    )
}