package io.github.solitaire.spwlyrics.application

import io.github.solitaire.spwlyrics.domain.CandidateScore
import io.github.solitaire.spwlyrics.domain.LyricsSource
import io.github.solitaire.spwlyrics.domain.MatchEngine

/** Keeps safety checks while allowing AMLL TTML matches some metadata tolerance. */
internal object PreferredSourceMatchPolicy {
    fun accepts(candidate: CandidateScore): Boolean {
        if (candidate.passesAutomaticGate) return true
        if (candidate.versionConflict || candidate.artistConflict) return false
        if (candidate.durationScore != null && candidate.durationScore < MatchEngine.MIN_DURATION) return false
        return when (candidate.candidate.source) {
            LyricsSource.AMLL -> candidate.titleScore >= 0.76 &&
                candidate.artistScore != null && candidate.artistScore >= 0.68 && candidate.score >= 0.72
            else -> false
        }
    }
}
