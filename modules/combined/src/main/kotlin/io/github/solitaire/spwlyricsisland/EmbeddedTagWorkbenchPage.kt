package io.github.solitaire.spwlyricsisland

import androidx.compose.runtime.Composable
import io.github.solitaire.spwlyrics.integration.workspace.LyricsWorkspacePageProvider
import io.github.univers629.spwtagworkbench.EmbeddedWorkbenchContent

/** Combined-only UI adapter so the upstream Lyrics module has no compile-time Tag Workbench link. */
internal object EmbeddedTagWorkbenchPage : LyricsWorkspacePageProvider {
    @Composable
    override fun Content(close: () -> Unit) {
        EmbeddedWorkbenchContent(onClose = close)
    }
}
