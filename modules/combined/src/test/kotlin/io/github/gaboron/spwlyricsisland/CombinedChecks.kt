package io.github.gaboron.spwlyricsisland

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint

/** Host-free smoke test for the combined adapter and merged resources. */
fun main() {
    val preferences = resource("/preference_config.json")
    check(preferences.contains("\"island.json\"")) { "island.json is missing from merged preferences" }
    check(preferences.contains("\"spw-lyrics.json\"")) { "spw-lyrics.json is missing from merged preferences" }
    check(resource("/META-INF/extensions.idx").trim() ==
        SPWLyricsIslandPlaybackExtension::class.java.name) { "Unexpected extension registration" }

    val extension = SPWLyricsIslandPlaybackExtension()
    val item = PlaybackExtensionPoint.MediaItem(
        title = "Adapter check",
        artist = "SPW Lyrics Island",
        album = "Verification",
        albumArtist = "SPW Lyrics Island",
        path = "C:\\verification\\adapter-check.mp3",
    )
    extension.onBeforeLoadLyrics(item)
    extension.onAfterLoadLyrics(item)
    extension.onStateChanged(PlaybackExtensionPoint.State.Ready)
    extension.onIsPlayingChanged(true)
    extension.onPositionUpdated(1_000)
    extension.onSeekTo(1_000)
    extension.onLyricsLineUpdated(null)
    extension.onLyricsLinesUpdated(emptyList())
    extension.onLyricsLineUpdated(
        PlaybackExtensionPoint.LyricsLine(0, 1_000, emptyList(), "adapter check", null),
    )
    println("PASS: merged preferences, extension registration, and both playback delegates")
}

private fun resource(name: String): String =
    checkNotNull(ResourceAnchor::class.java.getResourceAsStream(name)) { "Missing resource: $name" }
        .bufferedReader()
        .use { it.readText() }
private object ResourceAnchor
