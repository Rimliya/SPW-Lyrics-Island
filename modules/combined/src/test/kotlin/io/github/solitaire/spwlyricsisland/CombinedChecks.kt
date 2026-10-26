package io.github.solitaire.spwlyricsisland

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import io.github.solitaire.spwlyrics.integration.BassTimingAudioDecoder
import io.github.solitaire.spwlyrics.integration.workspace.LyricsWorkspacePageRegistry

/** Host-free smoke test for the combined adapter and merged resources. */
fun main() {
    val preferences = resource("/preference_config.json")
    check(preferences.contains("\"island.json\"")) { "island.json is missing from merged preferences" }
    check(preferences.contains("\"spw-lyrics.json\"")) { "spw-lyrics.json is missing from merged preferences" }
    check(preferences.contains("\"tag-workbench.json\"")) {
        "tag-workbench.json is missing from merged preferences"
    }
    check(resource("/META-INF/extensions.idx").trim() ==
        SPWLyricsIslandPlaybackExtension::class.java.name) { "Unexpected extension registration" }
    check(ResourceAnchor::class.java.getResourceAsStream("/native/windows-x64/ffmpeg.exe") == null) {
        "Media Runtime ffmpeg leaked into the BASS-only package"
    }
    check(ResourceAnchor::class.java.getResourceAsStream("/licenses/media-runtime/LICENSE") == null) {
        "Media Runtime license leaked into the BASS-only package"
    }
    checkNotNull(BassTimingAudioDecoder::class.java) { "Host-BASS timing decoder is missing" }
    checkNotNull(Class.forName("io.github.univers629.spwtagworkbench.EmbeddedWorkbenchRuntime")) {
        "Embedded Tag Workbench runtime is missing"
    }
    check(resource("/i18n/strings-zh.properties").isNotBlank()) {
        "Embedded Tag Workbench translations are missing"
    }
    check(resourceBytes("/native/win-x64/ebur128.dll").size > 1024) {
        "Embedded Tag Workbench ReplayGain native library is missing"
    }
    check(resource("/licenses/tag-workbench/THIRD_PARTY_NOTICES.md").contains("libebur128")) {
        "Embedded Tag Workbench third-party notice is missing"
    }
    check(resource("/licenses/tag-workbench/libebur128-COPYING.txt").contains("Permission is hereby granted")) {
        "Embedded Tag Workbench native license is missing"
    }
    LyricsWorkspacePageRegistry.setWorkbench(EmbeddedTagWorkbenchPage)
    try {
        check(LyricsWorkspacePageRegistry.workbench === EmbeddedTagWorkbenchPage) {
            "Combined Tag Workbench page registration failed"
        }
    } finally {
        LyricsWorkspacePageRegistry.setWorkbench(null)
    }

    TagPlaybackBridge.stop()
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
    TagPlaybackBridge.stop()
    println("PASS: merged preferences, BASS decoder, extension registration, and playback delegates")
}

private fun resource(name: String): String =
    checkNotNull(ResourceAnchor::class.java.getResourceAsStream(name)) { "Missing resource: $name" }
        .bufferedReader()
        .use { it.readText() }
private fun resourceBytes(name: String): ByteArray =
    checkNotNull(ResourceAnchor::class.java.getResourceAsStream(name)) { "Missing resource: $name" }
        .use { it.readBytes() }
private object ResourceAnchor