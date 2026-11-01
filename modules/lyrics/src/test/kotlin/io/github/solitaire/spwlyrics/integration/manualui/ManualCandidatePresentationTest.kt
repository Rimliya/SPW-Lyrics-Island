package io.github.solitaire.spwlyrics.integration.manualui

import io.github.solitaire.spwlyrics.domain.CandidateScore
import io.github.solitaire.spwlyrics.domain.LyricsCandidate
import io.github.solitaire.spwlyrics.domain.LyricsSource
import kotlin.test.Test
import kotlin.test.assertEquals

class ManualCandidatePresentationTest {
    @Test
    fun appliedAndAutomaticStayPinnedWithoutDuplicates() {
        val applied = score("applied", .60)
        val automatic = score("automatic", .70)
        val manual = score("manual", .99)
        val list = listOf(manual, automatic, applied)

        assertEquals(listOf(applied, automatic, manual),
            CandidateColumn.PREFERENCE.rows(list, automatic, applied))
        assertEquals(listOf(applied, automatic, manual),
            CandidateColumn.SCORE.rows(list, automatic, applied))
    }

    @Test
    fun manualRowsStillSortBySelectedColumn() {
        val low = score("low", .20)
        val high = score("high", .90)
        assertEquals(listOf(high, low), CandidateColumn.SCORE.rows(listOf(low, high), null, null))
    }

    private fun score(id: String, value: Double) = CandidateScore(
        candidate = LyricsCandidate(
            source = LyricsSource.QQ,
            remoteId = id,
            title = id,
            artists = listOf("artist"),
            album = "album",
            durationMs = 180_000,
        ),
        score = value,
        titleScore = value,
        artistScore = value,
        albumScore = value,
        durationScore = value,
        versionConflict = false,
    )
}
