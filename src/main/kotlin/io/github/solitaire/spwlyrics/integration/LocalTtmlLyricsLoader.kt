package io.github.solitaire.spwlyrics.integration

import io.github.solitaire.spwlyrics.application.LocalTtmlConversion
import io.github.solitaire.spwlyrics.codec.LrcCodec
import io.github.solitaire.spwlyrics.domain.LyricsDocument
import io.github.solitaire.spwlyrics.domain.InstrumentalLyrics
import io.github.solitaire.spwlyrics.domain.LyricsSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.LinkedHashMap
import org.jaudiotagger.audio.AudioFileIO
import io.github.solitaire.spwlyrics.storage.EmbeddedLyricsTags

/** Reads local content in the playback callback without involving online providers. */
internal class LocalTtmlLyricsLoader(
    private val conversion: LocalTtmlConversion = LocalTtmlConversion(),
) {
    private val cache = object : LinkedHashMap<CacheKey, LyricsDocument?>(MAX_CACHE_ENTRIES, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, LyricsDocument?>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    fun load(audioPath: String): LyricsDocument? = runCatching {
        val audio = Path.of(audioPath).toAbsolutePath().normalize()
        val name = audio.fileName?.toString() ?: return null
        val stem = name.substringBeforeLast('.', name)
        val lrc = audio.resolveSibling("$stem.lrc")
        val ttml = audio.resolveSibling("$stem.ttml")
        val key = CacheKey(audio, stamp(audio), stamp(lrc), stamp(ttml))
        synchronized(cache) {
            if (cache.containsKey(key)) return cache[key]
        }
        val document = read(audio, lrc, ttml)
        synchronized(cache) { cache[key] = document }
        document
    }.getOrNull()

    private fun read(audio: Path, lrc: Path, ttml: Path): LyricsDocument? {
        if (!Files.isRegularFile(audio)) return null
        if (Files.isRegularFile(lrc)) return readSidecar(lrc)?.let(::parseLocal)

        val embedded = readEmbedded(audio)
        if (embedded != null) return parseLocal(embedded)

        return readSidecar(ttml)?.let(::parseLocal)
    }

    private fun parseLocal(raw: String): LyricsDocument? {
        conversion.parse(raw)?.let { return it }
        // Normal LRC remains the host's responsibility; only placeholder payloads are parsed here
        // so the plugin can stop before starting a pointless online lookup.
        if (!InstrumentalLyrics.isPlaceholderRaw(raw)) return null
        return runCatching { LrcCodec.parse(raw, LyricsSource.LOCAL) }.getOrNull()
            ?.takeIf { document -> document.lines.any { it.text.isNotBlank() } }
    }

    private fun stamp(path: Path): FileStamp? = runCatching {
        Files.readAttributes(path, BasicFileAttributes::class.java)
            .takeIf { it.isRegularFile }
            ?.let { FileStamp(it.size(), it.lastModifiedTime().toMillis()) }
    }.getOrNull()

    private fun readSidecar(path: Path): String? = runCatching {
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_SIDECAR_BYTES) null
        else Files.readString(path)
    }.getOrNull()

    private fun readEmbedded(audio: Path): String? = runCatching {
        val tag = AudioFileIO.read(audio.toFile()).tag ?: return null
        EmbeddedLyricsTags.read(tag).takeIf(String::isNotBlank)
    }.getOrNull()

    private companion object {
        const val MAX_CACHE_ENTRIES = 8
        const val MAX_SIDECAR_BYTES = 4_000_000L
    }

    private data class FileStamp(val size: Long, val modifiedMs: Long)
    private data class CacheKey(
        val audio: Path,
        val audioStamp: FileStamp?,
        val lrcStamp: FileStamp?,
        val ttmlStamp: FileStamp?,
    )
}
