package io.github.solitaire.spwlyrics.storage

import io.github.solitaire.spwlyrics.codec.MetadataLyricsFormat
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsUiStateStoreTest {
    @Test
    fun remembersWorkspaceChoicesAcrossInstances() {
        val directory = Files.createTempDirectory("lyrics-ui-state")
        val path = directory.resolve("workspace.properties")
        try {
            val store = LyricsUiStateStore(path)
            assertEquals("PREFERENCE", store.candidateSort())
            assertEquals("ttml", store.lyricsFormat())
            assertTrue(store.metadataImportFillMissing())
            assertTrue(store.rememberCandidateSort("SCORE"))
            assertTrue(store.rememberSearchSource("QQ"))
            assertTrue(store.rememberLyricsFormat("LRC"))
            assertTrue(store.rememberMetadataImportFillMissing(false))

            val reloaded = LyricsUiStateStore(path)
            assertEquals("SCORE", reloaded.candidateSort())
            assertEquals("QQ", reloaded.searchSource())
            assertEquals("LRC", reloaded.lyricsFormat())
            assertFalse(reloaded.metadataImportFillMissing())
        } finally {
            path.deleteIfExists()
            directory.deleteIfExists()
        }
    }

    @Test
    fun resolvesPersistedFormatNames() {
        assertEquals(MetadataLyricsFormat.ENHANCED_LRC, MetadataLyricsFormat.fromStorageValue("enhanced_lrc"))
        assertEquals(MetadataLyricsFormat.TTML, MetadataLyricsFormat.fromStorageValue("unknown"))
        assertEquals(MetadataLyricsFormat.SPL, MetadataLyricsFormat.fromStorageValue("SPL"))
    }
}
