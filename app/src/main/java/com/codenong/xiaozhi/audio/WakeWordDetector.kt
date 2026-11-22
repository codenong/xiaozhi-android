package com.codenong.xiaozhi.audio

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

class WakeWordDetector(
    private val context: Context,
    private val sampleRate: Int = 16000
) {
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    @Volatile
    var isInitialized: Boolean = false
        private set

    fun initialize(): Boolean {
        if (isInitialized) return true
        return runCatching {
            val transducer = OnlineTransducerModelConfig(
                encoder = "kws/encoder.onnx",
                decoder = "kws/decoder.onnx",
                joiner = "kws/joiner.onnx"
            )
            val modelConfig = OnlineModelConfig(
                transducer = transducer,
                tokens = "kws/tokens.txt",
                numThreads = 2,
                debug = false,
                modelType = "zipformer2"
            )
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
                modelConfig = modelConfig,
                keywordsFile = "kws/keywords.txt"
            )
            val kws = KeywordSpotter(assetManager = context.assets, config = config)
            val s = kws.createStream()
            spotter = kws
            stream = s
            isInitialized = true
            true
        }.onFailure { err ->
            Log.e(TAG, "Failed to initialize Sherpa-ONNX KWS: ${err.message}", err)
        }.getOrDefault(false)
    }

    @Synchronized
    fun processPcm16(pcm: ShortArray, length: Int): String? {
        val kws = spotter ?: return null
        val s = stream ?: return null
        val floats = FloatArray(length) { pcm[it] / 32768.0f }
        s.acceptWaveform(samples = floats, sampleRate = sampleRate)
        while (kws.isReady(s)) {
            kws.decode(s)
            val result = kws.getResult(s)
            if (result.keyword.isNotBlank()) {
                kws.reset(s)
                return result.keyword
            }
        }
        return null
    }

    @Synchronized
    fun reset() {
        val kws = spotter ?: return
        val s = stream ?: return
        kws.reset(s)
    }

    @Synchronized
    fun release() {
        isInitialized = false
        runCatching {
            stream?.release()
            spotter?.release()
        }
        stream = null
        spotter = null
    }

    companion object {
        private const val TAG = "WakeWordDetector"
    }
}
