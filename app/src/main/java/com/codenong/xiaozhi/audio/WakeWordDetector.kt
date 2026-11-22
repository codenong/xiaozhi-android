package com.codenong.xiaozhi.audio

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

data class WakeWordEntry(
    val displayName: String,
    val rawLine: String
)

class WakeWordDetector(
    private val context: Context,
    private val sampleRate: Int = 16000
) {
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var exitStream: OnlineStream? = null
    private var activeWakeWordName: String? = null

    @Volatile
    var isInitialized: Boolean = false
        private set

    fun isModelAvailable(): Boolean = true

    fun loadWakeWordEntries(): List<WakeWordEntry> =
        loadEntriesFromAssets().filterNot { isExitKeyword(it.displayName) }

    fun loadEntriesFromAssets(): List<WakeWordEntry> {
        return runCatching {
            context.assets.open("kws/keywords.txt").bufferedReader().useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .mapNotNull { line ->
                        val atIdx = line.lastIndexOf('@')
                        val display = if (atIdx >= 0 && atIdx + 1 < line.length) {
                            line.substring(atIdx + 1).trim()
                        } else {
                            line
                        }
                        if (display.isNotEmpty()) WakeWordEntry(display, line) else null
                    }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    fun initialize(selectedWakeWord: String? = activeWakeWordName): Boolean {
        activeWakeWordName = selectedWakeWord
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
            spotter = kws
            stream = createStreamForSelection(kws, selectedWakeWord)
            exitStream = createExitStream(kws)
            isInitialized = true
            true
        }.onFailure { err ->
            Log.e(TAG, "Failed to initialize Sherpa-ONNX KWS: ${err.message}", err)
        }.getOrDefault(false)
    }

    @Synchronized
    fun setActiveWakeWord(displayName: String?) {
        activeWakeWordName = displayName
        val kws = spotter ?: return
        val oldStream = stream
        stream = createStreamForSelection(kws, displayName)
        oldStream?.release()
    }

    private fun createStreamForSelection(kws: KeywordSpotter, displayName: String?): OnlineStream {
        if (!displayName.isNullOrBlank()) {
            val entry = loadWakeWordEntries().firstOrNull { it.displayName == displayName }
            if (entry != null) {
                val custom = kws.createStream(entry.rawLine)
                if (custom.ptr != 0L) return custom
            }
        }
        return kws.createStream()
    }

    private fun createExitStream(kws: KeywordSpotter): OnlineStream? {
        val entry = loadEntriesFromAssets().firstOrNull { isExitKeyword(it.displayName) } ?: return null
        val custom = kws.createStream(entry.rawLine)
        return if (custom.ptr != 0L) custom else null
    }

    @Synchronized
    fun processPcm16(pcm: ShortArray, length: Int): String? {
        val kws = spotter ?: return null
        val s = stream ?: return null
        return feedAndDecode(kws, s, pcm, length)
    }

    @Synchronized
    fun processExitWordPcm16(pcm: ShortArray, length: Int): String? {
        val kws = spotter ?: return null
        val s = exitStream ?: return null
        return feedAndDecode(kws, s, pcm, length)
    }

    private fun feedAndDecode(
        kws: KeywordSpotter,
        targetStream: OnlineStream,
        pcm: ShortArray,
        length: Int
    ): String? {
        val floats = FloatArray(length) { pcm[it] / 32768.0f }
        targetStream.acceptWaveform(samples = floats, sampleRate = sampleRate)
        while (kws.isReady(targetStream)) {
            kws.decode(targetStream)
            val result = kws.getResult(targetStream)
            if (result.keyword.isNotBlank()) {
                kws.reset(targetStream)
                return result.keyword
            }
        }
        return null
    }

    @Synchronized
    fun reset() {
        val kws = spotter ?: return
        stream?.let { kws.reset(it) }
        exitStream?.let { kws.reset(it) }
    }

    @Synchronized
    fun release() {
        isInitialized = false
        runCatching {
            stream?.release()
            exitStream?.release()
            spotter?.release()
        }
        stream = null
        exitStream = null
        spotter = null
    }

    companion object {
        private const val TAG = "WakeWordDetector"
        const val EXIT_KEYWORD = "小智退下"

        fun isExitKeyword(keyword: String): Boolean = keyword.trim() == EXIT_KEYWORD
    }
}
