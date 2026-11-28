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
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

data class AecTestResult(
    val aecHardwareAvailable: Boolean,
    val aecCreatedEnabled: Boolean,
    val noiseAvgAmp: Long,
    val aecOnAvgAmp: Long,
    val aecOnPeakAmp: Int,
    val aecOffAvgAmp: Long,
    val aecOffPeakAmp: Int,
    val erleDb: Double,
    val verdict: String,
    val recordedAecOnPcm: ShortArray
)

class AecTester(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    suspend fun runComparisonTest(
        onProgress: (String) -> Unit
    ): Result<AecTestResult> = withContext(Dispatchers.IO) {
        runCatching {
            val sampleRate = 16000
            val aecAvailable = AcousticEchoCanceler.isAvailable()

            onProgress("1/3 正在采样环境底噪 (1秒，请保持安静)...")
            val noiseSamples = recordOnly(sampleRate, durationMs = 1000)
            val noiseAvg = avgAmplitude(noiseSamples, 0, noiseSamples.size)

            onProgress("2/3 正在测试【开启 AEC】播放+录音 (3秒)...")
            val onPass = playAndRecord(
                sampleRate = sampleRate,
                durationMs = 3000,
                useAec = true
            )
            // 跳过前 400ms 收敛期统计稳态残余回声
            val skipSamples = (sampleRate * 0.4).toInt().coerceAtMost(onPass.samples.size / 2)
            val aecOnAvg = avgAmplitude(onPass.samples, skipSamples, onPass.samples.size)
            val aecOnPeak = peakAmplitude(onPass.samples, skipSamples, onPass.samples.size)

            onProgress("3/3 正在测试【关闭 AEC】对比基准 (2.5秒)...")
            val offPass = playAndRecord(
                sampleRate = sampleRate,
                durationMs = 2500,
                useAec = false
            )
            val skipOff = (sampleRate * 0.3).toInt().coerceAtMost(offPass.samples.size / 2)
            val aecOffAvg = avgAmplitude(offPass.samples, skipOff, offPass.samples.size)
            val aecOffPeak = peakAmplitude(offPass.samples, skipOff, offPass.samples.size)

            val erleDb = 20.0 * log10(
                aecOffAvg.toDouble().coerceAtLeast(1.0) / aecOnAvg.toDouble().coerceAtLeast(1.0)
            )
            val verdict = when {
                !aecAvailable || !onPass.aecEnabled -> "硬件/HAL AEC 未启用，建议用半双工模式"
                erleDb >= 15.0 -> "AEC 效果优秀（回声抑制强，适合实时对话）"
                erleDb >= 8.0 -> "AEC 效果一般（有部分回声残留，可能偶发误打断）"
                else -> "AEC 效果较差（回声抑制不足，建议用半双工模式）"
            }

            Log.i(
                TAG,
                "AEC Test: avail=$aecAvailable enabled=${onPass.aecEnabled} noise=$noiseAvg " +
                    "aecOn=$aecOnAvg(peak=$aecOnPeak) aecOff=$aecOffAvg(peak=$aecOffPeak) erle=${"%.1f".format(erleDb)}dB"
            )

            AecTestResult(
                aecHardwareAvailable = aecAvailable,
                aecCreatedEnabled = onPass.aecEnabled,
                noiseAvgAmp = noiseAvg,
                aecOnAvgAmp = aecOnAvg,
                aecOnPeakAmp = aecOnPeak,
                aecOffAvgAmp = aecOffAvg,
                aecOffPeakAmp = aecOffPeak,
                erleDb = erleDb,
                verdict = verdict,
                recordedAecOnPcm = onPass.samples
            )
        }
    }

    suspend fun playRecordedPcm(samples: ShortArray) = withContext(Dispatchers.IO) {
        if (samples.isEmpty()) return@withContext
        val sampleRate = 16000
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
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        runCatching {
            audioManager.mode = AudioManager.MODE_NORMAL
            setSpeakerphone(true)
            track.play()
            var offset = 0
            while (offset < samples.size) {
                val count = minOf(1024, samples.size - offset)
                val written = track.write(samples, offset, count)
                if (written <= 0) break
                offset += written
            }
            Thread.sleep(300L)
        }.also {
            runCatching {
                track.stop()
                track.release()
            }
        }
    }

    private data class PassResult(
        val samples: ShortArray,
        val aecEnabled: Boolean
    )

    @SuppressLint("MissingPermission")
    private fun recordOnly(sampleRate: Int, durationMs: Int): ShortArray {
        val totalSamples = (sampleRate * durationMs) / 1000
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
            maxOf(minBuf, 4096)
        )
        val out = ShortArray(totalSamples)
        runCatching {
            record.startRecording()
            var readTotal = 0
            while (readTotal < totalSamples) {
                val n = record.read(out, readTotal, minOf(512, totalSamples - readTotal))
                if (n <= 0) break
                readTotal += n
            }
        }.also {
            runCatching {
                record.stop()
                record.release()
            }
        }
        return out
    }

    @SuppressLint("MissingPermission")
    private fun playAndRecord(
        sampleRate: Int,
        durationMs: Int,
        useAec: Boolean
    ): PassResult {
        val totalSamples = (sampleRate * durationMs) / 1000
        val probeTone = generateSpeechLikeProbe(sampleRate, totalSamples)

        audioManager.mode = if (useAec) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        setSpeakerphone(true)

        val minIn = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val minOut = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val record = AudioRecord(
            if (useAec) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minIn, 4096)
        )

        var aecEffect: AcousticEchoCanceler? = null
        var aecActuallyEnabled = false
        if (useAec && AcousticEchoCanceler.isAvailable() && record.state == AudioRecord.STATE_INITIALIZED) {
            aecEffect = AcousticEchoCanceler.create(record.audioSessionId)?.apply {
                enabled = true
                aecActuallyEnabled = enabled
            }
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (useAec) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minOut, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val recorded = ShortArray(totalSamples)
        var readTotal = 0

        val recThread = Thread {
            runCatching {
                record.startRecording()
                while (readTotal < totalSamples) {
                    val n = record.read(recorded, readTotal, minOf(512, totalSamples - readTotal))
                    if (n <= 0) break
                    readTotal += n
                }
            }
        }

        runCatching {
            recThread.start()
            track.play()
            var writtenTotal = 0
            while (writtenTotal < probeTone.size) {
                val count = minOf(512, probeTone.size - writtenTotal)
                val w = track.write(probeTone, writtenTotal, count)
                if (w <= 0) break
                writtenTotal += w
            }
            recThread.join(durationMs.toLong() + 1000L)
        }.also {
            runCatching { track.stop(); track.release() }
            runCatching { record.stop(); record.release() }
            runCatching { aecEffect?.release() }
            runCatching {
                setSpeakerphone(false)
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        }

        return PassResult(
            samples = if (readTotal < recorded.size) recorded.copyOf(readTotal) else recorded,
            aecEnabled = aecActuallyEnabled
        )
    }

    private fun setSpeakerphone(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                val speaker = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) {
                    audioManager.setCommunicationDevice(speaker)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                }
            } else {
                audioManager.clearCommunicationDevice()
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }

    private fun generateSpeechLikeProbe(sampleRate: Int, totalSamples: Int): ShortArray {
        val out = ShortArray(totalSamples)
        val cycleSamples = (sampleRate * 0.6).toInt()
        val activeSamples = (sampleRate * 0.45).toInt()
        for (i in 0 until totalSamples) {
            val posInCycle = i % cycleSamples
            if (posInCycle < activeSamples) {
                val t = i.toDouble() / sampleRate
                val signal = 0.45 * sin(2.0 * PI * 440.0 * t) +
                    0.35 * sin(2.0 * PI * 880.0 * t) +
                    0.20 * sin(2.0 * PI * 1320.0 * t)
                out[i] = (signal * 16000.0).toInt().coerceIn(-32767, 32767).toShort()
            } else {
                out[i] = 0
            }
        }
        return out
    }

    private fun avgAmplitude(samples: ShortArray, start: Int, end: Int): Long {
        if (end <= start) return 0L
        var sum = 0L
        for (i in start until end) {
            sum += abs(samples[i].toInt())
        }
        return sum / (end - start)
    }

    private fun peakAmplitude(samples: ShortArray, start: Int, end: Int): Int {
        var peak = 0
        for (i in start until end) {
            val a = abs(samples[i].toInt())
            if (a > peak) peak = a
        }
        return peak
    }

    companion object {
        private const val TAG = "XiaozhiAECTest"
    }
}
