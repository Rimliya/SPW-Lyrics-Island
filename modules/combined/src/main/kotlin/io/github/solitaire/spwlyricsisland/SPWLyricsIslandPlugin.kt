package io.github.solitaire.spwlyricsisland

import com.xuncorp.spw.workshop.api.PluginContext
import dev.gaboron.spwmedia.host.MediaRuntimePlugin
import io.github.univers629.spwtagworkbench.EmbeddedWorkbenchRuntime
import io.github.solitaire.spwlyrics.integration.PluginRuntime
import io.github.solitaire.spwlyrics.integration.workspace.LyricsWorkspacePageRegistry
import io.github.solitaire.spwlyrics.storage.DataDirectory
import io.github.solitaire.spwisland.host.IslandPlugin
import io.github.solitaire.spwisland.host.LegacyConfigMigration
import io.github.solitaire.spwisland.ui.IslandMenuActions

/**
 * Root lifecycle adapter. Upstream modules keep their own runtimes and can be updated
 * independently; this class coordinates startup, page registration and shutdown between them.
 */
class SPWLyricsIslandPlugin(context: PluginContext) : IslandPlugin(context) {
    private var mediaRuntime: MediaRuntimePlugin? = null
    override fun menuActions(): IslandMenuActions = object : IslandMenuActions {
        override fun canDisableCurrentLyrics() = PluginRuntime.canDisableCurrentLyrics()
        override fun disableCurrentLyrics() = PluginRuntime.disableCurrentLyrics()
    }

    override fun migrateLegacyConfig() {
        LegacyConfigMigration.migrate(
            currentPluginId = pluginContext.pluginId,
            legacyPluginIds = listOf(LEGACY_PLUGIN_ID),
            fileNames = listOf("island.json", "spw-lyrics.json"),
        )
    }

    override fun start() {
        super.start()
        try {
            mediaRuntime = MediaRuntimePlugin(
                pluginContext,
                embeddedPluginId = pluginContext.pluginId,
                embeddedStorage = DataDirectory.root.resolve("media-runtime"),
            ).also { it.start() }
            EmbeddedWorkbenchRuntime.start(
                pluginId = pluginContext.pluginId,
                pluginVersion = pluginContext.pluginVersion,
                spwVersion = pluginContext.spwVersion,
                pluginPath = pluginContext.pluginPath,
            )
            TagPlaybackBridge.start()
            LyricsWorkspacePageRegistry.setWorkbench(EmbeddedTagWorkbenchPage)
            PluginRuntime.install(pluginContext.pluginId, pluginContext.spwChannel)
        } catch (error: Throwable) {
            LyricsWorkspacePageRegistry.setWorkbench(null)
            runCatching { PluginRuntime.close() }
            runCatching { EmbeddedWorkbenchRuntime.stop() }
            TagPlaybackBridge.stop()
            runCatching { mediaRuntime?.stop() }
            mediaRuntime = null
            super.stop()
            throw error
        }
    }

    override fun stop() {
        try {
            LyricsWorkspacePageRegistry.setWorkbench(null)
            PluginRuntime.close()
        } finally {
            runCatching { EmbeddedWorkbenchRuntime.stop() }
            TagPlaybackBridge.stop()
            runCatching { mediaRuntime?.stop() }
            mediaRuntime = null
            super.stop()
        }
    }

    override fun update() = stop()

    override fun delete() = stop()

    private companion object {
        const val LEGACY_PLUGIN_ID = "io.github.gaboron.spwlyricsisland"
    }
}
