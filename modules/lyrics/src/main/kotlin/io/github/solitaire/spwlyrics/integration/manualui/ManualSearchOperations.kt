package io.github.solitaire.spwlyrics.integration.manualui

import io.github.solitaire.spwlyrics.application.ResolvedLyrics
import io.github.solitaire.spwlyrics.domain.CandidateScore
import io.github.solitaire.spwlyrics.domain.LyricsCandidate
import io.github.solitaire.spwlyrics.domain.LyricsSource
import io.github.solitaire.spwlyrics.domain.LyricsDocument
import io.github.solitaire.spwlyrics.domain.TrackQuery

/** Keeps the window independent of host APIs, cache storage, and matching rules. */
internal interface ManualSearchOperations {
    fun currentQuery(): TrackQuery?
    fun search(query: TrackQuery, keywords: String, source: LyricsSource?): List<CandidateScore>
    fun preview(candidate: LyricsCandidate): ResolvedLyrics?
    fun apply(query: TrackQuery, candidate: LyricsCandidate): Boolean
    fun useLocal(query: TrackQuery): Boolean
    fun useAutomatic(query: TrackQuery): Boolean
    fun delay(query: TrackQuery): Int
    fun setDelay(query: TrackQuery, delayMs: Int): Boolean
    fun editMetadata(query: TrackQuery, document: LyricsDocument)
}
