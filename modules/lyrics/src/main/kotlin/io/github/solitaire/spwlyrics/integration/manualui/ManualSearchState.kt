package io.github.solitaire.spwlyrics.integration.manualui

import io.github.solitaire.spwlyrics.domain.CandidateScore
import io.github.solitaire.spwlyrics.domain.LyricsDocument
import io.github.solitaire.spwlyrics.domain.LyricsSource
import io.github.solitaire.spwlyrics.domain.TrackQuery
import io.github.solitaire.spwlyrics.storage.LyricsDelaySettings

internal data class ManualSearchState(
    val query: TrackQuery? = null,
    val keywords: String = "",
    val source: LyricsSource? = null,
    val candidates: List<CandidateScore> = emptyList(),
    val selected: CandidateScore? = null,
    val document: LyricsDocument? = null,
    val previewMainAgent: String = "v1",
    val delayText: String = "0",
    val savedDelay: Int = 0,
    val readingTrack: Boolean = true,
    val searching: Boolean = false,
    val previewing: Boolean = false,
    val mutating: Boolean = false,
    val searched: Boolean = false,
    val status: String = "正在读取当前歌曲…",
    val error: Boolean = false,
) {
    val delayValue: Int? get() = delayText.toIntOrNull()?.takeIf {
        it in LyricsDelaySettings.MIN_MS..LyricsDelaySettings.MAX_MS
    }
    val busy: Boolean get() = readingTrack || searching || previewing || mutating
    val canSearch: Boolean get() = query != null && !readingTrack && !searching && !mutating
    val canMutate: Boolean get() = query != null && !busy
}
