package io.github.solitaire.spwlyrics.application

import io.github.solitaire.spwlyrics.domain.LyricsCandidate
import io.github.solitaire.spwlyrics.domain.LyricsDocument
import io.github.solitaire.spwlyrics.domain.LyricsSimilarity
import io.github.solitaire.spwlyrics.domain.InstrumentalLyrics
import io.github.solitaire.spwlyrics.domain.TrackQuery
import io.github.solitaire.spwlyrics.storage.CachedLyrics
import io.github.solitaire.spwlyrics.storage.LyricsCache
import io.github.solitaire.spwlyrics.storage.ManualOverride
import io.github.solitaire.spwlyrics.storage.LyricsDelaySettings
import io.github.solitaire.spwlyrics.codec.SpwLyricsEncoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

interface LyricsRefreshBridge {
    fun reloadCurrentLyrics(): Boolean
}

class LyricsLoadCoordinator(
    private val cache: LyricsCache,
    private val resolver: LyricsResolver,
    private val refreshBridge: LyricsRefreshBridge,
    private val notify: (String) -> Unit = {},
    private val executor: ExecutorService = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "spw-lyrics-worker").apply { isDaemon = true }
    },
    private val delays: LyricsDelaySettings? = null,
    private val output: LyricsOutputRouter? = null,
    private val similarityLevel: () -> LyricsSimilarity.Level = { LyricsSimilarity.Level.STANDARD },
) : AutoCloseable {
    private val refreshRetrier = LyricsRefreshRetrier(refreshBridge, notify)
    private val current = AtomicReference<TrackQuery?>()
    private val localLyrics = AtomicReference<Pair<String, LyricsDocument>?>()
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<*>>()
    private val activeAutomaticLoads = ConcurrentHashMap<String, Any>()
    private val qualityUpgradeInFlight = ConcurrentHashMap<String, CompletableFuture<*>>()
    private val qualityUpgradeAttempts = BackgroundAttemptGate(QUALITY_UPGRADE_RETRY_SECONDS)
    private val notifiedFailures = ConcurrentHashMap.newKeySet<String>()
    private val renderCacheLock = Any()
    private var renderedDocument: LyricsDocument? = null
    private var renderedDelay = Int.MIN_VALUE
    private var renderedLyrics: String? = null

    fun currentQuery(): TrackQuery? = current.get()

    fun selectionChanged() {
        inFlight.values.forEach { it.cancel(true) }; inFlight.clear()
        qualityUpgradeInFlight.values.forEach { it.cancel(true) }; qualityUpgradeInFlight.clear()
        activeAutomaticLoads.clear()
        current.get()?.takeUnless(::automaticWasSuperseded)?.let(::refreshOrNotify)
    }

    fun onLoad(
        query: TrackQuery,
        phase: LyricsLoadPhase,
        replacementPolicy: AutomaticReplacementPolicy,
        localTtml: (() -> LyricsDocument?)? = null,
    ): String? {
        current.set(query)
        val override = cache.getOverride(query)
        if (override == null && InstrumentalLyrics.isInstrumentalTitle(query.title)) return null
        var local: LyricsDocument? = null
        if (phase == LyricsLoadPhase.BEFORE_LOCAL) {
            local = if (override?.candidate == null) localTtml?.invoke() else null
            localLyrics.set(local?.let { query.key to it })
        } else if (override?.candidate == null) {
            // Reuse the local TTML document if SPW also invokes its after-missing hook.
            localLyrics.get()?.takeIf { it.first == query.key }?.let { return render(query, it.second) }
        }
        if (override?.local == true) return null
        if (local != null && InstrumentalLyrics.isPlaceholder(local)) return render(query, local)
        val automaticLoadAllowed = replacementPolicy.allowsAutomaticLoad(phase)
        // Local lyrics stay authoritative unless the policy asks for a verified plugin result.
        if (local != null && !automaticLoadAllowed) return render(query, local)
        if (override?.candidate == null && !automaticLoadAllowed) return null
        cache.getLyrics(query)?.takeIf { override?.candidate != null || it.selectionKey == resolver.selectionKey() }?.let { cached ->
            notifiedFailures.remove(query.key)
            if (InstrumentalLyrics.isPlaceholder(cached.document)) {
                return render(query, cached.document, cached.encoded)
            }
            if (override?.candidate == null && !activeAutomaticLoads.contains(query.key)) {
                startQualityUpgradeIfNeeded(query, resolvedFromCache(query, cached))
            }
            return render(query, cached.document, cached.encoded)
        }
        inFlight.computeIfAbsent(query.key) {
            val automaticToken = Any()
            if (override?.candidate == null) activeAutomaticLoads[query.key] = automaticToken
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(INITIAL_RESOLUTION_SECONDS)
            val future = CompletableFuture.runAsync({ resolveAndRefresh(query, override?.candidate, deadline) }, executor)
            future.whenComplete { _, error ->
                if (error != null && !future.isCancelled && override?.candidate == null && local == null) recordAutomaticFailure(query)
                activeAutomaticLoads.remove(query.key, automaticToken)
                inFlight.remove(query.key, future)
            }
            future
        }
        return local?.let { render(query, it) }
    }

    fun searchManual(query: TrackQuery, keywords: String, source: io.github.solitaire.spwlyrics.domain.LyricsSource?,
        onStatus: (io.github.solitaire.spwlyrics.domain.ProviderSearchStatus) -> Unit = {}) =
        resolver.searchManual(query, keywords, source, onStatus)

    fun preview(candidate: LyricsCandidate): ResolvedLyrics? = resolver.fetchManual(candidate)

    fun applyManual(query: TrackQuery, candidate: LyricsCandidate): Boolean {
        if (current.get()?.key != query.key) return false
        qualityUpgradeInFlight.remove(query.key)?.cancel(true)
        val resolved = resolver.fetchManual(candidate) ?: return false
        if (current.get()?.key != query.key) return false
        cache.putOverride(query, ManualOverride(local = false, source = candidate.source, candidate = candidate))
        inFlight.remove(query.key)?.cancel(true)
        cache.putLyrics(query, resolver.toCache(resolved))
        offer(query, resolved.document)
        notifiedFailures.remove(query.key)
        refreshOrNotify(query)
        return true
    }

    fun useLocal(query: TrackQuery): Boolean {
        if (current.get()?.key != query.key) return false
        cache.putOverride(query, ManualOverride(local = true))
        inFlight.remove(query.key)?.cancel(true)
        qualityUpgradeInFlight.remove(query.key)?.cancel(true)
        notifiedFailures.remove(query.key)
        output?.withdraw(query)
        refreshOrNotify(query)
        return true
    }

    /** True when the current track is showing an automatically fetched karaoke document. */
    fun canDisableCurrentLyrics(query: TrackQuery): Boolean {
        if (current.get()?.key != query.key) return false
        val override = cache.getOverride(query)
        if (override?.local == true || override?.candidate != null) return false
        val cached = cache.getLyrics(query) ?: return false
        return cached.document.lines.any { it.words.isNotEmpty() }
    }

    /** Blocks automatic lyrics for this track and falls back to its local lyrics. */
    fun disableCurrentLyrics(query: TrackQuery): Boolean = useLocal(query)

    fun useAutomatic(query: TrackQuery): Boolean {
        if (current.get()?.key != query.key) return false
        inFlight.remove(query.key)?.cancel(true)
        qualityUpgradeInFlight.remove(query.key)?.cancel(true)
        qualityUpgradeAttempts.clear(query.key)
        cache.removeOverride(query)
        cache.removeLyrics(query)
        output?.withdraw(query)
        notifiedFailures.remove(query.key)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(INITIAL_RESOLUTION_SECONDS)
        val automaticToken = Any()
        activeAutomaticLoads[query.key] = automaticToken
        val future = CompletableFuture.runAsync({ resolveAndRefresh(query, null, deadline) }, executor)
        inFlight[query.key] = future
        future.whenComplete { _, error ->
            if (error != null && !future.isCancelled) recordAutomaticFailure(query)
            activeAutomaticLoads.remove(query.key, automaticToken)
            inFlight.remove(query.key, future)
        }
        return true
    }

    fun setDelay(query: TrackQuery, delayMs: Int): Boolean {
        if (current.get()?.key != query.key) return false
        val settings = delays ?: return false
        settings.set(query, delayMs)
        output?.setDelay(query, delayMs)
        refreshOrNotify(query)
        return true
    }

    private fun resolveAndRefresh(query: TrackQuery, manual: LyricsCandidate?, deadlineNanos: Long) {
        if (manual == null) {
            resolveAutomaticProgressively(query, deadlineNanos)
            return
        }
        val fetched = resolver.fetchManual(manual)
        if (fetched == null) {
            return
        }
        cache.putLyrics(query, resolver.toCache(fetched))
        notifiedFailures.remove(query.key)
        if (current.get()?.key == query.key) {
            offer(query, fetched.document)
            refreshOrNotify(query)
        }
    }

    private fun resolveAutomaticProgressively(query: TrackQuery, snapshotDeadlineNanos: Long) {
        val reference = localReference(query)
        val final = resolver.resolveAutomaticProgressively(query, snapshotDeadlineNanos, reference, similarityLevel()) { interim ->
            applyAutomaticResult(query, interim)
        }
        if (final == null) {
            // Local lyrics are already displayed; a rejected online result is not a failure.
            if (reference == null) recordAutomaticFailure(query)
            return
        }
        if (automaticWasSuperseded(query)) return
        applyAutomaticResult(query, final)
    }

    private fun applyAutomaticResult(query: TrackQuery, result: ResolvedLyrics) {
        if (automaticWasSuperseded(query)) return
        if (result.selectionKey != resolver.selectionKey()) return
        val cached = cache.getLyrics(query)?.takeIf { it.selectionKey == result.selectionKey }
        if (cached != null && !resolver.canReplace(cached.document, result.document)) return
        if (cached?.document == result.document) return
        cache.putLyrics(query, resolver.toCache(result))
        notifiedFailures.remove(query.key)
        if (current.get()?.key == query.key) {
            offer(query, result.document)
            refreshOrNotify(query)
        }
    }

    private fun startQualityUpgradeIfNeeded(query: TrackQuery, resolved: ResolvedLyrics) {
        if (!resolver.shouldUpgrade(resolved.document)) return
        if (qualityUpgradeInFlight.containsKey(query.key) || !qualityUpgradeAttempts.allow(query.key, force = false)) return
        val future = CompletableFuture.runAsync({ upgradeQualityAndRefresh(query, resolved) }, executor)
        val previous = qualityUpgradeInFlight.putIfAbsent(query.key, future)
        if (previous != null) {
            future.cancel(true)
            return
        }
        future.whenComplete { _, _ -> qualityUpgradeInFlight.remove(query.key, future) }
    }

    private fun upgradeQualityAndRefresh(query: TrackQuery, fallback: ResolvedLyrics) {
        if (automaticWasSuperseded(query)) return
        val upgraded = resolver.resolveAutomaticFully(query, localReference(query), similarityLevel())
        if (automaticWasSuperseded(query)) return
        val cached = cache.getLyrics(query) ?: return
        if (cached.document != fallback.document) return
        if (upgraded != null && resolver.canReplace(cached.document, upgraded.document)) {
            applyAutomaticResult(query, upgraded)
        }
    }

    private fun localReference(query: TrackQuery): LyricsDocument? =
        localLyrics.get()?.takeIf { it.first == query.key }?.second

    private fun resolvedFromCache(query: TrackQuery, cached: CachedLyrics): ResolvedLyrics = ResolvedLyrics(
        candidate = LyricsCandidate(
            source = cached.document.source,
            remoteId = "",
            title = query.title,
            artists = query.artists,
            album = query.album,
            durationMs = query.durationMs,
            externalIds = query.externalIds,
        ),
        document = cached.document,
    )

    private fun recordAutomaticFailure(query: TrackQuery) {
        if (automaticWasSuperseded(query)) return
        notifyAutomaticFailure(query)
    }

    private fun automaticWasSuperseded(query: TrackQuery): Boolean =
        cache.getOverride(query)?.let { it.local || it.candidate != null } == true

    private fun notifyAutomaticFailure(query: TrackQuery) {
        if (current.get()?.key == query.key && notifiedFailures.add(query.key)) {
            notify("自动加载歌词失败；可从播放器“更多 → 手动搜索歌词”重新搜索。")
        }
    }

    private fun refreshOrNotify(query: TrackQuery) {
        refreshRetrier.refresh { current.get()?.key == query.key }
    }

    private fun render(query: TrackQuery, document: LyricsDocument, encoded: String? = null): String? {
        val delay = delays?.get(query) ?: 0
        if (output != null) return output.render(query, document, delay, encoded)
        if (delay == 0 && encoded != null) return encoded
        synchronized(renderCacheLock) {
            if (document === renderedDocument && delay == renderedDelay) renderedLyrics?.let { return it }
        }
        val result = SpwLyricsEncoder.encode(document, delay)
        synchronized(renderCacheLock) {
            renderedDocument = document
            renderedDelay = delay
            renderedLyrics = result
        }
        return result
    }

    private fun offer(query: TrackQuery, document: LyricsDocument) {
        output?.offer(query, document, delays?.get(query) ?: 0)
    }

    override fun close() {
        current.set(null)
        localLyrics.set(null)
        synchronized(renderCacheLock) {
            renderedDocument = null
            renderedLyrics = null
        }
        output?.close()
        activeAutomaticLoads.clear()
        inFlight.values.forEach { it.cancel(true) }
        qualityUpgradeInFlight.values.forEach { it.cancel(true) }
        executor.shutdownNow()
        resolver.close()
    }

    private companion object {
        const val INITIAL_RESOLUTION_SECONDS = 10L
        const val QUALITY_UPGRADE_RETRY_SECONDS = 60L
    }
}
