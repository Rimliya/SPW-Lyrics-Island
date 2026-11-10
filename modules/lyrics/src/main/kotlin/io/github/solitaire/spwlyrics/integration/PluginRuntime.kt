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
import io.github.solitaire.spwlyrics.application.LyricsRefreshBridge
import io.github.solitaire.spwlyrics.application.LyricsStartupRecovery
import io.github.solitaire.spwlyrics.codec.TtmlCodec
import io.github.solitaire.spwlyrics.codec.MetadataTimedLyricsReader
import io.github.solitaire.spwlyrics.domain.LyricsFormat
import io.github.solitaire.spwlyrics.domain.LyricsCandidate
import io.github.solitaire.spwlyrics.domain.LyricsDocument
import io.github.solitaire.spwlyrics.domain.LyricsSimilarity
import io.github.solitaire.spwlyrics.domain.LyricsSource
import io.github.solitaire.spwlyrics.domain.TrackQuery
import io.github.solitaire.spwlyrics.domain.ProviderSearchStatus
import io.github.solitaire.spwlyrics.codec.MetadataLyricsFormat
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
import io.github.solitaire.spwlyrics.storage.LyricsUiStateStore
import io.github.solitaire.spwlyrics.storage.FileTimingCalibrationStore
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object PluginRuntime {
    @Volatile private var coordinator: LyricsLoadCoordinator? = null
    @Volatile private var startupRecovery: LyricsStartupRecovery? = null
    @Volatile private var startupRecoveryExecutor: ScheduledExecutorService? = null
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

    private val externalPlayback = ExternalPlaybackClient()
    @Volatile private var externalTrack: ExternalPlayback? = null
    private var externalSourceRevision = -1L
    private var hostRestorePending = false
    private var hostRestoreAt = 0L
    private var externalTask: ScheduledFuture<*>? = null
    private val libraryTracks = SpwLibraryTrackSource { externalPlayback.snapshot()?.toQuery() }
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
        val uiState = LyricsUiStateStore(root.resolve("工作区状态.properties"))
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
            indexHttp = ProviderHttpClient(connectTimeout = Duration.ofSeconds(5), requestTimeout = Duration.ofSeconds(30)),
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
        val hostRefreshBridge = ReflectiveLyricsRefreshBridge()
        lateinit var installedCoordinator: LyricsLoadCoordinator
        val refreshBridge = object : LyricsRefreshBridge {
            override fun reloadCurrentLyrics(): Boolean {
                if (coordinator !== installedCoordinator) return false
                val track = externalPlayback.snapshot()
                if (track != null) {
                    loadExternal(track, installedCoordinator)
                    return true
                }
                return hostRefreshBridge.reloadCurrentLyrics()
            }
        }
        val output = LyricsOutputRouter(
            receiver = JmxTtmlReceiver(external = externalPlayback),
            isCurrent = { query ->
                if (query.isExternalPlayback) externalPlayback.snapshot()?.let {
                    it.uri == query.playbackUri && it.sessionId == query.playbackSessionId &&
                        it.trackRevision == query.playbackTrackRevision && it.provider == query.playbackProvider &&
                        it.sourceName.canonicalName == query.playbackObjectName
                } == true else externalPlayback.snapshot() == null &&
                    WorkshopApi.playback.getCurrentMediaItem()?.let { item ->
                        item.path == query.path && (query.trackId.isBlank() || item.id.isBlank() || query.trackId == item.id)
                    } == true
            },
            refresh = { refreshBridge.reloadCurrentLyrics() },
        )
        installedCoordinator = LyricsLoadCoordinator(
            cache = cache,
            resolver = resolver,
            refreshBridge = refreshBridge,
            notify = ::toastWarning,
            delays = delays,
            output = output,
            prepareQuery = libraryTracks::prepare,
            similarityLevel = { settings?.similarityLevel() ?: LyricsSimilarity.Level.STANDARD },
        )
        coordinator = installedCoordinator
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
        val afterMetadataSaved: (io.github.solitaire.spwlyrics.storage.AudioMetadataSnapshot, Boolean) -> Unit = { snapshot, lyricsChanged ->
            val path = snapshot.path
            val current = currentQuery()
            val isCurrent = current != null && runCatching { java.nio.file.Path.of(current.path).toRealPath() == path }.getOrDefault(false)
            localTtmlLyrics.invalidate(path.toString())
            if (lyricsChanged) {
                val query = if (isCurrent) current else TrackQuery("", emptyList(), "", path = path.toString())
                coordinator?.savedLyrics(query)
                java.awt.EventQueue.invokeLater { workspace?.session?.manual?.localLyricsSaved(query.path) }
            } else if (isCurrent) {
                check(refreshBridge.reloadCurrentLyrics()) { "请切换歌曲后重新播放以加载内嵌歌词。" }
            }
        }
        val metadataOperations = object : MetadataEditorOperations {
            override fun lyricsFormat() = MetadataLyricsFormat.fromStorageValue(uiState.lyricsFormat())
            override fun saveLyricsFormat(format: MetadataLyricsFormat) = uiState.rememberLyricsFormat(format.name)
            override fun metadataImportFillMissing() = uiState.metadataImportFillMissing()
            override fun saveMetadataImportFillMissing(value: Boolean) = uiState.rememberMetadataImportFillMissing(value)
            override fun currentAudioPath() = currentQuery()?.path?.takeIf(String::isNotBlank)
            override fun chooseAudioFile() = MetadataFilePicker.audio()
            override fun chooseLyricsFile() = MetadataFilePicker.lyrics()
            override fun chooseCoverFile() = MetadataFilePicker.cover()
            override fun openSearch(query: TrackQuery) { workspace?.searchMetadata(query) }
            override fun afterSave(snapshot: io.github.solitaire.spwlyrics.storage.AudioMetadataSnapshot, lyricsChanged: Boolean) =
                afterMetadataSaved(snapshot, lyricsChanged)
        }
        val manualOperations = object : ManualSearchOperations {
            override fun candidateSort() = uiState.candidateSort()
            override fun saveCandidateSort(value: String) = uiState.rememberCandidateSort(value)
            override fun searchSource() = uiState.searchSource()
            override fun saveSearchSource(value: String?) = uiState.rememberSearchSource(value)
            override fun applied(query: TrackQuery) = coordinator?.manualSelection(query)
            override fun currentQuery() = PluginRuntime.currentQuery()
            override fun search(query: TrackQuery, keywords: String, source: LyricsSource?, onStatus: (ProviderSearchStatus) -> Unit) =
                checkNotNull(coordinator) { "歌词搜索尚未启用。" }.searchManual(query, keywords, source, onStatus)
            override fun preview(candidate: LyricsCandidate) = coordinator?.preview(candidate)
            override fun automatic(query: TrackQuery) = coordinator?.previewAutomatic(query)
            override fun apply(query: TrackQuery, candidate: LyricsCandidate) =
                isCurrentTrack(query) && coordinator?.applyManual(query, candidate) == true
            override fun useLocal(query: TrackQuery) = isCurrentTrack(query) && coordinator?.useLocal(query) == true
            override fun useAutomatic(query: TrackQuery) = isCurrentTrack(query) && coordinator?.useAutomatic(query) == true
            override fun delay(query: TrackQuery) = delays.get(query)
            override fun setDelay(query: TrackQuery, delayMs: Int) =
                isCurrentTrack(query) && coordinator?.setDelay(query, delayMs) == true
            override fun metadata(candidate: LyricsCandidate) = resolver.metadata(candidate)
            override fun editMetadata(query: TrackQuery, candidate: LyricsCandidate, document: LyricsDocument?, preserveDraft: Boolean) {
                check(!query.isExternalPlayback && localMediaPath(query.path) != null) {
                    "外部音源的歌词选择保存在插件中。写入文件请先打开本地歌曲。"
                }
                workspace?.importSearch(query, candidate, document, preserveDraft)
            }
        }
        val timingTransport = SpwTimingPlayback().also { timingPlayback = it }
        val calibration = FileTimingCalibrationStore(root.resolve("打轴输入校准.properties"))
        workspace = LyricsWorkspace({ LyricsWorkspaceSession(manualOperations, metadataOperations, batch, describeBatchFailure,
            timingTransport, calibration, BassTimingAudioDecoder()) }, ::toastWarning)
        hostUi = HostUiIntegration(checkNotNull(workspace), ::toastWarning).also { it.start() }
        val recoveryExecutor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "spw-lyrics-startup-recovery").apply { isDaemon = true }
        }
        startupRecoveryExecutor = recoveryExecutor
        startupRecovery = LyricsStartupRecovery(recoveryExecutor, ::currentQuery, refreshBridge, { SpwLyricsExtension.discovered }).also { it.start() }
        startCacheMaintenance()
        externalTask = recoveryExecutor.scheduleWithFixedDelay({
            pollExternal(hostRefreshBridge, installedCoordinator)
        }, 0, 500, TimeUnit.MILLISECONDS)
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
        }, 45, 24 * 60 * 60, TimeUnit.SECONDS)
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
        externalTask?.cancel(false); externalTask = null
        externalTrack = null
        externalSourceRevision = -1L
        hostRestorePending = false
        hostRestoreAt = 0L
        startupRecovery?.close()
        startupRecovery = null
        startupRecoveryExecutor?.shutdownNow()
        startupRecoveryExecutor = null
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
        if (coordinator == null || externalPlayback.snapshot() != null) return null
        val query = libraryTracks.fromLyricsCallback(mediaItem)
        val result = coordinator?.onLoad(
            query,
            phase,
            settings?.automaticReplacementPolicy() ?: AutomaticReplacementPolicy.ALWAYS,
            localTtml,
            { localTtmlLyrics.loadEmbedded(mediaItem.path) },
        )
        if (phase == LyricsLoadPhase.BEFORE_LOCAL) startupRecovery?.beforeLoadCompleted(query)
        precache?.trackChanged(query)
        return result
    }

    @Synchronized
    private fun pollExternal(hostRefresh: LyricsRefreshBridge, target: LyricsLoadCoordinator) {
        // A cancelled tick from an earlier installation cannot mutate a restarted runtime.
        if (coordinator !== target) return
        runCatching {
            val next = externalPlayback.snapshot()
            val previous = externalTrack
            externalTrack = next
            val selected = target.currentQuery()
            val sourceRevision = next?.let(externalPlayback::sourceLyrics)?.revision ?: -1L
            if (next != null && (next.identity != previous?.identity || selected != next.toQuery() ||
                sourceRevision != externalSourceRevision)) loadExternal(next, target)
            else if (next == null) {
                if (previous != null) hostRestorePending = true
                if (selected != null && !selected.isExternalPlayback) hostRestorePending = false
                val now = System.nanoTime()
                if (hostRestorePending && now >= hostRestoreAt) {
                    hostRestorePending = !runCatching { hostRefresh.reloadCurrentLyrics() }.getOrDefault(false)
                    hostRestoreAt = now + 2_000_000_000L
                }
                val host = libraryTracks.current()
                if (host != null && localMediaPath(host.path) == null && host != selected) {
                    val policy = settings?.automaticReplacementPolicy() ?: AutomaticReplacementPolicy.ALWAYS
                    val missing = runCatching { WorkshopApi.playback.getLyricsLines().isEmpty() }.getOrDefault(false)
                    val phase = if (policy == AutomaticReplacementPolicy.WHEN_LOCAL_MISSING && missing)
                        LyricsLoadPhase.AFTER_LOCAL_MISSING else LyricsLoadPhase.BEFORE_LOCAL
                    target.onLoad(host, phase, policy)
                }
            }
            if (next != null) hostRestorePending = false
            externalSourceRevision = sourceRevision
        }
    }

    private fun loadExternal(track: ExternalPlayback, target: LyricsLoadCoordinator) {
        if (coordinator !== target || externalPlayback.snapshot()?.identity != track.identity) return
        val query = track.toQuery()
        val policy = settings?.automaticReplacementPolicy() ?: AutomaticReplacementPolicy.ALWAYS
        val original = externalPlayback.sourceLyrics(track)?.document()
        target.onLoad(query, LyricsLoadPhase.BEFORE_LOCAL, policy,
            localTtml = if (policy == AutomaticReplacementPolicy.ALWAYS) null else ({ original }),
            localEmbedded = { original })
        if (policy == AutomaticReplacementPolicy.WHEN_LOCAL_MISSING && original == null)
            target.onLoad(query, LyricsLoadPhase.AFTER_LOCAL_MISSING, policy)
    }

    private fun ExternalLyrics.document(): LyricsDocument? = runCatching {
        if (content.length > 2_000_000 || spl.length > 2_000_000) return null
        val document = when (format) {
            "empty" -> LyricsDocument(LyricsSource.LOCAL, LyricsFormat.PLAIN, emptyList())
            "ttml" -> TtmlCodec().parse(content, LyricsSource.LOCAL)
            "lrc" -> MetadataTimedLyricsReader.parse(spl.ifBlank { content }, mergeSameTimeRows = spl.isNotBlank())
            else -> return null
        }
        document.copy(metadata = document.metadata +
            (EXTERNAL_SOURCE_OFFSET to listOf(offsetMs.coerceIn(-600_000, 600_000).toString())))
    }.getOrNull()

    private fun ExternalPlayback.toQuery() = TrackQuery(title, TrackQuery.splitArtists(artist), album,
        durationMs = durationMs.takeIf { it > 0 }, trackId = uri,
        playbackProvider = provider, playbackUri = uri, playbackSessionId = sessionId, playbackTrackRevision = trackRevision,
        playbackObjectName = sourceName.canonicalName)

    private fun isCurrentTrack(query: TrackQuery): Boolean = currentQuery()?.let {
        if (query.isExternalPlayback) it == query else it.key == query.key
    } == true

    private fun localLyrics(query: TrackQuery): LyricsDocument? =
        query.path.takeIf(String::isNotBlank)?.let(localTtmlLyrics::load)

    private fun toastWarning(message: String) {
        runCatching { WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Warning) }
    }

    private const val LYRICS_CACHE_LIMIT_BYTES = DEFAULT_LYRICS_CACHE_BYTES
    private const val AMLL_INDEX_LIMIT_BYTES = 16L * 1024 * 1024
}

internal const val EXTERNAL_SOURCE_OFFSET = "spw.externalSourceOffsetMs"
