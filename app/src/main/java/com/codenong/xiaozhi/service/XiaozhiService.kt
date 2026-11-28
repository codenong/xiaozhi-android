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
import android.media.Ringtone
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
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
import com.codenong.xiaozhi.mcp.ReminderControlListener
import com.codenong.xiaozhi.phone.PhoneCallManager
import com.codenong.xiaozhi.reminder.ReminderManager
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
    val aecAvailable: Boolean = false,
    val availableWakeWords: List<String> = emptyList(),
    val activeWakeWord: String = "",
    val activeWakeTokens: String = "",
    val activeExitWord: String = WakeWordDetector.DEFAULT_EXIT_KEYWORD,
    val activeExitTokens: String = "",
    val wakeWordDebugInfo: String = "",
    val errorMessage: String? = null,
    val brightnessPercent: Int? = null,
    val musicTitle: String? = null,
    val isMusicPlaying: Boolean = false,
    val activeReminderMessage: String? = null
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
    private lateinit var reminderManager: ReminderManager
    private var fallbackRingtone: Ringtone? = null
    private var reminderTimeoutJob: Job? = null
    @Volatile
    private var pendingReminderPrompt: String? = null
    @Volatile
    private var isReminderActive: Boolean = false
    @Volatile
    private var resumeMusicAfterReminder: Boolean = false
    @Volatile
    private var lastFiredReminderMessage: String? = null
    private var deferredMusicJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var endConversationFallbackJob: Job? = null
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
    @Volatile
    private var pendingEndConversationAfterTts: Boolean = false
    @Volatile
    private var resumeMusicAfterVolumeTts: Boolean = false

    private val _uiState = MutableStateFlow(XiaozhiUiState())
    val uiState: StateFlow<XiaozhiUiState> = _uiState.asStateFlow()

    private var cachedWsConfig: WebSocketConfig? = null
    private var activationJob: Job? = null
    private var hasMicPermission: Boolean = false
    private var pendingWakeKeyword: String? = null
    private var lastOutputSampleRate: Int = 16000

    override fun onCreate() {
        super.onCreate()
        identityStore = DeviceIdentityStore(applicationContext)
        wakeWordDetector = WakeWordDetector(applicationContext)
        musicPlayer = MusicPlayer(
            context = applicationContext,
            onStateChanged = { playback ->
                audioEngine.isMusicPlaying = playback.isPlaying
                _uiState.update {
                    it.copy(
                        musicTitle = playback.title,
                        isMusicPlaying = playback.isPlaying
                    )
                }
            },
            onPlaybackCompleted = {
                serviceScope.launch {
                    val wsConnected = wsClient.isConnected()
                    Log.i("XiaozhiAudio", "Music completed: wsConnected=$wsConnected, state=${_uiState.value.deviceState}")
                    if (!wsConnected && isNetworkAvailable && hasMicPermission && _uiState.value.deviceState == DeviceState.IDLE) {
                        startConversation()
                    }
                }
            }
        )
        phoneCallManager = PhoneCallManager(applicationContext) { inCall ->
            serviceScope.launch {
                handleSystemCallStateChanged(inCall)
            }
        }
        phoneCallManager.startMonitoring()
        reminderManager = ReminderManager(applicationContext)
        reminderManager.rescheduleAll()
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
            },
            reminderControl = object : ReminderControlListener {
                override fun onGetCurrentTime(): String = reminderManager.getCurrentTimeJson()

                override fun onGetFiredReminder(): String = formatFiredAlertInstruction()

                override fun onCreateReminder(
                    message: String,
                    delaySeconds: Long?,
                    triggerAt: String?
                ): Pair<String, Boolean> {
                    return reminderManager.createReminder(message, delaySeconds, triggerAt).fold(
                        onSuccess = { item ->
                            val timeStr = reminderManager.formatTriggerTime(item.triggerAtMs)
                            "已设置提醒（ID:${item.id}）：将在 $timeStr 提醒「${item.message}」。请立即用语音明确告知用户已设好该提醒的时间和内容。" to false
                        },
                        onFailure = { err ->
                            (err.message ?: "设置提醒失败") to true
                        }
                    )
                }

                override fun onListReminders(): String {
                    val items = reminderManager.listReminders()
                    val listText = if (items.isEmpty()) {
                        "当前没有待触发的提醒"
                    } else {
                        items.joinToString("；") {
                            "ID ${it.id}（${reminderManager.formatTriggerTime(it.triggerAtMs)}）：${it.message}"
                        }
                    }
                    val firedMsg = if (isReminderActive) lastFiredReminderMessage?.trim().orEmpty() else ""
                    return if (firedMsg.isNotEmpty()) {
                        "${formatFiredAlertInstruction()}（其余待触发提醒：$listText）"
                    } else {
                        listText
                    }
                }

                override fun onCancelReminder(id: Int?, keyword: String?): String {
                    val removed = reminderManager.cancelReminder(id, keyword)
                    return if (removed.isEmpty()) {
                        "未找到匹配的待触发提醒"
                    } else {
                        "已取消 ${removed.size} 条提醒：" + removed.joinToString("、") { it.message }
                    }
                }
            },
            onEndConversation = {
                serviceScope.launch {
                    scheduleEndConversationAndCloseWs()
                }
                "已准备结束对话并关闭连接。简短告别即可（若用户要求闭嘴或不要插话请不要再说话）。"
            },
            onVolumeChanged = {
                if (musicPlayer.isPausedWithTrack()) {
                    resumeMusicAfterVolumeTts = true
                }
            }
        )
        wsClient = XiaozhiWebSocketClient(this)
        val hasAec = AcousticEchoCanceler.isAvailable()
        Log.i("XiaozhiAudio", "AcousticEchoCanceler.isAvailable() = $hasAec")
        audioEngine = AudioEngine(
            context = applicationContext,
            scope = serviceScope,
            opusCodec = opusCodec,
            wakeWordDetector = wakeWordDetector,
            onEncodedFrame = { opus ->
                val state = _uiState.value.deviceState
                if (state == DeviceState.LISTENING || (isRealtimeMode() && state == DeviceState.SPEAKING)) {
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
            onVoiceActivity = { /* 可用于本地打断优化 */ },
            onWakeSpeechEnergy = { active ->
                if (musicPlayer.isActive()) {
                    musicPlayer.setDucked(active)
                }
            }
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
        val savedWakeTokens = identityStore.getCustomWakeTokens()
        val savedExit = identityStore.getCustomExitWord()
        val savedExitTokens = identityStore.getCustomExitTokens()
        val effectiveWake = when {
            !savedWake.isNullOrBlank() -> savedWake
            savedWake == "" && entries.isNotEmpty() -> ""
            entries.isNotEmpty() -> entries.first()
            else -> ""
        }
        _uiState.update {
            it.copy(
                otaUrl = identity.otaUrl,
                deviceId = identity.deviceId,
                powerSaveEnabled = savedPowerSave,
                aecAvailable = hasAec,
                availableWakeWords = entries,
                activeWakeWord = effectiveWake,
                activeWakeTokens = savedWakeTokens,
                activeExitWord = savedExit,
                activeExitTokens = savedExitTokens
            )
        }
        bootstrapOta()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TRIGGER_REMINDER) {
            val reminderId = intent.getIntExtra(ReminderManager.EXTRA_REMINDER_ID, -1)
            val message = intent.getStringExtra(ReminderManager.EXTRA_REMINDER_MESSAGE).orEmpty()
            if (message.isNotBlank()) {
                handleReminderFired(reminderId, message)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun onMicPermissionChanged(granted: Boolean) {
        hasMicPermission = granted
        phoneCallManager.startMonitoring()
        if (granted && _uiState.value.deviceState == DeviceState.IDLE && !phoneCallManager.isInSystemCall) {
            if (cachedWsConfig != null && isNetworkAvailable && !wsClient.isConnected()) {
                startConversation()
            } else {
                maybeStartWakeWordListening()
            }
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
                        if (hasMicPermission && isNetworkAvailable && !phoneCallManager.isInSystemCall) {
                            startConversation()
                        } else {
                            maybeStartWakeWordListening()
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
        val state = _uiState.value.deviceState
        Log.i(
            "XiaozhiKWS",
            "handleWakeWordTriggered('$keyword'): state=$state, net=$isNetworkAvailable, hasWs=${cachedWsConfig != null}, wsConnected=${wsClient.isConnected()}"
        )
        if (state == DeviceState.IDLE || (state == DeviceState.LISTENING && !wsClient.isConnected())) {
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
        reminderTimeoutJob?.cancel()
        pendingReminderPrompt = null
        isReminderActive = false
        resumeMusicAfterReminder = false
        runCatching { fallbackRingtone?.stop() }
        fallbackRingtone = null
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
            audioEngine.notifyTtsSpeaking(false)
        audioEngine.exitWordEnabled = false
            wsClient.sendStopListening()
            if (_uiState.value.powerSaveEnabled) {
                audioEngine.pausePlayback()
                releaseWifiLock()
            } else {
                audioEngine.clearPlayback()
            }
            audioEngine.restoreAudioMode()
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

    fun dismissReminder() {
        reminderTimeoutJob?.cancel()
        pendingReminderPrompt = null
        val wasReminder = isReminderActive
        isReminderActive = false
        runCatching { fallbackRingtone?.stop() }
        fallbackRingtone = null
        _uiState.update { it.copy(activeReminderMessage = null) }
        if (wasReminder && _uiState.value.deviceState != DeviceState.IDLE) {
            stopConversation()
        }
        if (resumeMusicAfterReminder) {
            resumeMusicAfterReminder = false
            musicPlayer.resume()
        }
    }

    private fun formatFiredAlertInstruction(): String {
        val msg = lastFiredReminderMessage?.trim().orEmpty()
        val isGeneric = msg.isEmpty() || msg in setOf("提醒", "闹钟", "定时提醒", "时间到了")
        return if (isGeneric) {
            "没有具体提醒内容，请直接用语音说：提醒时间到了。"
        } else {
            "必须用语音直接播报以下具体提醒内容（不要只说时间到了）：时间到了，提醒你$msg。"
        }
    }

    private fun handleReminderFired(reminderId: Int, message: String) {
        if (reminderId > 0) {
            reminderManager.markTriggered(reminderId)
        }
        if (musicPlayer.isActive()) {
            resumeMusicAfterReminder = true
            musicPlayer.pause()
        }
        lastFiredReminderMessage = message
        _uiState.update { it.copy(activeReminderMessage = message) }
        updateNotification("⏰ 提醒：$message")

        val wsCfg = cachedWsConfig
        if (!isNetworkAvailable || wsCfg == null || wsCfg.url.isBlank()) {
            isReminderActive = true
            playFallbackReminderTone()
            reminderTimeoutJob?.cancel()
            reminderTimeoutJob = serviceScope.launch {
                delay(4000L)
                runCatching { fallbackRingtone?.stop() }
                fallbackRingtone = null
                finishReminderAndResumeMusic()
            }
            return
        }

        val trimmedMsg = message.trim()
        val isGeneric = trimmedMsg.isEmpty() || trimmedMsg in setOf("提醒", "闹钟", "定时提醒", "时间到了")
        val prompt = if (!isGeneric && trimmedMsg.length <= 12) trimmedMsg else "闹钟响了"
        isReminderActive = true
        pendingReminderPrompt = prompt

        reminderTimeoutJob?.cancel()
        reminderTimeoutJob = serviceScope.launch {
            delay(18000L)
            if (isReminderActive) {
                finishReminderAndResumeMusic()
            }
        }

        if (_uiState.value.deviceState == DeviceState.IDLE) {
            pendingWakeKeyword = null
            startConversation()
        } else {
            ignoreNextTtsStop = false
            wsClient.sendAbort("reminder_alert")
            audioEngine.clearPlayback()
            wsClient.sendWakeWordDetected(prompt)
            pendingReminderPrompt = null
        }
    }

    private fun finishReminderAndResumeMusic() {
        reminderTimeoutJob?.cancel()
        pendingReminderPrompt = null
        if (!isReminderActive) return
        isReminderActive = false
        if (_uiState.value.deviceState != DeviceState.IDLE) {
            stopConversation()
        }
        if (resumeMusicAfterReminder) {
            resumeMusicAfterReminder = false
            musicPlayer.resume()
        }
    }

    private fun playFallbackReminderTone() {
        runCatching {
            fallbackRingtone?.stop()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ring = RingtoneManager.getRingtone(applicationContext, uri)
            fallbackRingtone = ring
            ring?.play()
        }
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
            audioEngine.notifyTtsSpeaking(false)
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

    fun previewPromptTokens(phrase: String): String = audioEngine.previewTokens(phrase)

    fun updatePromptKeywords(
        wakeWord: String,
        exitWord: String,
        wakeTokens: String = "",
        exitTokens: String = ""
    ): Result<Unit> {
        val cleanWake = wakeWord.trim()
        val cleanExit = exitWord.trim().ifEmpty { WakeWordDetector.DEFAULT_EXIT_KEYWORD }
        val cleanWakeTok = wakeTokens.trim()
        val cleanExitTok = exitTokens.trim()
        val result = audioEngine.updateCustomKeywords(
            cleanWake.ifBlank { null },
            cleanExit,
            cleanWakeTok.ifBlank { null },
            cleanExitTok.ifBlank { null }
        )
        if (result.isSuccess) {
            identityStore.setSelectedWakeWord(cleanWake)
            identityStore.setCustomWakeTokens(cleanWakeTok)
            identityStore.setCustomExitWord(cleanExit)
            identityStore.setCustomExitTokens(cleanExitTok)
            val debugStr = wakeWordDetector.lastError?.let { "异常：$it" }
                ?: wakeWordDetector.activeWakeLinePreview
            _uiState.update { state ->
                state.copy(
                    activeWakeWord = cleanWake,
                    activeWakeTokens = cleanWakeTok,
                    activeExitWord = cleanExit,
                    activeExitTokens = cleanExitTok,
                    wakeWordDebugInfo = debugStr,
                    subtitle = if (state.deviceState == DeviceState.IDLE) {
                        idleHint(state.handsFreeWakeEnabled, cleanWake)
                    } else {
                        state.subtitle
                    }
                )
            }
            if (_uiState.value.deviceState == DeviceState.IDLE && _uiState.value.handsFreeWakeEnabled) {
                updateNotification("锁屏待机中 · 喊「${cleanWake.ifBlank { "唤醒词" }}」开始对话")
            }
        }
        return result
    }

    fun setAecTestActive(active: Boolean) {
        if (active) {
            if (_uiState.value.deviceState != DeviceState.IDLE) {
                stopConversation()
            }
            audioEngine.stopCapture()
            audioEngine.restoreAudioMode()
        } else {
            if (_uiState.value.deviceState == DeviceState.IDLE && hasMicPermission && _uiState.value.handsFreeWakeEnabled) {
                maybeStartWakeWordListening()
            }
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
            val exitWord = _uiState.value.activeExitWord
            audioEngine.exitWordEnabled = _uiState.value.deviceState == DeviceState.SPEAKING
            audioEngine.startWakeWordDetection(
                active,
                exitWord,
                _uiState.value.activeWakeTokens.ifBlank { null },
                _uiState.value.activeExitTokens.ifBlank { null }
            )
            val debugStr = wakeWordDetector.lastError?.let { "唤醒引擎异常：$it" }
                ?: wakeWordDetector.activeWakeLinePreview
            _uiState.update { state ->
                state.copy(
                    wakeWordDebugInfo = debugStr,
                    errorMessage = wakeWordDetector.lastError?.let { "离线唤醒异常：$it" } ?: state.errorMessage
                )
            }
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
            Log.w("XiaozhiWS", "startConversation aborted: isNetworkAvailable=false")
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
            Log.w("XiaozhiWS", "startConversation: cachedWsConfig is null/blank, running bootstrapOta()")
            bootstrapOta()
            return
        }
        isUserDisconnecting = false
        ignoreNextTtsStop = false
        audioEngine.exitWordEnabled = false
        acquireWakeLock()
        acquireWifiLock()
        if (wsClient.isConnected()) {
            Log.i("XiaozhiWS", "Reusing active WebSocket connection (skipping reconnect)")
            var sendOk = true
            pendingWakeKeyword?.let { kw ->
                val serverWakeWord = if (kw in _uiState.value.availableWakeWords) kw else "你好小智"
                sendOk = wsClient.sendWakeWordDetected(serverWakeWord)
                pendingWakeKeyword = null
            }
            if (sendOk) {
                audioEngine.ensurePlaybackStarted(lastOutputSampleRate)
                playConnectedChime()
                startListeningInternal()
                return
            }
            Log.w("XiaozhiWS", "Reused WebSocket send failed, reconnecting...")
        }
        _uiState.update {
            it.copy(
                deviceState = DeviceState.CONNECTING,
                subtitle = "正在连接语音网关...",
                errorMessage = null
            )
        }
        updateNotification("正在连接小智语音网关...")
        connectTimeoutJob?.cancel()
        connectTimeoutJob = serviceScope.launch {
            delay(8000L)
            if (_uiState.value.deviceState == DeviceState.CONNECTING) {
                Log.w("XiaozhiWS", "WebSocket handshake timeout (8s)")
                wsClient.disconnect()
                _uiState.update {
                    it.copy(
                        deviceState = DeviceState.IDLE,
                        subtitle = idleHint(),
                        errorMessage = "连接语音网关超时，请稍后重试"
                    )
                }
                maybeStartWakeWordListening()
            }
        }
        wsClient.connect(identityStore.getIdentity(), ws, aecEnabled = isRealtimeMode())
    }

    private fun isRealtimeMode(): Boolean =
        _uiState.value.autoContinue && _uiState.value.aecAvailable

    private fun listeningSubtitle(): String = if (isRealtimeMode()) {
        "我在听，随时可插话...（说「拜拜 / 退下」结束）"
    } else {
        "我在听，请说话...（说「拜拜 / 退下」结束）"
    }

    private fun startListeningInternal() {
        audioEngine.exitWordEnabled = true
        _uiState.update {
            it.copy(
                deviceState = DeviceState.LISTENING,
                emotion = "listening",
                emoji = "👂",
                subtitle = listeningSubtitle(),
                errorMessage = null
            )
        }
        updateNotification("正在聆听说话...")
        val mode = when {
            !_uiState.value.autoContinue -> "manual"
            _uiState.value.aecAvailable -> "realtime"
            else -> "auto"
        }
        wsClient.sendStartListening(mode)
        audioEngine.startConversationCapture(
            _uiState.value.activeWakeWord.ifBlank { null },
            _uiState.value.activeExitWord,
            _uiState.value.activeWakeTokens.ifBlank { null },
            _uiState.value.activeExitTokens.ifBlank { null }
        )
    }

    private fun scheduleEndConversationAndCloseWs() {
        Log.i("XiaozhiWS", "LLM requested conversation end; will close WS after TTS/timeout")
        pendingEndConversationAfterTts = true
        endConversationFallbackJob?.cancel()
        endConversationFallbackJob = serviceScope.launch {
            delay(2500L)
            if (pendingEndConversationAfterTts) {
                pendingEndConversationAfterTts = false
                closeWsAndReturnToWakeStandby()
            }
        }
    }

    private fun closeWsAndReturnToWakeStandby() {
        endConversationFallbackJob?.cancel()
        pendingEndConversationAfterTts = false
        stopConversation()
        wsClient.disconnect()
        Log.i("XiaozhiWS", "WebSocket closed by end-conversation tool; waiting for wake word")
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

    private fun playConnectedChime() {
        serviceScope.launch(Dispatchers.IO) {
            val playedAsset = runCatching {
                applicationContext.assets.openFd("wakeup.mp3").use { afd ->
                    val mp = MediaPlayer()
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    mp.setOnCompletionListener { it.release() }
                    mp.setOnErrorListener { player, _, _ ->
                        player.release()
                        true
                    }
                    mp.prepare()
                    mp.start()
                }
                true
            }.getOrDefault(false)

            if (!playedAsset) {
                runCatching {
                    val tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 65)
                    tone.startTone(ToneGenerator.TONE_PROP_ACK, 120)
                    delay(250L)
                    tone.release()
                }.onFailure { err ->
                    Log.w("XiaozhiAudio", "playConnectedChime failed: ${err.message}")
                }
            }
        }
    }

    override fun onServerHello(sessionId: String, outputSampleRate: Int) {
        connectTimeoutJob?.cancel()
        lastOutputSampleRate = outputSampleRate
        audioEngine.ensurePlaybackStarted(outputSampleRate)
        playConnectedChime()
        val reminderPrompt = pendingReminderPrompt
        if (reminderPrompt != null) {
            pendingReminderPrompt = null
            wsClient.sendWakeWordDetected(reminderPrompt)
            return
        }
        pendingWakeKeyword?.let { kw ->
            val serverWakeWord = if (kw in _uiState.value.availableWakeWords) kw else "你好小智"
            wsClient.sendWakeWordDetected(serverWakeWord)
            pendingWakeKeyword = null
        }
        startListeningInternal()
    }

    override fun onSttText(text: String) {
        if (isRealtimeMode() && _uiState.value.deviceState == DeviceState.SPEAKING) {
            audioEngine.notifyTtsSpeaking(false)
            audioEngine.clearPlayback()
        }
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
        if (isUserDisconnecting || _uiState.value.deviceState == DeviceState.IDLE) {
            Log.d("XiaozhiTTS", "Ignoring stale onTtsStart while IDLE/disconnecting")
            return
        }
        if (pendingEndConversationAfterTts) {
            endConversationFallbackJob?.cancel()
        }
        audioEngine.notifyTtsSpeaking(true)
        ignoreNextTtsStop = false
        _uiState.update { it.copy(deviceState = DeviceState.SPEAKING) }
        audioEngine.exitWordEnabled = true
        if (isRealtimeMode() && hasMicPermission) {
            audioEngine.startConversationCapture(
                _uiState.value.activeWakeWord.ifBlank { null },
                _uiState.value.activeExitWord,
                _uiState.value.activeWakeTokens.ifBlank { null },
                _uiState.value.activeExitTokens.ifBlank { null }
            )
        } else if (_uiState.value.handsFreeWakeEnabled && hasMicPermission) {
            maybeStartWakeWordListening()
        } else {
            audioEngine.stopCapture()
        }
        updateNotification("小智正在回复...")
    }

    override fun onTtsStop() {
        audioEngine.notifyTtsSpeaking(false)
        if (ignoreNextTtsStop) {
            ignoreNextTtsStop = false
            return
        }
        if (pendingEndConversationAfterTts) {
            pendingEndConversationAfterTts = false
            serviceScope.launch {
                delay(400L)
                closeWsAndReturnToWakeStandby()
            }
            return
        }
        if (isReminderActive) {
            serviceScope.launch {
                delay(1200L)
                finishReminderAndResumeMusic()
            }
            return
        }
        if (phoneCallManager.hasPendingCall()) {
            executePendingCallAndIdle()
            return
        }
        if (resumeMusicAfterVolumeTts) {
            resumeMusicAfterVolumeTts = false
            musicPlayer.resume()
            startMusicAndReturnIdle()
            return
        }
        if (musicPlayer.hasDeferredStart()) {
            startMusicAndReturnIdle()
            return
        }
        if (isRealtimeMode() && hasMicPermission) {
            _uiState.update {
                it.copy(
                    deviceState = DeviceState.LISTENING,
                    emotion = "listening",
                    emoji = "👂",
                    subtitle = listeningSubtitle(),
                    errorMessage = null
                )
            }
            updateNotification("正在聆听说话...")
        } else if (_uiState.value.autoContinue) {
            startListeningInternal()
        } else {
            _uiState.update {
                it.copy(deviceState = DeviceState.IDLE, emoji = "🤖", subtitle = idleHint())
            }
            maybeStartWakeWordListening()
        }
    }

    override fun onTtsSentence(text: String) {
        Log.i("XiaozhiTTS", "onTtsSentence: $text")
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
        if (isReminderActive && status.equals("error", ignoreCase = true)) {
            pendingReminderPrompt = null
            playFallbackReminderTone()
            reminderTimeoutJob?.cancel()
            reminderTimeoutJob = serviceScope.launch {
                delay(4000L)
                runCatching { fallbackRingtone?.stop() }
                fallbackRingtone = null
                finishReminderAndResumeMusic()
            }
        }
    }

    override fun onClosed(reason: String, isError: Boolean) {
        connectTimeoutJob?.cancel()
        if (isReminderActive && isError) {
            pendingReminderPrompt = null
            playFallbackReminderTone()
            reminderTimeoutJob?.cancel()
            reminderTimeoutJob = serviceScope.launch {
                delay(4000L)
                runCatching { fallbackRingtone?.stop() }
                fallbackRingtone = null
                finishReminderAndResumeMusic()
            }
        }
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
        val showDisconnectError = !userEnded && _uiState.value.deviceState != DeviceState.IDLE && (isError || !isNetworkAvailable)
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
        reminderTimeoutJob?.cancel()
        pendingReminderPrompt = null
        isReminderActive = false
        resumeMusicAfterReminder = false
        runCatching { fallbackRingtone?.stop() }
        fallbackRingtone = null
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
        const val ACTION_TRIGGER_REMINDER = "com.codenong.xiaozhi.TRIGGER_REMINDER"
        private const val CHANNEL_ID = "xiaozhi_voice_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
