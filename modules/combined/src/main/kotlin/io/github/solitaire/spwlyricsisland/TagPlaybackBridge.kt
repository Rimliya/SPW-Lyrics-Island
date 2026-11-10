package io.github.solitaire.spwlyricsisland

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import io.github.univers629.spwtagworkbench.data.Repo

internal object TagPlaybackBridge {
    private val relay = TagPlaybackRelay({ action -> Repo.postOnMain(action) }, object : TagPlaybackSink {
        override fun track(value: PlaybackExtensionPoint.MediaItem) = Repo.onTrackChanged(value)
        override fun playing(value: Boolean) = Repo.onPlayingChanged(value)
        override fun position(value: Long) = Repo.onPosition(value)
        override fun lines(value: List<PlaybackExtensionPoint.LyricsLine>) = Repo.onLyricLines(value)
        override fun line(value: PlaybackExtensionPoint.LyricsLine?) = Repo.onLyricLine(value)
    })

    fun start() = relay.start()
    fun stop() = relay.stop()
    fun onTrackChanged(value: PlaybackExtensionPoint.MediaItem) = relay.onTrackChanged(value)
    fun onPlayingChanged(value: Boolean) = relay.onPlayingChanged(value)
    fun onPosition(value: Long) = relay.onPosition(value)
    fun onLyricsLines(value: List<PlaybackExtensionPoint.LyricsLine>) = relay.onLyricsLines(value)
    fun onLyricsLine(value: PlaybackExtensionPoint.LyricsLine?) = relay.onLyricsLine(value)
}

internal interface TagPlaybackSink {
    fun track(value: PlaybackExtensionPoint.MediaItem)
    fun playing(value: Boolean)
    fun position(value: Long)
    fun lines(value: List<PlaybackExtensionPoint.LyricsLine>)
    fun line(value: PlaybackExtensionPoint.LyricsLine?)
}

/** Serializes startup replay with live callbacks and invalidates work queued before stop. */
internal class TagPlaybackRelay(
    private val post: (() -> Unit) -> Unit,
    private val sink: TagPlaybackSink,
) {
    private val lock = Any()
    private var running = false
    private var generation = 0L
    private var track: PlaybackExtensionPoint.MediaItem? = null
    private var playing: Boolean? = null
    private var position: Long? = null
    private var lyricsLines: List<PlaybackExtensionPoint.LyricsLine>? = null
    private var lyricsLine: PlaybackExtensionPoint.LyricsLine? = null
    private var hasLyricsLine = false

    fun start() = synchronized(lock) {
        if (running) return@synchronized
        running = true
        val savedTrack = track
        val savedPlaying = playing
        val savedPosition = position
        val savedLines = lyricsLines
        val savedLine = lyricsLine
        val savedHasLine = hasLyricsLine
        // Post under the callback lock, before live updates can enter the queue.
        dispatchLocked {
            savedTrack?.let(sink::track)
            savedPlaying?.let(sink::playing)
            savedPosition?.let(sink::position)
            savedLines?.let(sink::lines)
            if (savedHasLine) sink.line(savedLine)
        }
        clearLocked()
    }

    fun stop() = synchronized(lock) {
        running = false
        generation++
        clearLocked()
    }

    fun onTrackChanged(value: PlaybackExtensionPoint.MediaItem) = synchronized(lock) {
        if (running) dispatchLocked { sink.track(value) }
        else {
            if (track != value) {
                position = null
                lyricsLines = null
                lyricsLine = null
                hasLyricsLine = false
            }
            track = value
        }
    }

    fun onPlayingChanged(value: Boolean) = synchronized(lock) {
        if (running) dispatchLocked { sink.playing(value) } else playing = value
    }

    fun onPosition(value: Long) = synchronized(lock) {
        if (running) dispatchLocked { sink.position(value) } else position = value
    }

    fun onLyricsLines(value: List<PlaybackExtensionPoint.LyricsLine>) = synchronized(lock) {
        val copy = value.toList()
        if (running) dispatchLocked { sink.lines(copy) } else lyricsLines = copy
    }

    fun onLyricsLine(value: PlaybackExtensionPoint.LyricsLine?) = synchronized(lock) {
        if (running) dispatchLocked { sink.line(value) }
        else { lyricsLine = value; hasLyricsLine = true }
    }

    private fun dispatchLocked(action: () -> Unit) {
        val expected = generation
        post {
            synchronized(lock) {
                if (running && generation == expected) action()
            }
        }
    }

    private fun clearLocked() {
        track = null
        playing = null
        position = null
        lyricsLines = null
        lyricsLine = null
        hasLyricsLine = false
    }
}
