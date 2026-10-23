package io.github.solitaire.spwlyrics.domain

import kotlin.math.abs

/**
 * Compares two lyric documents by their text instead of their metadata, so a cover,
 * a live version or a same-named track cannot replace the lyrics being displayed.
 */
object LyricsSimilarity {
    /** Below this many usable lines the local text is too short to judge another document. */
    const val MIN_REFERENCE_LINES = 5
    const val MIN_LINE_CHARACTERS = 4
    const val LINE_MATCH = 0.62

    /** Rejection floor, configurable from the plugin settings. */
    enum class Level(val configValue: String, val rejectBelow: Double) {
        STRICT("strict", 0.40),
        STANDARD("standard", 0.25),
        OFF("off", 0.0),
        ;

        companion object {
            fun fromConfigValue(value: String): Level =
                entries.firstOrNull { it.configValue == value } ?: STANDARD
        }
    }

    data class Result(val coverage: Double, val comparable: Boolean)

    /** Text prepared once and shared by every provider tried during one resolve. */
    class Reference internal constructor(
        internal val lines: List<TextNormalizer.PreparedText>,
        internal val cjkShare: Double,
        internal val comparable: Boolean,
    )

    fun prepare(document: LyricsDocument?): Reference? {
        if (document == null) return null
        val lines = usableLines(document)
        val comparable = lines.size >= MIN_REFERENCE_LINES
        return Reference(
            lines = if (comparable) lines.map(TextNormalizer::prepare) else emptyList(),
            cjkShare = cjkShare(lines),
            comparable = comparable,
        )
    }

    fun compare(reference: Reference, candidate: LyricsDocument): Result {
        val candidateLines = usableLines(candidate)
        if (!reference.comparable || candidateLines.size < MIN_REFERENCE_LINES) {
            return Result(1.0, comparable = false)
        }
        if (!sameScript(reference.cjkShare, cjkShare(candidateLines))) return Result(1.0, comparable = false)
        val candidatePrepared = candidateLines.map(TextNormalizer::prepare)
        return Result(
            maxOf(coverage(reference.lines, candidatePrepared), coverage(candidatePrepared, reference.lines)),
            comparable = true,
        )
    }

    fun compare(reference: LyricsDocument, candidate: LyricsDocument): Result {
        val prepared = prepare(reference) ?: return Result(1.0, comparable = false)
        return compare(prepared, candidate)
    }

    /** A null reference means the track has no local lyrics to compare against. */
    fun accepts(
        reference: Reference?,
        candidate: LyricsDocument,
        level: Level = Level.STANDARD,
    ): Boolean {
        if (level == Level.OFF || reference == null) return true
        return compare(reference, candidate).let { !it.comparable || it.coverage >= level.rejectBelow }
    }

    fun accepts(
        reference: LyricsDocument?,
        candidate: LyricsDocument,
        level: Level = Level.STANDARD,
    ): Boolean = accepts(prepare(reference), candidate, level)

    private fun usableLines(document: LyricsDocument): List<String> = document.lines.asSequence()
        .filterNot { it.background }
        .map(::lineText)
        .filter { line -> line.count(Char::isLetterOrDigit) >= MIN_LINE_CHARACTERS }
        .toList()

    /** Karaoke JSON may leave the line text empty and only fill the word list. */
    private fun lineText(line: LyricLine): String {
        val text = line.text.trim()
        if (text.isNotEmpty()) return text
        return line.words.joinToString(separator = "") { it.text }.trim()
    }

    private fun coverage(
        source: List<TextNormalizer.PreparedText>,
        target: List<TextNormalizer.PreparedText>,
    ): Double = source.count { prepared ->
        target.any { other -> TextNormalizer.similarityAtLeast(prepared, other, LINE_MATCH) }
    }.toDouble() / source.size

    /** Translations, romaji and the original script cannot be compared line by line. */
    private fun sameScript(left: Double, right: Double): Boolean = abs(left - right) <= 0.45

    private fun cjkShare(lines: List<String>): Double {
        var cjk = 0
        var letters = 0
        lines.forEach { line ->
            line.forEach { character ->
                if (character.isLetter()) {
                    letters++
                    if (character.isCjk()) cjk++
                }
            }
        }
        return if (letters == 0) 0.0 else cjk.toDouble() / letters
    }

    private fun Char.isCjk(): Boolean =
        this in '\u3040'..'\u30ff' || this in '\u3400'..'\u4dbf' ||
            this in '\u4e00'..'\u9fff' || this in '\uac00'..'\ud7af' || this in '\uff66'..'\uff9f'
}