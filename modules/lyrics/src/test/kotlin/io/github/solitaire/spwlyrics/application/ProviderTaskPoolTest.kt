package io.github.solitaire.spwlyrics.application

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderTaskPoolTest {
    @Test
    fun collectLimitsParallelTasksAndKeepsResultOrder() {
        val pool = ProviderTaskPool(parallelism = 2)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        try {
            val results = pool.collect(
                tasks = (0 until 8).map { index ->
                    {
                        val now = active.incrementAndGet()
                        maximum.accumulateAndGet(now, ::maxOf)
                        try {
                            Thread.sleep(20)
                            "result-$index"
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                },
                deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
            )
            assertEquals((0 until 8).map { "result-$it" }, results.map { it.value })
            assertTrue(maximum.get() <= 2)
        } finally {
            pool.close()
        }
    }
}