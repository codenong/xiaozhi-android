# TODO

## 1. APK 内置自定义唤醒词与灵敏度调节
- [ ] **内置中文转拼音拆字（`ppinyin`）**：在 App 内置轻量级汉字 → 带声调声母/韵母映射表，用户输入中文唤醒词（如“小智小智”）后自动转换为 Sherpa-ONNX KWS 所需的 `keywords.txt` 格式，无需借助外部命令行工具 `sherpa-onnx-cli text2token`。
- [ ] **动态重载 KWS Stream**：保存自定义唤醒词后，通过 `KeywordSpotter.createStream(keywords)` 实时生效，无需重启 App。
- [ ] **灵敏度滑动条（零手动调参）**：在设置页提供分段滑动条，自动映射为底层的 `:boosting_score` 与 `#trigger_threshold`：
  - **灵敏**：高触发率，适合安静环境或远距离唤醒
  - **中等**（默认）：兼顾响应率与防误触
  - **可靠**：严格阈值，适合嘈杂环境防误唤醒
