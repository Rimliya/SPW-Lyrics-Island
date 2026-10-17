package dev.gaboron.spwlyrics.integration

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.config.ConfigManager
import dev.gaboron.spwlyrics.application.AutomaticReplacementPolicy
import dev.gaboron.spwlyrics.domain.LyricsSimilarity
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

@OptIn(UnstableSpwWorkshopApi::class)
class PluginSettings(
    private val manager: ConfigManager,
) : AutoCloseable {
    private val initialConfig = manager.getConfig(CONFIG_FILE)
    private val replacementPolicy = AtomicReference(readPolicy(initialConfig))
    private val similarityLevel = AtomicReference(readSimilarity(initialConfig))
    private val listener = Consumer<ConfigHelper> {
        // SPW saves settings through a separate helper; the callback can still hold cached values.
        if (!it.reload()) return@Consumer
        replacementPolicy.set(readPolicy(it))
        similarityLevel.set(readSimilarity(it))
    }

    init {
        manager.addConfigChangeListener(CONFIG_FILE, listener)
    }

    fun automaticReplacementPolicy(): AutomaticReplacementPolicy = replacementPolicy.get()

    fun similarityLevel(): LyricsSimilarity.Level = similarityLevel.get()

    override fun close() {
        manager.removeConfigChangeListener(listener)
    }

    private fun readPolicy(config: ConfigHelper): AutomaticReplacementPolicy =
        AutomaticReplacementPolicy.fromConfigValue(
            config.get(REPLACEMENT_POLICY_KEY, AutomaticReplacementPolicy.ALWAYS.configValue),
        )

    private fun readSimilarity(config: ConfigHelper): LyricsSimilarity.Level =
        LyricsSimilarity.Level.fromConfigValue(
            config.get(SIMILARITY_LEVEL_KEY, LyricsSimilarity.Level.STANDARD.configValue),
        )

    companion object {
        const val CONFIG_FILE = "spw-lyrics.json"
        const val REPLACEMENT_POLICY_KEY = "automatic_replacement_policy"
        const val SIMILARITY_LEVEL_KEY = "similarity_level"
    }
}
