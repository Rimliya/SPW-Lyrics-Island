package io.github.solitaire.spwlyricsisland

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import dev.gaboron.spwmedia.core.NativeAssets
import dev.gaboron.spwmedia.core.RuntimeEndpoint
import dev.gaboron.spwmedia.core.RuntimeEngine
import dev.gaboron.spwmedia.processing.ProcessingProvider
import dev.gaboron.spwmedia.sdk.MediaRuntime
import java.nio.file.Files
import java.nio.file.Path

/** Host-free smoke test for the combined adapter and merged resources. */
fun main() {
    val preferences = resource("/preference_config.json")
    check(preferences.contains("\"island.json\"")) { "island.json is missing from merged preferences" }
    check(preferences.contains("\"spw-lyrics.json\"")) { "spw-lyrics.json is missing from merged preferences" }
    check(resource("/META-INF/extensions.idx").trim() ==
        SPWLyricsIslandPlaybackExtension::class.java.name) { "Unexpected extension registration" }
    check(resourceBytes("/native/windows-x64/ffmpeg.exe").isNotEmpty()) { "Embedded Media Runtime ffmpeg is missing" }
    check(resource("/native/windows-x64/assets.properties").contains("ffmpeg.path=ffmpeg.exe")) {
        "Embedded Media Runtime manifest is invalid"
    }
    checkNotNull(ResourceAnchor::class.java.getResourceAsStream("/licenses/media-runtime/LICENSE")) {
        "Embedded Media Runtime license is missing"
    }.close()
    checkNotNull(Class.forName("dev.gaboron.spwmedia.host.MediaRuntimePlugin")) {
        "Embedded Media Runtime adapter is missing"
    }
    checkNotNull(Class.forName("dev.gaboron.spwmedia.sdk.MediaRuntime")) {
        "Embedded Media Runtime SDK is missing"
    }
    checkEmbeddedRuntime()

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

private fun checkEmbeddedRuntime() {
    val pluginId = "io.github.solitaire.spwlyricsisland"
    val storage = Files.createTempDirectory("spw-media-runtime-check")
    val endpoint = RuntimeEndpoint(
        RuntimeEngine(
            providers = listOf(ProcessingProvider()),
            assets = NativeAssets(Path.of("build/resources/main/native"), storage.resolve("assets")),
            storage = storage,
            workerCommands = emptyMap(),
        ),
    ) { pluginId }
    try {
        MediaRuntime.connect(pluginId).use { client ->
            val processing = client.capabilities().firstOrNull { it.id == "processing" }
            check(processing?.available == true) { "Embedded Media Runtime processing capability is unavailable" }
        }
    } finally {
        endpoint.close()
    }
}

private fun resource(name: String): String =
    checkNotNull(ResourceAnchor::class.java.getResourceAsStream(name)) { "Missing resource: $name" }
        .bufferedReader()
        .use { it.readText() }
private fun resourceBytes(name: String): ByteArray =
    checkNotNull(ResourceAnchor::class.java.getResourceAsStream(name)) { "Missing resource: $name" }
        .use { it.readBytes() }
private object ResourceAnchor
