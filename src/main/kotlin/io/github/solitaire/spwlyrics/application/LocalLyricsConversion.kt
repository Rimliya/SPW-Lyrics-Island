package io.github.solitaire.spwlyrics.application

import io.github.solitaire.spwlyrics.codec.LrcCodec
import io.github.solitaire.spwlyrics.domain.LyricsDocument
import io.github.solitaire.spwlyrics.domain.LyricsSource

/** Convert TTML and explicit instrumental notices; ordinary local LRC stays with SPW. */
internal class LocalLyricsConversion(private val ttml: LocalTtmlConversion = LocalTtmlConversion()) {
    fun parse(raw: String): LyricsDocument? = ttml.parse(raw) ?: runCatching {
        LrcCodec.parse(raw, LyricsSource.LOCAL).takeIf { it.isInstrumental }
    }.getOrNull()
}
