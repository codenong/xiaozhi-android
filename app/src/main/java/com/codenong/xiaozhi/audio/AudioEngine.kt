package com.codenong.xiaozhi.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs

class AudioEngine(
    private val scope: CoroutineScope,
    private val opusCodec: OpusCodec,
    private val onEncodedFrame: (ByteArray) -> Unit,
    private val onVoiceActivity: (Boolean) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var audioTrack: AudioTrack? = null

    private var recordJob: Job? = null
    private var playJob: Job? = null

    private val playQueue = LinkedBlockingQueue<ByteArray>(80)

    @Volatile
    var isCapturing: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (isCapturing) return
        val sampleRate = 16000
        val frameSamples = opusCodec.inputFrameSamples
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, frameSamples * 4)
        )
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
        }

        audioRecord = record
        isCapturing = true
        record.startRecording()

        recordJob = scope.launch(Dispatchers.IO) {
            val pcmBuffer = ShortArray(frameSamples)
            var lastVad = false
            while (isActive && isCapturing) {
                var read = 0
                while (read < frameSamples && isCapturing) {
                    val n = record.read(pcmBuffer, read, frameSamples - read)
                    if (n > 0) read += n else break
                }
                if (read == frameSamples) {
                    val avgAmp = pcmBuffer.fold(0L) { acc, s -> acc + abs(s.toInt()) } / frameSamples
                    val vad = avgAmp > 350
                    if (vad != lastVad) {
                        lastVad = vad
                        onVoiceActivity(vad)
                    }
                    val opus = opusCodec.encode(pcmBuffer)
                    onEncodedFrame(opus)
                }
            }
        }
    }

    fun stopCapture() {
        isCapturing = false
        recordJob?.cancel()
        recordJob = null
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
            aec?.release()
            ns?.release()
        }
        audioRecord = null
        aec = null
        ns = null
    }

    fun ensurePlaybackStarted(sampleRate: Int) {
        opusCodec.updateOutputSampleRate(sampleRate)
        if (audioTrack != null && playJob?.isActive == true) return

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, sampleRate * 2 / 5))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        track.play()

        playJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val packet = playQueue.take()
                val pcm = runCatching { opusCodec.decode(packet) }.getOrNull() ?: continue
                track.write(pcm, 0, pcm.size)
            }
        }
    }

    fun enqueueAudioPacket(opusPacket: ByteArray) {
        if (!playQueue.offer(opusPacket)) {
            playQueue.poll()
            playQueue.offer(opusPacket)
        }
    }

    fun clearPlayback() {
        playQueue.clear()
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        }
    }

    fun release() {
        stopCapture()
        playJob?.cancel()
        playQueue.clear()
        runCatching {
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null
    }
}
