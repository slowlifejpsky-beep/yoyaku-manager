package app.kikigaki.memo

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 録音した AAC(ADTS) の一部を WAV に戻す。Gemini が AAC を受け付けないときの予備 */
object AacDecoder {

    fun toWav(adts: ByteArray): ByteArray {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AacRecorder.SAMPLE_RATE, 1)
        format.setInteger(MediaFormat.KEY_IS_ADTS, 1)
        // AAC-LC / 16000Hz / モノラル を表す設定値
        format.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x14, 0x08)))
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val pcm = ByteArrayOutputStream(adts.size * 8)
        var sampleRate = AacRecorder.SAMPLE_RATE
        var channels = 1
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var pos = 0
            var frames = 0L
            var inputDone = false
            var outputDone = false
            var idle = 0
            while (!outputDone && idle < 500) {
                var progressed = false
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(5_000)
                    if (idx >= 0) {
                        val len = frameLength(adts, pos)
                        if (len <= 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val buf = codec.getInputBuffer(idx)!!
                            buf.clear()
                            buf.put(adts, pos, len)
                            codec.queueInputBuffer(idx, 0, len, frames * 1024L * 1_000_000L / AacRecorder.SAMPLE_RATE, 0)
                            pos += len
                            frames++
                        }
                        progressed = true
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 5_000)
                if (out >= 0) {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(out)!!
                        val chunk = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(chunk)
                        pcm.write(chunk)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    progressed = true
                } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    progressed = true
                }
                idle = if (progressed) 0 else idle + 1
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        return wav(pcm.toByteArray(), sampleRate, channels)
    }

    /** [pos] から始まる ADTS フレームの長さ。フレームが無ければ 0 */
    private fun frameLength(data: ByteArray, pos: Int): Int {
        if (pos + 7 > data.size) return 0
        if ((data[pos].toInt() and 0xFF) != 0xFF || (data[pos + 1].toInt() and 0xF0) != 0xF0) return 0
        val len = ((data[pos + 3].toInt() and 0x03) shl 11) or
            ((data[pos + 4].toInt() and 0xFF) shl 3) or
            ((data[pos + 5].toInt() and 0xE0) shr 5)
        return if (len < 7 || pos + len > data.size) 0 else len
    }

    private fun wav(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * channels * 2)
        header.putShort((channels * 2).toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        return header.array() + pcm
    }
}
