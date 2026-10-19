package dev.gaboron.spwlyrics.integration

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.PluginPermissionDeniedException
import com.xuncorp.spw.workshop.api.WorkshopApi
import dev.gaboron.spwlyrics.domain.TrackQuery
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Keeps library IDs only for the current plugin session; cache keys remain recording based. */
internal class SpwLibraryTrackSource {
    private val byId = ConcurrentHashMap<String, MediaItem>()
    @Volatile private var ordered: List<MediaItem>? = null
    @Volatile private var orderedAtNanos = 0L

    fun load(): List<TrackQuery> {
        return allTracks().asSequence()
            .filter { it.readable && it.title.isNotBlank() && (it.artist.isNotBlank() || it.album.isNotBlank()) }
            .map { it.toQuery() }
            .toList()
    }

    fun fromLyricsCallback(item: MediaItem): TrackQuery {
        val metadata = byId[item.id]?.takeIf { it.path == item.path && it.title == item.title }
            ?: lookupCallbackMetadata(item)
        return item.toQuery(durationOverride = item.duration.takeIf { it > 0 } ?: metadata?.duration)
    }

    fun current(): TrackQuery? {
        requirePermission()
        return WorkshopApi.playback.getCurrentMediaItem()?.also { item ->
            if (item.id.isNotBlank()) byId[item.id] = item
        }?.toQuery()
    }

    /** Resolves queue metadata to the canonical library item so cache keys match playback. */
    fun canonicalize(raw: TrackQuery): TrackQuery? {
        val tracks = runCatching { allTracks() }.getOrNull().orEmpty()
        val exact = when {
            raw.trackId.isNotBlank() -> tracks.firstOrNull { it.id == raw.trackId }
            raw.path.isNotBlank() -> tracks.firstOrNull { it.path.equals(raw.path, ignoreCase = true) }
            else -> null
        }
        val matched = exact ?: matchByMetadata(tracks, raw)
        return matched?.toQuery() ?: raw.takeIf { it.path.isNotBlank() && it.title.isNotBlank() }
    }

    /** Library-order fallback used only when the host queue cannot be read. */
    fun nextInLibrary(current: TrackQuery): TrackQuery? {
        val tracks = runCatching { allTracks() }.getOrNull().orEmpty()
        val index = tracks.indexOfFirst { current.trackId.isNotBlank() && it.id == current.trackId }
            .takeIf { it >= 0 }
            ?: tracks.indexOfFirst { current.path.isNotBlank() && it.path.equals(current.path, ignoreCase = true) }
        if (index < 0) return null
        return tracks.asSequence().drop(index + 1)
            .firstOrNull { it.readable && it.title.isNotBlank() }
            ?.toQuery()
    }

    fun clear() {
        byId.clear()
        ordered = null
        orderedAtNanos = 0
    }

    @Synchronized
    private fun allTracks(): List<MediaItem> {
        val now = System.nanoTime()
        ordered?.takeIf { now - orderedAtNanos < LIBRARY_CACHE_NANOS }?.let { return it }
        requirePermission()
        val tracks = try {
            WorkshopApi.library.getAllTracks().toCompletableFuture().join()
        } catch (error: CompletionException) {
            throw error.cause ?: error
        }
        byId.clear()
        tracks.filter { it.id.isNotBlank() }.forEach { byId[it.id] = it }
        ordered = tracks
        orderedAtNanos = now
        return tracks
    }

    private fun matchByMetadata(tracks: List<MediaItem>, raw: TrackQuery): MediaItem? {
        if (raw.title.isBlank()) return null
        val candidates = tracks.filter { item ->
            item.title.equals(raw.title, ignoreCase = true) &&
                (raw.artists.isEmpty() || item.artists().any { artist ->
                    raw.artists.any { it.equals(artist, ignoreCase = true) }
                })
        }
        val duration = raw.durationMs ?: return candidates.singleOrNull()
        return candidates.filter { it.duration <= 0 || abs(it.duration - duration) <= DURATION_TOLERANCE_MS }
            .minByOrNull { abs(it.duration - duration) }
    }

    private fun MediaItem.artists(): List<String> = TrackQuery.splitArtists(artist)

    private fun lookupCallbackMetadata(item: MediaItem): MediaItem? {
        if (item.id.isBlank() || item.duration > 0 ||
            !WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_READ)) return null
        val found = runCatching {
            WorkshopApi.library.getTrackById(item.id).toCompletableFuture().join()
        }.getOrNull()?.takeIf { it.path == item.path && it.title == item.title } ?: return null
        byId[item.id] = found
        return found
    }

    private fun requirePermission() {
        if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_READ)) {
            throw PluginPermissionDeniedException("spw-lyrics", PluginPermission.LIBRARY_READ)
        }
    }

    private fun MediaItem.toQuery(durationOverride: Long? = duration) = TrackQuery(
        title = title,
        artists = TrackQuery.splitArtists(artist),
        album = album,
        albumArtists = TrackQuery.splitArtists(albumArtist),
        path = path,
        durationMs = durationOverride?.takeIf { it > 0 },
        trackId = id,
    )

    private companion object {
        val LIBRARY_CACHE_NANOS = TimeUnit.MINUTES.toNanos(2)
        const val DURATION_TOLERANCE_MS = 5_000L
    }
}
