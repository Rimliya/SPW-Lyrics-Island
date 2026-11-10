package io.github.solitaire.spwlyrics.storage

import io.github.solitaire.spwlyrics.domain.LyricsDocument
import io.github.solitaire.spwlyrics.domain.LyricsSimilarity
import kotlinx.serialization.Serializable

@Serializable
data class LyricsCacheValidation(
    val referenceHash: String,
    val similarity: String,
    val version: Int = 1,
) {
    companion object {
        fun current(reference: LyricsDocument?, level: LyricsSimilarity.Level) =
            LyricsCacheValidation(reference?.referenceFingerprint.orEmpty(), level.configValue)
    }
}
