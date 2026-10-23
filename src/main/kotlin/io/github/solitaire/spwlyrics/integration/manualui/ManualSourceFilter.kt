package io.github.solitaire.spwlyrics.integration.manualui

import androidx.compose.runtime.Composable
import io.github.solitaire.spwlyrics.domain.LyricsSource
import io.github.solitaire.spwlyrics.integration.workspace.LyricsMenu

@Composable
internal fun ManualSourceFilter(source: LyricsSource?, select: (LyricsSource?) -> Unit, enabled: Boolean) {
    LyricsMenu(source?.displayName ?: "全部来源", listOf(null) + LyricsSource.entries.filter { it != LyricsSource.LOCAL },
        source, { it?.displayName ?: "全部来源" }, select, enabled = enabled)
}
