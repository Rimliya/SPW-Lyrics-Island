package io.github.solitaire.spwlyrics.application

import io.github.solitaire.spwlyrics.domain.LyricsDocument
import io.github.solitaire.spwlyrics.domain.LyricsPreferences

/** Uses parsed timing granularity and the user's selection order. */
internal object LyricsSelectionPolicy {
    fun select(candidates: List<FetchedLyrics>, preferences: LyricsPreferences): FetchedLyrics? = candidates.filter {
        it.document.quality in preferences.qualities
    }.minWithOrNull(
        Comparator { left, right -> preferences.compare(left.candidate.source, left.document.quality, right.candidate.source, right.document.quality) },
    )

    fun canReplace(current: LyricsDocument, replacement: LyricsDocument, preferences: LyricsPreferences): Boolean {
        // Source order may choose a preferred provider, but it must never downgrade timing granularity.
        if (replacement.quality.rank < current.quality.rank) return false
        return preferences.compare(replacement.source, replacement.quality, current.source, current.quality) <= 0
    }
}
