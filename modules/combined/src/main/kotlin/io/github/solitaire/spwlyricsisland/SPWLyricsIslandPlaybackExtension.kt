package io.github.solitaire.spwlyricsisland

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import io.github.solitaire.spwlyrics.integration.SpwLyricsExtension
import io.github.solitaire.spwisland.host.IslandPlaybackExtension

/**
 * Single host-facing extension. Each upstream module still owns its callback implementation;
 * this adapter only fans the host callbacks out to the active modules.
 */
class SPWLyricsIslandPlaybackExtension : PlaybackExtensionPoint {
    private val island = IslandPlaybackExtension()
    private val lyrics = SpwLyricsExtension()

    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        island.onBeforeLoadLyrics(mediaItem)
        return lyrics.onBeforeLoadLyrics(mediaItem)
    }

    override fun onAfterLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        TagPlaybackBridge.onTrackChanged(mediaItem)
        return lyrics.onAfterLoadLyrics(mediaItem)
    }

    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        island.onStateChanged(state)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        island.onIsPlayingChanged(isPlaying)
        TagPlaybackBridge.onPlayingChanged(isPlaying)
    }

    override fun onPositionUpdated(position: Long) {
        island.onPositionUpdated(position)
        TagPlaybackBridge.onPosition(position)
    }

    override fun onSeekTo(position: Long) {
        island.onSeekTo(position)
        TagPlaybackBridge.onPosition(position)
    }

    override fun onLyricsLineUpdated(lyricsLine: PlaybackExtensionPoint.LyricsLine?) {
        island.onLyricsLineUpdated(lyricsLine)
        TagPlaybackBridge.onLyricsLine(lyricsLine)
    }

    override fun onLyricsLinesUpdated(lyricsLines: List<PlaybackExtensionPoint.LyricsLine>) {
        island.onLyricsLinesUpdated(lyricsLines)
        TagPlaybackBridge.onLyricsLines(lyricsLines)
    }
}
