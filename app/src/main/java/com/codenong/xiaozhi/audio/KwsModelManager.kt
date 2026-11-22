package com.codenong.xiaozhi.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class KwsModelManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    val modelDir: File = File(context.filesDir, "kws")

    private val requiredFiles = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")

    fun isModelReady(): Boolean {
        return requiredFiles.all { name ->
            val f = File(modelDir, name)
            f.exists() && f.length() > 0
        }
    }

    suspend fun downloadAndExtract(
        modelZipUrl: String,
        onProgress: (Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = modelZipUrl.trim()
            if (url.isEmpty()) error("未配置模型包地址")

            modelDir.mkdirs()
            val tempZip = File(context.cacheDir, "kws-model.zip")
            if (tempZip.exists()) tempZip.delete()

            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("下载模型失败 HTTP ${response.code}")
                }
                val body = response.body ?: error("模型响应为空")
                val total = body.contentLength()
                var downloaded = 0L
                val buf = ByteArray(8192)
                body.byteStream().use { input ->
                    tempZip.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buf)
                            if (read == -1) break
                            output.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) {
                                onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 99))
                            }
                        }
                    }
                }
            }

            ZipInputStream(tempZip.inputStream().buffered()).use { zis ->
                val buf = ByteArray(8192)
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val fileName = File(entry.name).name
                        if (fileName in requiredFiles) {
                            val outFile = File(modelDir, fileName)
                            outFile.outputStream().use { out ->
                                while (true) {
                                    val len = zis.read(buf)
                                    if (len <= 0) break
                                    out.write(buf, 0, len)
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            tempZip.delete()

            if (!isModelReady()) {
                error("压缩包缺少必要的模型文件（encoder/decoder/joiner/tokens.txt）")
            }
            onProgress(100)
        }
    }
}
