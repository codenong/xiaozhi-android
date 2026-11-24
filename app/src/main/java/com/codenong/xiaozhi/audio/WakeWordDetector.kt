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
    private val pinyinConverter = PinyinConverter(context)
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var exitSpotter: KeywordSpotter? = null
    private var exitStream: OnlineStream? = null
    private var activeWakeWordName: String? = null
    private var activeExitWordName: String = DEFAULT_EXIT_KEYWORD
    private var floatBuffer: FloatArray = FloatArray(0)

    @Volatile
    var isInitialized: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var activeWakeLinePreview: String = ""
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

    @Synchronized
    fun initialize(
        selectedWakeWord: String? = activeWakeWordName,
        exitWord: String = activeExitWordName
    ): Boolean {
        activeWakeWordName = selectedWakeWord
        activeExitWordName = exitWord.ifBlank { DEFAULT_EXIT_KEYWORD }
        if (isInitialized) return true

        return runCatching {
            val kwsDir = ensureModelFilesExtracted()
            val wakeFile = File(kwsDir, "active_wake.txt")
            val exitFile = File(kwsDir, "active_exit.txt")

            val wakeContent = buildWakeFileContent(selectedWakeWord)
            val exitContent = buildExitFileContent(activeExitWordName)
            activeWakeLinePreview = wakeContent.trim()
            wakeFile.writeText(wakeContent, Charsets.UTF_8)
            exitFile.writeText(exitContent, Charsets.UTF_8)

            val newWakeSpotter = buildSpotter(kwsDir, wakeFile)
            val newWakeStream = newWakeSpotter.createStream()
            val newExitSpotter = buildSpotter(kwsDir, exitFile)
            val newExitStream = newExitSpotter.createStream()

            spotter = newWakeSpotter
            stream = newWakeStream
            exitSpotter = newExitSpotter
            exitStream = newExitStream
            lastError = null
            isInitialized = true
            true
        }.onFailure { err ->
            lastError = err.message ?: err.javaClass.simpleName
            Log.e(TAG, "Failed to initialize Sherpa-ONNX KWS: ${err.message}", err)
            // Fallback to bundled assets if filesystem spotter failed
            runCatching {
                val transducer = OnlineTransducerModelConfig(
                    encoder = "kws/encoder.onnx",
                    decoder = "kws/decoder.onnx",
                    joiner = "kws/joiner.onnx"
                )
                val modelConfig = OnlineModelConfig(
                    transducer = transducer,
                    tokens = "kws/tokens.txt",
                    numThreads = 1,
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
                stream = kws.createStream()
                exitSpotter = kws
                exitStream = kws.createStream()
                isInitialized = true
            }
        }.getOrDefault(isInitialized)
    }

    @Synchronized
    fun setActiveWakeWord(displayName: String?) {
        updateCustomKeywords(displayName, activeExitWordName)
    }

    @Synchronized
    fun updateCustomKeywords(wakeWord: String?, exitWord: String?): Result<Unit> {
        val cleanWake = wakeWord?.trim().orEmpty()
        val cleanExit = exitWord?.trim().orEmpty().ifEmpty { DEFAULT_EXIT_KEYWORD }

        val wakeContent = if (cleanWake.isNotEmpty()) {
            val preset = loadWakeWordEntries().firstOrNull { it.displayName == cleanWake }
            preset?.rawLine ?: pinyinConverter.toKeywordLine(cleanWake, 1.8f, 0.20f).getOrElse {
                return Result.failure(it)
            }
        } else {
            loadWakeWordEntries().joinToString("\n") { it.rawLine }
        }

        val exitPreset = loadEntriesFromAssets().firstOrNull { it.displayName == cleanExit }
        val exitContent = exitPreset?.rawLine ?: pinyinConverter.toKeywordLine(cleanExit, 1.0f, 0.45f).getOrElse {
            return Result.failure(it)
        }

        activeWakeWordName = cleanWake.ifEmpty { null }
        activeExitWordName = cleanExit

        if (!isInitialized) {
            return Result.success(Unit)
        }

        return runCatching {
            val kwsDir = ensureModelFilesExtracted()
            val wakeFile = File(kwsDir, "active_wake.txt")
            val exitFile = File(kwsDir, "active_exit.txt")
            wakeFile.writeText(wakeContent + "\n", Charsets.UTF_8)
            exitFile.writeText(exitContent + "\n", Charsets.UTF_8)

            val newWakeSpotter = buildSpotter(kwsDir, wakeFile)
            val newWakeStream = newWakeSpotter.createStream()
            val newExitSpotter = buildSpotter(kwsDir, exitFile)
            val newExitStream = newExitSpotter.createStream()

            val oldWakeStream = stream
            val oldWakeSpotter = spotter
            val oldExitStream = exitStream
            val oldExitSpotter = exitSpotter

            activeWakeLinePreview = wakeContent.trim()
            lastError = null
            spotter = newWakeSpotter
            stream = newWakeStream
            exitSpotter = newExitSpotter
            exitStream = newExitStream

            runCatching { oldWakeStream?.release() }
            runCatching { oldWakeSpotter?.release() }
            runCatching { oldExitStream?.release() }
            runCatching { oldExitSpotter?.release() }
            Unit
        }
    }

    private fun buildWakeFileContent(selectedWakeWord: String?): String {
        val clean = selectedWakeWord?.trim().orEmpty()
        if (clean.isNotEmpty()) {
            val preset = loadWakeWordEntries().firstOrNull { it.displayName == clean }
            val line = preset?.rawLine
                ?: pinyinConverter.toKeywordLine(clean, 1.8f, 0.20f).getOrNull()
            if (line != null) return "$line\n"
        }
        return loadWakeWordEntries().joinToString("\n") { it.rawLine } + "\n"
    }

    private fun buildExitFileContent(exitWord: String): String {
        val target = exitWord.ifBlank { DEFAULT_EXIT_KEYWORD }
        val preset = loadEntriesFromAssets().firstOrNull { it.displayName == target }
        val line = preset?.rawLine
            ?: pinyinConverter.toKeywordLine(target, 1.0f, 0.45f).getOrNull()
            ?: "x iǎo zh ì t uì x ià :1.0 #0.45 @小智退下"
        return "$line\n"
    }

    private fun ensureModelFilesExtracted(): File {
        val dir = File(context.filesDir, "kws").apply { mkdirs() }
        val files = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
        for (name in files) {
            val out = File(dir, name)
            if (!out.exists() || out.length() == 0L) {
                context.assets.open("kws/$name").use { input ->
                    out.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        return dir
    }

    private fun buildSpotter(kwsDir: File, keywordsFile: File): KeywordSpotter {
        val transducer = OnlineTransducerModelConfig(
            encoder = File(kwsDir, "encoder.onnx").absolutePath,
            decoder = File(kwsDir, "decoder.onnx").absolutePath,
            joiner = File(kwsDir, "joiner.onnx").absolutePath
        )
        val modelConfig = OnlineModelConfig(
            transducer = transducer,
            tokens = File(kwsDir, "tokens.txt").absolutePath,
            numThreads = 1,
            debug = false,
            modelType = "zipformer2"
        )
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
            modelConfig = modelConfig,
            keywordsFile = keywordsFile.absolutePath
        )
        return KeywordSpotter(assetManager = null, config = config)
    }

    @Synchronized
    fun processPcm16(pcm: ShortArray, length: Int): String? {
        val kws = spotter ?: return null
        val s = stream ?: return null
        return feedAndDecode(kws, s, pcm, length)
    }

    @Synchronized
    fun processExitWordPcm16(pcm: ShortArray, length: Int): String? {
        val kws = exitSpotter ?: return null
        val s = exitStream ?: return null
        return feedAndDecode(kws, s, pcm, length)
    }

    private fun feedAndDecode(
        kws: KeywordSpotter,
        targetStream: OnlineStream,
        pcm: ShortArray,
        length: Int
    ): String? {
        if (floatBuffer.size != length) {
            floatBuffer = FloatArray(length)
        }
        for (i in 0 until length) {
            floatBuffer[i] = pcm[i] / 32768.0f
        }
        targetStream.acceptWaveform(samples = floatBuffer, sampleRate = sampleRate)
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
        spotter?.let { kws -> stream?.let { kws.reset(it) } }
        exitSpotter?.let { kws -> exitStream?.let { kws.reset(it) } }
    }

    @Synchronized
    fun release() {
        isInitialized = false
        runCatching {
            stream?.release()
            spotter?.release()
            exitStream?.release()
            exitSpotter?.release()
        }
        stream = null
        spotter = null
        exitStream = null
        exitSpotter = null
    }

    fun isCurrentExitKeyword(keyword: String): Boolean = keyword.trim() == activeExitWordName

    companion object {
        private const val TAG = "WakeWordDetector"
        const val DEFAULT_EXIT_KEYWORD = "小智退下"

        fun isExitKeyword(keyword: String): Boolean = keyword.trim() == DEFAULT_EXIT_KEYWORD
    }
}
