package dev.gaboron.spwlyrics.integration

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.PluginPermissionDeniedException
import dev.gaboron.spwlyrics.application.AutomaticReplacementPolicy
import dev.gaboron.spwlyrics.application.LyricsLoadPhase
import dev.gaboron.spwlyrics.application.LyricsLoadCoordinator
import dev.gaboron.spwlyrics.application.LyricsBatchProcessor
import dev.gaboron.spwlyrics.application.LyricsPrefetcher
import dev.gaboron.spwlyrics.application.LyricsResolver
import dev.gaboron.spwlyrics.application.LyricsOutputRouter
import dev.gaboron.spwlyrics.domain.LyricsCandidate
import dev.gaboron.spwlyrics.domain.LyricsDocument
import dev.gaboron.spwlyrics.domain.LyricsSimilarity
import dev.gaboron.spwlyrics.domain.LyricsSource
import dev.gaboron.spwlyrics.domain.TrackQuery
import dev.gaboron.spwlyrics.provider.AmllProvider
import dev.gaboron.spwlyrics.provider.KugouMusicProvider
import dev.gaboron.spwlyrics.provider.LocalLyricsProvider
import dev.gaboron.spwlyrics.provider.NeteaseMusicProvider
import dev.gaboron.spwlyrics.provider.ProviderHttpClient
import dev.gaboron.spwlyrics.provider.QqMusicProvider
import dev.gaboron.spwlyrics.storage.DataDirectory
import dev.gaboron.spwlyrics.storage.FileLyricsCache
import dev.gaboron.spwlyrics.integration.manualui.ManualSearchWindow
import dev.gaboron.spwlyrics.integration.manualui.ManualSearchOperations
import dev.gaboron.spwlyrics.integration.batchui.BatchProcessingWindow
import dev.gaboron.spwlyrics.integration.metadataui.MetadataEditorWindow
import dev.gaboron.spwlyrics.storage.FileLyricsDelaySettings
import java.time.Duration
import kotlin.io.path.Path

object PluginRuntime {
    @Volatile private var coordinator: LyricsLoadCoordinator? = null
    @Volatile private var batchProcessor: LyricsBatchProcessor? = null
    @Volatile private var batchWindow: BatchProcessingWindow? = null
    @Volatile private var settings: PluginSettings? = null
    @Volatile private var prefetcher: LyricsPrefetcher? = null
    @Volatile private var manualWindow: ManualSearchWindow? = null
    @Volatile private var metadataWindow: MetadataEditorWindow? = null
    @Volatile private var manualSearchShortcut: SpwManualSearchBinding? = null
    @Volatile private var metadataEditorShortcut: SpwMetadataEditorBinding? = null
    @Volatile private var cacheFolderOpener: CacheFolderOpener? = null
    private val libraryTracks = SpwLibraryTrackSource()
    private val nextTracks = SpwNextTrackSource(libraryTracks)
    private val localTtmlLyrics = LocalTtmlLyricsLoader()
    private const val PREFETCH_DELAY_MILLIS = 2_000L

    @Synchronized
    @OptIn(UnstableSpwWorkshopApi::class)
    fun install() {
        if (coordinator != null) return
        val root = DataDirectory.root
        val cacheDirectory = root.resolve("cache")
        val cache = FileLyricsCache(cacheDirectory)
        val delays = FileLyricsDelaySettings(root.resolve("歌词延迟"))
        cacheFolderOpener = CacheFolderOpener(cacheDirectory)
        val http = ProviderHttpClient()
        val providers = listOf(
            AmllProvider(
                cacheDirectory.resolve("AMLL 索引"),
                ProviderHttpClient(requestTimeout = Duration.ofSeconds(6)),
                legacyIndexPath = root.resolve("amll").resolve("amll-index.jsonl"),
            ),
            QqMusicProvider(http),
            KugouMusicProvider(http),
            NeteaseMusicProvider(http),
            LocalLyricsProvider(),
        )
        val resolver = LyricsResolver(providers)
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
            similarityLevel = { settings?.similarityLevel() ?: LyricsSimilarity.Level.STANDARD },
        )
        val batch = LyricsBatchProcessor(
            loadLibrary = libraryTracks::load,
            cache = cache,
            resolver = resolver,
            localLyrics = ::localLyrics,
        )
        batchProcessor = batch
        batchWindow = BatchProcessingWindow(batch, ::toastWarning) { error ->
            if (error is PluginPermissionDeniedException) "请在 SPW 的插件权限中授予曲库读取权限后重试。"
            else "批量处理失败：${error.message ?: "未知错误"}"
        }
        metadataWindow = MetadataEditorWindow(
            readCurrentPath = { currentQuery()?.path },
            refreshSavedPath = { path ->
                val currentPath = WorkshopApi.playback.getCurrentMediaItem()?.path
                if (currentPath != null && runCatching { java.nio.file.Path.of(currentPath).toRealPath() == path }.getOrDefault(false)) {
                    check(refreshBridge.reloadCurrentLyrics()) { "请切换歌曲后重新播放以加载内嵌歌词。" }
                }
            },
            openLyricsSearch = ::openManualSearch,
            onFailure = ::toastWarning,
        )
        manualWindow = ManualSearchWindow(object : ManualSearchOperations {
            override fun currentQuery() = PluginRuntime.currentQuery()
            override fun search(query: TrackQuery, keywords: String, source: LyricsSource?) =
                coordinator?.searchManual(query, keywords, source).orEmpty()
            override fun preview(candidate: LyricsCandidate) = coordinator?.preview(candidate)
            override fun apply(query: TrackQuery, candidate: LyricsCandidate) =
                isCurrentTrack(query) && coordinator?.applyManual(query, candidate) == true
            override fun useLocal(query: TrackQuery) = isCurrentTrack(query) && coordinator?.useLocal(query) == true
            override fun useAutomatic(query: TrackQuery) = isCurrentTrack(query) && coordinator?.useAutomatic(query) == true
            override fun delay(query: TrackQuery) = delays.get(query)
            override fun setDelay(query: TrackQuery, delayMs: Int) =
                isCurrentTrack(query) && coordinator?.setDelay(query, delayMs) == true
            override fun openBatch() = openBatchProcessing()
            override fun openMetadata() = openMetadataEditor()
            override fun editMetadata(query: TrackQuery, document: LyricsDocument) {
                metadataWindow?.importSearch(query, document)
            }
        }, ::toastWarning)
        settings = PluginSettings(WorkshopApi.manager.createConfigManager())
        prefetcher = LyricsPrefetcher(
            cache = cache,
            nextTrack = nextTracks::next,
            resolve = { query ->
                val local = localLyrics(query)
                resolver.resolveAutomaticFully(
                    query,
                    reference = local,
                    similarity = settings?.similarityLevel() ?: LyricsSimilarity.Level.STANDARD,
                )?.let(resolver::toCache)
            },
            enabled = { settings?.prefetchNextTrack() == true },
            policy = { settings?.automaticReplacementPolicy() ?: AutomaticReplacementPolicy.ALWAYS },
            hasLocalLyrics = { query -> localLyrics(query) != null },
            localLyrics = ::localLyrics,
            delayMillis = PREFETCH_DELAY_MILLIS,
        )
        val shortcut = SpwManualSearchBinding(::openManualSearch)
        manualSearchShortcut = shortcut
        runCatching { shortcut.register() }.onFailure {
            toastWarning("无法注册歌词搜索快捷键，请检查 SPW 的快捷键权限和冲突设置。")
        }.onSuccess { granted ->
            if (!granted) toastWarning("歌词搜索快捷键未启用：请在 SPW 的插件权限中授予快捷键权限。")
        }
        val metadataShortcut = SpwMetadataEditorBinding(::openMetadataEditor)
        metadataEditorShortcut = metadataShortcut
        runCatching { metadataShortcut.register() }.onFailure {
            toastWarning("无法注册元数据编辑快捷键，请检查 SPW 的快捷键权限和设置。")
        }
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
        manualWindow?.open()
    }
    @Synchronized
    fun openBatchProcessing() {
        batchWindow?.open()
    }
    @Synchronized
    fun openMetadataEditor() {
        metadataWindow?.open()
    }
    fun openCacheFolder() {
        if (cacheFolderOpener?.open() != true) toastWarning("无法打开 SPW Lyrics 本地缓存文件夹。")
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
        manualSearchShortcut?.close()
        manualSearchShortcut = null
        metadataEditorShortcut?.close()
        metadataEditorShortcut = null
        manualWindow?.close()
        manualWindow = null
        metadataWindow?.close()
        metadataWindow = null
        cacheFolderOpener = null
        prefetcher?.close()
        prefetcher = null
        settings?.close()
        settings = null
        batchWindow?.close()
        batchWindow = null
        batchProcessor?.close()
        batchProcessor = null
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
        prefetcher?.onTrackLoaded(query)
        val result = coordinator?.onLoad(
            query,
            phase,
            settings?.automaticReplacementPolicy() ?: AutomaticReplacementPolicy.ALWAYS,
            localTtml,
        )
        return result
    }

    private fun localLyrics(query: TrackQuery): LyricsDocument? =
        query.path.takeIf(String::isNotBlank)?.let(localTtmlLyrics::load)

    private fun isCurrentTrack(query: TrackQuery): Boolean = currentQuery()?.key == query.key

    private fun toastWarning(message: String) {
        runCatching { WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Warning) }
    }

}
