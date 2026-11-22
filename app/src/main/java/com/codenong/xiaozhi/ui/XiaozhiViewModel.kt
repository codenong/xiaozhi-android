package com.codenong.xiaozhi.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codenong.xiaozhi.audio.AudioEngine
import com.codenong.xiaozhi.audio.OpusCodec
import com.codenong.xiaozhi.data.DeviceIdentityStore
import com.codenong.xiaozhi.mcp.McpServer
import com.codenong.xiaozhi.model.ActivationInfo
import com.codenong.xiaozhi.model.DeviceState
import com.codenong.xiaozhi.model.WebSocketConfig
import com.codenong.xiaozhi.network.OtaClient
import com.codenong.xiaozhi.network.XiaozhiSocketListener
import com.codenong.xiaozhi.network.XiaozhiWebSocketClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class XiaozhiUiState(
    val deviceState: DeviceState = DeviceState.STARTING,
    val emotion: String = "neutral",
    val emoji: String = "🤖",
    val subtitle: String = "正在初始化...",
    val userSpeech: String = "",
    val activation: ActivationInfo? = null,
    val otaUrl: String = DeviceIdentityStore.DEFAULT_OTA_URL,
    val deviceId: String = "",
    val autoContinue: Boolean = true,
    val errorMessage: String? = null,
    val brightnessPercent: Int? = null
)

class XiaozhiViewModel(application: Application) : AndroidViewModel(application), XiaozhiSocketListener {

    private val identityStore = DeviceIdentityStore(application)
    private val otaClient = OtaClient()
    private val opusCodec = OpusCodec()
    private val mcpServer = McpServer(application) { brightness ->
        _uiState.update { it.copy(brightnessPercent = brightness) }
    }

    private val wsClient = XiaozhiWebSocketClient(this)
    private val audioEngine = AudioEngine(
        scope = viewModelScope,
        opusCodec = opusCodec,
        onEncodedFrame = { opus ->
            if (_uiState.value.deviceState == DeviceState.LISTENING) {
                wsClient.sendAudioFrame(opus)
            }
        },
        onVoiceActivity = { /* 可用于本地打断优化 */ }
    )

    private val _uiState = MutableStateFlow(XiaozhiUiState())
    val uiState: StateFlow<XiaozhiUiState> = _uiState.asStateFlow()

    private var cachedWsConfig: WebSocketConfig? = null
    private var activationJob: Job? = null

    init {
        val identity = identityStore.getIdentity()
        _uiState.update {
            it.copy(otaUrl = identity.otaUrl, deviceId = identity.deviceId)
        }
        bootstrapOta()
    }

    fun bootstrapOta() {
        activationJob?.cancel()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    deviceState = DeviceState.STARTING,
                    subtitle = "正在连接小智引导服务...",
                    errorMessage = null
                )
            }
            val identity = identityStore.getIdentity()
            otaClient.fetchConfig(identity)
                .onSuccess { res ->
                    cachedWsConfig = res.webSocket
                    if (res.activation != null) {
                        _uiState.update {
                            it.copy(
                                deviceState = DeviceState.ACTIVATING,
                                activation = res.activation,
                                subtitle = "请在 xiaozhi.me 控制台输入激活码绑定此设备"
                            )
                        }
                        startActivationPolling()
                    } else {
                        _uiState.update {
                            it.copy(
                                deviceState = DeviceState.IDLE,
                                activation = null,
                                subtitle = "已就绪，点击屏幕开始对话"
                            )
                        }
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            deviceState = DeviceState.ERROR,
                            errorMessage = err.message ?: "网络请求失败",
                            subtitle = "连接引导服务失败，请检查网络或 OTA 地址"
                        )
                    }
                }
        }
    }

    private fun startActivationPolling() {
        activationJob?.cancel()
        activationJob = viewModelScope.launch {
            val identity = identityStore.getIdentity()
            while (true) {
                delay(3000)
                val bound = otaClient.checkActivation(identity).getOrNull() ?: false
                if (bound) {
                    bootstrapOta()
                    break
                }
            }
        }
    }

    fun onFaceTapped() {
        when (_uiState.value.deviceState) {
            DeviceState.IDLE -> startConversation()
            DeviceState.LISTENING -> stopConversation()
            DeviceState.SPEAKING -> {
                wsClient.sendAbort("user_interrupt")
                audioEngine.clearPlayback()
                startListeningInternal()
            }
            DeviceState.ERROR -> bootstrapOta()
            else -> Unit
        }
    }

    fun toggleAutoContinue() {
        _uiState.update { it.copy(autoContinue = !it.autoContinue) }
    }

    fun updateOtaUrl(newUrl: String) {
        identityStore.updateOtaUrl(newUrl)
        val identity = identityStore.getIdentity()
        _uiState.update { it.copy(otaUrl = identity.otaUrl) }
        bootstrapOta()
    }

    private fun startConversation() {
        val ws = cachedWsConfig
        if (ws == null || ws.url.isBlank()) {
            bootstrapOta()
            return
        }
        _uiState.update {
            it.copy(deviceState = DeviceState.CONNECTING, subtitle = "正在连接语音网关...")
        }
        wsClient.connect(identityStore.getIdentity(), ws)
    }

    private fun startListeningInternal() {
        _uiState.update {
            it.copy(
                deviceState = DeviceState.LISTENING,
                emotion = "listening",
                emoji = "👂",
                subtitle = "我在听，请说话..."
            )
        }
        val mode = if (_uiState.value.autoContinue) "auto" else "manual"
        wsClient.sendStartListening(mode)
        audioEngine.startCapture()
    }

    private fun stopConversation() {
        audioEngine.stopCapture()
        wsClient.sendStopListening()
        wsClient.disconnect()
        _uiState.update {
            it.copy(
                deviceState = DeviceState.IDLE,
                emotion = "neutral",
                emoji = "🤖",
                subtitle = "点击屏幕开始对话"
            )
        }
    }

    override fun onServerHello(sessionId: String, outputSampleRate: Int) {
        audioEngine.ensurePlaybackStarted(outputSampleRate)
        startListeningInternal()
    }

    override fun onSttText(text: String) {
        _uiState.update { it.copy(userSpeech = text) }
    }

    override fun onLlmEmotion(emotion: String, emojiText: String) {
        _uiState.update {
            it.copy(
                emotion = emotion.ifBlank { it.emotion },
                emoji = emojiText.ifBlank { it.emoji }
            )
        }
    }

    override fun onTtsStart() {
        audioEngine.stopCapture()
        _uiState.update { it.copy(deviceState = DeviceState.SPEAKING) }
    }

    override fun onTtsStop() {
        if (_uiState.value.autoContinue) {
            startListeningInternal()
        } else {
            _uiState.update {
                it.copy(deviceState = DeviceState.IDLE, emoji = "🤖", subtitle = "点击屏幕继续对话")
            }
        }
    }

    override fun onTtsSentence(text: String) {
        _uiState.update { it.copy(subtitle = text) }
    }

    override fun onAudioPacket(opusBytes: ByteArray) {
        if (_uiState.value.deviceState != DeviceState.LISTENING) {
            audioEngine.enqueueAudioPacket(opusBytes)
        }
    }

    override fun onMcpRequest(payload: JSONObject) {
        mcpServer.handleRequest(payload)?.let { response ->
            wsClient.sendMcpResponse(response)
        }
    }

    override fun onAlert(status: String, message: String, emotion: String) {
        _uiState.update {
            it.copy(subtitle = "[$status] $message", emotion = emotion.ifBlank { "neutral" })
        }
    }

    override fun onClosed(reason: String) {
        audioEngine.stopCapture()
        _uiState.update {
            it.copy(
                deviceState = DeviceState.IDLE,
                emoji = "🤖",
                subtitle = "连接已断开，点击屏幕重新唤醒"
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        activationJob?.cancel()
        wsClient.disconnect()
        audioEngine.release()
    }
}
