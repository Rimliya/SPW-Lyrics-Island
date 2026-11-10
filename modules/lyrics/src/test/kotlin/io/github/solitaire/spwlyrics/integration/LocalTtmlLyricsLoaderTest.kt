package io.github.solitaire.spwlyrics.integration

import io.github.solitaire.spwlyrics.domain.InstrumentalLyrics
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalTtmlLyricsLoaderTest {
    @Test
    fun loadsPlaceholderLrcAsLocalLyrics() {
        val directory = Files.createTempDirectory("spw-lyrics-placeholder")
        try {
            val audio = directory.resolve("song.wav")
            Files.write(audio, ByteArray(0))
            directory.resolve("song.lrc").writeText("[00:00.00]纯音乐，请欣赏")

            val document = LocalTtmlLyricsLoader().load(audio.toString())

            assertNotNull(document)
            assertTrue(InstrumentalLyrics.isPlaceholder(document))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun embeddedOnlyIgnoresSidecarAndInvalidatesCache() {
        val directory = Files.createTempDirectory("spw-lyrics-embedded")
        try {
            val audio = directory.resolve("song.wav")
            Files.write(audio, ByteArray(0))
            directory.resolve("song.lrc").writeText("[00:00.00]sidecar")
            var embedded = "[00:00.00]embedded one"
            val loader = LocalTtmlLyricsLoader(readEmbedded = { embedded })

            // Ordinary sidecar LRC stays on the host loading path.
            assertNull(loader.load(audio.toString()))
            assertEquals("embedded one", loader.loadEmbedded(audio.toString())?.lines?.single()?.text)

            embedded = "[00:00.00]embedded two"
            loader.invalidate(audio.toString())
            assertEquals("embedded two", loader.loadEmbedded(audio.toString())?.lines?.single()?.text)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

}
