package com.codenong.xiaozhi.network

import android.os.Build
import com.codenong.xiaozhi.model.ActivationInfo
import com.codenong.xiaozhi.model.DeviceIdentity
import com.codenong.xiaozhi.model.OtaResponse
import com.codenong.xiaozhi.model.WebSocketConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class OtaClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchConfig(identity: DeviceIdentity, appVersion: String = "1.0.0"): Result<OtaResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bodyJson = JSONObject().apply {
                    put("version", 2)
                    put("language", Locale.getDefault().toLanguageTag())
                    put("mac_address", identity.deviceId)
                    put("uuid", identity.clientId)
                    put("chip_model_name", "android-${Build.HARDWARE}")
                    put("application", JSONObject().apply {
                        put("name", "xiaozhi-android")
                        put("version", appVersion)
                    })
                    put("board", JSONObject().apply {
                        put("type", "android-tablet")
                        put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("mac", identity.deviceId)
                    })
                    put("protocol", JSONObject().apply {
                        put("features", JSONObject().apply {
                            put("mcp", true)
                            put("mcp_20260728", true)
                            put("aec", false)
                        })
                    })
                }

                val request = Request.Builder()
                    .url(identity.otaUrl)
                    .addHeader("Device-Id", identity.deviceId)
                    .addHeader("Client-Id", identity.clientId)
                    .addHeader("User-Agent", "xiaozhi-android/$appVersion")
                    .addHeader("Accept-Language", Locale.getDefault().toLanguageTag())
                    .addHeader("Activation-Version", "1")
                    .post(bodyJson.toString().toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error("OTA HTTP ${response.code}: $raw")
                    }
                    val root = JSONObject(raw)
                    val wsObj = root.optJSONObject("websocket")
                    val wsConfig = wsObj?.let {
                        WebSocketConfig(
                            url = it.optString("url"),
                            token = it.optString("token"),
                            version = it.optInt("version", 1)
                        )
                    }
                    val actObj = root.optJSONObject("activation")
                    val activation = actObj?.let {
                        ActivationInfo(
                            code = it.optString("code"),
                            message = it.optString("message"),
                            challenge = it.optString("challenge")
                        )
                    }
                    OtaResponse(webSocket = wsConfig, activation = activation)
                }
            }
        }

    suspend fun checkActivation(identity: DeviceIdentity, appVersion: String = "1.0.0"): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = identity.otaUrl.trimEnd('/')
                val activateUrl = "$base/activate"
                val request = Request.Builder()
                    .url(activateUrl)
                    .addHeader("Device-Id", identity.deviceId)
                    .addHeader("Client-Id", identity.clientId)
                    .addHeader("User-Agent", "xiaozhi-android/$appVersion")
                    .addHeader("Activation-Version", "1")
                    .post("{}".toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> true
                        202 -> false
                        else -> error("Activate HTTP ${response.code}: ${response.body?.string().orEmpty()}")
                    }
                }
            }
        }
}
