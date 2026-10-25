package dev.gaboron.spwmedia.host

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import dev.gaboron.spwmedia.core.*
import dev.gaboron.spwmedia.protocol.Protocol
import dev.gaboron.spwmedia.processing.ProcessingProvider
import dev.gaboron.spwmedia.playback.PlaybackProvider
import dev.gaboron.spwmedia.web.WebProvider
import dev.gaboron.spwmedia.metadata.MetadataProvider
import dev.gaboron.spwmedia.audio.AudioProvider
import dev.gaboron.spwmedia.rendering.RenderingProvider
import java.nio.file.*
import java.lang.management.ManagementFactory
import javax.management.ObjectName

/** Host adapter owns only composition, installation paths and SPW lifecycle. */
class MediaRuntimePlugin(
    context: PluginContext,
    private val embeddedPluginId: String? = null,
    private val embeddedRoot: Path? = null,
    private val embeddedStorage: Path? = null,
) : SpwPlugin(context) {
    private var endpoint: RuntimeEndpoint? = null
    override fun start() {
        if (endpoint != null) return
        if (embeddedPluginId != null &&
            ManagementFactory.getPlatformMBeanServer().isRegistered(ObjectName(Protocol.OBJECT_NAME))) return
        val supplied = embeddedRoot ?: Path.of(pluginContext.pluginPath).toAbsolutePath()
        val contextRoot = if (Files.isDirectory(supplied)) supplied else supplied.parent
        val ownCode = Path.of(javaClass.protectionDomain.codeSource.location.toURI())
        val codeRoot = if (Files.isDirectory(ownCode) && ownCode.fileName.toString() == "classes") ownCode.parent else ownCode.parent
        val roots = listOf(contextRoot.resolve("classes"), contextRoot, codeRoot, codeRoot.parent).distinct()
        val root = roots.firstOrNull { Files.isDirectory(it.resolve("native")) } ?: contextRoot
        val pluginRoot = listOf(contextRoot, codeRoot.parent, root.parent)
            .firstOrNull { Files.isDirectory(it.resolve("lib")) } ?: contextRoot
        val storage = embeddedStorage ?: runtimeStorage()
        val classpath = buildList {
            listOf(pluginRoot, root).distinct().forEach { location ->
                if (Files.isDirectory(location)) Files.list(location).use { stream ->
                    stream.filter { it.toString().endsWith(".jar") }.forEach { add(it.toString()) }
                }
                val lib = location.resolve("lib")
                if (Files.isDirectory(lib)) Files.list(lib).use { stream ->
                    stream.filter { it.toString().endsWith(".jar") }.forEach { add(it.toString()) }
                }
            }
            // Worker JVM needs Kotlin; reuse the host's existing Kotlin location rather than bundling another copy.
            add(kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI().let { Path.of(it).toString() })
        }.joinToString(java.io.File.pathSeparator)
        val assets = NativeAssets(root.resolve("native"), storage.resolve("native").resolve(pluginContext.pluginVersion))
        val engine = RuntimeEngine(listOf(ProcessingProvider(), PlaybackProvider(), WebProvider(), MetadataProvider(), AudioProvider(), RenderingProvider()), assets, storage, emptyMap(), workerCommand = { mode ->
            when (mode) {
                "playback" -> WorkerCommands.playback(assets, classpath)
                "web", "capture" -> assets.find("platform")?.let { listOf(it.toString(), mode) }
                else -> null
            }
        })
        try {
            val identity = CallerIdentity(javaClass.classLoader)
            endpoint = RuntimeEndpoint(engine) { embeddedPluginId ?: identity.current() }
        } catch (e: Throwable) { engine.close(); throw e }
    }
    override fun stop() { endpoint?.close(); endpoint = null }
}
