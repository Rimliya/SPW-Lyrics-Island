package io.github.solitaire.spwlyricsisland

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import io.github.solitaire.spwlyrics.integration.BassTimingAudioDecoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sqrt

/** Manual host-BASS smoke test; not part of CI because the build machine has no bass.dll. */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: BassTimingSmoke <bass-dir> <audio-file>..." }
    val bassDirectory = Path.of(args[0]).toAbsolutePath()
    val bassDll = bassDirectory.resolve("bass.dll")
    require(Files.isRegularFile(bassDll)) { "bass.dll not found: $bassDll" }
    System.setProperty("compose.application.resources.dir", bassDirectory.toString())

    val bass = Native.load(bassDll.toString(), SmokeBass::class.java)
    check(bass.BASS_Init(0, 44_100, BASS_DEVICE_DECODEFLOAT, null, null)) {
        "BASS_Init failed: ${bass.BASS_ErrorGetCode()}"
    }
    try {
        for (argument in args.drop(1)) {
            val audio = Path.of(argument).toAbsolutePath()
            require(Files.isRegularFile(audio)) { "audio file not found: $audio" }
            BassTimingAudioDecoder().use { decoder ->
                check(decoder.dependency().available) { "BASS decoder dependency is unavailable" }
                val started = System.nanoTime()
                val pcm = decoder.decode(audio, 5_000, 8_000) { false }
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val rms = sqrt(pcm.samples.sumOf { (it * it).toDouble() } / pcm.samples.size)
                check(pcm.sampleRate == 16_000) { "unexpected sample rate ${pcm.sampleRate}" }
                check(pcm.samples.size in 64_000..128_100) { "unexpected sample count ${pcm.samples.size}" }
                check(rms > 1e-6) { "decoded window is silent" }
                println("PASS ${audio.fileName}: samples=${pcm.samples.size} rms=${"%.6f".format(rms)} elapsed=${elapsed}ms")
            }
        }
    } finally {
        bass.BASS_Free()
    }
}

private const val BASS_DEVICE_DECODEFLOAT = 0x8000

@Suppress("FunctionName")
private interface SmokeBass : Library {
    fun BASS_Init(
        device: Int,
        freq: Int,
        flags: Int,
        win: PointerByReference?,
        clsid: Pointer?,
    ): Boolean

    fun BASS_Free(): Boolean
    fun BASS_ErrorGetCode(): Int
}