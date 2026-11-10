package io.github.solitaire.spwlyrics.integration

import io.github.solitaire.spwlyrics.application.LocalLyricsConversion
import io.github.solitaire.spwlyrics.domain.LyricsDocument
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import org.jaudiotagger.audio.AudioFileIO
import io.github.solitaire.spwlyrics.storage.EmbeddedLyricsTags
import io.github.solitaire.spwlyrics.codec.MetadataLyricsImport
import io.github.solitaire.spwlyrics.domain.LyricsFormat
import io.github.solitaire.spwlyrics.domain.LyricsSource

/** Reads local content in the playback callback without involving online providers. */
internal class LocalTtmlLyricsLoader(
    private val conversion: LocalLyricsConversion = LocalLyricsConversion(),
    private val readEmbedded: (Path) -> String? = ::readEmbeddedLyrics,
) {
    private data class Stamp(val size: Long, val modified: FileTime)
    private class Snapshot(val stamps: List<Stamp?>, val document: LyricsDocument?, val raw: String?) {
        val reference: LyricsDocument? by lazy {
            document ?: runCatching { MetadataLyricsImport.preview(raw.orEmpty()) }.getOrNull()
        }
    }
    private data class Key(val audio: Path, val embeddedOnly: Boolean)
    private val snapshots = object : LinkedHashMap<Key, Snapshot>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Snapshot>?): Boolean = size > 64
    }

    fun load(audioPath: String): LyricsDocument? = snapshot(audioPath, embeddedOnly = false)?.document

    fun loadReference(audioPath: String): LyricsDocument? = snapshot(audioPath, embeddedOnly = false)?.reference

    fun loadEmbedded(audioPath: String): LyricsDocument? = snapshot(audioPath, embeddedOnly = true)?.document

    fun invalidate(audioPath: String) {
        val audio = localMediaPath(audioPath) ?: return
        synchronized(snapshots) { snapshots.keys.removeAll { it.audio == audio } }
    }

    private fun snapshot(audioPath: String, embeddedOnly: Boolean): Snapshot? = runCatching {
        val audio = localMediaPath(audioPath) ?: return null
        val key = Key(audio, embeddedOnly)
        val stem = audio.fileName.toString().substringBeforeLast('.', audio.fileName.toString())
        val lrc = audio.resolveSibling("$stem.lrc")
        val ttml = audio.resolveSibling("$stem.ttml")
        val stamps = listOf(audio, lrc, ttml).map(::stamp)
        if (stamps.first() == null) return null
        synchronized(snapshots) { snapshots[key]?.takeIf { it.stamps == stamps }?.let { return it } }
        val raw = when {
            embeddedOnly -> readEmbedded(audio)
            stamps[1] != null -> readSidecar(lrc)
            else -> readEmbedded(audio) ?: readSidecar(ttml)
        }
        val document = if (embeddedOnly) MetadataLyricsImport.preview(raw.orEmpty())
            ?: LyricsDocument(LyricsSource.LOCAL, LyricsFormat.PLAIN, emptyList())
        else raw?.let(conversion::parse)
        val result = Snapshot(stamps, document, raw)
        synchronized(snapshots) { snapshots[key] = result }
        result
    }.getOrNull()

    private fun stamp(path: Path): Stamp? = runCatching {
        Files.readAttributes(path, BasicFileAttributes::class.java).takeIf { it.isRegularFile }
            ?.let { Stamp(it.size(), it.lastModifiedTime()) }
    }.getOrNull()

    private fun readSidecar(path: Path): String? = runCatching {
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_SIDECAR_BYTES) null
        else Files.readString(path)
    }.getOrNull()

    private companion object {
        const val MAX_SIDECAR_BYTES = 4_000_000L
    }
}

private fun readEmbeddedLyrics(audio: Path): String? = runCatching {
    val tag = AudioFileIO.read(audio.toFile()).tag ?: return null
    EmbeddedLyricsTags.read(tag).takeIf(String::isNotBlank)
}.getOrNull()
