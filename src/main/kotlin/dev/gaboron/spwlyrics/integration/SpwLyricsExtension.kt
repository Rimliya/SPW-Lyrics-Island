package dev.gaboron.spwlyrics.integration

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint

class SpwLyricsExtension : PlaybackExtensionPoint {
    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? =
        PluginRuntime.beforeLoad(mediaItem)

    override fun onAfterLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? =
        PluginRuntime.afterLocalLyricsMissing(mediaItem)
}
