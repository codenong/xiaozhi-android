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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    arrayOf(Manifest.permission.RECORD_AUDIO)
                }
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
                onToggleAutoContinue = { viewModel.toggleAutoContinue() },
                onToggleHandsFreeWake = { viewModel.toggleHandsFreeWake() },
                onSelectWakeWord = { viewModel.selectWakeWord(it) },
                onSaveOtaUrl = { viewModel.updateOtaUrl(it) },
                onSaveUpdateUrl = { viewModel.saveUpdateUrl(it) },
                onCheckUpdate = { viewModel.checkForUpdate(silent = false) },
                onDownloadUpdate = { viewModel.downloadAndInstallUpdate() },
                onDismissUpdate = { viewModel.dismissUpdateDialog() }
            )
        }
    }
}
