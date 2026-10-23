package io.github.solitaire.spwlyrics.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstrumentalLyricsTest {
    @Test
    fun recognizesDocumentPlaceholders() {
        assertTrue(InstrumentalLyrics.isPlaceholder(document("纯音乐，请欣赏")))
        assertTrue(InstrumentalLyrics.isPlaceholder(document("纯音乐，请欣赏。", "Instrumental")))
        assertTrue(InstrumentalLyrics.isPlaceholder(document("此曲为纯音乐，请欣赏")))
        assertTrue(InstrumentalLyrics.isPlaceholder(document("Instrumental music")))
    }

    @Test
    fun doesNotHideRealLyrics() {
        assertFalse(InstrumentalLyrics.isPlaceholder(document("第一句歌词", "请欣赏")))
        assertFalse(InstrumentalLyrics.isPlaceholder(document("第一句歌词", "第二句歌词")))
        assertFalse(InstrumentalLyrics.isPlaceholder(document("Instrumental intro", "第一句歌词")))
        assertFalse(InstrumentalLyrics.isPlaceholder(null))
    }

    @Test
    fun recognizesRawLocalPayloads() {
        assertTrue(InstrumentalLyrics.isPlaceholderRaw("[00:00.00]纯音乐，请欣赏"))
        assertTrue(InstrumentalLyrics.isPlaceholderRaw("[ar:Artist]\n[00:00.00]纯音乐，请欣赏"))
        assertTrue(InstrumentalLyrics.isPlaceholderRaw("Instrumental"))
        assertFalse(InstrumentalLyrics.isPlaceholderRaw("[00:00.00]第一句\n[00:01.00]第二句"))
    }

    private fun document(vararg lines: String) = LyricsDocument(
        source = LyricsSource.LOCAL,
        format = LyricsFormat.PLAIN,
        lines = lines.map { LyricLine(text = it) },
    )
}
