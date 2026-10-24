package io.github.solitaire.spwlyrics.domain

/**
 * Recognizes local placeholder lyrics such as "纯音乐，请欣赏". A track with only these lines
 * is already fully described by its local/embedded lyrics, so online providers add no value.
 */
object InstrumentalLyrics {
    private const val MAX_PLACEHOLDER_LINES = 12
    private val exact = setOf(
        "纯音乐", "请欣赏", "敬请欣赏", "纯音乐请欣赏", "纯音乐敬请欣赏",
        "此曲为纯音乐", "此曲为纯音乐请欣赏", "本曲为纯音乐", "本曲为纯音乐请欣赏",
        "该曲为纯音乐", "该曲为纯音乐请欣赏", "纯音乐无人声", "纯音乐请欣赏无人声",
        "instrumental", "instrumentalmusic", "instrumentalversion", "instrumentalonly",
        "pleaseenjoy", "enjoythemusic",
    )
    private val pattern = Regex(
        "^(?:此曲为|本曲为|该曲为)?(?:纯音乐|instrumental(?:music|version)?)(?:请欣赏|敬请欣赏|pleaseenjoy)?$",
        RegexOption.IGNORE_CASE,
    )
    private val metadataLine = Regex("^\\[[A-Za-z][\\w-]*:.*]$")
    private val INSTRUMENTAL_TITLES = setOf("inst", "inst.", "instrumental", "instrumental version", "off vocal", "karaoke", "伴奏", "纯音乐")
    private val INSTRUMENTAL_TITLE_MARKER = Regex(
        """(?i)(?:[\(（\[\【]\s*(?:inst\.?|instrumental(?:\s+version)?|off[\s-]*vocal|karaoke|伴奏|纯音乐)\s*[\)\）\]\】]|(?:^|[\s\-–—])(?:inst\.?|off[\s-]*vocal|伴奏|纯音乐)(?:$|[\s\-–—]))""",
    )
    private val markers = Regex("\\[[^\\]]*]|<[^>]*>")

    /** Detects explicit title markers such as `Song (inst.)`, `[Instrumental]` or `- Off Vocal`. */
    fun isInstrumentalTitle(title: String): Boolean {
        val text = title.trim().lowercase()
        if (text.isEmpty()) return false
        return text in INSTRUMENTAL_TITLES || INSTRUMENTAL_TITLE_MARKER.containsMatchIn(text)
    }
    fun isPlaceholder(document: LyricsDocument?): Boolean {
        if (document == null) return false
        val values = document.lines.asSequence()
            .filterNot { it.background }
            .flatMap { line ->
                sequenceOf(
                    line.text.ifBlank { line.words.joinToString(separator = "") { word -> word.text } },
                    line.translation.orEmpty(),
                    line.romanization.orEmpty(),
                )
            }
            .map(TextNormalizer::compact)
            .filter(String::isNotBlank)
            .toList()
        return values.isNotEmpty() && values.size <= MAX_PLACEHOLDER_LINES && values.all(::matches)
    }

    /** Detects the placeholder before a non-TTML sidecar or embedded payload is parsed. */
    fun isPlaceholderRaw(raw: String): Boolean {
        val values = raw.lineSequence()
            .map { it.trim().removePrefix("\uFEFF") }
            .filter { it.isNotBlank() && !metadataLine.matches(it) }
            .map { markers.replace(it, " ") }
            .map(TextNormalizer::compact)
            .filter(String::isNotBlank)
            .toList()
        return values.isNotEmpty() && values.size <= MAX_PLACEHOLDER_LINES && values.all(::matches)
    }

    private fun matches(value: String): Boolean = value in exact || pattern.matches(value)
}
