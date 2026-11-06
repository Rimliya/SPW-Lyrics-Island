package io.github.solitaire.spwlyrics.integration.metadataui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moriafly.salt.ui.*
import io.github.solitaire.spwlyrics.integration.composeui.DesktopButton
import io.github.solitaire.spwlyrics.integration.composeui.DesktopCheck
import io.github.solitaire.spwlyrics.integration.workspace.*

@Composable
internal fun MetadataImportPanel(controller: MetadataEditorController) {
    val state = controller.state
    MetadataContentTransition(state.onlineImport, Modifier.fillMaxWidth(),
        contentKey = { pending -> pending?.let { Triple(it.source, it.fields, it.coverUrl) } }) { pending ->
        if (pending != null) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("在线信息 · ${pending.source}", color = SaltTheme.colors.text)
            pending.notice?.let { LyricsStatus(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DesktopButton("仅补缺失", { controller.selectImportPreset(true) }, enabled = !state.busy,
                    primary = state.importOnlyMissing)
                DesktopButton("全部替换", { controller.selectImportPreset(false) }, enabled = !state.busy,
                    primary = !state.importOnlyMissing)
            }
            LyricsStatus("勾选即替换，未勾选即保留；在线空值不会覆盖当前内容。")
            pending.fields.filterValues(String::isNotBlank).forEach { (field, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    DesktopCheck(field in pending.selected, { controller.selectImport(field, field !in pending.selected) }, !state.busy, "")
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(field.label, color = SaltTheme.colors.subText, style = SaltTheme.textStyles.sub)
                        Text(value.replace('\n', '、'), color = SaltTheme.colors.text)
                        Text("当前：${state.draft[field].replace('\n', '、').ifBlank { "（空）" }}",
                            color = SaltTheme.colors.subText, style = SaltTheme.textStyles.sub)
                    }
                }
            }
            if (pending.fields.values.none(String::isNotBlank)) LyricsStatus("此来源没有可导入的歌曲标签。")
            if (pending.cover != null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DesktopCheck(pending.coverSelected, controller::selectCoverImport, !state.busy, "替换封面")
                MetadataCoverPreview(pending.cover, "在线候选封面")
                LyricsStatus(if (state.draft.cover == null) "当前没有封面。" else "当前已有封面，勾选后会替换。")
            }
            pending.coverNotice?.let { LyricsStatus(it) }
            if (pending.coverUrl != null && pending.cover == null) {
                DesktopButton("重试封面", controller::retryCoverImport, enabled = !state.busy)
            }
            if (!pending.lyricsText.isNullOrBlank()) {
                val currentLines = state.draft.lyrics.lineSequence().count { it.isNotBlank() }
                DesktopCheck(pending.lyricsSelected, controller::selectLyricsImport, !state.busy,
                    "替换内嵌歌词（候选 ${pending.lyricsDocument?.lines?.size ?: 0} 行 / 当前 $currentLines 行）")
            } else {
                LyricsStatus("此候选没有可导入的歌词，可更换歌词来源后重试。")
            }
            DesktopButton("应用到草稿", controller::applyImport,
                enabled = !state.busy && pending.hasSelection, primary = true)
        }
    }
}
