# PocketDeck 口袋舰桥

PiDeck 桌面端 Web 服务的 Flutter 原生客户端：去掉 WebView，用原生 UI 全量对齐网页版。
扫码配对，进场即聊。

> Upstream reference: [ayuayue/PiDeck](https://github.com/ayuayue/PiDeck) v0.8.0
> （`webApi.ts` / `WebChatApp.tsx` / `WebTimeline.tsx`）

## 功能

- **聊天核心**：历史分页 + SSE 流式（文本/思考/工具交错时间线分段）+ 中断 + 模型切换 + 上下文用量圆环
- **会话管理**：分组抽屉、重命名、复制/克隆、重启、压缩、导出 HTML、分支家族切换
- **监控条**：文件修改 / 子代理 / todo 快照，5s 轮询
- **工作区**：Git（status/diff/log）+ 文件浏览/预览双 tab 弹窗
- **审批卡**：单选/批量单选/多选/输入，点选项只选中、点提交才发
- **深浅主题**：浅色 Catppuccin Latte / 深色 One Dark Pro，对比度锁 WCAG AA 4.5

## 快速开始

```bash
flutter pub get
flutter run
```

配对：在 PiDeck 桌面端打开配对二维码，APP 内扫码即可（局域网 Base URL 如
`http://192.168.1.100:8765`，缺端口默认 8765）。

## 接口契约

见 [`docs/api-contract.md`](docs/api-contract.md)（唯一真相源，改接口先改它）。

## 构建与验证

```bash
flutter analyze        # 必须干净
flutter test           # 全量测试
flutter build apk --release
```

产物：`build/app/outputs/flutter-apk/app-release.apk`。

Release 签名：`android/key.properties` + keystore 均已 gitignore，
照 `android/key.properties.example` 配好后 `flutter build apk --release` 自动签名；
无签名文件时 debug key 自动兜底（见 `android/app/build.gradle.kts`）。

## 发版（GitHub Actions 云打包）

打 tag 即发版，Actions 自动跑 analyze + test + 打包，APK 挂到 GitHub Release 下：

```bash
git tag v1.0.0
git push origin v1.0.0     # 走 SSH，HTTPS 写通道不通
```

正式签名（与本地包覆盖安装兼容）需先在仓库 Settings → Secrets 配四个值：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | keystore 转 base64（命令见下） |
| `KEY_ALIAS` | 如 `pideck` |
| `KEY_PASSWORD` | key 口令 |
| `STORE_PASSWORD` | store 口令 |

```bash
# 本机把 keystore 转 base64（换成你的实际路径），输出贴进 KEYSTORE_BASE64
certutil -encode android/keystore/pideck-release.jks %TEMP%\ks.b64 && type %TEMP%\ks.b64
```

缺 Secrets 时 Actions 降级打出 debug 签名包：能装，但与正式版签名不一致、
不可覆盖安装（需卸载重装）。

## License

MIT，见 [LICENSE](LICENSE)。
