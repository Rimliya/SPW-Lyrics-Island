package io.github.solitaire.spwlyrics.provider

/** A provider task owns its open connections; cancelling it closes blocking network reads. */
internal class ProviderCancellation {
    private val lock = Any()
    private var cancelled = false
    private val connections = mutableSetOf<() -> Unit>()

    fun cancel() {
        val pending = synchronized(lock) {
            cancelled = true
            connections.toList().also { connections.clear() }
        }
        pending.forEach { runCatching(it) }
    }

    fun <T> run(block: () -> T): T {
        val previous = current.get()
        current.set(this)
        try {
            checkActive()
            return block().also { checkActive() }
        } finally {
            current.set(previous)
        }
    }

    private fun register(close: () -> Unit): AutoCloseable {
        synchronized(lock) {
            if (!cancelled) {
                connections.add(close)
                return AutoCloseable { synchronized(lock) { connections.remove(close) } }
            }
        }
        runCatching(close)
        throw InterruptedException("Provider request cancelled")
    }

    companion object {
        private val current = ThreadLocal<ProviderCancellation?>()

        fun checkActive() {
            if (Thread.currentThread().isInterrupted || current.get()?.let { synchronized(it.lock) { it.cancelled } } == true)
                throw InterruptedException("Provider request cancelled")
        }

        fun registerConnection(close: () -> Unit): AutoCloseable {
            checkActive()
            return current.get()?.register(close) ?: AutoCloseable { }
        }
    }
}
