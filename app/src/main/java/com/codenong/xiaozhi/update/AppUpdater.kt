package com.codenong.xiaozhi.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class RemoteAppVersion(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val changelog: String
)

class AppUpdater(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun getCurrentVersionCode(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    fun getCurrentVersionName(): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.versionName ?: "1.0.0"
    }

    suspend fun checkForUpdate(versionJsonUrl: String): Result<RemoteAppVersion?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = versionJsonUrl.trim()
                if (url.isEmpty()) return@runCatching null

                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error("HTTP ${response.code}")
                    }
                    val json = JSONObject(raw)
                    val remoteCode = json.optLong("versionCode", 0L)
                    val remoteName = json.optString("versionName", "")
                    val apkUrl = json.optString("apkUrl", "")
                    val changelog = json.optString("changelog", "")
                    if (remoteCode > getCurrentVersionCode() && apkUrl.isNotBlank()) {
                        RemoteAppVersion(
                            versionCode = remoteCode,
                            versionName = remoteName.ifBlank { remoteCode.toString() },
                            apkUrl = apkUrl,
                            changelog = changelog
                        )
                    } else {
                        null
                    }
                }
            }
        }

    suspend fun downloadApk(
        apkUrl: String,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val outFile = File(dir, "xiaozhi-update.apk")
            if (outFile.exists()) outFile.delete()

            val request = Request.Builder().url(apkUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("下载失败 HTTP ${response.code}")
                }
                val body = response.body ?: error("空响应体")
                val total = body.contentLength()
                var downloaded = 0L
                val buf = ByteArray(8192)

                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buf)
                            if (read == -1) break
                            output.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) {
                                onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                }
            }
            onProgress(100)
            outFile
        }
    }

    fun installApk(apkFile: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(permIntent)
            return
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
