package io.github.solitaire.spwlyrics.integration

import javax.swing.JOptionPane

object SpwLyricsActions {
    @JvmStatic
    fun openWorkspace() = PluginRuntime.openWorkspace()

    @JvmStatic
    fun openManualSearch() = PluginRuntime.openManualSearch()

    @JvmStatic
    fun openBatchProcessing() = PluginRuntime.openBatchProcessing()

    @JvmStatic
    fun openMetadataEditor() = PluginRuntime.openMetadataEditor()

    @JvmStatic
    fun openCacheFolder() = PluginRuntime.openCacheFolder()

    @JvmStatic
    fun resetLyricsOrder() = PluginRuntime.resetLyricsOrder()

    @JvmStatic
    fun clearLyricsCache() {
        if (confirm("清理歌词缓存？\n\n只会删除插件自动生成或手动缓存的歌词 JSON；手动匹配记录、本地歌词文件和音频文件不会删除。")) {
            PluginRuntime.clearLyricsCache()
        }
    }

    @JvmStatic
    fun clearAmllIndex() {
        if (confirm("清理 AMLL 索引？\n\n下次搜索 AMLL 歌词时会重新下载索引。")) {
            PluginRuntime.clearAmllIndex()
        }
    }

    private fun confirm(message: String): Boolean = JOptionPane.showConfirmDialog(
        null,
        message,
        "SPW Lyrics",
        JOptionPane.YES_NO_OPTION,
        JOptionPane.WARNING_MESSAGE,
    ) == JOptionPane.YES_OPTION
}