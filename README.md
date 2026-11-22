# xiaozhi-android

基于 [78/xiaozhi-esp32](https://github.com/78/xiaozhi-esp32) 协议实现的极简原生 Android (Kotlin + Jetpack Compose) 客户端，让闲置手机/平板一键变成桌面小智 AI 语音机器人。

## 核心特性
- **零配置开箱即用**：首次启动自动生成并持久化 `Client-Id` (UUID v4) 与虚拟 `Device-Id` (MAC)，默认直连官方 OTA (`https://api.tenclass.net/xiaozhi/ota/`)。
- **无感激活引导**：未绑定设备时自动全屏居中展示 6 位激活码与 `xiaozhi.me` 跳转入口，后台每 3 秒自动轮询 `/activate`，绑定后自动进入对话界面。
- **WebSocket + Opus 音频流**：16kHz 单声道 60ms Opus 编码上行，支持 16kHz/24kHz 下行解码播放；启用 `VOICE_COMMUNICATION` + 系统 `AcousticEchoCanceler` / `NoiseSuppressor`。
- **设备端 MCP Server**：支持 `2024-11-05` 与 `2026-07-28` 协议，内置 `self.get_device_status`、`self.audio_speaker.set_volume`、`self.screen.set_brightness`。
- **桌面机器人 UI**：支持屏幕常亮、动态表情状态（待机/聆听/说话/思考）与实时双语字幕。

## 推送到你的 GitHub 仓库
```bash
unzip xiaozhi-android-mvp.zip -d xiaozhi-android
cd xiaozhi-android
git init -b main
git add .
git commit -m "feat: initial xiaozhi-android MVP"
git remote add origin https://github.com/codenong/xiaozhi-android.git
git push -u origin main
```

## 编译运行
使用 Android Studio Ladybug (或更新版本) 打开项目，等待 Gradle Sync 完成后连接真机或平板运行即可。
