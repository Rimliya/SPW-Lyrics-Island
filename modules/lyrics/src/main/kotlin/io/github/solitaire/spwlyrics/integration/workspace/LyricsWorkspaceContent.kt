package io.github.solitaire.spwlyrics.integration.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.solitaire.spwlyrics.integration.batchui.BatchProcessingContent
import io.github.solitaire.spwlyrics.integration.composeui.*
import io.github.solitaire.spwlyrics.integration.manualui.ManualSearchContent
import io.github.solitaire.spwlyrics.integration.metadataui.MetadataDiscardDialog
import io.github.solitaire.spwlyrics.integration.metadataui.MetadataEditorContent
import io.github.univers629.spwtagworkbench.EmbeddedWorkbenchContent

@Composable
internal fun LyricsWorkspaceContent(session: LyricsWorkspaceSession, close: () -> Unit) {
    val pages = rememberSaveableStateHolder()
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val focused = session.timingFocused
        val tagWorkbench = session.page == LyricsToolPage.TAG_WORKBENCH
        val dense = focused || tagWorkbench
        val gutter by animateDpAsState(if (dense || maxWidth < 720.dp) 16.dp else 32.dp, tween(220), label = "workspace gutter")
        val width by animateDpAsState(if (dense) maxWidth else minOf(maxWidth, 1280.dp), tween(220), label = "workspace width")
        val gap by animateDpAsState(if (dense) 8.dp else 20.dp, tween(220), label = "workspace density")
        Column(Modifier.width(width).fillMaxHeight().padding(horizontal = gutter).padding(top = gap),
            verticalArrangement = Arrangement.spacedBy(gap)) {
            LyricsWorkspaceHeader(session.page, dense, session::select) { session.requestClose(close) }
            LyricsTransition(session.page, Modifier.weight(1f).fillMaxWidth()) { page ->
                pages.SaveableStateProvider(page.name) {
                    LyricsViewport(Modifier.fillMaxSize()) {
                        when (page) {
                            LyricsToolPage.TAG_WORKBENCH -> EmbeddedWorkbenchContent { session.select(LyricsToolPage.BATCH) }
                            LyricsToolPage.SEARCH -> ManualSearchContent(session.manual)
                            LyricsToolPage.METADATA -> MetadataEditorContent(session.metadata, session.timing, session.lyricsView, { session.lyricsView = it })
                            LyricsToolPage.BATCH -> BatchProcessingContent(session.batch)
                        }
                    }
                }
            }
        }
    }
    MetadataDiscardDialog(session.metadata)
}
