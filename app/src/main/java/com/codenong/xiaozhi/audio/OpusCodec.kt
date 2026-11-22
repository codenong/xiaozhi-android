package com.codenong.xiaozhi.audio

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal

class OpusCodec(
    private val inputSampleRate: Int = 16000,
    initialOutputSampleRate: Int = 16000,
    private val frameDurationMs: Int = 60
) {
    val inputFrameSamples: Int = inputSampleRate * frameDurationMs / 1000

    private val encoder = OpusEncoder(inputSampleRate, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
        bitrate = 24000
        signalType = OpusSignal.OPUS_SIGNAL_VOICE
        useVBR = true
        useDTX = true
    }

    @Volatile
    var outputSampleRate: Int = initialOutputSampleRate
        private set

    private var decoder = OpusDecoder(initialOutputSampleRate, 1)
    private val encodeBuf = ByteArray(1275)
    private val decodeBuf = ShortArray(5760)

    fun encode(pcm: ShortArray): ByteArray {
        val encodedBytes = encoder.encode(pcm, 0, inputFrameSamples, encodeBuf, 0, encodeBuf.size)
        return encodeBuf.copyOf(encodedBytes)
    }

    @Synchronized
    fun updateOutputSampleRate(sampleRate: Int) {
        if (sampleRate != outputSampleRate && (sampleRate == 16000 || sampleRate == 24000 || sampleRate == 48000)) {
            outputSampleRate = sampleRate
            decoder = OpusDecoder(sampleRate, 1)
        }
    }

    @Synchronized
    fun decode(opusPacket: ByteArray): ShortArray {
        val frameSize = outputSampleRate * frameDurationMs / 1000
        val decodedSamples = decoder.decode(opusPacket, 0, opusPacket.size, decodeBuf, 0, frameSize, false)
        return decodeBuf.copyOf(decodedSamples)
    }
}
