package io.github.solitaire.spwlyrics.application

import io.github.solitaire.spwlyrics.provider.ProviderHttpClient
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ProviderHttpCancellationTest {
    @Test fun cancellingProviderClosesBlockedReadAndFreesItsWorker() {
        ServerSocket(0).use { server ->
            val started = CountDownLatch(1)
            val disconnected = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            ProviderTaskPool(1).use { pool ->
                val peer = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream()
                        val reader = input.bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\nConnection: close\r\n\r\nx".toByteArray())
                            flush()
                        }
                        started.countDown()
                        if (input.read() == -1) disconnected.countDown()
                    }
                }
                val work = executor.submit {
                    val client = ProviderHttpClient(requestTimeout = Duration.ofSeconds(30))
                    pool.collect(listOf({ client.get("http://127.0.0.1:${server.localPort}/lyrics") }), Long.MAX_VALUE)
                }
                try {
                    assertTrue(started.await(5, TimeUnit.SECONDS))
                    work.cancel(true)
                    assertTrue(disconnected.await(3, TimeUnit.SECONDS), "Cancelled request kept its socket open")
                    assertEquals("next", pool.collect(listOf({ "next" }),
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(3)).single().value)
                    peer.get(5, TimeUnit.SECONDS)
                } finally { work.cancel(true); executor.shutdownNow() }
            }
        }
    }
}
