package com.codenong.xiaozhi.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codenong.xiaozhi.model.DeviceState

@Composable
fun RobotFaceScreen(
    state: XiaozhiUiState,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onTapFace: () -> Unit,
    onToggleAutoContinue: () -> Unit,
    onSaveOtaUrl: (String) -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1117))
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "小智 AI · ${state.deviceState.name}",
                color = Color(0xFF8B949E),
                fontSize = 14.sp
            )
            TextButton(onClick = { showSettings = true }) {
                Text("高级设置", color = Color(0xFF58A6FF))
            }
        }

        when {
            !hasMicPermission -> {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 420.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("需要麦克风权限", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "为了和小智进行实时语音对话，请先授予录音权限。",
                            color = Color(0xFF8B949E),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = onRequestMicPermission) {
                            Text("立即授权")
                        }
                    }
                }
            }

            state.deviceState == DeviceState.ACTIVATING && state.activation != null -> {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 460.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("设备未绑定 · 一步激活", color = Color(0xFF58A6FF), fontSize = 16.sp)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = state.activation.code,
                            color = Color.White,
                            fontSize = 52.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 8.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "打开 xiaozhi.me 登录控制台 → 添加设备 → 输入上方 6 位数字。绑定后将自动进入对话。",
                            color = Color(0xFF8B949E),
                            textAlign = TextAlign.Center,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = {
                                clipboard.setText(AnnotatedString(state.activation.code))
                            }) {
                                Text("复制激活码", color = Color.White)
                            }
                            Button(onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://xiaozhi.me"))
                                context.startActivity(intent)
                            }) {
                                Text("打开 xiaozhi.me")
                            }
                        }
                    }
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clickable { onTapFace() }
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = state.emoji, fontSize = 104.sp)
                    Spacer(Modifier.height(24.dp))
                    if (state.userSpeech.isNotBlank()) {
                        Text(
                            text = "你：${state.userSpeech}",
                            color = Color(0xFF8B949E),
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        text = state.subtitle,
                        color = Color.White,
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 560.dp)
                    )
                    state.errorMessage?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text(text = err, color = Color(0xFFFF7B72), fontSize = 13.sp)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("连续对话模式", color = Color(0xFF8B949E), fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Switch(
                checked = state.autoContinue,
                onCheckedChange = { onToggleAutoContinue() },
                modifier = Modifier.padding(start = 10.dp)
            )
        }
    }

    if (showSettings) {
        var draftUrl by remember(state.otaUrl) { mutableStateOf(state.otaUrl) }
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("高级设置") },
            text = {
                Column {
                    Text("设备 MAC (自动生成): ${state.deviceId}", fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = draftUrl,
                        onValueChange = { draftUrl = it },
                        label = { Text("OTA 服务器地址") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onSaveOtaUrl(draftUrl)
                    showSettings = false
                }) {
                    Text("保存并重连")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) {
                    Text("取消")
                }
            }
        )
    }
}
