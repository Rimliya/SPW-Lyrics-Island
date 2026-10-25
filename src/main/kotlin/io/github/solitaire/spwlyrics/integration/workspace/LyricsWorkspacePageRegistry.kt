package io.github.solitaire.spwlyrics.integration.workspace

import androidx.compose.runtime.Composable

/** A product-level page supplied by the Combined adapter, never referenced directly by Lyrics. */
internal interface LyricsWorkspacePageProvider {
    @Composable
    fun Content(close: () -> Unit)
}

/** Registration happens before the Lyrics runtime is installed, so no Compose state is needed. */
internal object LyricsWorkspacePageRegistry {
    @Volatile
    private var workbenchProvider: LyricsWorkspacePageProvider? = null

    val workbench: LyricsWorkspacePageProvider?
        get() = workbenchProvider

    fun setWorkbench(provider: LyricsWorkspacePageProvider?) {
        workbenchProvider = provider
    }
}
