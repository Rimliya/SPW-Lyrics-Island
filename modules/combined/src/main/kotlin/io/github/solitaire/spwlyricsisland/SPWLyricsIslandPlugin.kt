package io.github.solitaire.spwlyricsisland

import com.xuncorp.spw.workshop.api.PluginContext
import io.github.solitaire.spwlyrics.integration.PluginRuntime
import io.github.solitaire.spwisland.host.IslandPlugin
import io.github.solitaire.spwisland.host.LegacyConfigMigration
import io.github.solitaire.spwisland.ui.IslandMenuActions

/**
 * Root lifecycle adapter. The two upstream modules keep their own runtimes and can be updated
 * independently; this class only coordinates startup and shutdown between them.
 */
class SPWLyricsIslandPlugin(context: PluginContext) : IslandPlugin(context) {
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
            PluginRuntime.install()
        } catch (error: Throwable) {
            runCatching { PluginRuntime.close() }
            super.stop()
            throw error
        }
    }

    override fun stop() {
        try {
            PluginRuntime.close()
        } finally {
            super.stop()
        }
    }

    override fun update() = stop()

    override fun delete() = stop()

    private companion object {
        const val LEGACY_PLUGIN_ID = "io.github.gaboron.spwlyricsisland"
    }
}