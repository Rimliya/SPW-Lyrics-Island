package io.github.solitaire.spwlyrics.provider

import io.github.solitaire.spwlyrics.domain.LyricsSource
import io.github.solitaire.spwlyrics.domain.ProviderSearchPhase
import io.github.solitaire.spwlyrics.domain.TrackQuery
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmllIndexStoreTest {
    @Test
    fun coldIndexBuildDoesNotBlockTheSearchCaller() {
        val root = Files.createTempDirectory("spw-amll-index")
        val http = BlockingHttp(INDEX)
        val store = AmllIndexStore(root, http, Clock.systemUTC())
        val query = TrackQuery("Song", listOf("Artist"), "Album")
        try {
            val initial = store.search(query, "Song", 10)
            assertTrue(initial.isEmpty())
            assertTrue(http.entered.await(2, TimeUnit.SECONDS))

            val statuses = mutableListOf<ProviderSearchPhase>()
            val statusLatch = CountDownLatch(1)
            val manual = store.searchManual(query, "Song", 10) { status ->
                statuses += status.phase
                if (status.phase == ProviderSearchPhase.AVAILABLE) statusLatch.countDown()
            }
            assertTrue(manual.isEmpty())

            http.release.countDown()
            assertTrue(statusLatch.await(3, TimeUnit.SECONDS))
            assertTrue(awaitResult(store, query))
            assertEquals(LyricsSource.AMLL, store.search(query, "Song", 10).single().source)
            assertTrue(statuses.contains(ProviderSearchPhase.SEARCHING))
        } finally {
            store.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun freshLocalIndexLoadsWithoutNetwork() {
        val root = Files.createTempDirectory("spw-amll-local")
        Files.writeString(root.resolve("index.jsonl"), INDEX)
        val http = BlockingHttp(INDEX)
        val store = AmllIndexStore(root, http, Clock.fixed(Instant.parse("2026-10-27T00:00:00Z"), ZoneOffset.UTC))
        val query = TrackQuery("Song", listOf("Artist"), "Album")
        try {
            store.search(query, "Song", 10)
            assertTrue(awaitResult(store, query))
            assertEquals(0, http.calls)
        } finally {
            store.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun awaitResult(store: AmllIndexStore, query: TrackQuery): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            if (store.search(query, "Song", 10).isNotEmpty()) return true
            Thread.sleep(20)
        }
        return false
    }

    private class BlockingHttp(private val content: String) : ProviderHttp {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var calls = 0

        override fun get(url: String, headers: Map<String, String>): String {
            calls++
            entered.countDown()
            release.await(3, TimeUnit.SECONDS)
            return content
        }

        override fun postJson(url: String, body: String, headers: Map<String, String>): String =
            error("unexpected post")

        override fun postForm(url: String, values: Map<String, String>, headers: Map<String, String>): String =
            error("unexpected post")
    }

    private companion object {
        const val INDEX = "{\"id\":\"one\",\"metadata\":[[\"musicName\",[\"Song\"]],[\"artists\",[\"Artist\"]],[\"album\",[\"Album\"]],[\"appleMusicId\",[\"123\"]]],\"rawLyricFile\":\"one.ttml\"}\n"
    }
}