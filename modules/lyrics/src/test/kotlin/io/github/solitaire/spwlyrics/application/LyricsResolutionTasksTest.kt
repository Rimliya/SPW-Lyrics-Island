package io.github.solitaire.spwlyrics.application

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class LyricsResolutionTasksTest {
    @Test fun changedValidationReplacesOldWorkWhileIdenticalValidationSharesIt() {
        val executor = Executors.newFixedThreadPool(2)
        LyricsResolutionTasks(executor).use { tasks ->
            val firstStarted = CountDownLatch(1)
            val secondStarted = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val first = tasks.start("track", context = "old-reference") {
                    firstStarted.countDown()
                    CountDownLatch(1).await(5, TimeUnit.SECONDS)
                }
                assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
                val second = tasks.start("track", context = "new-reference") { active ->
                    secondStarted.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    assertTrue(active())
                }
                assertTrue(first.isCancelled)
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
                val shared = tasks.start("track", context = "new-reference") { error("Duplicate work") }
                assertSame(second, shared)
                release.countDown()
                second.get(5, TimeUnit.SECONDS)
            } finally { release.countDown() }
        }
    }
}
