package io.github.solitaire.spwlyrics.provider

import io.github.solitaire.spwlyrics.domain.LyricsCandidate
import io.github.solitaire.spwlyrics.domain.LyricsDocument
import io.github.solitaire.spwlyrics.domain.LyricsSource
import io.github.solitaire.spwlyrics.domain.TrackQuery
import io.github.solitaire.spwlyrics.domain.ProviderSearchStatus
import io.github.solitaire.spwlyrics.domain.ProviderSearchPhase

interface LyricsProvider {
    val source: LyricsSource
    /** Local configuration only; this check must never wait for network work. */
    fun configurationStatus(): ProviderSearchStatus? = null
    fun automaticSearchQueries(query: TrackQuery): List<String> = query.searchQueries()
    fun search(query: TrackQuery, keywords: String, limit: Int = 20): List<LyricsCandidate>
    fun searchManual(query: TrackQuery, keywords: String, limit: Int = 20): List<LyricsCandidate> =
        search(query, keywords, limit)
    fun searchManual(query: TrackQuery, keywords: String, limit: Int,
        onStatus: (ProviderSearchStatus) -> Unit): List<LyricsCandidate> =
        searchManual(query, keywords, limit).also {
            onStatus(ProviderSearchStatus(source, ProviderSearchPhase.AVAILABLE,
                if (it.isEmpty()) "请求成功，没有匹配候选。" else "请求成功，找到 ${it.size} 个候选。"))
        }
    fun fetch(candidate: LyricsCandidate): LyricsDocument?
    fun metadata(candidate: LyricsCandidate): LyricsCandidate = candidate
}

class LocalLyricsProvider : LyricsProvider {
    override val source: LyricsSource = LyricsSource.LOCAL
    override fun search(query: TrackQuery, keywords: String, limit: Int): List<LyricsCandidate> = emptyList()
    override fun fetch(candidate: LyricsCandidate): LyricsDocument? = null
}
