package com.codenong.xiaozhi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            val state by viewModel.uiState.collectAsState()
            var hasMicPermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }

            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                hasMicPermission = granted
            }

            LaunchedEffect(Unit) {
                if (!hasMicPermission) {
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
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
                hasMicPermission = hasMicPermission,
                onRequestMicPermission = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
                onTapFace = { viewModel.onFaceTapped() },
                onToggleAutoContinue = { viewModel.toggleAutoContinue() },
                onSaveOtaUrl = { viewModel.updateOtaUrl(it) }
            )
        }
    }
}
