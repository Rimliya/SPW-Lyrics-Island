// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package io.github.solitaire.spwisland.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.solitaire.spwisland.core.PlaybackTimeline
import io.github.solitaire.spwisland.core.SpectrumMode
import io.github.solitaire.spwisland.core.performance
import io.github.solitaire.spwisland.ui.*
import io.github.solitaire.spwisland.platform.ProcessSpectrum
import io.github.solitaire.spwisland.platform.LinuxProcessSpectrum
import io.github.solitaire.spwisland.platform.SpectrumSource
import io.github.solitaire.spwisland.ui.ComposeFontPickerWindow
import com.sun.jna.Platform
import io.github.solitaire.spwisland.remote.LinuxIslandProcess
import javax.swing.SwingUtilities
import io.github.solitaire.spwisland.diagnostics.IslandLog

class IslandRuntime(private val menuActions: IslandMenuActions? = null) : AutoCloseable {
    val timeline = PlaybackTimeline()
    private val metadata = TrackMetadataLoader(timeline, performance = { settings.read().performance },
        enabled = { settings.read().let { it.enabled || it.spout.enabled } })
    private val external = ExternalPlaybackSession(timeline, { settings.read().performance }, { settings.read().enabled || settings.read().spout.enabled }) {
        metadata.recoverMissingTrack(allowIdle = true)
        metadata.observeCurrent(force = true)
    }
    fun trackChanged(track: io.github.solitaire.spwisland.core.Track,
                     item: com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem? = null) {
        IslandLog.event("playback", "track.changed")
        metadata.load(track, item)
    }
    fun stateChanged(value: io.github.solitaire.spwisland.core.PlaybackStatus) {
        timeline.stateChanged(value)
        metadata.recoverMissingTrack()
        metadata.observeCurrent()
    }
    fun playingChanged(value: Boolean) {
        timeline.playingChanged(value)
        metadata.recoverMissingTrack()
        metadata.observeCurrent()
    }
    fun positionChanged(value: Long) {
        timeline.positionChanged(value)
        metadata.recoverMissingTrack()
        metadata.observeCurrent()
    }
    fun seekChanged(value: Long) {
        timeline.seek(value)
    }
    fun lineChanged(line: io.github.solitaire.spwisland.core.LyricLine?) = timeline.lineChanged(line)
    fun lyricsChanged(lines: List<io.github.solitaire.spwisland.core.LyricLine>) {
        IslandLog.event("playback", "lyrics.document_changed", mapOf("lines" to lines.size))
        timeline.lyricsChanged(lines)
    }
    private val ui: IslandUiLifecycle? = if (Platform.isLinux()) null else IslandUiLifecycle(::createWindow, ::report)
    private var linuxWindow: LinuxIslandProcess? = null
    private val spectrum: SpectrumSource = if (Platform.isLinux()) LinuxProcessSpectrum()
        else ProcessSpectrum(::notifySpectrumFallback)
    @Volatile private var closed = false
    private val settings = HostSettings(WorkshopApi.manager.createConfigManager()) {
        updateSpectrumMode()
        if (!closed) ui?.reload()
    }
    private val fontPicker = if (Platform.isWindows()) ComposeFontPickerWindow({ selection ->
        safely {
            settings.setFont(selection.family, selection.weight)
        }
    }, ::report) else null
    private val shortcut = IslandShortcut { safely { settings.set("enabled", !settings.read().enabled) } }
    private val volume = HostVolumeControl(::report)
    fun start() {
        updateSpectrumMode()
        shortcut.start()
        metadata.start()
        external.start()
        if (Platform.isLinux()) {
            linuxWindow = LinuxIslandProcess(external, settings, playbackActions(), ::report, spectrum)
        } else ui?.start()
    }
    private fun createWindow(): IslandWindow = IslandWindow(external, settings, playbackActions(), ::report,
        spectrum::levels, spectrum::usesSyntheticFallback, { spectrum.status }, menuActions)

    private fun playbackActions(): PlaybackActions = object : PlaybackActions {
        override fun previous() = safely("playback.previous") {
            val track = external.current
            if (track != null) check(external.client.control(track, "previous")) {
                "外部来源未接受上一曲操作，请确认当前歌曲和来源能力。"
            } else WorkshopApi.playback.previous()
        }
        override fun toggle() = safely("playback.toggle") {
            val track = external.current
            if (track != null) check(external.client.control(track, if (external.snapshot().playing) "pause" else "play")) {
                "外部来源未接受播放操作，请确认当前歌曲和来源能力。"
            }
            else if (timeline.snapshot().playing) WorkshopApi.playback.pause() else WorkshopApi.playback.play()
        }
        override fun next() = safely("playback.next") {
            val track = external.current
            if (track != null) check(external.client.control(track, "next")) {
                "外部来源未接受下一曲操作，请确认当前歌曲和来源能力。"
            } else WorkshopApi.playback.next()
        }
        override fun seek(positionMs: Long) = safely("playback.seek") {
            val track = external.current
            if (track == null) {
                WorkshopApi.playback.seekTo(positionMs)
                timeline.seek(positionMs)
            } else {
                check(external.client.seek(track, positionMs)) { "外部来源未接受跳转，请确认当前歌曲和来源能力。" }
                external.seekPreview(track, positionMs)
            }
        }
        override fun adjustVolume(delta: Double, completed: (Double?) -> Unit) {
            if (external.current == null) volume.adjust(delta, completed)
            else completed(external.changeVolume(delta, absolute = false))
        }
        override fun setVolume(value: Double, completed: (Double?) -> Unit) {
            if (external.current == null) volume.set(value, completed)
            else completed(external.changeVolume(value, absolute = true))
        }
    }
    private fun updateSpectrumMode() {
        val current = settings.read()
        // The Spout2 output is independent of desktop visibility and keeps its live spectrum.
        IslandLog.cadence(current.performance.diagnosticSampleIntervalNs)
        spectrum.setEnabled((current.enabled || current.spout.enabled) &&
            current.performance.spectrumMode == SpectrumMode.LIVE &&
            current.showsSpectrum && (!Platform.isLinux() || external.snapshot().playing))
    }
    fun recover() = safely("window.recover") {
        settings.set("auto_hide_on_hover", false); settings.set("click_through", false)
        settings.set("enabled", true); settings.resetPosition()
    }
    fun installGnomePointer() {
        if (!Platform.isLinux() || closed) return
        Thread({ safely {
            val message = io.github.solitaire.spwisland.platform.LinuxHelper.installGnomePointer()
            if (!closed) WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Warning)
        } }, "SPW Island GNOME setup").apply { isDaemon = true; uncaughtExceptionHandler = IslandLog.uncaught("IslandRuntime") }.start()
    }
    fun resetSettings() = safely { settings.resetAll() }
    fun about() {
        if (closed) return
        linuxWindow?.about() ?: ui?.about()
    }
    fun chooseFont() {
        if (!closed) fontPicker?.show(settings.read())
    }

    fun openSource() { SwingUtilities.invokeLater { IslandLog.guard("IslandRuntime", "invokeLater.callback_failed") { if (!closed) safely { ProjectLinks.openSource() } } } }
    private fun safely(name: String = "operation", block: () -> Unit) {
        val operation = IslandLog.operation("runtime", name)
        try { block(); operation.close() }
        catch (error: Exception) { operation.failed(error); report(error) }
    }
    private fun report(error: Throwable) {
        IslandLog.failure("runtime", "operation.failed", error)
        IslandLog.attempt("IslandRuntime", "report.attempt1") { WorkshopApi.ui.toast(error.message ?: "词岛操作失败", WorkshopApi.Ui.ToastType.Error) }
    }
    private fun notifySpectrumFallback(message: String) {
        IslandLog.attempt("IslandRuntime", "notifySpectrumFallback.attempt2") { WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Warning) }
    }
    override fun close() {
        if (closed) return
        closed = true
        try { ui?.close() } catch (error: Throwable) { IslandLog.failure("IslandRuntime", "close.ui.failed", error) }
        val remote = linuxWindow
        linuxWindow = null
        listOfNotNull(external, shortcut, volume, remote, fontPicker, metadata, settings, spectrum).forEach { resource ->
            try { resource.close() } catch (error: Throwable) {
                IslandLog.failure("IslandRuntime", "caught.error", error); report(error) }
        }
    }
}
