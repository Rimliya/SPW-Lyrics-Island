package io.github.solitaire.spwlyrics.provider

import io.github.solitaire.spwlyrics.domain.LyricsCandidate
import io.github.solitaire.spwlyrics.domain.LyricsDocument
import io.github.solitaire.spwlyrics.domain.LyricsSource
import io.github.solitaire.spwlyrics.domain.TrackQuery

interface LyricsProvider {
    val source: LyricsSource
    fun automaticSearchQueries(query: TrackQuery): List<String> = query.searchQueries()
    fun search(query: TrackQuery, keywords: String, limit: Int = 20): List<LyricsCandidate>
    fun searchManual(query: TrackQuery, keywords: String, limit: Int = 20): List<LyricsCandidate> =
        search(query, keywords, limit)
    fun fetch(candidate: LyricsCandidate): LyricsDocument?
}

class LocalLyricsProvider : LyricsProvider {
    override val source: LyricsSource = LyricsSource.LOCAL
    override fun search(query: TrackQuery, keywords: String, limit: Int): List<LyricsCandidate> = emptyList()
    override fun fetch(candidate: LyricsCandidate): LyricsDocument? = null
}
