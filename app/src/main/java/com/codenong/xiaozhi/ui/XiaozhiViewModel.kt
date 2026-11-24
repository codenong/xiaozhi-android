package com.codenong.xiaozhi.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codenong.xiaozhi.data.DeviceIdentityStore
import com.codenong.xiaozhi.service.XiaozhiService
import com.codenong.xiaozhi.service.XiaozhiUiState
import com.codenong.xiaozhi.update.AppUpdater
import com.codenong.xiaozhi.update.RemoteAppVersion
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUpdateUiState(
    val currentVersionName: String = "1.0.0",
    val updateUrl: String = "",
    val guideUrl: String = "",
    val showGuide: Boolean = false,
    val availableUpdate: RemoteAppVersion? = null,
    val isChecking: Boolean = false,
    val downloadProgress: Int? = null,
    val statusMessage: String? = null
)

class XiaozhiViewModel(application: Application) : AndroidViewModel(application) {

    private val identityStore = DeviceIdentityStore(application)
    private val appUpdater = AppUpdater(application)

    private val _uiState = MutableStateFlow(XiaozhiUiState())
    val uiState: StateFlow<XiaozhiUiState> = _uiState.asStateFlow()

    private val _updateState = MutableStateFlow(
        AppUpdateUiState(
            currentVersionName = appUpdater.getCurrentVersionName(),
            updateUrl = identityStore.getUpdateCheckUrl(),
            guideUrl = identityStore.getGuideUrl(),
            showGuide = !identityStore.hasSeenGuide()
        )
    )
    val updateState: StateFlow<AppUpdateUiState> = _updateState.asStateFlow()

    private var service: XiaozhiService? = null
    private var collectJob: Job? = null
    private var pendingMicPermission: Boolean? = null
    private var bound = false

    init {
        if (_updateState.value.updateUrl.isNotBlank()) {
            checkForUpdate(silent = true)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as XiaozhiService.LocalBinder).getService()
            service = svc
            pendingMicPermission?.let { svc.onMicPermissionChanged(it) }
            collectJob?.cancel()
            collectJob = viewModelScope.launch {
                svc.uiState.collect { _uiState.value = it }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            collectJob?.cancel()
        }
    }

    fun startAndBindService() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, XiaozhiService::class.java)
        ContextCompat.startForegroundService(ctx, intent)
        if (!bound) {
            bound = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    fun onMicPermissionChanged(granted: Boolean) {
        pendingMicPermission = granted
        service?.onMicPermissionChanged(granted)
        if (granted && service == null) {
            startAndBindService()
        }
    }

    fun onFaceTapped() = service?.onFaceTapped()
    fun endConversation() = service?.endConversation()
    fun stopMusic() = service?.stopMusic()
    fun toggleMusicPlayback() = service?.toggleMusicPlayback()

    fun shutdownApp() {
        service?.shutdownService()
        val ctx = getApplication<Application>()
        if (bound) {
            runCatching { ctx.unbindService(connection) }
            bound = false
        }
        runCatching { ctx.stopService(Intent(ctx, XiaozhiService::class.java)) }
        service = null
    }
    fun toggleAutoContinue() = service?.toggleAutoContinue()
    fun toggleHandsFreeWake() = service?.toggleHandsFreeWake()
    fun selectWakeWord(wakeWord: String) = service?.selectWakeWord(wakeWord)
    fun updateOtaUrl(newUrl: String) = service?.updateOtaUrl(newUrl)

    fun saveUpdateUrl(url: String) {
        identityStore.setUpdateCheckUrl(url)
        _updateState.update { it.copy(updateUrl = url.trim(), statusMessage = null) }
    }

    fun saveGuideUrl(url: String) {
        identityStore.setGuideUrl(url)
        _updateState.update { it.copy(guideUrl = url.trim()) }
    }

    fun resolveGuideUrl(): String {
        val explicit = _updateState.value.guideUrl.trim()
        if (explicit.isNotEmpty()) return explicit
        val update = _updateState.value.updateUrl.trim()
        if (update.startsWith("http://") || update.startsWith("https://")) {
            val idx = update.lastIndexOf('/')
            if (idx > "https://".length) {
                return update.substring(0, idx + 1) + "guide.html"
            }
        }
        return LOCAL_GUIDE_URL
    }

    fun openGuide() {
        _updateState.update { it.copy(showGuide = true) }
    }

    fun dismissGuide() {
        identityStore.setGuideSeen(true)
        _updateState.update { it.copy(showGuide = false) }
    }

    fun checkForUpdate(silent: Boolean = false) {
        val url = _updateState.value.updateUrl
        if (url.isBlank()) {
            if (!silent) {
                _updateState.update { it.copy(statusMessage = "请先填写 version.json 地址") }
            }
            return
        }
        viewModelScope.launch {
            _updateState.update { it.copy(isChecking = true, statusMessage = null) }
            appUpdater.checkForUpdate(url)
                .onSuccess { remote ->
                    _updateState.update {
                        it.copy(
                            isChecking = false,
                            availableUpdate = remote,
                            statusMessage = if (remote == null && !silent) "当前已是最新版本" else null
                        )
                    }
                }
                .onFailure { err ->
                    _updateState.update {
                        it.copy(
                            isChecking = false,
                            statusMessage = if (silent) null else "检查更新失败: ${err.message}"
                        )
                    }
                }
        }
    }

    fun downloadAndInstallUpdate() {
        val update = _updateState.value.availableUpdate ?: return
        viewModelScope.launch {
            _updateState.update { it.copy(downloadProgress = 0, statusMessage = null) }
            appUpdater.downloadApk(update.apkUrl) { progress ->
                _updateState.update { it.copy(downloadProgress = progress) }
            }.onSuccess { file ->
                _updateState.update { it.copy(downloadProgress = null, availableUpdate = null) }
                appUpdater.installApk(file)
            }.onFailure { err ->
                _updateState.update {
                    it.copy(downloadProgress = null, statusMessage = err.message ?: "下载失败")
                }
            }
        }
    }

    fun dismissUpdateDialog() {
        _updateState.update { it.copy(availableUpdate = null, downloadProgress = null) }
    }

    override fun onCleared() {
        super.onCleared()
        collectJob?.cancel()
        if (bound) {
            runCatching { getApplication<Application>().unbindService(connection) }
            bound = false
        }
    }

    companion object {
        const val LOCAL_GUIDE_URL = "file:///android_asset/guide.html"
    }
}
