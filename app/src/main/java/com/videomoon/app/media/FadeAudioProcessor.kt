package com.videomoon.app.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** 書き出し時に音楽の頭と終わりをフェードさせる（16bit PCM） */
class FadeAudioProcessor(
    private val fadeInMs: Long,
    private val fadeOutMs: Long,
    private val totalMs: Long,
) : BaseAudioProcessor() {

    private var frame = 0L
    private var sampleRate = 44_100
    private var channels = 2

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        return if (fadeInMs <= 0 && fadeOutMs <= 0) AudioProcessor.AudioFormat.NOT_SET else inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(remaining)
        val total = totalMs * sampleRate / 1000
        val fin = fadeInMs * sampleRate / 1000
        val fout = fadeOutMs * sampleRate / 1000
        val frameBytes = 2 * channels
        while (inputBuffer.remaining() >= frameBytes) {
            var g = 1f
            if (fin > 0 && frame < fin) g = frame.toFloat() / fin
            val left = total - frame
            if (fout > 0 && left < fout) g = min(g, max(0f, left.toFloat() / fout))
            repeat(channels) {
                val s = inputBuffer.short
                out.putShort((s * g).toInt().coerceIn(-32768, 32767).toShort())
            }
            frame++
        }
        out.flip()
    }

    override fun onFlush() { frame = 0 }
    override fun onReset() { frame = 0 }
}
