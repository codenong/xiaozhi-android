package com.codenong.xiaozhi.network

import com.codenong.xiaozhi.model.DeviceIdentity
import com.codenong.xiaozhi.model.WebSocketConfig
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface XiaozhiSocketListener {
    fun onServerHello(sessionId: String, outputSampleRate: Int)
    fun onSttText(text: String)
    fun onLlmEmotion(emotion: String, emojiText: String)
    fun onTtsStart()
    fun onTtsStop()
    fun onTtsSentence(text: String)
    fun onAudioPacket(opusBytes: ByteArray)
    fun onMcpRequest(payload: JSONObject)
    fun onAlert(status: String, message: String, emotion: String)
    fun onClosed(reason: String, isError: Boolean)
}

class XiaozhiWebSocketClient(
    private val listener: XiaozhiSocketListener
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    @Volatile
    var sessionId: String = ""
        private set

    @Volatile
    private var aecEnabled: Boolean = false

    @Volatile
    private var socketOpen: Boolean = false

    fun isConnected(): Boolean = socketOpen && webSocket != null

    fun connect(identity: DeviceIdentity, config: WebSocketConfig, aecEnabled: Boolean = false) {
        this.aecEnabled = aecEnabled
        disconnect()
        val reqBuilder = Request.Builder()
            .url(config.url)
            .addHeader("Protocol-Version", config.version.toString())
            .addHeader("Device-Id", identity.deviceId)
            .addHeader("Client-Id", identity.clientId)

        if (config.token.isNotBlank()) {
            val auth = if (config.token.startsWith("Bearer ", ignoreCase = true)) {
                config.token
            } else {
                "Bearer ${config.token}"
            }
            reqBuilder.addHeader("Authorization", auth)
        }

        webSocket = client.newWebSocket(reqBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== this@XiaozhiWebSocketClient.webSocket) return
                socketOpen = true
                Log.i(TAG_WS, "onOpen connected to ${config.url} (aec=$aecEnabled)")
                sendHello(config.version)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== this@XiaozhiWebSocketClient.webSocket) return
                handleJsonMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (webSocket !== this@XiaozhiWebSocketClient.webSocket) return
                listener.onAudioPacket(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== this@XiaozhiWebSocketClient.webSocket) return
                socketOpen = false
                this@XiaozhiWebSocketClient.webSocket = null
                sessionId = ""
                val detail = t.message ?: response?.message ?: "无法连接语音服务器"
                Log.w(TAG_WS, "onFailure: $detail")
                listener.onClosed(detail, true)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== this@XiaozhiWebSocketClient.webSocket) return
                socketOpen = false
                this@XiaozhiWebSocketClient.webSocket = null
                sessionId = ""
                val isError = code != 1000
                Log.i(TAG_WS, "onClosed: code=$code reason=$reason isError=$isError")
                listener.onClosed(reason.ifEmpty { "连接已断开 ($code)" }, isError)
            }
        })
    }

    private fun sendHello(version: Int) {
        val hello = JSONObject().apply {
            put("type", "hello")
            put("version", version)
            put("transport", "websocket")
            put("features", JSONObject().apply {
                put("mcp", true)
                put("aec", aecEnabled)
            })
            put("audio_params", JSONObject().apply {
                put("format", "opus")
                put("sample_rate", 16000)
                put("channels", 1)
                put("frame_duration", 60)
            })
        }
        Log.i(TAG_WS, "-> hello: $hello")
        webSocket?.send(hello.toString())
    }

    fun sendWakeWordDetected(keyword: String): Boolean {
        val msg = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "detect")
            put("text", keyword)
        }
        Log.i(TAG_WS, "-> listen/detect: $keyword")
        val ok = webSocket?.send(msg.toString()) == true
        if (!ok) socketOpen = false
        return ok
    }

    fun sendStartListening(mode: String = "auto"): Boolean {
        val msg = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "start")
            put("mode", mode)
        }
        Log.i(TAG_WS, "-> listen/start: mode=$mode")
        val ok = webSocket?.send(msg.toString()) == true
        if (!ok) socketOpen = false
        return ok
    }

    fun sendStopListening() {
        val msg = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "stop")
        }
        Log.i(TAG_WS, "-> listen/stop")
        webSocket?.send(msg.toString())
    }

    fun sendAbort(reason: String = "user_interrupt") {
        val msg = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "abort")
            put("reason", reason)
        }
        Log.i(TAG_WS, "-> abort: reason=$reason")
        webSocket?.send(msg.toString())
    }

    fun sendMcpResponse(payload: JSONObject) {
        val msg = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "mcp")
            put("payload", payload)
        }
        webSocket?.send(msg.toString())
    }

    fun sendAudioFrame(opusBytes: ByteArray) {
        webSocket?.send(opusBytes.toByteString())
    }

    fun disconnect() {
        socketOpen = false
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        sessionId = ""
    }

    private fun handleJsonMessage(raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val type = json.optString("type")
        when (type) {
            "hello" -> {
                sessionId = json.optString("session_id")
                val outRate = json.optJSONObject("audio_params")?.optInt("sample_rate", 16000) ?: 16000
                Log.i(TAG_WS, "<- hello: sessionId=$sessionId sampleRate=$outRate")
                listener.onServerHello(sessionId, outRate)
            }
            "stt" -> {
                val sttText = json.optString("text")
                Log.i(TAG_WS, "<- stt (user input): $sttText")
                listener.onSttText(sttText)
            }
            "llm" -> {
                val emo = json.optString("emotion")
                val llmText = json.optString("text")
                Log.i(TAG_WS, "<- llm: emotion=$emo text=$llmText")
                listener.onLlmEmotion(emo, llmText)
            }
            "tts" -> {
                val state = json.optString("state")
                val ttsText = json.optString("text")
                Log.i("XiaozhiTTS", "<- tts state=$state${if (ttsText.isNotEmpty()) " text=$ttsText" else ""}")
                when (state) {
                    "start" -> listener.onTtsStart()
                    "stop" -> listener.onTtsStop()
                    "sentence_start" -> listener.onTtsSentence(ttsText)
                }
            }
            "mcp" -> {
                val payload = json.optJSONObject("payload")
                Log.i(TAG_WS, "<- mcp: method=${payload?.optString("method")} id=${payload?.opt("id")}")
                payload?.let { listener.onMcpRequest(it) }
            }
            "alert" -> {
                val status = json.optString("status")
                val message = json.optString("message")
                val emotion = json.optString("emotion")
                Log.i(TAG_WS, "<- alert: [$status] $message")
                listener.onAlert(status, message, emotion)
            }
            else -> Log.d(TAG_WS, "<- $type: $raw")
        }
    }

    companion object {
        private const val TAG_WS = "XiaozhiWS"
    }
}
