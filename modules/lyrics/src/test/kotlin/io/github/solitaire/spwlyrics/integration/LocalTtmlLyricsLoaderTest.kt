package io.github.solitaire.spwlyrics.integration

import io.github.solitaire.spwlyrics.domain.InstrumentalLyrics
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
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
}
