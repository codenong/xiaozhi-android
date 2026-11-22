package com.codenong.xiaozhi.audio

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

data class WakeWordEntry(
    val displayName: String,
    val rawLine: String
)

class WakeWordDetector(
    private val context: Context,
    private val sampleRate: Int = 16000
) {
    private val modelManager = KwsModelManager(context)
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var activeWakeWordName: String? = null

    @Volatile
    var isInitialized: Boolean = false
        private set

    fun isModelAvailable(): Boolean = modelManager.isModelReady()

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
        if (!modelManager.isModelReady()) return false

        return runCatching {
            val dir = modelManager.modelDir
            dir.mkdirs()
            val keywordsDest = File(dir, "keywords.txt")
            context.assets.open("kws/keywords.txt").use { input ->
                keywordsDest.outputStream().use { output -> input.copyTo(output) }
            }

            val transducer = OnlineTransducerModelConfig(
                encoder = File(dir, "encoder.onnx").absolutePath,
                decoder = File(dir, "decoder.onnx").absolutePath,
                joiner = File(dir, "joiner.onnx").absolutePath
            )
            val modelConfig = OnlineModelConfig(
                transducer = transducer,
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = 2,
                debug = false,
                modelType = "zipformer2"
            )
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
                modelConfig = modelConfig,
                keywordsFile = keywordsDest.absolutePath
            )
            val kws = KeywordSpotter(assetManager = null, config = config)
            spotter = kws
            stream = createStreamForSelection(kws, selectedWakeWord)
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
            val entry = loadEntriesFromAssets().firstOrNull { it.displayName == displayName }
            if (entry != null) {
                val custom = kws.createStream(entry.rawLine)
                if (custom.ptr != 0L) return custom
            }
        }
        return kws.createStream()
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
