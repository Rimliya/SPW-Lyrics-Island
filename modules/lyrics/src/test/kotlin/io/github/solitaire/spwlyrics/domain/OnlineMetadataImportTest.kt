package io.github.solitaire.spwlyrics.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnlineMetadataImportTest {
    private val draft = AudioMetadata(fields = mapOf(
        AudioMetadataField.TITLE to "Current title",
        AudioMetadataField.ALBUM to "",
    ))

    @Test
    fun missingPresetSelectsOnlyEmptyFields() {
        val pending = OnlineMetadataImport(
            source = "test",
            fields = mapOf(
                AudioMetadataField.TITLE to "Candidate title",
                AudioMetadataField.ARTIST to "Candidate artist",
            ),
        ).selectFor(draft, onlyMissing = true)

        assertEquals(setOf(AudioMetadataField.ARTIST), pending.selected)
        assertFalse(pending.coverSelected)
        assertFalse(pending.lyricsSelected)
        val applied = pending.apply(draft)
        assertEquals("Current title", applied[AudioMetadataField.TITLE])
        assertEquals("Candidate artist", applied[AudioMetadataField.ARTIST])
    }

    @Test
    fun replacePresetSelectsEveryAvailableValue() {
        val pending = OnlineMetadataImport(
            source = "test",
            fields = mapOf(
                AudioMetadataField.TITLE to "Candidate title",
                AudioMetadataField.ALBUM to "Candidate album",
            ),
        ).selectFor(draft, onlyMissing = false)

        assertEquals(setOf(AudioMetadataField.TITLE, AudioMetadataField.ALBUM), pending.selected)
        assertTrue(pending.hasSelection)
        val applied = pending.apply(draft)
        assertEquals("Candidate title", applied[AudioMetadataField.TITLE])
        assertEquals("Candidate album", applied[AudioMetadataField.ALBUM])
    }
}
