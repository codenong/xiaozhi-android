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
import com.codenong.xiaozhi.service.XiaozhiService
import com.codenong.xiaozhi.service.XiaozhiUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class XiaozhiViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(XiaozhiUiState())
    val uiState: StateFlow<XiaozhiUiState> = _uiState.asStateFlow()

    private var service: XiaozhiService? = null
    private var collectJob: Job? = null
    private var pendingMicPermission: Boolean? = null
    private var bound = false

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
    fun toggleAutoContinue() = service?.toggleAutoContinue()
    fun toggleHandsFreeWake() = service?.toggleHandsFreeWake()
    fun selectWakeWord(wakeWord: String) = service?.selectWakeWord(wakeWord)
    fun updateOtaUrl(newUrl: String) = service?.updateOtaUrl(newUrl)

    override fun onCleared() {
        super.onCleared()
        collectJob?.cancel()
        if (bound) {
            runCatching { getApplication<Application>().unbindService(connection) }
            bound = false
        }
    }
}
