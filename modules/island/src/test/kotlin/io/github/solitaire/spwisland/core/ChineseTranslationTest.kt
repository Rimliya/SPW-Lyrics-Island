package io.github.solitaire.spwisland.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChineseTranslationTest {
    @Test fun selectsFirstChineseCandidateFromMergedSubtitles() {
        assertEquals("中文翻译", ChineseTranslation.select("English\n日本語の歌\n中文翻译\n另一译文"))
        assertEquals("中文翻译", ChineseTranslation.select("中文翻译\n中文翻译"))
        assertNull(ChineseTranslation.select("English\nにほんご\n한국어"))
    }
    @Test fun preservesUntaggedLegacyTranslation() {
        assertEquals("English", ChineseTranslation.select(" English \n"))
        assertNull(ChineseTranslation.select("\n "))
    }
}
