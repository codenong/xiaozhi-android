package com.codenong.xiaozhi.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.media.audiofx.AcousticEchoCanceler
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
    context: Context,
    private val scope: CoroutineScope,
    private val opusCodec: OpusCodec,
    private val wakeWordDetector: WakeWordDetector,
    private val onEncodedFrame: (ByteArray) -> Unit,
    private val onWakeWordDetected: (String) -> Unit,
    private val onExitWordDetected: (String) -> Unit,
    private val onVoiceActivity: (Boolean) -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var audioTrack: AudioTrack? = null

    private var recordJob: Job? = null
    private var playJob: Job? = null

    private val playQueue = LinkedBlockingQueue<ByteArray>(80)

    @Volatile
    var captureMode: CaptureMode = CaptureMode.OFF
        private set

    @Volatile
    var exitWordEnabled: Boolean = false

    @Volatile
    var powerSaveEnabled: Boolean = false

    @Volatile
    private var exitWordCooldownUntilMs: Long = 0L

    private var kwsActiveUntilMs: Long = 0L
    private val kwsPreRoll = ArrayDeque<ShortArray>(PRE_ROLL_FRAMES + 1)

    fun startWakeWordDetection(
        selectedWakeWord: String? = null,
        exitWord: String = WakeWordDetector.DEFAULT_EXIT_KEYWORD,
        wakeTokens: String? = null,
        exitTokens: String? = null
    ) {
        val ok = wakeWordDetector.initialize(selectedWakeWord, exitWord, wakeTokens, exitTokens)
        Log.i("XiaozhiKWS", "startWakeWordDetection(wake=$selectedWakeWord, exit=$exitWord, powerSave=$powerSaveEnabled) initOk=$ok")
        if (!ok) return
        wakeWordDetector.reset()
        kwsActiveUntilMs = 0L
        kwsPreRoll.clear()
        captureMode = CaptureMode.WAKE_WORD
        ensureCaptureLoopRunning()
    }

    fun updateSelectedWakeWord(selectedWakeWord: String?) {
        wakeWordDetector.setActiveWakeWord(selectedWakeWord)
    }

    fun previewTokens(phrase: String): String = wakeWordDetector.previewTokens(phrase)

    fun updateCustomKeywords(
        wakeWord: String?,
        exitWord: String?,
        wakeTokens: String? = null,
        exitTokens: String? = null
    ): Result<Unit> {
        return wakeWordDetector.updateCustomKeywords(wakeWord, exitWord, wakeTokens, exitTokens)
    }

    fun startConversationCapture(
        selectedWakeWord: String? = null,
        exitWord: String = WakeWordDetector.DEFAULT_EXIT_KEYWORD,
        wakeTokens: String? = null,
        exitTokens: String? = null
    ) {
        wakeWordDetector.initialize(selectedWakeWord, exitWord, wakeTokens, exitTokens)
        wakeWordDetector.reset()
        exitWordCooldownUntilMs = System.currentTimeMillis() + 800L
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
                        if (exitWordEnabled || !powerSaveEnabled) {
                            val keyword = wakeWordDetector.processPcm16(pcmBuffer, read)
                            if (keyword != null) {
                                onWakeWordDetected(keyword)
                            } else if (exitWordEnabled) {
                                val exitKeyword = wakeWordDetector.processExitWordPcm16(pcmBuffer, read)
                                if (exitKeyword != null) {
                                    onExitWordDetected(exitKeyword)
                                }
                            }
                        } else {
                            val now = System.currentTimeMillis()
                            val avgAmp = averageAmplitude(pcmBuffer, read)
                            if (avgAmp > WAKE_ENERGY_THRESHOLD) {
                                if (now >= kwsActiveUntilMs && kwsPreRoll.isNotEmpty()) {
                                    for (pre in kwsPreRoll) {
                                        val preKw = wakeWordDetector.processPcm16(pre, pre.size)
                                        if (preKw != null) {
                                            onWakeWordDetected(preKw)
                                        }
                                    }
                                    kwsPreRoll.clear()
                                }
                                kwsActiveUntilMs = now + WAKE_HANGOVER_MS
                            }

                            if (now < kwsActiveUntilMs) {
                                val keyword = wakeWordDetector.processPcm16(pcmBuffer, read)
                                if (keyword != null) {
                                    kwsActiveUntilMs = 0L
                                    kwsPreRoll.clear()
                                    onWakeWordDetected(keyword)
                                }
                            } else {
                                if (kwsPreRoll.size >= PRE_ROLL_FRAMES) {
                                    kwsPreRoll.removeFirst()
                                }
                                kwsPreRoll.addLast(pcmBuffer.copyOf(read))
                            }
                        }
                    }

                    CaptureMode.CONVERSATION -> {
                        if (exitWordEnabled && System.currentTimeMillis() >= exitWordCooldownUntilMs) {
                            val exitKeyword = wakeWordDetector.processExitWordPcm16(pcmBuffer, read)
                            if (exitKeyword != null) {
                                onExitWordDetected(exitKeyword)
                                continue
                            }
                        }
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
        if (audioTrack != null && playJob?.isActive == true) {
            enableSpeakerphone()
            runCatching {
                if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack?.play()
                }
            }
            return
        }

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        enableSpeakerphone()
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
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    runCatching { track.play() }
                }
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

    fun clearPlayback(resumePlay: Boolean = true) {
        playQueue.clear()
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            if (resumePlay) {
                audioTrack?.play()
            }
        }
    }

    fun pausePlayback() {
        clearPlayback(resumePlay = false)
    }

    private fun averageAmplitude(pcm: ShortArray, length: Int): Long {
        var sum = 0L
        for (i in 0 until length) {
            sum += abs(pcm[i].toInt())
        }
        return if (length > 0) sum / length else 0L
    }

    fun enableSpeakerphone() {
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (speaker != null) {
                    audioManager.setCommunicationDevice(speaker)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
            }
        }
    }

    fun restoreAudioMode() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    fun release() {
        stopCapture()
        restoreAudioMode()
        wakeWordDetector.release()
        playJob?.cancel()
        playQueue.clear()
        runCatching {
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null
    }

    companion object {
        private const val WAKE_ENERGY_THRESHOLD = 45L
        private const val WAKE_HANGOVER_MS = 3000L
        private const val PRE_ROLL_FRAMES = 6
    }
}
