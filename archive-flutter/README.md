# archive-flutter（只读参考，不编译）

Flutter 旧实现归档：扫码配对 / 聊天流式 / 会话抽屉 / 工作区 / 审批卡的 A–D 批功能
在此冻结，最后提交 `5bc4bfa`。E/F 只有模型类。

- 主线是仓库根下的 `android-native/`（Kotlin + Jetpack Compose，只做 Android），改功能去那边。
- 这里的代码不再编译、不再跑 `flutter analyze/test`，CI 也不跑 Flutter。
- `android/keystore/` + `android/key.properties` 是签名材料，留在仓库根 `android/` 下供
  `android-native` 复用，不在此归档内。
- 服务端契约以仓库根 `docs/api-contract.md` 为准，不以这里的旧客户端代码为准。
