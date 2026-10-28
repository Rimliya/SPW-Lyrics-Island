package io.github.solitaire.spwlyrics.provider

import io.github.solitaire.spwlyrics.domain.LyricsCandidate
import io.github.solitaire.spwlyrics.domain.LyricsSource
import io.github.solitaire.spwlyrics.domain.ProviderSearchPhase
import io.github.solitaire.spwlyrics.domain.ProviderSearchStatus
import io.github.solitaire.spwlyrics.domain.TrackQuery
import java.awt.Desktop
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class AmllIndexStore(
    private val cacheDirectory: Path,
    private val http: ProviderHttp,
    private val clock: Clock,
    private val legacyIndexPath: Path? = null,
) : AutoCloseable {
    private val indexPath = cacheDirectory.resolve(INDEX_FILE_NAME)
    private val legacyCacheIndexPath = cacheDirectory.resolve(LEGACY_INDEX_FILE_NAME)
    private val lock = Any()
    private val refreshExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "spw-amll-index-refresh").apply { isDaemon = true }
    }
    private val refreshListeners = mutableListOf<(ProviderSearchStatus) -> Unit>()

    @Volatile private var searchIndex: AmllSearchIndex? = null
    @Volatile private var lastRefreshAttempt: Instant? = null
    @Volatile private var refreshFailure: Throwable? = null
    @Volatile private var loading = false
    @Volatile private var refreshing = false
    @Volatile private var closed = false
    private var generation = 0L
    private var refreshTask: Future<*>? = null

    fun search(query: TrackQuery, keywords: String, limit: Int, manual: Boolean = false): List<LyricsCandidate> {
        ensureCurrent()
        return searchIndex?.search(query, keywords, limit, manual).orEmpty()
    }

    fun searchManual(
        query: TrackQuery,
        keywords: String,
        limit: Int,
        onStatus: (ProviderSearchStatus) -> Unit,
    ): List<LyricsCandidate> {
        val previousAttempt = lastRefreshAttempt
        ensureCurrent()
        val status = synchronized(lock) {
            val busy = loading || refreshing
            if (busy) refreshListeners += onStatus
            statusLocked(previousAttempt)
        }
        onStatus(status)
        return searchIndex?.search(query, keywords, limit, manual = true).orEmpty()
    }

    fun indexSizeBytes(): Long = indexFiles().mapNotNull { path ->
        runCatching { if (Files.isRegularFile(path)) Files.size(path) else null }.getOrNull()
    }.maxOrNull() ?: 0L

    fun enforceIndexLimit(maxBytes: Long): Boolean {
        if (maxBytes <= 0L || indexSizeBytes() > maxBytes) return clearIndex()
        return false
    }

    fun clearIndex(): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        generation++
        refreshTask?.cancel(true)
        refreshTask = null
        loading = false
        refreshing = false
        refreshFailure = null
        lastRefreshAttempt = null
        searchIndex = AmllSearchIndex(emptyList())
        refreshListeners.clear()
        indexFiles().forEach { runCatching { Files.deleteIfExists(it) } }
        true
    }

    private fun ensureCurrent() {
        val now = clock.instant()
        synchronized(lock) {
            if (closed || loading || refreshing) return
            runCatching { Files.createDirectories(cacheDirectory) }
            val existing = searchIndex
            if (existing == null) {
                if (!mayAttemptRefreshLocked(now)) return
                lastRefreshAttempt = now
                refreshFailure = null
                loading = true
                val request = generation
                refreshTask = refreshExecutor.submit { loadThenMaybeRefresh(now, request) }
            } else {
                if (!isStale(now) || !mayAttemptRefreshLocked(now)) return
                lastRefreshAttempt = now
                refreshFailure = null
                refreshing = true
                val request = generation
                refreshTask = refreshExecutor.submit { refreshExisting(now, request) }
            }
        }
    }

    private fun loadThenMaybeRefresh(requestedAt: Instant, request: Long) {
        val result = runCatching {
            val local = loadIndexFile()
            if (local != null && isCurrent(request)) publish(local)
            if (local == null || local.isEmpty || isStale(requestedAt)) {
                synchronized(lock) { if (isCurrentLocked(request)) refreshing = true }
                val refreshed = refresh(requestedAt, request)
                if (isCurrent(request)) publish(refreshed)
            }
        }
        finish(result, request)
    }

    private fun refreshExisting(requestedAt: Instant, request: Long) {
        val result = runCatching {
            val refreshed = refresh(requestedAt, request)
            if (isCurrent(request)) publish(refreshed)
        }
        finish(result, request)
    }

    private fun finish(result: Result<Unit>, request: Long) {
        val listeners = synchronized(lock) {
            if (!isCurrentLocked(request)) return
            val refreshed = refreshing
            loading = false
            refreshing = false
            refreshFailure = result.exceptionOrNull()
            refreshTask = null
            val status = statusLocked(lastRefreshAttempt).copy(
                resultsChanged = refreshed && result.isSuccess && searchIndex?.isEmpty == false,
            )
            refreshListeners.toList().also { refreshListeners.clear() } to status
        }
        if (!closed) listeners.first.forEach { runCatching { it(listeners.second) } }
    }

    private fun statusLocked(previousAttempt: Instant?): ProviderSearchStatus = when {
        loading -> ProviderSearchStatus(LyricsSource.AMLL, ProviderSearchPhase.SEARCHING, "正在准备 AMLL 本地索引；完成后请再次搜索。")
        refreshing -> ProviderSearchStatus(LyricsSource.AMLL, ProviderSearchPhase.SEARCHING,
            if (searchIndex?.isEmpty == false) "正在更新索引（使用本地索引）。" else "正在获取 AMLL 索引。")
        refreshFailure != null -> indexFailure(checkNotNull(refreshFailure))
        lastRefreshAttempt != previousAttempt -> ProviderSearchStatus(LyricsSource.AMLL, ProviderSearchPhase.AVAILABLE, "索引获取成功。")
        searchIndex?.isEmpty == false -> ProviderSearchStatus(LyricsSource.AMLL, ProviderSearchPhase.AVAILABLE, "本地索引可用；本次未检查网络。")
        else -> ProviderSearchStatus(LyricsSource.AMLL, ProviderSearchPhase.FAILED, "索引获取失败，请稍后重试。")
    }

    private fun indexFailure(error: Throwable): ProviderSearchStatus {
        val failure = ProviderSearchFailure.status(LyricsSource.AMLL, error)
        val reason = if (searchIndex?.isEmpty == false) "更新失败（使用本地索引）" else "索引获取失败"
        return failure.copy(detail = "$reason：${failure.detail}")
    }

    private fun isStale(now: Instant): Boolean = !Files.isRegularFile(indexPath) ||
        !Files.getLastModifiedTime(indexPath).toInstant().plus(INDEX_TTL).isAfter(now)

    private fun mayAttemptRefreshLocked(now: Instant): Boolean {
        val delay = if (Files.isRegularFile(indexPath)) REFRESH_RETRY_DELAY else INITIAL_REFRESH_RETRY_DELAY
        return lastRefreshAttempt?.plus(delay)?.isAfter(now) != true
    }

    private fun refresh(now: Instant, request: Long): AmllSearchIndex = runCatching {
        val content = http.get(AmllProvider.INDEX_URL)
        check(isCurrent(request) && !Thread.currentThread().isInterrupted) { "AMLL index refresh cancelled" }
        val records = parseRecords(content.lineSequence())
        check(records.isNotEmpty()) { "AMLL index contains no valid records" }
        val temporary = Files.createTempFile(cacheDirectory, "amll-index", ".tmp")
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            Files.setLastModifiedTime(temporary, FileTime.from(now))
            check(isCurrent(request) && !Thread.currentThread().isInterrupted) { "AMLL index refresh cancelled" }
            moveReplacing(temporary, indexPath)
        } finally {
            if (Files.exists(temporary)) {
                val recycled = runCatching {
                    Desktop.isDesktopSupported() &&
                        Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH) &&
                        Desktop.getDesktop().moveToTrash(temporary.toFile())
                }.getOrDefault(false)
                if (!recycled) System.getLogger("SPW Lyrics").log(System.Logger.Level.WARNING, "AMLL 临时索引保留在：$temporary")
            }
        }
        AmllSearchIndex(records)
    }.getOrThrow()

    private fun loadIndexFile(): AmllSearchIndex? {
        val available = indexFiles().firstOrNull(Files::isRegularFile) ?: return null
        return Files.newBufferedReader(available, StandardCharsets.UTF_8).use { reader ->
            AmllSearchIndex(parseRecords(reader.lineSequence()))
        }
    }

    private fun publish(index: AmllSearchIndex) {
        searchIndex = index
        refreshFailure = null
    }

    private fun indexFiles(): List<Path> = listOfNotNull(indexPath, legacyCacheIndexPath, legacyIndexPath).distinct()

    private fun moveReplacing(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun parseRecords(lines: Sequence<String>): List<AmllRecord> = lines.mapNotNull(::parseRecord).toList()

    private fun isCurrent(request: Long): Boolean = synchronized(lock) { isCurrentLocked(request) }

    private fun isCurrentLocked(request: Long): Boolean = !closed && generation == request

    private fun parseRecord(line: String): AmllRecord? = runCatching {
        val root = providerJson.parseToJsonElement(line) as JsonObject
        val metadata = (root["metadata"] as? JsonArray).orEmpty().associate { pairElement ->
            val pair = pairElement as JsonArray
            val key = (pair[0] as JsonPrimitive).content
            val values = (pair[1] as JsonArray).map { (it as JsonPrimitive).content }
            key to values
        }
        val rawLyricFile = root.string("rawLyricFile")?.takeIf(String::isNotBlank)
        val id = rawLyricFile ?: root.string("id")?.takeIf(String::isNotBlank) ?: return@runCatching null
        AmllRecord(
            id = id,
            titles = metadata["musicName"].orEmpty().filter(String::isNotBlank).ifEmpty { return@runCatching null },
            artists = metadata["artists"].orEmpty(),
            albums = metadata["album"].orEmpty(),
            ids = metadata.filterKeys { it.endsWith("Id", true) },
            rawLyricFile = rawLyricFile,
        )
    }.getOrNull()

    override fun close() {
        val task = synchronized(lock) {
            closed = true
            generation++
            val current = refreshTask
            refreshTask = null
            refreshListeners.clear()
            current
        }
        task?.cancel(true)
        refreshExecutor.shutdownNow()
    }

    private companion object {
        const val INDEX_FILE_NAME = "raw-lyrics-index.jsonl"
        const val LEGACY_INDEX_FILE_NAME = "index.jsonl"
        val INDEX_TTL: Duration = Duration.ofHours(6)
        val INITIAL_REFRESH_RETRY_DELAY: Duration = Duration.ofSeconds(30)
        val REFRESH_RETRY_DELAY: Duration = Duration.ofMinutes(5)
    }
}
