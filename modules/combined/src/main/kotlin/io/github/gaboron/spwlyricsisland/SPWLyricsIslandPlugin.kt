package io.github.gaboron.spwlyricsisland

import com.xuncorp.spw.workshop.api.PluginContext
import dev.gaboron.spwlyrics.integration.PluginRuntime
import io.github.gaboron.spwisland.host.IslandPlugin
import io.github.gaboron.spwisland.ui.IslandMenuActions

/**
 * Root lifecycle adapter. The two upstream modules keep their own runtimes and can be updated
 * independently; this class only coordinates startup and shutdown between them.
 */
class SPWLyricsIslandPlugin(context: PluginContext) : IslandPlugin(context) {
    override fun menuActions(): IslandMenuActions = object : IslandMenuActions {
        override fun canDisableCurrentLyrics() = PluginRuntime.canDisableCurrentLyrics()
        override fun disableCurrentLyrics() = PluginRuntime.disableCurrentLyrics()
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
}
