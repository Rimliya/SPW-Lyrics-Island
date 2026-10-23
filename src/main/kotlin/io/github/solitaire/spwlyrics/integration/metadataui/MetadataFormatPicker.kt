package io.github.solitaire.spwlyrics.integration.metadataui

import androidx.compose.runtime.Composable
import io.github.solitaire.spwlyrics.codec.MetadataLyricsFormat
import io.github.solitaire.spwlyrics.integration.workspace.LyricsMenu

@Composable
internal fun MetadataFormatPicker(format: MetadataLyricsFormat, select: (MetadataLyricsFormat) -> Unit, enabled: Boolean) {
    LyricsMenu(format.label, MetadataLyricsFormat.entries.toList(), format, { it.label }, select, enabled = enabled)
}
