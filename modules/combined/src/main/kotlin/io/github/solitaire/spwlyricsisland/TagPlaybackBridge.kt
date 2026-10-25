package io.github.solitaire.spwlyricsisland

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import io.github.univers629.spwtagworkbench.data.Repo

/**
 * Buffers the latest host playback snapshot until the embedded Tag Workbench is running.
 *
 * The host can deliver callbacks while the combined plugin is still starting. Dropping them
 * leaves the workbench paused or on an old position until the next host event, so keep only the
 * latest value and replay it once the workbench runtime owns its main-thread state.
 */
internal object TagPlaybackBridge {
    private data class Snapshot(
        val track: PlaybackExtensionPoint.MediaItem?,
        val hasTrack: Boolean,
        val playing: Boolean?,
        val position: Long?,
        val lyricsLines: List<PlaybackExtensionPoint.LyricsLine>?,
        val lyricsLine: PlaybackExtensionPoint.LyricsLine?,
        val hasLyricsLine: Boolean,
    )

    private val lock = Any()
    private var running = false
    private var track: PlaybackExtensionPoint.MediaItem? = null
    private var hasTrack = false
    private var playing: Boolean? = null
    private var position: Long? = null
    private var lyricsLines: List<PlaybackExtensionPoint.LyricsLine>? = null
    private var lyricsLine: PlaybackExtensionPoint.LyricsLine? = null
    private var hasLyricsLine = false

    fun start() {
        val snapshot = synchronized(lock) {
            running = true
            Snapshot(track, hasTrack, playing, position, lyricsLines, lyricsLine, hasLyricsLine)
                .also { clearLocked() }
        }
        if (Repo.stopped) return
        Repo.postOnMain {
            if (snapshot.hasTrack) snapshot.track?.let { Repo.onTrackChanged(it) }
            snapshot.playing?.let { Repo.onPlayingChanged(it) }
            snapshot.position?.let { Repo.onPosition(it) }
            snapshot.lyricsLines?.let { Repo.onLyricLines(it) }
            if (snapshot.hasLyricsLine) Repo.onLyricLine(snapshot.lyricsLine)
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            clearLocked()
        }
    }

    fun onTrackChanged(item: PlaybackExtensionPoint.MediaItem) {
        val dispatch = synchronized(lock) {
            if (running) true else { track = item; hasTrack = true; false }
        }
        if (dispatch && !Repo.stopped) Repo.postOnMain { Repo.onTrackChanged(item) }
    }

    fun onPlayingChanged(value: Boolean) {
        val dispatch = synchronized(lock) {
            if (running) true else { playing = value; false }
        }
        if (dispatch && !Repo.stopped) Repo.postOnMain { Repo.onPlayingChanged(value) }
    }

    fun onPosition(value: Long) {
        val dispatch = synchronized(lock) {
            if (running) true else { position = value; false }
        }
        if (dispatch && !Repo.stopped) Repo.postOnMain { Repo.onPosition(value) }
    }

    fun onLyricsLines(value: List<PlaybackExtensionPoint.LyricsLine>) {
        val copy = value.toList()
        val dispatch = synchronized(lock) {
            if (running) true else { lyricsLines = copy; false }
        }
        if (dispatch && !Repo.stopped) Repo.postOnMain { Repo.onLyricLines(copy) }
    }

    fun onLyricsLine(value: PlaybackExtensionPoint.LyricsLine?) {
        val dispatch = synchronized(lock) {
            if (running) true else { lyricsLine = value; hasLyricsLine = true; false }
        }
        if (dispatch && !Repo.stopped) Repo.postOnMain { Repo.onLyricLine(value) }
    }

    private fun clearLocked() {
        track = null
        hasTrack = false
        playing = null
        position = null
        lyricsLines = null
        lyricsLine = null
        hasLyricsLine = false
    }
}
