package app.kikigaki.memo

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * マイク音声を AAC(ADTS) に圧縮しながらファイルへ追記する。
 * ADTS は1フレームごとに独立しているので、途中で落ちても書けた所までは再生でき、
 * ファイルの任意のフレーム境界で切り出してそのまま Gemini に渡せる。
 */
class AacRecorder(private val out: File) {

    /** ファイルに書き終えたバイト数(常にフレーム境界) */
    @Volatile var bytesWritten: Long = 0
        private set
    @Volatile var elapsedMs: Long = 0
        private set
    /** 直近の音量 0..1 */
    @Volatile var level: Float = 0f
        private set
    @Volatile var error: String? = null
        private set

    @Volatile private var running = false
    private var worker: Thread? = null

    val isRunning: Boolean get() = worker?.isAlive == true

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf * 4, SAMPLE_RATE * 2),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("マイクを使えません")
        }
        val codec: MediaCodec
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                codec.release()
                throw IllegalStateException("マイクを開始できません(他のアプリが使用中かもしれません)")
            }
            codec.start()
        } catch (e: Exception) {
            record.release()
            throw e
        }
        bytesWritten = out.length()
        running = true
        worker = thread(name = "aac-recorder") { loop(record, codec) }
    }

    fun stop() {
        running = false
    }

    private fun loop(record: AudioRecord, codec: MediaCodec) {
        val fos = FileOutputStream(out, true)
        val pcm = ByteArray(2048)
        val info = MediaCodec.BufferInfo()
        var samples = 0L
        var lastSync = SystemClock.elapsedRealtime()
        try {
            while (running) {
                val n = record.read(pcm, 0, pcm.size)
                if (n < 0) throw IllegalStateException("録音が中断されました($n)")
                if (n == 0) continue

                var peak = 0
                var i = 0
                while (i + 1 < n) {
                    val v = abs((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF))
                    if (v > peak) peak = v
                    i += 2
                }
                level = min(1f, peak / 32768f)

                feed(codec, pcm, n, samples * 1_000_000L / SAMPLE_RATE, info, fos)
                samples += n / 2
                elapsedMs = samples * 1000 / SAMPLE_RATE
                drain(codec, info, fos, false)

                val now = SystemClock.elapsedRealtime()
                if (now - lastSync > SYNC_INTERVAL_MS) {
                    fos.fd.sync()
                    lastSync = now
                }
            }
            // 残りを吐き出して締める
            val idx = codec.dequeueInputBuffer(100_000)
            if (idx >= 0) {
                codec.queueInputBuffer(
                    idx, 0, 0, samples * 1_000_000L / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                drain(codec, info, fos, true)
            }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            running = false
            level = 0f
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { fos.fd.sync() }
            runCatching { fos.close() }
        }
    }

    private fun feed(
        codec: MediaCodec, pcm: ByteArray, n: Int, ptsUs: Long,
        info: MediaCodec.BufferInfo, fos: FileOutputStream,
    ) {
        var off = 0
        var waits = 0
        while (off < n) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) {
                drain(codec, info, fos, false)
                if (++waits > 500) throw IllegalStateException("音声の圧縮が止まりました")
                continue
            }
            val buf = codec.getInputBuffer(idx)!!
            buf.clear()
            val len = min(buf.remaining(), n - off)
            buf.put(pcm, off, len)
            codec.queueInputBuffer(idx, 0, len, ptsUs, 0)
            off += len
        }
    }

    private var frame = ByteArray(4096)

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, fos: FileOutputStream, eos: Boolean) {
        var tries = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (eos) 10_000L else 0L)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!eos || ++tries > 100) return
                continue
            }
            if (idx < 0) continue
            val size = info.size
            if (size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                val total = size + 7
                if (frame.size < total) frame = ByteArray(total * 2)
                adtsHeader(frame, total)
                val buf = codec.getOutputBuffer(idx)!!
                buf.position(info.offset)
                buf.limit(info.offset + size)
                buf.get(frame, 7, size)
                fos.write(frame, 0, total)
                bytesWritten += total
            }
            codec.releaseOutputBuffer(idx, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
        }
    }

    private fun adtsHeader(b: ByteArray, len: Int) {
        val profile = 2  // AAC LC
        val freqIdx = 8  // 16000Hz
        val chan = 1
        b[0] = 0xFF.toByte()
        b[1] = 0xF1.toByte()
        b[2] = (((profile - 1) shl 6) or (freqIdx shl 2) or (chan shr 2)).toByte()
        b[3] = (((chan and 3) shl 6) or (len shr 11)).toByte()
        b[4] = ((len and 0x7FF) shr 3).toByte()
        b[5] = (((len and 7) shl 5) or 0x1F).toByte()
        b[6] = 0xFC.toByte()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val BIT_RATE = 32000
        /** 32kbps + ADTSヘッダ分。1時間で約14MB */
        const val APPROX_BYTES_PER_SEC = 4110L
        private const val SYNC_INTERVAL_MS = 3000L

        /**
         * [data] の先頭から、完結している ADTS フレームだけを数えた長さを返す。
         * 途中で切れた末尾や壊れた部分の手前で止まる。
         */
        fun alignedLength(data: ByteArray, limit: Int = data.size): Int {
            var i = 0
            while (i + 7 <= limit) {
                if ((data[i].toInt() and 0xFF) != 0xFF || (data[i + 1].toInt() and 0xF0) != 0xF0) break
                val len = ((data[i + 3].toInt() and 0x03) shl 11) or
                    ((data[i + 4].toInt() and 0xFF) shl 3) or
                    ((data[i + 5].toInt() and 0xE0) shr 5)
                if (len < 7 || i + len > limit) break
                i += len
            }
            return i
        }
    }
}
