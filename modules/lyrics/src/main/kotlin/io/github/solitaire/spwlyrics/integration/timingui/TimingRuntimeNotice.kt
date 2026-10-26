package io.github.solitaire.spwlyrics.integration.timingui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalUriHandler
import com.moriafly.salt.ui.*
import io.github.solitaire.spwlyrics.domain.TimingAudioDependency
import io.github.solitaire.spwlyrics.integration.SteamWorkshopLauncher
import io.github.solitaire.spwlyrics.integration.composeui.DesktopButton
import io.github.solitaire.spwlyrics.integration.workspace.LyricsLayoutTransition
import io.github.solitaire.spwlyrics.integration.workspace.LyricsStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableSaltUiApi::class)
@Composable
internal fun TimingRuntimeNotice(dependency: TimingAudioDependency) {
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    var error by remember(dependency.installUrl) { mutableStateOf<String?>(null) }
    AnimatedVisibility(!dependency.available, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column {
            JustifiedRow(startContent = { LyricsStatus("频谱需要 Salt Player 的宿主 BASS，请先播放任意歌曲后重试") }, endContent = {
                if (dependency.installUrl.isNotBlank()) DesktopButton(dependency.installLabel, {
                    if (!opening) scope.launch {
                        opening = true
                        error = null
                        try {
                            if (dependency.installUrl.startsWith("steam:", ignoreCase = true)) {
                                withContext(Dispatchers.IO) { SteamWorkshopLauncher.open(dependency.installUrl) }
                            } else uri.openUri(dependency.installUrl)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "无法打开安装入口，请稍后重试。"
                        } catch (_: LinkageError) {
                            error = "无法加载宿主的协议启动组件，请重启 SPW 后重试。"
                        } finally {
                            opening = false
                        }
                    }
                }, enabled = !opening, loading = opening)
            }, verticalAlignment = Alignment.CenterVertically)
            LyricsLayoutTransition(error) { message ->
                message?.let { LyricsStatus(it, true) }
            }
        }
    }
}
