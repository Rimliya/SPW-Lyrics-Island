package io.github.solitaire.spwlyrics.integration

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint

class SpwLyricsExtension : PlaybackExtensionPoint {
    init { discovered = true }

    override fun onSeekTo(position: Long) = PluginRuntime.timingSeeked()

    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? =
        PluginRuntime.beforeLoad(mediaItem)

    override fun onAfterLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? =
        PluginRuntime.afterLocalLyricsMissing(mediaItem)

    companion object {
        @Volatile internal var discovered = false
            private set
    }
}
