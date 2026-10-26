package io.github.solitaire.spwlyrics.integration

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.WString
import io.github.solitaire.spwlyrics.application.TimingAudioDecoder
import io.github.solitaire.spwlyrics.domain.TimingAudioDependency
import io.github.solitaire.spwlyrics.domain.TimingPcm
import io.github.solitaire.spwlyrics.storage.DataDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Experimental timing decoder backed by the host's already-initialized BASS engine.
 *
 * The project does not initialize or shut down BASS: Salt Player owns the engine and its
 * format plugins. This decoder only creates short-lived decode streams, seeks to a bounded
 * window, downmixes to mono and resamples to the 16 kHz PCM expected by timing analysis.
 */
internal class BassTimingAudioDecoder : TimingAudioDecoder {
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var closed = false

    override fun dependency(): TimingAudioDependency =
        TimingAudioDependency(BassLibrary.isAvailable())

    override fun decode(path: Path, startMs: Long, durationMs: Long, cancelled: () -> Boolean): TimingPcm {
        require(startMs >= 0 && durationMs in 1000..60000)
        check(!closed)
        this.cancelled.set(false)

        val startedAt = System.nanoTime()
        val absolutePath = path.toAbsolutePath()
        TimingBassLog.write("decode start path=\"$absolutePath\" startMs=$startMs durationMs=$durationMs")
        var bass: BassTimingNative? = null
        var stream = 0
        try {
            val api = BassLibrary.load().also { bass = it }
            stream = api.BASS_StreamCreateFile(
                false,
                WString(absolutePath.toString()),
                0L,
                0L,
                BASS_SAMPLE_FLOAT or BASS_STREAM_DECODE or BASS_UNICODE,
            )
            if (stream == 0) {
                val code = api.BASS_ErrorGetCode()
                TimingBassLog.write("open failed code=$code path=\"$absolutePath\"")
                check(false) { bassFailure(code) }
            }

            val info = BassChannelInfo()
            if (!api.BASS_ChannelGetInfo(stream, info)) {
                val code = api.BASS_ErrorGetCode()
                TimingBassLog.write("channel-info failed code=$code stream=$stream")
                check(false) { bassFailure(code) }
            }
            val sourceRate = info.freq
            val channels = info.chans
            if (sourceRate <= 0 || channels <= 0) {
                TimingBassLog.write("invalid-format sourceRate=$sourceRate channels=$channels")
                check(false) { "BASS 返回了无效的音频格式。" }
            }
            TimingBassLog.write("open ok stream=${stream.toUInt()} sourceRate=$sourceRate channels=$channels ctype=${info.ctype} flags=${info.flags} plugin=${info.plugin}")

            val bytePosition = api.BASS_ChannelSeconds2Bytes(stream, startMs / 1000.0)
            if (!api.BASS_ChannelSetPosition(stream, bytePosition, BASS_POS_BYTE)) {
                val code = api.BASS_ErrorGetCode()
                TimingBassLog.write("seek failed startMs=$startMs byte=$bytePosition code=$code")
                check(false) { "无法定位到 ${startMs}ms（${bassFailure(code)}）" }
            }
            TimingBassLog.write("seek ok startMs=$startMs byte=$bytePosition")

            val expected = ceil(durationMs * TARGET_RATE / 1000.0).toInt()
            val output = FloatArray(expected)
            val reader = MonoFrameReader(api, stream, channels)
            val step = sourceRate.toDouble() / TARGET_RATE
            var sourcePosition = 0.0
            var written = 0

            while (written < expected) {
                if (this.cancelled.get() || cancelled() || Thread.currentThread().isInterrupted) {
                    throw InterruptedException()
                }
                val leftIndex = floor(sourcePosition).toLong()
                if (!reader.ensure(leftIndex)) break
                val left = reader[leftIndex]
                val right = if (reader.ensure(leftIndex + 1)) reader[leftIndex + 1] else left
                val fraction = (sourcePosition - leftIndex).toFloat()
                output[written++] = left + (right - left) * fraction
                sourcePosition += step
            }

            check(written > 0) { "音频分析没有返回可用采样。" }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            TimingBassLog.write("decode done expected=$expected written=$written elapsedMs=$elapsedMs")
            return TimingPcm(startMs, TARGET_RATE, output.copyOf(written))
        } catch (error: Throwable) {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            TimingBassLog.write("decode failed elapsedMs=$elapsedMs type=${error.javaClass.simpleName} message=${error.message.orEmpty()}")
            throw error
        } finally {
            if (stream != 0) {
                runCatching { bass?.BASS_StreamFree(stream) }
                    .onFailure { TimingBassLog.write("stream-free failed stream=${stream.toUInt()} message=${it.message.orEmpty()}") }
            }
        }
    }

    override fun cancel() {
        cancelled.set(true)
    }

    override fun close() {
        closed = true
        cancel()
    }

    private class MonoFrameReader(
        private val bass: BassTimingNative,
        private val stream: Int,
        private val channels: Int,
    ) {
        private val raw = FloatArray(READ_FRAMES * channels)
        private var frames = FloatArray(0)
        private var baseFrame = 0L
        private var eof = false

        fun ensure(index: Long): Boolean {
            while (index >= baseFrame + frames.size && !eof) readChunk()
            return index >= baseFrame && index < baseFrame + frames.size
        }

        operator fun get(index: Long): Float = frames[(index - baseFrame).toInt()]

        private fun readChunk() {
            val bytes = bass.BASS_ChannelGetData(stream, raw, raw.size * Float.SIZE_BYTES)
            if (bytes <= 0) {
                eof = true
                return
            }
            val decodedFrames = bytes / Float.SIZE_BYTES / channels
            if (decodedFrames <= 0) {
                eof = true
                return
            }

            val keepPrevious = if (frames.isNotEmpty()) 1 else 0
            val next = FloatArray(keepPrevious + decodedFrames)
            if (keepPrevious == 1) next[0] = frames.last()
            for (frame in 0 until decodedFrames) {
                var sum = 0f
                val offset = frame * channels
                for (channel in 0 until channels) sum += raw[offset + channel]
                next[keepPrevious + frame] = sum / channels
            }
            baseFrame = if (keepPrevious == 1) baseFrame + frames.size - 1 else 0L
            frames = next
        }
    }

    internal companion object {
        const val TARGET_RATE = 16_000
        const val READ_FRAMES = 8_192
        const val BASS_SAMPLE_FLOAT = 0x100
        internal const val BASS_STREAM_DECODE = 0x200000
        internal const val BASS_UNICODE = 0x80000000.toInt()
        const val BASS_POS_BYTE = 0
        internal const val BASS_ERROR_INIT = 8
    }
}

private object BassLibrary {
    private val lock = Any()

    @Volatile
    private var loaded: BassTimingNative? = null

    @Volatile
    private var lastFailure: String? = null

    fun isAvailable(): Boolean =
        runCatching { load() }.isSuccess

    fun load(): BassTimingNative {
        loaded?.let { return it }
        synchronized(lock) {
            loaded?.let { return it }
            try {
                val located = locateBass()
                val source = located?.toString() ?: "bass"
                val native = located?.let { path ->
                    Native.load(path.toString(), BassTimingNative::class.java)
                } ?: Native.load("bass", BassTimingNative::class.java)
                val code = probe(native)
                check(code != BassTimingAudioDecoder.BASS_ERROR_INIT) {
                    "宿主 BASS 尚未初始化，请先开始播放任意歌曲后重试。"
                }
                loaded = native
                lastFailure = null
                TimingBassLog.write("bass ready source=$source probeCode=$code")
                return native
            } catch (error: Throwable) {
                val message = "宿主 BASS 不可用：${error.message.orEmpty()}"
                if (lastFailure != message) TimingBassLog.write("bass unavailable message=$message")
                lastFailure = message
                throw IllegalStateException(message, error)
            }
        }
    }

    private fun probe(bass: BassTimingNative): Int {
        val handle = bass.BASS_StreamCreateFile(
            false,
            WString("__spw_lyrics_bass_probe_missing__.bin"),
            0L,
            0L,
            BassTimingAudioDecoder.BASS_STREAM_DECODE or BassTimingAudioDecoder.BASS_UNICODE,
        )
        if (handle != 0) runCatching { bass.BASS_StreamFree(handle) }
        return bass.BASS_ErrorGetCode()
    }

    private fun locateBass(): Path? {
        val property = System.getProperty("compose.application.resources.dir").orEmpty()
        if (property.isNotBlank()) {
            val candidate = Path.of(property).resolve("bass.dll")
            if (Files.isRegularFile(candidate)) return candidate
        }
        val command = ProcessHandle.current().info().command().orElse("")
        if (command.isNotBlank()) {
            val parent = Path.of(command).toAbsolutePath().parent ?: return null
            listOf(parent.resolve("app/resources/bass.dll"), parent.resolve("resources/bass.dll"))
                .firstOrNull(Files::isRegularFile)
                ?.let { return it }
        }
        return null
    }
}

private object TimingBassLog {
    private const val MAX_BYTES = 2L * 1024 * 1024
    private val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val lock = Any()

    fun write(message: String) {
        synchronized(lock) {
            runCatching {
                val file = DataDirectory.root.resolve("bass-timing.log")
                file.parent?.let(Files::createDirectories)
                if (Files.isRegularFile(file) && Files.size(file) >= MAX_BYTES) {
                    Files.move(
                        file,
                        file.resolveSibling("bass-timing.log.1"),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                val safe = message.replace('\r', ' ').replace('\n', ' ')
                val line = "${LocalDateTime.now().format(timestamp)} $safe${System.lineSeparator()}"
                Files.writeString(
                    file,
                    line,
                    Charsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            }
        }
    }
}

private fun bassFailure(code: Int): String =
    when (code) {
        2 -> "无法打开音频文件（BASS 2）"
        3 -> "音频驱动不可用（BASS 3）"
        6 -> "SPW 音频引擎尚未初始化（BASS 6）"
        8 -> "宿主 BASS 尚未初始化（BASS 8）"
        20 -> "当前音频格式不受 BASS 插件支持（BASS 20）"
        21 -> "音频编码器不可用（BASS 21）"
        41 -> "音频文件无法解码（BASS 41）"
        else -> "BASS 解码失败（错误码 $code）"
    }

@Structure.FieldOrder("freq", "chans", "flags", "ctype", "origres", "plugin", "sample", "filename")
internal class BassChannelInfo : Structure() {
    @JvmField var freq: Int = 0
    @JvmField var chans: Int = 0
    @JvmField var flags: Int = 0
    @JvmField var ctype: Int = 0
    @JvmField var origres: Int = 0
    @JvmField var plugin: Int = 0
    @JvmField var sample: Int = 0
    @JvmField var filename: com.sun.jna.Pointer? = null
}

@Suppress("FunctionName")
internal interface BassTimingNative : Library {
    fun BASS_StreamCreateFile(mem: Boolean, file: WString, offset: Long, length: Long, flags: Int): Int
    fun BASS_ChannelGetInfo(handle: Int, info: BassChannelInfo): Boolean
    fun BASS_ChannelSeconds2Bytes(handle: Int, seconds: Double): Long
    fun BASS_ChannelSetPosition(handle: Int, position: Long, mode: Int): Boolean
    fun BASS_ChannelGetData(handle: Int, buffer: FloatArray, length: Int): Int
    fun BASS_StreamFree(handle: Int): Boolean
    fun BASS_ErrorGetCode(): Int
}
