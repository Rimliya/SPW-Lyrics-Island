package io.github.solitaire.spwlyrics.storage

import io.github.solitaire.spwlyrics.codec.SpwLyricsEncoder
import io.github.solitaire.spwlyrics.domain.LyricsCandidate
import io.github.solitaire.spwlyrics.domain.LyricsDocument
import io.github.solitaire.spwlyrics.domain.LyricsFormat
import io.github.solitaire.spwlyrics.domain.LyricsSource
import io.github.solitaire.spwlyrics.domain.TrackQuery
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CachedLyrics(
    val document: LyricsDocument,
    val encoded: String? = null,
    val savedAtEpochMs: Long,
    val modelVersion: Int = LYRICS_CACHE_MODEL_VERSION,
    val encoderVersion: Int = SpwLyricsEncoder.VERSION,
    val selectionKey: String = "",
)

@Serializable
data class ManualOverride(
    val local: Boolean,
    val source: LyricsSource? = null,
    val candidate: LyricsCandidate? = null,
    val modelVersion: Int = MANUAL_OVERRIDE_MODEL_VERSION,
)

const val LYRICS_CACHE_MODEL_VERSION = 6
const val MANUAL_OVERRIDE_MODEL_VERSION = 2
const val DEFAULT_LYRICS_CACHE_BYTES = 128L * 1024 * 1024

data class CacheMaintenanceResult(
    val beforeBytes: Long,
    val afterBytes: Long,
    val deletedFiles: Int,
) {
    val removedBytes: Long get() = (beforeBytes - afterBytes).coerceAtLeast(0)
}

interface LyricsCache {
    fun getLyrics(query: TrackQuery): CachedLyrics?
    /**
     * Fast presence check for batch listings. Implementations may skip decoding cached JSON and
     * therefore only report a likely hit; normal playback still validates the entry before use.
     */
    fun hasLyrics(query: TrackQuery): Boolean = getLyrics(query) != null
    fun putLyrics(query: TrackQuery, lyrics: CachedLyrics)
    fun removeLyrics(query: TrackQuery)
    fun getOverride(query: TrackQuery): ManualOverride?
    fun putOverride(query: TrackQuery, override: ManualOverride)
    fun removeOverride(query: TrackQuery)
}

class FileLyricsCache(
    private val root: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val successTtl: Duration = Duration.ofDays(30),
) : LyricsCache {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val layout = ReadableCacheLayout(root)
    private val memory = object : LinkedHashMap<String, CachedLyrics>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLyrics>?): Boolean = size > 64
    }
    private val overrides = object : LinkedHashMap<OverrideKey, ManualOverride?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<OverrideKey, ManualOverride?>?): Boolean =
            size > 64
    }
    private val trackLocks = Array(32) { Any() }
    private val maintenanceLock = Any()

    init {
        Files.createDirectories(root)
    }

    override fun getLyrics(query: TrackQuery): CachedLyrics? {
        val key = query.key
        synchronized(memory) { memory[key]?.takeIf(::validLyrics)?.let { return it } }
        return synchronized(trackLock(key)) {
            synchronized(memory) { memory[key]?.takeIf(::validLyrics)?.let { return it } }
            val stored = read(layout.lyrics(query))?.let { runCatching { json.decodeFromString<CachedLyrics>(it) }.getOrNull() }
                ?.takeIf(::validLyrics)
                ?.let { if (it.encoded == null) it.copy(encoded = SpwLyricsEncoder.encode(it.document)) else it }
            stored?.let { synchronized(memory) { memory[key] = it } }
            stored
        }
    }

    override fun hasLyrics(query: TrackQuery): Boolean {
        synchronized(memory) { memory[query.key]?.takeIf(::validLyrics)?.let { return true } }
        val modified = runCatching { Files.getLastModifiedTime(layout.lyrics(query)).toMillis() }.getOrNull()
            ?: return false
        return !expired(modified, successTtl)
    }

    override fun putLyrics(query: TrackQuery, lyrics: CachedLyrics) {
        val key = query.key
        synchronized(trackLock(key)) {
            synchronized(memory) { memory[key] = lyrics }
            write(layout.lyrics(query), json.encodeToString(lyrics))
        }
    }

    override fun removeLyrics(query: TrackQuery) {
        val key = query.key
        synchronized(trackLock(key)) {
            synchronized(memory) { memory.remove(key) }
            Files.deleteIfExists(layout.lyrics(query))
        }
    }

    override fun getOverride(query: TrackQuery): ManualOverride? {
        val path = layout.override(query)
        val key = OverrideKey(query.key, stamp(path))
        synchronized(overrides) { if (overrides.containsKey(key)) return overrides[key] }
        return synchronized(trackLock(query.key)) {
            synchronized(overrides) { if (overrides.containsKey(key)) return overrides[key] }
            val stored = read(path)
                ?.let { runCatching { json.decodeFromString<ManualOverride>(it) }.getOrNull() }
                ?.takeIf { it.modelVersion == MANUAL_OVERRIDE_MODEL_VERSION }
            synchronized(overrides) { overrides[key] = stored }
            stored
        }
    }

    override fun putOverride(query: TrackQuery, override: ManualOverride) {
        val key = query.key
        synchronized(trackLock(key)) {
            write(layout.override(query), json.encodeToString(override))
            invalidateOverrides(query)
            synchronized(memory) { memory.remove(key) }
        }
    }

    override fun removeOverride(query: TrackQuery) {
        val key = query.key
        synchronized(trackLock(key)) {
            Files.deleteIfExists(layout.override(query))
            invalidateOverrides(query)
            synchronized(memory) { memory.remove(key) }
        }
    }

    fun prune(maxBytes: Long = DEFAULT_LYRICS_CACHE_BYTES, protect: TrackQuery? = null): CacheMaintenanceResult =
        maintain(maxBytes, protect, clearAll = false)

    fun clearCachedLyrics(): CacheMaintenanceResult =
        maintain(0L, protect = null, clearAll = true)

    private fun maintain(maxBytes: Long, protect: TrackQuery?, clearAll: Boolean): CacheMaintenanceResult =
        synchronized(maintenanceLock) {
            val protectedPath = protect?.let { layout.lyrics(it).toAbsolutePath().normalize() }
            val files = runCatching {
                Files.list(layout.lyricsDirectory).use { paths ->
                    paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json", true) }
                        .map { path ->
                            val size = runCatching { Files.size(path) }.getOrDefault(0L)
                            val modified = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
                            CacheFile(path.toAbsolutePath().normalize(), size, modified)
                        }
                        .toList()
                }
            }.getOrDefault(emptyList())
            val before = files.sumOf(CacheFile::bytes)
            var remaining = before
            val target = if (clearAll) 0L else (maxBytes * 9 / 10).coerceAtLeast(0L)
            var deleted = 0
            for (file in files.sortedBy(CacheFile::lastModified)) {
                if (!clearAll && remaining <= target) break
                if (file.path == protectedPath) continue
                if (!clearAll && Files.isRegularFile(layout.overridesDirectory.resolve(file.path.fileName))) continue
                if (runCatching { Files.deleteIfExists(file.path) }.getOrDefault(false)) {
                    remaining -= file.bytes
                    deleted++
                }
            }
            if (deleted > 0) synchronized(memory) { memory.clear() }
            CacheMaintenanceResult(before, remaining.coerceAtLeast(0), deleted)
        }

    private data class CacheFile(val path: Path, val bytes: Long, val lastModified: Long)
    private fun invalidateOverrides(query: TrackQuery) = synchronized(overrides) {
        val iterator = overrides.keys.iterator()
        while (iterator.hasNext()) if (iterator.next().query == query.key) iterator.remove()
    }

    private fun stamp(path: Path): FileStamp? = runCatching {
        Files.readAttributes(path, BasicFileAttributes::class.java)
            .takeIf { it.isRegularFile }
            ?.let { FileStamp(it.size(), it.lastModifiedTime().toMillis()) }
    }.getOrNull()

    private fun trackLock(key: String): Any = trackLocks[(key.hashCode() and Int.MAX_VALUE) % trackLocks.size]

    private fun validLyrics(value: CachedLyrics): Boolean =
        value.modelVersion == LYRICS_CACHE_MODEL_VERSION && value.encoderVersion == SpwLyricsEncoder.VERSION &&
            (value.document.format != LyricsFormat.TTML || value.document.rawTtml != null) &&
            !expired(value.savedAtEpochMs, successTtl)

    private fun expired(epochMs: Long, ttl: Duration): Boolean =
        Instant.ofEpochMilli(epochMs).plus(ttl).isBefore(clock.instant())

    private fun read(path: Path): String? = if (Files.isRegularFile(path)) {
        runCatching { Files.readString(path, StandardCharsets.UTF_8) }.getOrNull()
    } else {
        null
    }

    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
        Files.writeString(temporary, content, StandardCharsets.UTF_8)
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private data class FileStamp(val size: Long, val modifiedMs: Long)
    private data class OverrideKey(val query: String, val stamp: FileStamp?)

}
