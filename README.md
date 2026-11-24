# xiaozhi-android

基于 [78/xiaozhi-esp32](https://github.com/78/xiaozhi-esp32) 协议实现的极简原生 Android (Kotlin + Jetpack Compose) 客户端，让闲置手机/平板一键变成桌面小智 AI 语音机器人。

## 核心特性
- **零配置开箱即用**：首次启动自动生成并持久化 `Client-Id` (UUID v4) 与虚拟 `Device-Id` (MAC)，默认直连官方 OTA (`https://api.tenclass.net/xiaozhi/ota/`)。
- **无感激活引导**：未绑定设备时自动全屏居中展示 6 位激活码与 `xiaozhi.me` 跳转入口，后台每 3 秒自动轮询 `/activate`，绑定后自动进入对话界面。
- **本地离线免手触唤醒（Sherpa-ONNX KWS）**：待机与播报期间支持本地离线唤醒词检测（默认“你好小智”、“小智同学”），**支持通过修改 `keywords.txt` 零训练自定义唤醒词**。
- **WebSocket + Opus 音频流**：16kHz 单声道 60ms Opus 编码上行，支持 16kHz/24kHz 下行解码播放；启用 `VOICE_COMMUNICATION` + 系统 `AcousticEchoCanceler` / `NoiseSuppressor`。
- **设备端 MCP Server**：支持 `2024-11-05` 与 `2026-07-28` 协议，内置 `self.get_device_status`、`self.audio_speaker.set_volume`、`self.screen.set_brightness`。

## 自定义唤醒词
修改 `app/src/main/assets/kws/keywords.txt` 即可添加任意中英文唤醒词，无需重新训练模型：
```text
n ǐ h ǎo x iǎo zh ì :1.5 #0.25 @你好小智
x iǎo zh ì t óng x ué :1.5 #0.25 @小智同学
```
可使用 `sherpa-onnx-cli text2token --tokens tokens.txt --tokens-type ppinyin` 将中文词语自动转为带声调声母/韵母格式。

## 推送到 GitHub 并自动编译
仓库已配置 `.github/workflows/android-build.yml`，推送代码后 GitHub Actions 会自动下载 Sherpa-ONNX AAR 与唤醒词模型并输出 `app-debug.apk`：
```

## License
This project is licensed under the GNU General Public License v3.0.
See the [LICENSE](./LICENSE) file for details.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
