package io.github.solitaire.spwlyrics.integration

import io.github.solitaire.spwlyrics.application.LyricsRefreshBridge

class ReflectiveLyricsRefreshBridge : LyricsRefreshBridge {
    private val playbackReloader = SpwPlaybackLyricsReloader()

    override fun reloadCurrentLyrics(): Boolean = playbackReloader.reload()
}
