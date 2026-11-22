package com.codenong.xiaozhi.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.codenong.xiaozhi.model.DeviceState
import com.codenong.xiaozhi.service.XiaozhiUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RobotFaceScreen(
    state: XiaozhiUiState,
    updateState: AppUpdateUiState,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onTapFace: () -> Unit,
    onEndConversation: () -> Unit,
    onToggleAutoContinue: () -> Unit,
    onToggleHandsFreeWake: () -> Unit,
    onSelectWakeWord: (String) -> Unit,
    onSaveOtaUrl: (String) -> Unit,
    onSaveUpdateUrl: (String) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onDismissUpdate: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun checkBatteryIgnored(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    var isBatteryIgnored by remember { mutableStateOf(checkBatteryIgnored()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isBatteryIgnored = checkBatteryIgnored()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val openBatterySettings = {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    val activeWakeLabel = if (state.handsFreeWakeEnabled) {
        state.activeWakeWord.ifBlank { "全部" }
    } else {
        "已关闭"
    }

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
                text = "小智 AI · ${state.deviceState.name} · 唤醒词: $activeWakeLabel",
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
                            "为了和小智进行实时语音对话及离线语音唤醒，请先授予录音权限。",
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
                        Spacer(Modifier.height(20.dp))
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

                        Spacer(Modifier.height(20.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2630)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isBatteryIgnored) "✓ 锁屏保活已开启" else "锁屏保活未开启",
                                        color = if (isBatteryIgnored) Color(0xFF3FB950) else Color(0xFFF0883E),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "关闭电池优化后，锁屏/熄屏也能继续唤醒对话",
                                        color = Color(0xFF8B949E),
                                        fontSize = 12.sp
                                    )
                                }
                                if (!isBatteryIgnored) {
                                    Spacer(Modifier.width(12.dp))
                                    OutlinedButton(onClick = { openBatterySettings() }) {
                                        Text("去开启", color = Color(0xFF58A6FF), fontSize = 13.sp)
                                    }
                                }
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
                    if (state.deviceState == DeviceState.CONNECTING ||
                        state.deviceState == DeviceState.LISTENING ||
                        state.deviceState == DeviceState.SPEAKING
                    ) {
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = onEndConversation,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFDA3633),
                                contentColor = Color.White
                            )
                        ) {
                            Text("结束对话")
                        }
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
            Text("免手触唤醒", color = Color(0xFF8B949E), fontSize = 14.sp)
            Switch(
                checked = state.handsFreeWakeEnabled,
                onCheckedChange = { onToggleHandsFreeWake() },
                modifier = Modifier.padding(start = 8.dp)
            )
            Spacer(Modifier.width(24.dp))
            Text("连续对话", color = Color(0xFF8B949E), fontSize = 14.sp)
            Switch(
                checked = state.autoContinue,
                onCheckedChange = { onToggleAutoContinue() },
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }

    if (showSettings) {
        var draftOtaUrl by remember(state.otaUrl) { mutableStateOf(state.otaUrl) }
        var draftUpdateUrl by remember(updateState.updateUrl) { mutableStateOf(updateState.updateUrl) }
        var draftWakeWord by remember(state.activeWakeWord) { mutableStateOf(state.activeWakeWord) }

        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("高级设置 (v${updateState.currentVersionName})") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("设备 MAC (自动生成): ${state.deviceId}", fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = draftOtaUrl,
                        onValueChange = { draftOtaUrl = it },
                        label = { Text("OTA 服务器地址") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = draftUpdateUrl,
                        onValueChange = { draftUpdateUrl = it },
                        label = { Text("更新地址 (version.json)") },
                        placeholder = { Text("https://xxx.pages.dev/version.json") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            enabled = !updateState.isChecking,
                            onClick = {
                                onSaveUpdateUrl(draftUpdateUrl)
                                onCheckUpdate()
                            }
                        ) {
                            Text(if (updateState.isChecking) "检查中..." else "检查更新")
                        }
                        OutlinedButton(onClick = { openBatterySettings() }) {
                            Text(if (isBatteryIgnored) "✓ 锁屏保活" else "开启锁屏保活")
                        }
                    }
                    updateState.statusMessage?.let { msg ->
                        Spacer(Modifier.height(6.dp))
                        Text(msg, fontSize = 12.sp, color = Color(0xFF58A6FF))
                    }
                    if (state.availableWakeWords.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("选择唤醒词（来自 keywords.txt）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            state.availableWakeWords.forEach { word ->
                                FilterChip(
                                    selected = draftWakeWord == word,
                                    onClick = { draftWakeWord = word },
                                    label = { Text(word) }
                                )
                            }
                            FilterChip(
                                selected = draftWakeWord.isEmpty(),
                                onClick = { draftWakeWord = "" },
                                label = { Text("全部") }
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2630)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("关于", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Spacer(Modifier.height(4.dp))
                            Text("当前版本：v${updateState.currentVersionName}", fontSize = 12.sp, color = Color(0xFF8B949E))
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "联系邮箱：codenong@gmail.com",
                                fontSize = 12.sp,
                                color = Color(0xFF58A6FF),
                                modifier = Modifier.clickable {
                                    clipboard.setText(AnnotatedString("codenong@gmail.com"))
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:codenong@gmail.com")))
                                    }
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (draftWakeWord != state.activeWakeWord) {
                        onSelectWakeWord(draftWakeWord)
                    }
                    if (draftUpdateUrl.trim() != updateState.updateUrl) {
                        onSaveUpdateUrl(draftUpdateUrl)
                    }
                    if (draftOtaUrl.trim() != state.otaUrl) {
                        onSaveOtaUrl(draftOtaUrl)
                    }
                    showSettings = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) {
                    Text("取消")
                }
            }
        )
    }

    updateState.availableUpdate?.let { remote ->
        AlertDialog(
            onDismissRequest = {
                if (updateState.downloadProgress == null) onDismissUpdate()
            },
            title = { Text("发现新版本 v${remote.versionName}") },
            text = {
                Column {
                    if (remote.changelog.isNotBlank()) {
                        Text(remote.changelog, fontSize = 14.sp)
                        Spacer(Modifier.height(12.dp))
                    }
                    updateState.downloadProgress?.let { progress ->
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("正在下载更新：$progress%", fontSize = 12.sp, color = Color.Gray)
                    }
                    updateState.statusMessage?.let { msg ->
                        Spacer(Modifier.height(6.dp))
                        Text(msg, fontSize = 12.sp, color = Color(0xFFFF7B72))
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = updateState.downloadProgress == null,
                    onClick = onDownloadUpdate
                ) {
                    Text(if (updateState.downloadProgress != null) "下载中..." else "立即更新")
                }
            },
            dismissButton = {
                if (updateState.downloadProgress == null) {
                    TextButton(onClick = onDismissUpdate) {
                        Text("稍后")
                    }
                }
            }
        )
    }
}
