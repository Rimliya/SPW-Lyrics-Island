package io.github.solitaire.spwlyrics.application

import java.nio.file.Path
import io.github.solitaire.spwlyrics.domain.TimingPcm
import io.github.solitaire.spwlyrics.domain.TimingAudioDependency

internal interface TimingAudioDecoder : AutoCloseable {
    fun dependency() = TimingAudioDependency(true)
    fun decode(path: Path, startMs: Long, durationMs: Long, cancelled: () -> Boolean): TimingPcm
    fun cancel()
    override fun close() = cancel()
}
