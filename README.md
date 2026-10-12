# PocketDeck 口袋舰桥

PiDeck 桌面端的手机客户端。把桌面上聊到一半的 AI 会话装进口袋：在同一个 Wi-Fi 下扫个码，打开就是上次的会话，接着聊。

桌面端（上游）：[ayuayue/PiDeck](https://github.com/ayuayue/PiDeck)，对照它的网页版照着做的。

## 能做什么

- 聊天：看历史、发消息、流式回复，思考过程和工具调用都展开给你看
- 会话：分组列表，重命名、复制、克隆分支、重启、压缩、导出都在手机上能点
- 审批：桌面端弹确认框（比如"继续吗"）时，手机上直接选
- 工作区：顺手看一眼 Git 改了啥、项目里有哪些文件
- 两套主题：浅色 / 深色，抽屉底下切换

## 上手

1. 电脑上打开 PiDeck 的配对二维码
2. 手机 APP 扫码（同一 Wi-Fi 下就行）
3. 进场就是聊天，不用再找会话

```bash
flutter pub get
flutter run
```

## 自己打包

```bash
flutter build apk --release
```

包在 `build/app/outputs/flutter-apk/app-release.apk`。

想打正式签名就按 `android/key.properties.example` 配一下，不配也能打（能装，但和正式包互相覆盖不了）。

## 跟桌面端怎么对接

看 [`docs/api-contract.md`](docs/api-contract.md)。

## License

MIT
