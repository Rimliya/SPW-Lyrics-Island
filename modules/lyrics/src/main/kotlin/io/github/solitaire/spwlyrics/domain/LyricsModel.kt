package io.github.solitaire.spwlyrics.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@Serializable
enum class LyricsFormat {
    TTML,
    QRC,
    KRC,
    YRC,
    LRC,
    PLAIN,
}
@Serializable
enum class LyricsQuality(val rank: Int) {
    PLAIN(0),
    LINE_SYNCED(1),
    KARAOKE_SYNCED(2),
}

@Serializable
data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

@Serializable
data class LyricLine(
    val startMs: Long? = null,
    val endMs: Long? = null,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val translation: String? = null,
    val romanization: String? = null,
    val background: Boolean = false,
    val agent: String? = null,
) {
    fun effectiveEndMs(): Long? = endMs ?: words.maxOfOrNull(LyricWord::endMs)
}

@Serializable
data class LyricsDocument(
    val source: LyricsSource,
    val format: LyricsFormat,
    val lines: List<LyricLine>,
    val metadata: Map<String, List<String>> = emptyMap(),
    val rawTtml: String? = null,
) {
    /** Computed once for this immutable local document, outside the per-frame rendering path. */
    internal val referenceFingerprint: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(this).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    val isInstrumental: Boolean
        get() = InstrumentalLyricsClassifier.matches(lines)

    val quality: LyricsQuality
        get() = LyricsGranularityClassifier.classify(lines)
}
