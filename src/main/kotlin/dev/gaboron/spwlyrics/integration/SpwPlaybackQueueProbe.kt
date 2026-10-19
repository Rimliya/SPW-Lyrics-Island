package dev.gaboron.spwlyrics.integration

import com.xuncorp.spw.workshop.api.WorkshopApi
import dev.gaboron.spwlyrics.domain.TrackQuery
import java.lang.reflect.Modifier
import java.net.URI
import java.nio.file.Path

/**
 * Best-effort read of the host play queue.
 *
 * Workshop API only exposes the current media item. This probe walks the host playback service
 * for the underlying media player. Any host change degrades to [QueueNext.available] = false and
 * the caller falls back to library order.
 */
internal class SpwPlaybackQueueProbe(
    private val roots: () -> List<Any> = {
        runCatching { listOf(WorkshopApi.instance, WorkshopApi.playback) }.getOrDefault(emptyList())
    },
) {
    @Volatile private var cachedPlayer: Any? = null

    fun next(): QueueNext {
        val player = player() ?: return QueueNext(false, null)
        val index = readInt(player, "getCurrentMediaItemIndex")
            ?: readInt(player, "getCurrentWindowIndex")
            ?: return QueueNext(false, null)
        val count = readInt(player, "getMediaItemCount") ?: return QueueNext(false, null)
        if (count <= 0) return QueueNext(true, null)
        var nextIndex = index + 1
        if (nextIndex >= count) {
            if (!repeatAll(player)) return QueueNext(true, null)
            nextIndex = 0
        }
        if (nextIndex == index) return QueueNext(true, null)
        return QueueNext(true, invoke(player, "getMediaItemAt", nextIndex)?.toQuery())
    }

    private fun player(): Any? {
        cachedPlayer?.let { if (isQueuePlayer(it)) return it; cachedPlayer = null }
        val found = playbackService()?.let { findPlayer(it) }
            ?: ObjectGraphProbe.find(
                roots = roots(),
                maxDepth = 6,
                matches = ::isQueuePlayer,
                mayTraverse = ::mayTraverse,
            )
        if (found != null) cachedPlayer = found
        return found
    }

    private fun playbackService(): Any? = runCatching {
        val controller = Class.forName(PLAYBACK_CONTROLLER)
        val field = controller.declaredFields.firstOrNull { candidate ->
            Modifier.isStatic(candidate.modifiers) && candidate.type.name == PLAYBACK_SERVICE
        } ?: return@runCatching null
        field.trySetAccessible()
        field.get(null)
    }.getOrNull()

    private fun findPlayer(service: Any): Any? = ObjectGraphProbe.find(
        roots = listOf(service),
        maxDepth = 4,
        matches = ::isQueuePlayer,
        mayTraverse = ::mayTraverse,
    )

    private fun isQueuePlayer(value: Any): Boolean {
        if (!mayTraverse(value.javaClass)) return false
        val methods = value.javaClass.methods
        val itemAt = methods.any { it.name == "getMediaItemAt" && it.parameterCount == 1 }
        val index = methods.any {
            it.name == "getCurrentMediaItemIndex" || it.name == "getCurrentWindowIndex"
        }
        return itemAt && index
    }

    private fun mayTraverse(type: Class<*>): Boolean =
        type.name.startsWith("com.xuncorp.") ||
            type.name.startsWith("androidx.media3.") ||
            type.name.startsWith("com.google.android.exoplayer2.")

    private fun readInt(target: Any, name: String): Int? = (invoke(target, name) as? Number)?.toInt()

    private fun repeatAll(player: Any): Boolean =
        (invoke(player, "getRepeatMode") as? Number)?.toInt() == REPEAT_MODE_ALL

    private fun invoke(target: Any, name: String, vararg args: Any): Any? {
        val method = target.javaClass.methods.firstOrNull { candidate ->
            candidate.name == name && candidate.parameterCount == args.size &&
                candidate.parameterTypes.indices.all { index ->
                    candidate.parameterTypes[index].isInstance(args[index]) ||
                        primitiveMatches(candidate.parameterTypes[index], args[index])
                }
        } ?: return null
        return runCatching {
            method.trySetAccessible()
            method.invoke(target, *args)
        }.getOrNull()
    }

    private fun primitiveMatches(type: Class<*>, value: Any): Boolean = when (type) {
        Int::class.javaPrimitiveType -> value is Int
        Long::class.javaPrimitiveType -> value is Long
        Boolean::class.javaPrimitiveType -> value is Boolean
        else -> false
    }

    private fun Any.toQuery(): TrackQuery? {
        val metadata = member(this, "mediaMetadata", "getMediaMetadata") ?: this
        val title = string(metadata, "title", "getTitle")?.trim().orEmpty()
        val path = pathOf(this).orEmpty()
        if (title.isBlank() && path.isBlank()) return null
        val artist = string(metadata, "artist", "getArtist").orEmpty()
        val album = string(metadata, "albumTitle", "getAlbumTitle", "album").orEmpty()
        val albumArtist = string(metadata, "albumArtist", "getAlbumArtist").orEmpty()
        val duration = long(metadata, "durationMs", "getDurationMs")
        val mediaId = string(this, "mediaId", "getMediaId").orEmpty()
        return TrackQuery(
            title = title,
            artists = TrackQuery.splitArtists(artist),
            album = album,
            albumArtists = TrackQuery.splitArtists(albumArtist),
            path = path,
            durationMs = duration?.takeIf { it > 0 },
            trackId = mediaId,
        )
    }

    private fun pathOf(item: Any): String? {
        val local = member(item, "localConfiguration", "getLocalConfiguration")
        val request = member(item, "requestMetadata", "getRequestMetadata")
        val uri = member(local, "uri", "getUri") ?: member(request, "mediaUri", "getMediaUri")
        return uri?.toString()?.let(::uriToPath)
    }

    private fun member(target: Any?, vararg names: String): Any? {
        if (target == null) return null
        for (name in names) {
            val getter = runCatching {
                target.javaClass.methods.firstOrNull {
                    it.name == name && it.parameterCount == 0 && it.returnType != Void.TYPE
                }
            }.getOrNull()
            if (getter != null) {
                val value = runCatching {
                    getter.trySetAccessible()
                    getter.invoke(target)
                }.getOrNull()
                if (value != null) return value
            }
            val field = runCatching {
                generateSequence(target.javaClass) { it.superclass }
                    .flatMap { it.declaredFields.asSequence() }
                    .firstOrNull { it.name == name }
            }.getOrNull()
            if (field != null) {
                val value = runCatching {
                    field.trySetAccessible()
                    field.get(target)
                }.getOrNull()
                if (value != null) return value
            }
        }
        return null
    }

    private fun string(target: Any?, vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        when (val value = member(target, name)) {
            is CharSequence -> value.toString()
            else -> null
        }
    }

    private fun long(target: Any?, vararg names: String): Long? = names.firstNotNullOfOrNull { name ->
        (member(target, name) as? Number)?.toLong()
    }

    private fun uriToPath(value: String): String? {
        val text = value.trim()
        if (text.isBlank()) return null
        return runCatching {
            val uri = URI(text)
            when {
                uri.scheme.isNullOrBlank() -> text
                uri.scheme.equals("file", ignoreCase = true) -> Path.of(uri).toString()
                else -> null
            }
        }.getOrElse { text.takeUnless { it.contains("://") } }
    }

    private companion object {
        const val PLAYBACK_SERVICE = "com.xuncorp.voxzen.service.PlaybackService"
        const val PLAYBACK_CONTROLLER = "com.xuncorp.voxzen.service.PlaybackController"
        const val REPEAT_MODE_ALL = 1
    }
}

internal data class QueueNext(val available: Boolean, val track: TrackQuery?)

internal class SpwNextTrackSource(
    private val library: SpwLibraryTrackSource,
    private val queue: SpwPlaybackQueueProbe = SpwPlaybackQueueProbe(),
) {
    fun next(current: TrackQuery): TrackQuery? {
        val result = queue.next()
        if (result.available) {
            return result.track?.let { raw ->
                library.canonicalize(raw) ?: raw.takeIf { it.path.isNotBlank() && it.title.isNotBlank() }
            }
        }
        return library.nextInLibrary(current)
    }
}
