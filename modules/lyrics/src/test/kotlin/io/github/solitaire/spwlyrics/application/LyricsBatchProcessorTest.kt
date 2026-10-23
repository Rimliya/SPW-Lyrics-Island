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

        assertEquals("纯音乐", batchSkipReason(instrumental, AutomaticReplacementPolicy.ALWAYS)?.first)
        assertEquals("仅手动选择", batchSkipReason(null, AutomaticReplacementPolicy.MANUAL_ONLY)?.first)
        assertEquals("已有本地歌词", batchSkipReason(local, AutomaticReplacementPolicy.WHEN_LOCAL_MISSING)?.first)
        assertNull(batchSkipReason(local, AutomaticReplacementPolicy.ALWAYS))
        assertNull(batchSkipReason(null, AutomaticReplacementPolicy.WHEN_LOCAL_MISSING))
    }

    private fun document(text: String) = LyricsDocument(
        source = LyricsSource.LOCAL,
        format = LyricsFormat.PLAIN,
        lines = listOf(LyricLine(text = text)),
    )
}