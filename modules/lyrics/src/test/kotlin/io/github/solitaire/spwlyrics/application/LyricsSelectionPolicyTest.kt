package io.github.solitaire.spwlyrics.application

import io.github.solitaire.spwlyrics.domain.LyricLine
import io.github.solitaire.spwlyrics.domain.LyricWord
import io.github.solitaire.spwlyrics.domain.LyricsDocument
import io.github.solitaire.spwlyrics.domain.LyricsFormat
import io.github.solitaire.spwlyrics.domain.LyricsPreferences
import io.github.solitaire.spwlyrics.domain.LyricsPriorityMode
import io.github.solitaire.spwlyrics.domain.LyricsQuality
import io.github.solitaire.spwlyrics.domain.LyricsSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsSelectionPolicyTest {
    private val sourceFirst = LyricsPreferences(
        mode = LyricsPriorityMode.SOURCE_FIRST,
        sources = listOf(LyricsSource.QQ, LyricsSource.NETEASE, LyricsSource.AMLL),
    )

    @Test
    fun sourceFirstCannotDowngradeTimingGranularity() {
        val current = document(LyricsSource.NETEASE, LyricsQuality.LINE_SYNCED)
        val preferredPlain = document(LyricsSource.QQ, LyricsQuality.PLAIN)
        assertFalse(LyricsSelectionPolicy.canReplace(current, preferredPlain, sourceFirst))
    }

    @Test
    fun preferredSourceCanStillUpgradeOrKeepQuality() {
        val current = document(LyricsSource.NETEASE, LyricsQuality.LINE_SYNCED)
        val preferredKaraoke = document(LyricsSource.QQ, LyricsQuality.KARAOKE_SYNCED)
        assertTrue(LyricsSelectionPolicy.canReplace(current, preferredKaraoke, sourceFirst))
    }

    private fun document(source: LyricsSource, quality: LyricsQuality): LyricsDocument {
        val lines = when (quality) {
            LyricsQuality.KARAOKE_SYNCED -> listOf(
                LyricLine(0, 1_000, "逐字歌词", words = listOf(LyricWord(0, 500, "逐字"), LyricWord(500, 1_000, "歌词"))),
                LyricLine(1_000, 2_000, "第二行歌词", words = listOf(LyricWord(1_000, 1_500, "第二行"), LyricWord(1_500, 2_000, "歌词"))),
            )
            LyricsQuality.LINE_SYNCED -> listOf(
                LyricLine(0, 1_000, "逐行歌词"),
                LyricLine(1_000, 2_000, "第二行歌词"),
            )
            LyricsQuality.PLAIN -> listOf(
                LyricLine(text = "普通歌词"),
                LyricLine(text = "第二行歌词"),
            )
        }
        return LyricsDocument(source, LyricsFormat.LRC, lines)
    }
}
