package com.codenong.xiaozhi.network

import com.codenong.xiaozhi.model.DeviceIdentity
import com.codenong.xiaozhi.model.WebSocketConfig
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

    fun connect(identity: DeviceIdentity, config: WebSocketConfig) {
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
                this@XiaozhiWebSocketClient.webSocket = null
                sessionId = ""
                val detail = t.message ?: response?.message ?: "无法连接语音服务器"
                listener.onClosed(detail, true)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== this@XiaozhiWebSocketClient.webSocket) return
                this@XiaozhiWebSocketClient.webSocket = null
                sessionId = ""
                val isError = code != 1000
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
                put("aec", false)
            })
            put("audio_params", JSONObject().apply {
                put("format", "opus")
                put("sample_rate", 16000)
                put("channels", 1)
                put("frame_duration", 60)
            })
        }
        webSocket?.send(hello.toString())
    }

    fun sendWakeWordDetected(keyword: String) {
        val msg = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "detect")
            put("text", keyword)
        }
        webSocket?.send(msg.toString())
    }

    fun sendStartListening(mode: String = "auto") {
        val msg = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "start")
            put("mode", mode)
        }
        webSocket?.send(msg.toString())
    }

    fun sendStopListening() {
        val msg = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "stop")
        }
        webSocket?.send(msg.toString())
    }

    fun sendAbort(reason: String = "user_interrupt") {
        val msg = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "abort")
            put("reason", reason)
        }
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
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        sessionId = ""
    }

    private fun handleJsonMessage(raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (json.optString("type")) {
            "hello" -> {
                sessionId = json.optString("session_id")
                val outRate = json.optJSONObject("audio_params")?.optInt("sample_rate", 16000) ?: 16000
                listener.onServerHello(sessionId, outRate)
            }
            "stt" -> listener.onSttText(json.optString("text"))
            "llm" -> listener.onLlmEmotion(json.optString("emotion"), json.optString("text"))
            "tts" -> when (json.optString("state")) {
                "start" -> listener.onTtsStart()
                "stop" -> listener.onTtsStop()
                "sentence_start" -> listener.onTtsSentence(json.optString("text"))
            }
            "mcp" -> json.optJSONObject("payload")?.let { listener.onMcpRequest(it) }
            "alert" -> listener.onAlert(
                json.optString("status"),
                json.optString("message"),
                json.optString("emotion")
            )
        }
    }
}
