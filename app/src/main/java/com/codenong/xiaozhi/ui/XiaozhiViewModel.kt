package com.codenong.xiaozhi.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codenong.xiaozhi.audio.AudioEngine
import com.codenong.xiaozhi.audio.OpusCodec
import com.codenong.xiaozhi.audio.WakeWordDetector
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
    val handsFreeWakeEnabled: Boolean = true,
    val availableWakeWords: List<String> = emptyList(),
    val activeWakeWord: String = "",
    val errorMessage: String? = null,
    val brightnessPercent: Int? = null
)

class XiaozhiViewModel(application: Application) : AndroidViewModel(application), XiaozhiSocketListener {

    private val identityStore = DeviceIdentityStore(application)
    private val otaClient = OtaClient()
    private val opusCodec = OpusCodec()
    private val wakeWordDetector = WakeWordDetector(application)
    private val mcpServer = McpServer(application) { brightness ->
        _uiState.update { it.copy(brightnessPercent = brightness) }
    }

    private val wsClient = XiaozhiWebSocketClient(this)
    private val audioEngine = AudioEngine(
        scope = viewModelScope,
        opusCodec = opusCodec,
        wakeWordDetector = wakeWordDetector,
        onEncodedFrame = { opus ->
            if (_uiState.value.deviceState == DeviceState.LISTENING) {
                wsClient.sendAudioFrame(opus)
            }
        },
        onWakeWordDetected = { keyword ->
            viewModelScope.launch {
                handleWakeWordTriggered(keyword)
            }
        },
        onVoiceActivity = { /* 可用于本地打断优化 */ }
    )

    private val _uiState = MutableStateFlow(XiaozhiUiState())
    val uiState: StateFlow<XiaozhiUiState> = _uiState.asStateFlow()

    private var cachedWsConfig: WebSocketConfig? = null
    private var activationJob: Job? = null
    private var hasMicPermission: Boolean = false
    private var pendingWakeKeyword: String? = null

    init {
        val identity = identityStore.getIdentity()
        val entries = wakeWordDetector.loadEntriesFromAssets().map { it.displayName }
        val savedWake = identityStore.getSelectedWakeWord()
        val effectiveWake = when {
            savedWake != null && entries.contains(savedWake) -> savedWake
            savedWake == "" && entries.isNotEmpty() -> ""
            entries.isNotEmpty() -> entries.first()
            else -> ""
        }
        _uiState.update {
            it.copy(
                otaUrl = identity.otaUrl,
                deviceId = identity.deviceId,
                availableWakeWords = entries,
                activeWakeWord = effectiveWake
            )
        }
        bootstrapOta()
    }

    fun onMicPermissionChanged(granted: Boolean) {
        hasMicPermission = granted
        if (granted && _uiState.value.deviceState == DeviceState.IDLE) {
            maybeStartWakeWordListening()
        }
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
                                subtitle = idleHint()
                            )
                        }
                        maybeStartWakeWordListening()
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

    private fun handleWakeWordTriggered(keyword: String) {
        val state = _uiState.value.deviceState
        if (state == DeviceState.IDLE) {
            pendingWakeKeyword = keyword
            startConversation()
        } else if (state == DeviceState.SPEAKING) {
            wsClient.sendAbort("wake_word_detected")
            audioEngine.clearPlayback()
            startListeningInternal()
        }
    }

    fun onFaceTapped() {
        when (_uiState.value.deviceState) {
            DeviceState.IDLE -> {
                pendingWakeKeyword = null
                startConversation()
            }
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

    fun toggleHandsFreeWake() {
        val next = !_uiState.value.handsFreeWakeEnabled
        _uiState.update {
            it.copy(
                handsFreeWakeEnabled = next,
                subtitle = if (_uiState.value.deviceState == DeviceState.IDLE) idleHint(next) else it.subtitle
            )
        }
        if (_uiState.value.deviceState == DeviceState.IDLE) {
            if (next) maybeStartWakeWordListening() else audioEngine.stopCapture()
        }
    }

    fun selectWakeWord(wakeWord: String) {
        identityStore.setSelectedWakeWord(wakeWord)
        audioEngine.updateSelectedWakeWord(wakeWord.ifBlank { null })
        _uiState.update { state ->
            state.copy(
                activeWakeWord = wakeWord,
                subtitle = if (state.deviceState == DeviceState.IDLE) {
                    idleHint(state.handsFreeWakeEnabled, wakeWord)
                } else {
                    state.subtitle
                }
            )
        }
    }

    fun updateOtaUrl(newUrl: String) {
        identityStore.updateOtaUrl(newUrl)
        val identity = identityStore.getIdentity()
        _uiState.update { it.copy(otaUrl = identity.otaUrl) }
        bootstrapOta()
    }

    private fun maybeStartWakeWordListening() {
        if (hasMicPermission && _uiState.value.handsFreeWakeEnabled) {
            val active = _uiState.value.activeWakeWord.ifBlank { null }
            audioEngine.startWakeWordDetection(active)
        }
    }

    private fun idleHint(
        wakeEnabled: Boolean = _uiState.value.handsFreeWakeEnabled,
        wakeWord: String = _uiState.value.activeWakeWord
    ): String {
        if (!wakeEnabled) return "点击屏幕开始对话"
        val label = wakeWord.ifBlank { _uiState.value.availableWakeWords.joinToString(" / ").ifBlank { "唤醒词" } }
        return "喊「$label」或点击屏幕唤醒"
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
        audioEngine.startConversationCapture()
    }

    private fun stopConversation() {
        wsClient.sendStopListening()
        wsClient.disconnect()
        _uiState.update {
            it.copy(
                deviceState = DeviceState.IDLE,
                emotion = "neutral",
                emoji = "🤖",
                subtitle = idleHint()
            )
        }
        maybeStartWakeWordListening()
    }

    override fun onServerHello(sessionId: String, outputSampleRate: Int) {
        audioEngine.ensurePlaybackStarted(outputSampleRate)
        pendingWakeKeyword?.let { kw ->
            wsClient.sendWakeWordDetected(kw)
            pendingWakeKeyword = null
        }
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
        if (_uiState.value.handsFreeWakeEnabled && hasMicPermission) {
            maybeStartWakeWordListening()
        } else {
            audioEngine.stopCapture()
        }
        _uiState.update { it.copy(deviceState = DeviceState.SPEAKING) }
    }

    override fun onTtsStop() {
        if (_uiState.value.autoContinue) {
            startListeningInternal()
        } else {
            _uiState.update {
                it.copy(deviceState = DeviceState.IDLE, emoji = "🤖", subtitle = idleHint())
            }
            maybeStartWakeWordListening()
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
        _uiState.update {
            it.copy(
                deviceState = DeviceState.IDLE,
                emoji = "🤖",
                subtitle = idleHint()
            )
        }
        maybeStartWakeWordListening()
    }

    override fun onCleared() {
        super.onCleared()
        activationJob?.cancel()
        wsClient.disconnect()
        audioEngine.release()
    }
}
