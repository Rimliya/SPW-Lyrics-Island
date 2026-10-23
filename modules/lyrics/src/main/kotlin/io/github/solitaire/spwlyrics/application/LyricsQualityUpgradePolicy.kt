package io.github.solitaire.spwlyrics.application

import io.github.solitaire.spwlyrics.domain.LyricsDocument
import io.github.solitaire.spwlyrics.domain.LyricsQuality

/** Keeps automatic fallback lyrics replaceable until a karaoke timeline is found. */
internal object LyricsQualityUpgradePolicy {
    fun shouldUpgrade(document: LyricsDocument): Boolean = document.quality != LyricsQuality.KARAOKE_SYNCED
}
