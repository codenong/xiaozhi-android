package com.codenong.xiaozhi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.codenong.xiaozhi.ui.RobotFaceScreen
import com.codenong.xiaozhi.ui.XiaozhiViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: XiaozhiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by viewModel.uiState.collectAsState()
            val updateState by viewModel.updateState.collectAsState()
            var hasMicPermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }

            val permissionsToRequest = remember {
                val base = mutableListOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_PHONE_STATE
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    base += Manifest.permission.POST_NOTIFICATIONS
                }
                base.toTypedArray()
            }

            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                val micGranted = results[Manifest.permission.RECORD_AUDIO] ?: hasMicPermission
                hasMicPermission = micGranted
                viewModel.onMicPermissionChanged(micGranted)
            }

            LaunchedEffect(hasMicPermission) {
                if (hasMicPermission) {
                    viewModel.startAndBindService()
                    viewModel.onMicPermissionChanged(true)
                } else {
                    launcher.launch(permissionsToRequest)
                }
            }

            LaunchedEffect(state.brightnessPercent) {
                val pct = state.brightnessPercent ?: return@LaunchedEffect
                val attrs = window.attributes
                attrs.screenBrightness = (pct / 100f).coerceIn(0.05f, 1.0f)
                window.attributes = attrs
            }

            RobotFaceScreen(
                state = state,
                updateState = updateState,
                hasMicPermission = hasMicPermission,
                onRequestMicPermission = { launcher.launch(permissionsToRequest) },
                onTapFace = { viewModel.onFaceTapped() },
                onEndConversation = { viewModel.endConversation() },
                onToggleMusicPlayback = { viewModel.toggleMusicPlayback() },
                onDismissReminder = { viewModel.dismissReminder() },
                onExitApp = {
                    viewModel.shutdownApp()
                    finishAndRemoveTask()
                },
                onToggleAutoContinue = { viewModel.toggleAutoContinue() },
                onToggleHandsFreeWake = { viewModel.toggleHandsFreeWake() },
                onTogglePowerSaveMode = { viewModel.togglePowerSaveMode() },
                onSelectWakeWord = { viewModel.selectWakeWord(it) },
                onUpdatePromptKeywords = { wake, exit -> viewModel.updatePromptKeywords(wake, exit) },
                onSaveOtaUrl = { viewModel.updateOtaUrl(it) },
                onSaveUpdateUrl = { viewModel.saveUpdateUrl(it) },
                onSaveGuideUrl = { viewModel.saveGuideUrl(it) },
                resolvedGuideUrl = viewModel.resolveGuideUrl(),
                onOpenGuide = { viewModel.openGuide() },
                onDismissGuide = { viewModel.dismissGuide() },
                onCheckUpdate = { viewModel.checkForUpdate(silent = false) },
                onDownloadUpdate = { viewModel.downloadAndInstallUpdate() },
                onDismissUpdate = { viewModel.dismissUpdateDialog() }
            )
        }
    }
}
