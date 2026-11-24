package com.codenong.xiaozhi.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.codenong.xiaozhi.MainActivity
import com.codenong.xiaozhi.audio.AudioEngine
import com.codenong.xiaozhi.audio.MusicPlayer
import com.codenong.xiaozhi.audio.OpusCodec
import com.codenong.xiaozhi.audio.WakeWordDetector
import com.codenong.xiaozhi.data.DeviceIdentityStore
import com.codenong.xiaozhi.mcp.McpServer
import com.codenong.xiaozhi.mcp.MusicControlListener
import com.codenong.xiaozhi.mcp.PhoneControlListener
import com.codenong.xiaozhi.phone.CallPrepareResult
import com.codenong.xiaozhi.phone.PhoneCallManager
import com.codenong.xiaozhi.model.ActivationInfo
import com.codenong.xiaozhi.model.DeviceState
import com.codenong.xiaozhi.model.WebSocketConfig
import com.codenong.xiaozhi.network.OtaClient
import com.codenong.xiaozhi.network.XiaozhiSocketListener
import com.codenong.xiaozhi.network.XiaozhiWebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    val powerSaveEnabled: Boolean = false,
    val availableWakeWords: List<String> = emptyList(),
    val activeWakeWord: String = "",
    val errorMessage: String? = null,
    val brightnessPercent: Int? = null,
    val musicTitle: String? = null,
    val isMusicPlaying: Boolean = false
)

class XiaozhiService : Service(), XiaozhiSocketListener {

    inner class LocalBinder : Binder() {
        fun getService(): XiaozhiService = this@XiaozhiService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var identityStore: DeviceIdentityStore
    private val otaClient = OtaClient()
    private val opusCodec = OpusCodec()
    private lateinit var wakeWordDetector: WakeWordDetector
    private lateinit var mcpServer: McpServer
    private lateinit var wsClient: XiaozhiWebSocketClient
    private lateinit var audioEngine: AudioEngine
    private lateinit var musicPlayer: MusicPlayer
    private lateinit var phoneCallManager: PhoneCallManager
    private var deferredMusicJob: Job? = null
    private var deferredCallJob: Job? = null

    private lateinit var batteryAlertMonitor: BatteryAlertMonitor
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var isNetworkAvailable: Boolean = true
    @Volatile
    private var isUserDisconnecting: Boolean = false
    @Volatile
    private var ignoreNextTtsStop: Boolean = false

    private val _uiState = MutableStateFlow(XiaozhiUiState())
    val uiState: StateFlow<XiaozhiUiState> = _uiState.asStateFlow()

    private var cachedWsConfig: WebSocketConfig? = null
    private var activationJob: Job? = null
    private var hasMicPermission: Boolean = false
    private var pendingWakeKeyword: String? = null

    override fun onCreate() {
        super.onCreate()
        identityStore = DeviceIdentityStore(applicationContext)
        wakeWordDetector = WakeWordDetector(applicationContext)
        musicPlayer = MusicPlayer(applicationContext) { playback ->
            _uiState.update {
                it.copy(
                    musicTitle = playback.title,
                    isMusicPlaying = playback.isPlaying
                )
            }
        }
        phoneCallManager = PhoneCallManager(applicationContext) { inCall ->
            serviceScope.launch {
                handleSystemCallStateChanged(inCall)
            }
        }
        phoneCallManager.startMonitoring()
        mcpServer = McpServer(
            context = applicationContext,
            onBrightnessRequest = { brightness ->
                _uiState.update { it.copy(brightnessPercent = brightness) }
            },
            musicControl = object : MusicControlListener {
                override fun onPlayMusicUrl(url: String, title: String?): String {
                    serviceScope.launch {
                        playMusicFromMcp(url, title)
                    }
                    return "已开始在设备端缓冲播放${title?.let { "《$it》" } ?: "音乐"}"
                }

                override fun onStopMusic(): String {
                    serviceScope.launch { stopMusic() }
                    return "已停止播放音乐"
                }

                override fun onPauseMusic(): String {
                    serviceScope.launch { musicPlayer.pause() }
                    return "已暂停播放音乐"
                }

                override fun onResumeMusic(): String {
                    serviceScope.launch { musicPlayer.resume() }
                    return "已继续播放音乐"
                }
            },
            phoneControl = object : PhoneControlListener {
                override fun onCallTarget(target: String): Pair<String, Boolean> {
                    return when (val res = phoneCallManager.prepareCall(target)) {
                        is CallPrepareResult.ReadyToCall -> {
                            serviceScope.launch {
                                scheduleDeferredPhoneCall()
                            }
                            val label = res.displayName?.let { "$it（${res.number}）" } ?: res.number
                            val routeHint = if (phoneCallManager.hasBluetoothAudioConnected()) {
                                "，将使用蓝牙免提"
                            } else {
                                "，将自动开启外放"
                            }
                            "正在为您拨打给 $label$routeHint" to false
                        }

                        is CallPrepareResult.MultipleMatches -> {
                            val listText = res.matches.joinToString("；") { "${it.name} ${it.number}" }
                            "找到多个匹配联系人，请向用户确认要拨打哪一位：$listText" to false
                        }

                        is CallPrepareResult.Error -> res.message to true
                    }
                }

                override fun onSearchContact(name: String): String {
                    val list = phoneCallManager.searchContacts(name)
                    return if (list.isEmpty()) {
                        "通讯录中未找到与「$name」匹配的联系人"
                    } else {
                        list.joinToString("；") { "${it.name}: ${it.number}" }
                    }
                }
            }
        )
        wsClient = XiaozhiWebSocketClient(this)
        audioEngine = AudioEngine(
            context = applicationContext,
            scope = serviceScope,
            opusCodec = opusCodec,
            wakeWordDetector = wakeWordDetector,
            onEncodedFrame = { opus ->
                if (_uiState.value.deviceState == DeviceState.LISTENING) {
                    wsClient.sendAudioFrame(opus)
                }
            },
            onWakeWordDetected = { keyword ->
                serviceScope.launch {
                    handleWakeWordTriggered(keyword)
                }
            },
            onExitWordDetected = {
                serviceScope.launch {
                    handleExitWordTriggered()
                }
            },
            onVoiceActivity = { /* 可用于本地打断优化 */ }
        )

        batteryAlertMonitor = BatteryAlertMonitor(applicationContext)
        batteryAlertMonitor.start()
        registerNetworkMonitor()
        createNotificationChannel()
        startForegroundCompat("小智正在后台待机聆听")

        val savedPowerSave = identityStore.isPowerSaveEnabled()
        audioEngine.powerSaveEnabled = savedPowerSave
        val identity = identityStore.getIdentity()
        val entries = wakeWordDetector.loadWakeWordEntries().map { it.displayName }
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
                powerSaveEnabled = savedPowerSave,
                availableWakeWords = entries,
                activeWakeWord = effectiveWake
            )
        }
        bootstrapOta()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun onMicPermissionChanged(granted: Boolean) {
        hasMicPermission = granted
        phoneCallManager.startMonitoring()
        if (granted && _uiState.value.deviceState == DeviceState.IDLE && !phoneCallManager.isInSystemCall) {
            maybeStartWakeWordListening()
        }
    }

    fun bootstrapOta() {
        activationJob?.cancel()
        serviceScope.launch {
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
        activationJob = serviceScope.launch {
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
        if (WakeWordDetector.isExitKeyword(keyword)) {
            val state = _uiState.value.deviceState
            if (state == DeviceState.LISTENING || state == DeviceState.SPEAKING) {
                handleExitWordTriggered()
            }
            return
        }
        val state = _uiState.value.deviceState
        if (state == DeviceState.IDLE) {
            pendingWakeKeyword = keyword
            startConversation()
        } else if (state == DeviceState.SPEAKING) {
            ignoreNextTtsStop = true
            wsClient.sendAbort("wake_word_detected")
            audioEngine.clearPlayback()
            startListeningInternal()
        }
    }

    private fun handleExitWordTriggered() {
        stopMusic()
        endConversation()
    }

    private fun playMusicFromMcp(url: String, title: String?) {
        deferredMusicJob?.cancel()
        val shouldDefer = _uiState.value.deviceState != DeviceState.IDLE
        musicPlayer.play(url, title, deferStart = shouldDefer)
        if (shouldDefer) {
            deferredMusicJob = serviceScope.launch {
                delay(2500)
                if (musicPlayer.hasDeferredStart()) {
                    startMusicAndReturnIdle()
                }
            }
        }
    }

    private fun startMusicAndReturnIdle() {
        deferredMusicJob?.cancel()
        musicPlayer.startDeferred()
        if (_uiState.value.deviceState != DeviceState.IDLE) {
            isUserDisconnecting = true
            ignoreNextTtsStop = false
            audioEngine.exitWordEnabled = false
            wsClient.sendStopListening()
            if (_uiState.value.powerSaveEnabled) {
                audioEngine.pausePlayback()
                releaseWifiLock()
            } else {
                audioEngine.clearPlayback()
            }
            audioEngine.restoreAudioMode()
            wsClient.disconnect()
            _uiState.update {
                it.copy(
                    deviceState = DeviceState.IDLE,
                    emotion = "neutral",
                    emoji = "🤖",
                    subtitle = idleHint()
                )
            }
            if (_uiState.value.handsFreeWakeEnabled) {
                maybeStartWakeWordListening()
            } else {
                audioEngine.stopCapture()
                releaseAllLocks()
            }
        }
    }

    fun stopMusic() {
        deferredMusicJob?.cancel()
        musicPlayer.stop()
    }

    private fun scheduleDeferredPhoneCall() {
        deferredCallJob?.cancel()
        deferredCallJob = serviceScope.launch {
            delay(2000L)
            if (phoneCallManager.hasPendingCall()) {
                executePendingCallAndIdle()
            }
        }
    }

    private fun executePendingCallAndIdle() {
        deferredCallJob?.cancel()
        if (!phoneCallManager.hasPendingCall()) return
        if (_uiState.value.deviceState != DeviceState.IDLE) {
            isUserDisconnecting = true
            ignoreNextTtsStop = false
            audioEngine.exitWordEnabled = false
            wsClient.sendStopListening()
            audioEngine.pausePlayback()
            audioEngine.restoreAudioMode()
            releaseWifiLock()
            wsClient.disconnect()
        }
        audioEngine.stopCapture()
        _uiState.update {
            it.copy(
                deviceState = DeviceState.IDLE,
                emotion = "neutral",
                emoji = "📞",
                subtitle = "正在呼出电话..."
            )
        }
        phoneCallManager.executePendingCall()
    }

    private fun handleSystemCallStateChanged(inCall: Boolean) {
        if (inCall) {
            if (musicPlayer.isActive()) {
                musicPlayer.pause()
            }
            if (_uiState.value.deviceState != DeviceState.IDLE) {
                isUserDisconnecting = true
                ignoreNextTtsStop = false
                audioEngine.exitWordEnabled = false
                wsClient.sendAbort("system_call")
                wsClient.sendStopListening()
                audioEngine.pausePlayback()
                audioEngine.restoreAudioMode()
                releaseWifiLock()
                wsClient.disconnect()
            }
            audioEngine.stopCapture()
            _uiState.update {
                it.copy(
                    deviceState = DeviceState.IDLE,
                    emoji = "📞",
                    subtitle = "系统通话中，已暂停小智语音"
                )
            }
            updateNotification("通话中 · 已暂停小智语音")
        } else {
            _uiState.update {
                it.copy(
                    deviceState = DeviceState.IDLE,
                    emoji = "🤖",
                    subtitle = idleHint()
                )
            }
            if (_uiState.value.handsFreeWakeEnabled && hasMicPermission) {
                maybeStartWakeWordListening()
            } else {
                updateNotification("小智已待机")
            }
        }
    }

    fun toggleMusicPlayback() {
        if (_uiState.value.isMusicPlaying) {
            musicPlayer.pause()
        } else {
            deferredMusicJob?.cancel()
            musicPlayer.resume()
        }
    }

    fun shutdownService() {
        isUserDisconnecting = true
        deferredCallJob?.cancel()
        phoneCallManager.cancelPendingCall()
        phoneCallManager.stopMonitoring()
        deferredMusicJob?.cancel()
        activationJob?.cancel()
        runCatching { wsClient.sendAbort("app_exit") }
        runCatching { wsClient.sendStopListening() }
        wsClient.disconnect()
        musicPlayer.release()
        audioEngine.release()
        releaseAllLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun endConversation() {
        when (_uiState.value.deviceState) {
            DeviceState.CONNECTING,
            DeviceState.LISTENING,
            DeviceState.SPEAKING -> stopConversation()
            else -> Unit
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
                ignoreNextTtsStop = true
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

    fun togglePowerSaveMode() {
        val next = !_uiState.value.powerSaveEnabled
        identityStore.setPowerSaveEnabled(next)
        audioEngine.powerSaveEnabled = next
        _uiState.update { it.copy(powerSaveEnabled = next) }
        if (_uiState.value.deviceState == DeviceState.IDLE && _uiState.value.handsFreeWakeEnabled) {
            if (next) {
                releaseWifiLock()
                audioEngine.pausePlayback()
            } else {
                acquireWifiLock()
            }
        }
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
            if (next) {
                maybeStartWakeWordListening()
            } else {
                audioEngine.stopCapture()
                releaseAllLocks()
            }
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
            acquireWakeLock()
            if (!_uiState.value.powerSaveEnabled) {
                acquireWifiLock()
            }
            val active = _uiState.value.activeWakeWord.ifBlank { null }
            audioEngine.exitWordEnabled = _uiState.value.deviceState == DeviceState.SPEAKING
            audioEngine.startWakeWordDetection(active)
            updateNotification("锁屏待机中 · 喊「${active ?: "唤醒词"}」开始对话")
        }
    }

    private fun idleHint(
        wakeEnabled: Boolean = _uiState.value.handsFreeWakeEnabled,
        wakeWord: String = _uiState.value.activeWakeWord
    ): String {
        if (!wakeEnabled) return "点击屏幕开始对话"
        val label = wakeWord.ifBlank { _uiState.value.availableWakeWords.joinToString(" / ").ifBlank { "唤醒词" } }
        return "喊「$label」或点击屏幕唤醒（锁屏可用）"
    }

    private fun startConversation() {
        if (musicPlayer.isActive()) {
            musicPlayer.pause()
        }
        if (!isNetworkAvailable) {
            _uiState.update {
                it.copy(
                    deviceState = DeviceState.IDLE,
                    subtitle = "当前无网络连接，请检查 Wi-Fi",
                    errorMessage = "网络已断开，无法连接小智服务"
                )
            }
            updateNotification("网络已断开，无法使用")
            return
        }
        val ws = cachedWsConfig
        if (ws == null || ws.url.isBlank()) {
            bootstrapOta()
            return
        }
        isUserDisconnecting = false
        ignoreNextTtsStop = false
        audioEngine.exitWordEnabled = false
        acquireWakeLock()
        acquireWifiLock()
        _uiState.update {
            it.copy(
                deviceState = DeviceState.CONNECTING,
                subtitle = "正在连接语音网关...",
                errorMessage = null
            )
        }
        updateNotification("正在连接小智语音网关...")
        wsClient.connect(identityStore.getIdentity(), ws)
    }

    private fun startListeningInternal() {
        audioEngine.exitWordEnabled = true
        _uiState.update {
            it.copy(
                deviceState = DeviceState.LISTENING,
                emotion = "listening",
                emoji = "👂",
                subtitle = "我在听，请说话...（说「${WakeWordDetector.EXIT_KEYWORD}」结束）",
                errorMessage = null
            )
        }
        updateNotification("正在聆听说话...")
        val mode = if (_uiState.value.autoContinue) "auto" else "manual"
        wsClient.sendStartListening(mode)
        audioEngine.startConversationCapture(_uiState.value.activeWakeWord.ifBlank { null })
    }

    private fun stopConversation() {
        isUserDisconnecting = true
        ignoreNextTtsStop = false
        audioEngine.exitWordEnabled = false
        wsClient.sendAbort("user_exit")
        wsClient.sendStopListening()
        if (_uiState.value.powerSaveEnabled) {
            audioEngine.pausePlayback()
            releaseWifiLock()
        } else {
            audioEngine.clearPlayback()
        }
        audioEngine.restoreAudioMode()
        wsClient.disconnect()
        _uiState.update {
            it.copy(
                deviceState = DeviceState.IDLE,
                emotion = "neutral",
                emoji = "🤖",
                subtitle = idleHint(),
                errorMessage = if (isNetworkAvailable) null else "网络已断开，无法连接小智服务"
            )
        }
        if (_uiState.value.handsFreeWakeEnabled) {
            maybeStartWakeWordListening()
        } else {
            audioEngine.stopCapture()
            releaseAllLocks()
            updateNotification("小智已待机")
        }
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
        ignoreNextTtsStop = false
        _uiState.update { it.copy(deviceState = DeviceState.SPEAKING) }
        audioEngine.exitWordEnabled = true
        if (_uiState.value.handsFreeWakeEnabled && hasMicPermission) {
            maybeStartWakeWordListening()
        } else {
            audioEngine.stopCapture()
        }
        updateNotification("小智正在回复...")
    }

    override fun onTtsStop() {
        if (ignoreNextTtsStop) {
            ignoreNextTtsStop = false
            return
        }
        if (phoneCallManager.hasPendingCall()) {
            executePendingCallAndIdle()
            return
        }
        if (musicPlayer.hasDeferredStart()) {
            startMusicAndReturnIdle()
            return
        }
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

    override fun onClosed(reason: String, isError: Boolean) {
        audioEngine.exitWordEnabled = false
        ignoreNextTtsStop = false
        if (_uiState.value.powerSaveEnabled) {
            audioEngine.pausePlayback()
            releaseWifiLock()
        } else {
            audioEngine.clearPlayback()
        }
        audioEngine.restoreAudioMode()
        val userEnded = isUserDisconnecting
        isUserDisconnecting = false
        val showDisconnectError = !userEnded && (isError || !isNetworkAvailable)
        val errText = if (showDisconnectError) {
            if (!isNetworkAvailable) "网络已断开，请检查 Wi-Fi 或移动网络" else "连接已断开：$reason"
        } else {
            null
        }
        _uiState.update {
            it.copy(
                deviceState = DeviceState.IDLE,
                emoji = "🤖",
                subtitle = if (showDisconnectError) "网络异常，连接已断开" else idleHint(),
                errorMessage = errText
            )
        }
        if (_uiState.value.handsFreeWakeEnabled) {
            maybeStartWakeWordListening()
            if (showDisconnectError) {
                updateNotification("网络已断开，等待恢复")
            }
        } else {
            audioEngine.stopCapture()
            releaseAllLocks()
            updateNotification(if (showDisconnectError) "网络已断开" else "小智已待机")
        }
    }

    private fun registerNetworkMonitor() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm
        val activeCaps = cm.getNetworkCapabilities(cm.activeNetwork)
        isNetworkAvailable = activeCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (!isNetworkAvailable) {
            _uiState.update { it.copy(errorMessage = "当前无网络连接，请检查 Wi-Fi 或移动网络") }
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                serviceScope.launch {
                    isNetworkAvailable = true
                    _uiState.update { state ->
                        if (state.errorMessage?.contains("网络") == true || state.errorMessage?.contains("连接") == true) {
                            state.copy(
                                errorMessage = null,
                                subtitle = if (state.deviceState == DeviceState.IDLE) idleHint() else state.subtitle
                            )
                        } else {
                            state
                        }
                    }
                    if (_uiState.value.deviceState == DeviceState.ERROR) {
                        bootstrapOta()
                    }
                }
            }

            override fun onLost(network: Network) {
                serviceScope.launch {
                    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                    val stillOnline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                    isNetworkAvailable = stillOnline
                    if (!stillOnline) {
                        _uiState.update {
                            it.copy(errorMessage = "当前无网络连接，请检查 Wi-Fi 或移动网络")
                        }
                        updateNotification("网络已断开，无法使用")
                    }
                }
            }
        }
        networkCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xiaozhi:WakeWordLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "xiaozhi:WifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWifiLock() {
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private fun releaseAllLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "小智语音服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "支持在锁屏和熄屏状态下继续与小智语音对话"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("小智 AI")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(contentText: String) {
        val notification = buildNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let { cb ->
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        }
        batteryAlertMonitor.stop()
        activationJob?.cancel()
        wsClient.disconnect()
        deferredCallJob?.cancel()
        phoneCallManager.stopMonitoring()
        deferredMusicJob?.cancel()
        musicPlayer.release()
        audioEngine.release()
        releaseAllLocks()
        serviceScope.cancel()
    }

    companion object {
        private const val CHANNEL_ID = "xiaozhi_voice_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
