package com.codenong.xiaozhi.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs

enum class CaptureMode {
    OFF,
    WAKE_WORD,
    CONVERSATION
}

class AudioEngine(
    private val scope: CoroutineScope,
    private val opusCodec: OpusCodec,
    private val wakeWordDetector: WakeWordDetector,
    private val onEncodedFrame: (ByteArray) -> Unit,
    private val onWakeWordDetected: (String) -> Unit,
    private val onVoiceActivity: (Boolean) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var audioTrack: AudioTrack? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private var recordJob: Job? = null
    private var playJob: Job? = null

    private val playQueue = LinkedBlockingQueue<ByteArray>(80)

    @Volatile
    var captureMode: CaptureMode = CaptureMode.OFF
        private set

    fun startWakeWordDetection(selectedWakeWord: String? = null) {
        if (!wakeWordDetector.initialize(selectedWakeWord)) return
        wakeWordDetector.reset()
        captureMode = CaptureMode.WAKE_WORD
        ensureCaptureLoopRunning()
    }

    fun updateSelectedWakeWord(selectedWakeWord: String?) {
        wakeWordDetector.setActiveWakeWord(selectedWakeWord)
    }

    fun startConversationCapture() {
        captureMode = CaptureMode.CONVERSATION
        ensureCaptureLoopRunning()
    }

    fun stopCapture() {
        captureMode = CaptureMode.OFF
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

    @SuppressLint("MissingPermission")
    private fun ensureCaptureLoopRunning() {
        if (audioRecord != null && recordJob?.isActive == true) return
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
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
        }

        audioRecord = record
        record.startRecording()

        recordJob = scope.launch(Dispatchers.IO) {
            val pcmBuffer = ShortArray(frameSamples)
            var lastVad = false
            while (isActive && captureMode != CaptureMode.OFF) {
                var read = 0
                while (read < frameSamples && captureMode != CaptureMode.OFF) {
                    val n = record.read(pcmBuffer, read, frameSamples - read)
                    if (n > 0) read += n else break
                }
                if (read != frameSamples) continue

                when (captureMode) {
                    CaptureMode.WAKE_WORD -> {
                        val keyword = wakeWordDetector.processPcm16(pcmBuffer, read)
                        if (keyword != null) {
                            onWakeWordDetected(keyword)
                        }
                    }

                    CaptureMode.CONVERSATION -> {
                        val avgAmp = pcmBuffer.fold(0L) { acc, s -> acc + abs(s.toInt()) } / frameSamples
                        val vad = avgAmp > 350
                        if (vad != lastVad) {
                            lastVad = vad
                            onVoiceActivity(vad)
                        }
                        val opus = opusCodec.encode(pcmBuffer)
                        onEncodedFrame(opus)
                    }

                    CaptureMode.OFF -> break
                }
            }
        }
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
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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

        runCatching {
            loudnessEnhancer = LoudnessEnhancer(track.audioSessionId).apply {
                setTargetGain(800) // +8 dB，自带系统防破音限幅
                enabled = true
            }
        }

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
        wakeWordDetector.release()
        playJob?.cancel()
        playQueue.clear()
        runCatching {
            loudnessEnhancer?.release()
            audioTrack?.stop()
            audioTrack?.release()
        }
        loudnessEnhancer = null
        audioTrack = null
    }
}
