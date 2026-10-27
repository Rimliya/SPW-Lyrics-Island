package io.github.solitaire.spwlyrics.provider

import io.github.solitaire.spwlyrics.codec.LyricsContentFilter
import io.github.solitaire.spwlyrics.codec.TtmlCodec
import io.github.solitaire.spwlyrics.domain.LyricsCandidate
import io.github.solitaire.spwlyrics.domain.LyricsDocument
import io.github.solitaire.spwlyrics.domain.LyricsSource
import io.github.solitaire.spwlyrics.domain.TrackQuery
import io.github.solitaire.spwlyrics.domain.ProviderSearchStatus
import java.nio.file.Path
import java.time.Clock

class AmllProvider(
    cacheDirectory: Path,
    private val http: ProviderHttp,
    private val clock: Clock = Clock.systemUTC(),
    legacyIndexPath: Path? = null,
) : LyricsProvider, AutoCloseable {
    override val source: LyricsSource = LyricsSource.AMLL
    private val index = AmllIndexStore(cacheDirectory, http, clock, legacyIndexPath)

    override fun search(query: TrackQuery, keywords: String, limit: Int): List<LyricsCandidate> =
        index.search(query, keywords, limit)

    override fun searchManual(query: TrackQuery, keywords: String, limit: Int): List<LyricsCandidate> =
        index.search(query, keywords, limit, manual = true)

    override fun searchManual(query: TrackQuery, keywords: String, limit: Int,
        onStatus: (ProviderSearchStatus) -> Unit): List<LyricsCandidate> = index.searchManual(query, keywords, limit, onStatus)

    fun clearIndex(): Boolean = index.clearIndex()

    fun enforceIndexLimit(maxBytes: Long): Boolean = index.enforceIndexLimit(maxBytes)

    override fun close() = index.close()

    override fun fetch(candidate: LyricsCandidate): LyricsDocument? = runCatching {
        val url = candidate.context["url"] ?: "$RAW_BASE/${candidate.remoteId}.ttml"
        LyricsContentFilter.clean(TtmlCodec().parse(http.get(url), source), candidate)
    }.getOrNull()

    companion object {
        const val INDEX_URL = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/am-lyrics/index.jsonl"
        const val RAW_BASE = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/am-lyrics"
    }
}
