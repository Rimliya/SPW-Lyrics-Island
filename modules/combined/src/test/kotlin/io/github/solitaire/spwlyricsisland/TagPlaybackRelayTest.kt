package io.github.solitaire.spwlyricsisland

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.LyricsLine
import kotlin.test.Test
import kotlin.test.assertEquals

class TagPlaybackRelayTest {
    private class Harness : TagPlaybackSink {
        val queue = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val relay = TagPlaybackRelay({ queue.addLast(it) }, this)
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        override fun track(value: MediaItem) { events += "track:${value.title}" }
        override fun playing(value: Boolean) { events += "playing:$value" }
        override fun position(value: Long) { events += "position:$value" }
        override fun lines(value: List<LyricsLine>) { events += "lines:${value.size}" }
        override fun line(value: LyricsLine?) { events += "line:${value?.pureMainText}" }
    }

    @Test fun dropsPreviousTrackLyricsAndPositionDuringStartup() {
        val h = Harness()
        h.relay.onTrackChanged(track("A"))
        h.relay.onPosition(60_000)
        h.relay.onLyricsLines(listOf(line()))
        h.relay.onLyricsLine(line())
        h.relay.onTrackChanged(track("B"))
        h.relay.start()
        h.drain()
        assertEquals(listOf("track:B"), h.events)
    }

    @Test fun replaysBeforeLiveUpdates() {
        val h = Harness()
        h.relay.onTrackChanged(track("A"))
        h.relay.onPosition(1_000)
        h.relay.start()
        h.relay.onPosition(2_000)
        h.drain()
        assertEquals(listOf("track:A", "position:1000", "position:2000"), h.events)
    }

    @Test fun discardsQueuedEventsAcrossStopAndRestart() {
        val h = Harness()
        h.relay.start()
        h.relay.onTrackChanged(track("old"))
        h.relay.onPosition(9_000)
        h.relay.stop()
        h.relay.onTrackChanged(track("new"))
        h.relay.start()
        h.drain()
        assertEquals(listOf("track:new"), h.events)
    }

    @Test fun freezesBufferedListsAndKeepsExplicitNullLine() {
        val h = Harness()
        val lines = mutableListOf(line())
        h.relay.onLyricsLines(lines)
        h.relay.onLyricsLine(null)
        lines.clear()
        h.relay.start()
        h.drain()
        assertEquals(listOf("lines:1", "line:null"), h.events)
    }

    private fun track(title: String) = MediaItem(title = title, artist = "artist", album = "album",
        albumArtist = "artist", path = "D:/$title.flac")
    private fun line() = LyricsLine(0, 1_000, emptyList(), "lyric", null)
}
