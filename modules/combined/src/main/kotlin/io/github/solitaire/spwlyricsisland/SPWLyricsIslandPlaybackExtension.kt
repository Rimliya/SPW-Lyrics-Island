package io.github.solitaire.spwlyricsisland

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import io.github.solitaire.spwlyrics.integration.SpwLyricsExtension
import io.github.solitaire.spwisland.host.IslandPlaybackExtension
import io.github.univers629.spwtagworkbench.data.Repo

/**
 * Single host-facing extension. Each upstream module still owns its callback implementation;
 * this adapter only fans the host callbacks out to both modules.
 */
class SPWLyricsIslandPlaybackExtension : PlaybackExtensionPoint {
    private val island = IslandPlaybackExtension()
    private val lyrics = SpwLyricsExtension()

    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        island.onBeforeLoadLyrics(mediaItem)
        return lyrics.onBeforeLoadLyrics(mediaItem)
    }

    override fun onAfterLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        if (!Repo.stopped) Repo.postOnMain { Repo.onTrackChanged(mediaItem) }
        return lyrics.onAfterLoadLyrics(mediaItem)
    }

    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        island.onStateChanged(state)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        island.onIsPlayingChanged(isPlaying)
        if (!Repo.stopped) Repo.postOnMain { Repo.onPlayingChanged(isPlaying) }
    }

    override fun onPositionUpdated(position: Long) {
        island.onPositionUpdated(position)
        if (!Repo.stopped) Repo.postOnMain { Repo.onPosition(position) }
    }

    override fun onSeekTo(position: Long) {
        island.onSeekTo(position)
        if (!Repo.stopped) Repo.postOnMain { Repo.onPosition(position) }
    }

    override fun onLyricsLineUpdated(lyricsLine: PlaybackExtensionPoint.LyricsLine?) {
        island.onLyricsLineUpdated(lyricsLine)
        if (!Repo.stopped) Repo.postOnMain { Repo.onLyricLine(lyricsLine) }
    }

    override fun onLyricsLinesUpdated(lyricsLines: List<PlaybackExtensionPoint.LyricsLine>) {
        island.onLyricsLinesUpdated(lyricsLines)
        if (!Repo.stopped) Repo.postOnMain { Repo.onLyricLines(lyricsLines) }
    }
}
