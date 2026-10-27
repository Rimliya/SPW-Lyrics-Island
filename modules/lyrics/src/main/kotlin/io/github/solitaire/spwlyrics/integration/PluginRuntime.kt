package io.github.solitaire.spwlyrics.integration

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.PluginPermissionDeniedException
import io.github.solitaire.spwlyrics.application.AutomaticReplacementPolicy
import io.github.solitaire.spwlyrics.application.LyricsLoadPhase
import io.github.solitaire.spwlyrics.application.LyricsLoadCoordinator
import io.github.solitaire.spwlyrics.application.LyricsBatchProcessor
import io.github.solitaire.spwlyrics.application.LyricsResolver
import io.github.solitaire.spwlyrics.application.LyricsPrecache
import io.github.solitaire.spwlyrics.application.LyricsOutputRouter
import io.github.solitaire.spwlyrics.domain.LyricsCandidate
import io.github.solitaire.spwlyrics.domain.LyricsDocument
import io.github.solitaire.spwlyrics.domain.LyricsSimilarity
import io.github.solitaire.spwlyrics.domain.LyricsSource
import io.github.solitaire.spwlyrics.domain.TrackQuery
import io.github.solitaire.spwlyrics.domain.ProviderSearchStatus
import io.github.solitaire.spwlyrics.provider.AmllProvider
import io.github.solitaire.spwlyrics.provider.KugouMusicProvider
import io.github.solitaire.spwlyrics.provider.LocalLyricsProvider
import io.github.solitaire.spwlyrics.provider.NeteaseMusicProvider
import io.github.solitaire.spwlyrics.provider.ProviderHttpClient
import io.github.solitaire.spwlyrics.provider.QqMusicProvider
import io.github.solitaire.spwlyrics.provider.SodaMusicProvider
import io.github.solitaire.spwlyrics.provider.AppleMusicProvider
import io.github.solitaire.spwlyrics.provider.LrcShareProvider
import io.github.solitaire.spwlyrics.provider.MusicBrainzProvider
import io.github.solitaire.spwlyrics.storage.DataDirectory
import io.github.solitaire.spwlyrics.storage.DEFAULT_LYRICS_CACHE_BYTES
import io.github.solitaire.spwlyrics.storage.FileLyricsCache
import io.github.solitaire.spwlyrics.integration.manualui.ManualSearchOperations
import io.github.solitaire.spwlyrics.integration.metadataui.MetadataEditorOperations
import io.github.solitaire.spwlyrics.integration.metadataui.MetadataFilePicker
import io.github.solitaire.spwlyrics.integration.workspace.LyricsWorkspace
import io.github.solitaire.spwlyrics.integration.workspace.LyricsWorkspaceSession
import io.github.solitaire.spwlyrics.integration.workspace.LyricsToolPage
import io.github.solitaire.spwlyrics.integration.hostui.HostUiIntegration
import io.github.solitaire.spwlyrics.storage.FileLyricsDelaySettings
import io.github.solitaire.spwlyrics.storage.FileTimingCalibrationStore
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object PluginRuntime {
    @Volatile private var coordinator: LyricsLoadCoordinator? = null
    @Volatile private var batchProcessor: LyricsBatchProcessor? = null
    @Volatile private var precache: LyricsPrecache? = null
    @Volatile private var workspace: LyricsWorkspace? = null
    @Volatile private var timingPlayback: SpwTimingPlayback? = null
    @Volatile private var hostUi: HostUiIntegration? = null
    @Volatile private var settings: PluginSettings? = null
    @Volatile private var cacheFolderOpener: CacheFolderOpener? = null
    @Volatile private var lyricsCache: FileLyricsCache? = null
    @Volatile private var amllProvider: AmllProvider? = null
    @Volatile private var maintenanceExecutor: ScheduledExecutorService? = null
    @Volatile private var maintenanceTask: ScheduledFuture<*>? = null
    private val libraryTracks = SpwLibraryTrackSource()
    private val localTtmlLyrics = LocalTtmlLyricsLoader()

    @Synchronized
    @OptIn(UnstableSpwWorkshopApi::class)
    fun install() {
        if (coordinator != null) return
        val root = DataDirectory.root
        val cacheDirectory = root.resolve("cache")
        val cache = FileLyricsCache(cacheDirectory)
        lyricsCache = cache
        val delays = FileLyricsDelaySettings(root.resolve("歌词延迟"))
        cacheFolderOpener = CacheFolderOpener(cacheDirectory)
        settings = PluginSettings(WorkshopApi.manager.createConfigManager(), {
            coordinator?.selectionChanged()
            coordinator?.currentQuery()?.let { precache?.trackChanged(it, force = true) }
        }, ::toastWarning)
        val http = ProviderHttpClient()
        val amll = AmllProvider(
            cacheDirectory.resolve("AMLL 索引"),
            ProviderHttpClient(requestTimeout = Duration.ofSeconds(6)),
            legacyIndexPath = root.resolve("amll").resolve("amll-index.jsonl"),
        ).also { amllProvider = it }
        val providers = listOf(
            amll,
            QqMusicProvider(http),
            KugouMusicProvider(http),
            NeteaseMusicProvider(http),
            SodaMusicProvider(http),
            AppleMusicProvider(http),
            LrcShareProvider(http) { settings?.lrcShareApiKey().orEmpty() },
            MusicBrainzProvider(http),
            LocalLyricsProvider(),
        )
        val resolver = LyricsResolver(providers, preferences = { settings?.lyricsPreferences() ?: io.github.solitaire.spwlyrics.domain.LyricsPreferences() })
        val refreshBridge = ReflectiveLyricsRefreshBridge()
        val output = LyricsOutputRouter(
            receiver = JmxTtmlReceiver(),
            isCurrent = { query ->
                WorkshopApi.playback.getCurrentMediaItem()?.let { item ->
                    item.path == query.path && (query.trackId.isBlank() || item.id.isBlank() || query.trackId == item.id)
                } == true
            },
            refresh = { refreshBridge.reloadCurrentLyrics() },
        )
        coordinator = LyricsLoadCoordinator(
            cache = cache,
            resolver = resolver,
            refreshBridge = refreshBridge,
            notify = ::toastWarning,
            delays = delays,
            output = output,
            prepareQuery = libraryTracks::prepare,
            similarityLevel = { settings?.similarityLevel() ?: LyricsSimilarity.Level.STANDARD },
        )
        precache = LyricsPrecache(
            loadLibrary = libraryTracks::loadForPrecache,
            prepare = { query ->
                val local = localTtmlLyrics.load(query.path)
                coordinator?.precache(query, hasLocalLyrics = local != null, reference = local)
            },
            enabled = { settings?.prefetchNextTrack() == true },
        )
        val batch = LyricsBatchProcessor(
            loadLibrary = libraryTracks::load,
            cache = cache,
            resolver = resolver,
            localLyrics = ::localLyrics,
            similarityLevel = { settings?.similarityLevel() ?: LyricsSimilarity.Level.STANDARD },
            replacementPolicy = { settings?.automaticReplacementPolicy() ?: AutomaticReplacementPolicy.ALWAYS },
        )
        batchProcessor = batch
        val describeBatchFailure: (Throwable) -> String = { error ->
            if (error is PluginPermissionDeniedException) "请在 SPW 的插件权限中授予曲库读取权限后重试。"
            else "批量处理失败：${error.message ?: "未知错误"}"
        }
        val metadataOperations = object : MetadataEditorOperations {
            override fun currentAudioPath() = currentQuery()?.path
            override fun chooseAudioFile() = MetadataFilePicker.audio()
            override fun chooseLyricsFile() = MetadataFilePicker.lyrics()
            override fun chooseCoverFile() = MetadataFilePicker.cover()
            override fun openSearch(query: TrackQuery) { workspace?.searchMetadata(query) }
            override fun afterSave(path: java.nio.file.Path) {
                val currentPath = WorkshopApi.playback.getCurrentMediaItem()?.path
                if (currentPath != null && runCatching { java.nio.file.Path.of(currentPath).toRealPath() == path }.getOrDefault(false)) {
                    check(refreshBridge.reloadCurrentLyrics()) { "请切换歌曲后重新播放以加载内嵌歌词。" }
                }
            }
        }
        val manualOperations = object : ManualSearchOperations {
            override fun currentQuery() = PluginRuntime.currentQuery()
            override fun search(query: TrackQuery, keywords: String, source: LyricsSource?, onStatus: (ProviderSearchStatus) -> Unit) =
                checkNotNull(coordinator) { "歌词搜索尚未启用。" }.searchManual(query, keywords, source, onStatus)
            override fun preview(candidate: LyricsCandidate) = coordinator?.preview(candidate)
            override fun apply(query: TrackQuery, candidate: LyricsCandidate) =
                isCurrentTrack(query) && coordinator?.applyManual(query, candidate) == true
            override fun useLocal(query: TrackQuery) = isCurrentTrack(query) && coordinator?.useLocal(query) == true
            override fun useAutomatic(query: TrackQuery) = isCurrentTrack(query) && coordinator?.useAutomatic(query) == true
            override fun delay(query: TrackQuery) = delays.get(query)
            override fun setDelay(query: TrackQuery, delayMs: Int) =
                isCurrentTrack(query) && coordinator?.setDelay(query, delayMs) == true
            override fun metadata(candidate: LyricsCandidate) = resolver.metadata(candidate)
            override fun editMetadata(query: TrackQuery, candidate: LyricsCandidate, document: LyricsDocument?, preserveDraft: Boolean) {
                workspace?.importSearch(query, candidate, document, preserveDraft)
            }
        }
        val timingTransport = SpwTimingPlayback().also { timingPlayback = it }
        val calibration = FileTimingCalibrationStore(root.resolve("打轴输入校准.properties"))
        workspace = LyricsWorkspace({ LyricsWorkspaceSession(manualOperations, metadataOperations, batch, describeBatchFailure,
            timingTransport, calibration, BassTimingAudioDecoder()) }, ::toastWarning)
        hostUi = HostUiIntegration(checkNotNull(workspace), ::toastWarning).also { it.start() }
        startCacheMaintenance()
    }

    fun beforeLoad(mediaItem: PlaybackExtensionPoint.MediaItem): String? =
        load(mediaItem, LyricsLoadPhase.BEFORE_LOCAL) {
            localTtmlLyrics.load(mediaItem.path)
        }
    fun afterLocalLyricsMissing(mediaItem: PlaybackExtensionPoint.MediaItem): String? =
        load(mediaItem, LyricsLoadPhase.AFTER_LOCAL_MISSING)
    fun currentQuery(): TrackQuery? = runCatching { libraryTracks.current() }.getOrElse { error ->
        toastWarning(if (error is PluginPermissionDeniedException) {
            "请在 SPW 的插件权限中授予曲库读取权限。"
        } else {
            "读取当前歌曲失败：${error.message ?: "未知错误"}"
        })
        null
    }
    @Synchronized
    fun openManualSearch() {
        workspace?.open(LyricsToolPage.SEARCH)
    }
    @Synchronized
    fun openBatchProcessing() {
        workspace?.open(LyricsToolPage.BATCH)
    }
    @Synchronized
    fun openMetadataEditor() {
        workspace?.open(LyricsToolPage.METADATA)
    }
    fun openWorkspace() = workspace?.open(LyricsToolPage.BATCH)
    fun timingSeeked() { timingPlayback?.seeked() }
    fun openCacheFolder() {
        if (cacheFolderOpener?.open() != true) toastWarning("无法打开 SPW Lyrics 本地缓存文件夹。")
    }

    fun clearLyricsCache() {
        val cache = lyricsCache
        val executor = maintenanceExecutor
        if (cache == null || executor == null) {
            toastWarning("歌词缓存尚未初始化。")
            return
        }
        executor.execute {
            val result = runCatching { cache.clearCachedLyrics() }
            result.onSuccess { cleaned ->
                toastWarning("已清理歌词缓存：${cleaned.deletedFiles} 个文件，释放 ${formatBytes(cleaned.removedBytes)}。")
            }.onFailure {
                toastWarning("清理歌词缓存失败：${it.message ?: "未知错误"}")
            }
        }
    }

    fun clearAmllIndex() {
        val provider = amllProvider
        val executor = maintenanceExecutor
        if (provider == null || executor == null) {
            toastWarning("AMLL 索引尚未初始化。")
            return
        }
        executor.execute {
            val removed = runCatching { provider.clearIndex() }.getOrDefault(false)
            toastWarning(if (removed) "已清理 AMLL 索引，下次搜索时会重新获取。" else "AMLL 索引已为空或清理失败。")
        }
    }

    private fun startCacheMaintenance() {
        maintenanceTask?.cancel(false)
        maintenanceExecutor?.shutdownNow()
        val executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "spw-lyrics-cache-maintenance").apply { isDaemon = true }
        }
        maintenanceExecutor = executor
        maintenanceTask = executor.scheduleWithFixedDelay({
            runCatching {
                lyricsCache?.prune(LYRICS_CACHE_LIMIT_BYTES, coordinator?.currentQuery())
                amllProvider?.enforceIndexLimit(AMLL_INDEX_LIMIT_BYTES)
            }
        }, 45, 24 * 60, TimeUnit.MINUTES)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }

    fun resetLyricsOrder() {
        toastWarning(if (settings?.resetSelection() == true) "已恢复默认歌词排序。关闭并重新打开设置可查看默认顺序。" else "重置未完成，请重新打开设置后重试。")
    }

    fun canDisableCurrentLyrics(): Boolean {
        val active = coordinator ?: return false
        val query = currentQuery() ?: return false
        return runCatching { active.canDisableCurrentLyrics(query) }.getOrDefault(false)
    }

    fun disableCurrentLyrics(): Boolean {
        val active = coordinator ?: return false
        val query = currentQuery() ?: return false
        Thread({
            val disabled = runCatching { active.disableCurrentLyrics(query) }.getOrDefault(false)
            runCatching {
                WorkshopApi.ui.toast(
                    if (disabled) "已禁用本曲自动歌词；可在插件设置中打开手动搜索选择正确歌词。"
                    else "未禁用：当前歌词已切换或不可用。",
                    if (disabled) WorkshopApi.Ui.ToastType.Warning else WorkshopApi.Ui.ToastType.Error,
                )
            }
        }, "spw-lyrics-disable-current").apply { isDaemon = true }.start()
        return true
    }

    @Synchronized
    fun close() {
        maintenanceTask?.cancel(false); maintenanceTask = null
        maintenanceExecutor?.shutdownNow(); maintenanceExecutor = null
        lyricsCache = null
        amllProvider = null
        hostUi?.close()
        hostUi = null
        workspace?.close()
        workspace = null
        timingPlayback = null
        cacheFolderOpener = null
        settings?.close()
        settings = null
        batchProcessor?.close()
        batchProcessor = null
        precache?.close()
        precache = null
        coordinator?.close()
        coordinator = null
        libraryTracks.clear()
    }

    private fun load(
        mediaItem: PlaybackExtensionPoint.MediaItem,
        phase: LyricsLoadPhase,
        localTtml: (() -> LyricsDocument?)? = null,
    ): String? {
        val query = libraryTracks.fromLyricsCallback(mediaItem)
        val result = coordinator?.onLoad(
            query,
            phase,
            settings?.automaticReplacementPolicy() ?: AutomaticReplacementPolicy.ALWAYS,
            localTtml,
        )
        precache?.trackChanged(query)
        return result
    }

    private fun isCurrentTrack(query: TrackQuery): Boolean = currentQuery()?.key == query.key

    private fun localLyrics(query: TrackQuery): LyricsDocument? =
        query.path.takeIf(String::isNotBlank)?.let(localTtmlLyrics::load)

    private fun toastWarning(message: String) {
        runCatching { WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Warning) }
    }

    private const val LYRICS_CACHE_LIMIT_BYTES = DEFAULT_LYRICS_CACHE_BYTES
    private const val AMLL_INDEX_LIMIT_BYTES = 16L * 1024 * 1024
}
