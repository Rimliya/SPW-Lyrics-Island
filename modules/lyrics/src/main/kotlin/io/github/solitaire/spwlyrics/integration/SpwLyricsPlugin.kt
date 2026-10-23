package io.github.solitaire.spwlyrics.integration

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin

class SpwLyricsPlugin(pluginContext: PluginContext) : SpwPlugin(pluginContext) {
    override fun start() {
        LegacyConfigMigration.migrate(
            currentPluginId = pluginContext.pluginId,
            legacyPluginIds = listOf(LEGACY_PLUGIN_ID),
            fileNames = listOf(PluginSettings.CONFIG_FILE),
        )
        PluginRuntime.install()
    }

    override fun stop() = PluginRuntime.close()

    private companion object {
        const val LEGACY_PLUGIN_ID = "spw-lyrics"
    }
}