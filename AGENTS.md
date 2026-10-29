# PocketDeck 口袋舰桥 — AGENTS.md

> PiDeck 桌面端 Web 服务的手机原生客户端：扫码配对，手机上接着聊电脑上的 AI 会话。
> 上游参照：PiDeck 桌面端（`webApi.ts` / `WebChatApp.tsx` / `WebTimeline.tsx`），版本号跟桌面端走。
> **当前主线是 `android-native/`（Kotlin + Jetpack Compose，只做 Android）**；Flutter 旧实现已归档到 `archive-flutter/`，只读参考，不编译。

## 0. 主线与版本（先看这节，别走错目录）

- 主线：`android-native/`，独立 Gradle 根（`pideck-native`，`include(":app")`），与 Flutter `android/` 无关。
- 归档：`archive-flutter/`（`lib/` + `test/` + `pubspec.yaml`，`0.9.0+2`）是 Flutter 旧实现，A–D 批＋ E/F 模型类都在那里，最后提交 `5bc4bfa`，只读参考，不编译，不跑 `flutter analyze/test`。
- 包名注意：源码目录 `com.xrbbm.pideck`，但 `namespace` / `applicationId` = `com.xrbbm.pocketdeck`（`android-native/app/build.gradle.kts`），两者不一致，改包相关先核对。
- 版本：跟桌面端 `vX`，原生后缀 `-nN`（如 `versionName 0.9.0-n1`）；Flutter 旧后缀 `-pN`（如 `v0.9.0-p2`）已作废。
- 技术栈：Kotlin `2.4.0` + AGP `9.1.0` + `compile/targetSdk 36` + `minSdk 26` + JVM17；Compose BOM `2025.10.01` + M3；
  `okhttp 5.1.0` + `okhttp-sse`、`kotlinx-serialization-json`、`coroutines`、`datastore-preferences`（配对持久化）、
  CameraX + MLKit（扫码，替代 `mobile_scanner`）、`commonmark`（Markdown 渲染）、`material-icons-core/extended`。
- 文档债（已知，不用现在修，动手别踩）：
  `docs/api-contract.md` 头部实现指向仍是 `lib/api/api_client.dart`（契约本身仍是唯一真相源，改接口先改它，路径现为 `archive-flutter/lib/api/api_client.dart`）；
  README FAQ 说草稿存手机上，实际草稿只在内存（见 §3）。

## 1. 目录（`android-native/app/src/main/java/com/xrbbm/pideck/`）

- `core/PairingParser.kt` + `core/PairingStore.kt`：扫码地址解析（缺端口默认 **8765**）+ DataStore `pideck_pairing`
 （keys：`pair_base_url` / `pair_token` / `last_session_id` / `app_dark_theme`，与旧 Flutter 同名；新增 `drawer_expanded_groups` 逗号拼接，空 = 全折叠）。
- `core/net/ApiClient.kt`：REST + SSE。默认 `connect 10s / read 15s / write 15s`，REST 包 `withTimeout(15s)`；
  `streamChat` = `POST /api/chat` + `Accept: text/event-stream`（`okhttp-sse`）；`streamSession` = `GET /api/sessions/:id/stream` 且 `readTimeout(0)` 常驻（服务端心跳稀疏，不断线）。
- `core/net/ChatStream.kt` + `ChatMap.kt`：SSE 增量切帧 + 帧折叠成 UI 消息（时间线分段，见 §3）+ 历史映射。
- `core/net/ModelsCore.kt` / `ModelsAsk.kt` / `ModelsWorkspace.kt` / `ModelsDsh.kt`：服务端形状直译（含 DSH / 技能 / 扩展 / Git / 文件 / 搜索命中模型）。
- `core/net/BranchFamily.kt` + `JsonLens.kt`：分支家族推导（root 向上、children 向下）+ JSON 容错读取。
- `ui/chat/ChatScreen.kt` + `ChatTimeline.kt` + `ChatViewModel.kt`：聊天页（历史＋流式＋审批＋中断＋模型签＋上下文＋执行卡＋输入框，最大三块）。
- `ui/chat/ToolCards.kt` + `AskCards.kt`：执行卡 L1–L3（见 §3）+ 审批卡。`Strips.kt`：文件/子代理/todo 常驻监控条。
- `ui/chat/ModelSheet.kt` + `PromptSheet.kt` + `MessageSearchSheet.kt` + `SkillsExtensionsSheet.kt` + `DshToolsSheet.kt` + `ImageAttach.kt`：
  模型档位表（档位取真实模型，见 §3）/ 提示词库（250ms 防抖搜索＋分类）/ 服务器索引搜索＋跳转 / 技能扩展开关 / DSH 工具面板（目标・子代理・技能・插件）/ 图片附件（上限 4 张，大图压到 1600 长边再传）。
- `ui/shell/ShellScreen.kt` + `SessionsDrawer.kt`：抽屉壳（`GET /api/state` **5s** 轮询）+ 分组会话抽屉（宽 **80%**，默认折叠＋持久化）。
- `ui/workspace/WorkspaceDrawer.kt` + `FilePreview.kt`：工作区 **`ModalBottomSheet`**（Git/文件双 tab）+ 文件预览（md 渲染 vs 行号文本）。
- `ui/theme/PiDeckTheme.kt` + `WebTheme.kt` + `AppIcons.kt`：旧 Catppuccin/OneDark 移植 + 网页复刻 zinc（**默认走网页色**）+ 图标集中地（Material Icons Extended，**无 emoji**；Lucide A 套已删，定 B 套 Material）。
- `ui/pair/PairScreen.kt`：扫码配对（CameraX + MLKit，支持手输地址）。
- 测试 12 个 / 64 例（`src/test/…`）：`PairingParser` / `ChatStream` / `AskCard` / `ChatPlan` / `ChatTimeline`（混合消息分组＋跟随指纹＋搜索跳转 key）/ `ImageAttach` / `SessionsDrawer` / `ThemeContrast`（WCAG AA 门）/ `Workspace` / `MarkdownBody`（表格＋行内样式）/ `ModelsDsh`，与实现一一对应。

## 2. 服务端契约（详见 `docs/api-contract.md`，它仍是唯一真相源）

- Base URL = 扫码得到，如 `http://<host>:8765`。局域网**不发任何鉴权头**；`token` 只预留（公网才加 `Authorization: Bearer` / `?token=`）。
- 轮询：抽屉壳 `GET /api/state` **5s**（同时订阅 `GET /api/events` SSE，`state` 事件到就重拉；流断了才回退 5s 轮询）；监控条双 5s 循环（失败保快照）；本会话待审批 3s；提示词搜索 250ms 防抖。
- 搜索：`GET /api/sessions/:id/search?q=` → `{result:{sessions:[{matches:[{messageId,role,snippet}]}]}}`；`{result:null}` = 索引不可用，回退本地已加载搜索。
- DSH 面板：`GET :id/dsh/{goal,skills,subagents}`、`GET :id/dsh/subagents/:child/history`、`GET /api/dsh/plugins`、`POST /api/dsh/plugins/install`、`POST /api/dsh/plugins/:id/{enable,disable,uninstall}`。空数组 = 该部署没开 DSH runtime，照实显示“没有…”。
- 历史：`GET /api/sessions/:id/messages/page?before=&pageSize=30`，`nextBefore=null` = 到顶；`unavailable` 进错误态（inline 错误＋重试/忽略，输入框保留）。
- 发送：`POST /api/chat`，body 含**每轮唯一的 `messageId`（UUID）**，复用会被服务端去重吞掉。
  图片走 `parts` 里 `type=file` 节点（`mediaType` + data URL `url`），**不是**顶层 `body.images`（送错字段图片丢失）。
- 中断：先停本地订阅（保留已流出部分），再 `POST runtime/abort`（`target={sessionId,agentId,runtimeGeneration}`，失败静默）。
- 模型：运行中 → `runtime/model` + `runtime/thinking` 即时下发；**idle 会话 → `POST /api/sessions/:id/update` 写模型档位，下次运行生效**，本地签立即更新。
- 审批：`state.pendingUiRequests` 只取 `sessionId==当前会话` 的那条，`POST /api/ui-response` 原样回传身份字段＋`response`。
- 上下文圆环：`runtime/state` 取 `WebContextUsage`，有 runtime 且 `window>0` 才显示；百分比可点开用量 sheet。
- 会话操作：重命名/偏好走 `update`（catalog 层，无需 runtime）；复制 `copy` 回 `{result:{session:{id}}}`；开分支 `runtime/clone` 回 `{session:{id}}`；
  重启 `runtime/restart`、压缩 `runtime/compact`（可选 prompt）；导出 `GET :id/export-html` 拿字节。
- 监控：`GET :id/{file-changes,subagents,todo}` 只读快照。Git（`status|diff|log`）＋文件（`files|file-content`）**全带 `projectId`**；文件内容 512KB 有界。
- 远端跟随（解决“显示空闲但实际在跑 / 运行中内容不同步”）：`busy && !streaming` 时订阅 `GET :id/stream`；
  本地发送先关外部流防双写，settle 触发刷新；顶栏状态跟随 runtime/streaming。

## 3. 实现铁律（都是 bug 换来的，别改）

- 时间线分段：文本/思考/工具按到达顺序交错，同 kind 连续 delta 才合并，被打断即开新段。禁止并成一段（后到的字会把卡片顶浮空）。
- 混合消息先归一化再分组（`ChatTimeline.kt`：`normalizeTimelineParts`）：历史映射（thinking+text 同条）和流式（text/tool/text 同条）都会产出混合消息，`isExecutionMessage` 的 `parts.all` 会把它判 Single 导致 tool/reasoning 漏到 `MessageRow` 裸渲染（思考卡裸奔、流式调用变一级卡）。修法只动分组层——按 part 拆成纯单 part 消息（id 后缀 `#index`）再进原有折叠逻辑；渲染层和 ViewModel 不动。
- 执行卡三级（网页 1:1，`ChatTimeline.kt`）：L1 头部计数行（可整体收纳）→ L2 具体工具/思考行 → L3 行内输入/思考正文，**无嵌套卡片**。
  连续 tool/reasoning 折进同一 ExecutionCard；**同类型工具调用不再跨消息并组**（旧 `bash × N` 已删，`0a43f4a`）。
- Compose 展开态必须按身份 key：`remember(callId)` / `remember(text.hashCode())`（`ChatTimeline.kt:395,439`），否则复用串位（等价旧 `ValueKey` 规则）。
- 搜索跳转按 **history key** 比：页面消息 id 是 `{sessionId}-history-{8hex}`（归一化后再加 `#index`），而 `/search` 命中只给 `{8hex}`。`ChatTimeline.kt` 的 `historyKey()` 先 `substringBefore('#')` 再 `substringAfterLast("-history-")`，两边都过它；直接比整串会让所有命中都显示“未加载，无法跳转”。
- assistant 正文（历史＋流式）都过 `stripThinkingTags` 剥 `<thinking>…</thinking>`；思考只走 reasoning 通道；用户输入和工具原文不动。
- 审批卡：单选点选项只选中（提交灰→亮），点“提交”才发；批量末题统一提交。**最后动哪里就提交哪里**（点选项只发选项，打字只发手输，不拼接）。换题按 request 重建选中态。
- 思考档位取**实际所选模型的 `levels`**，空才回退网页 `WEB_THINKING_LEVELS`（`ModelSheet.kt:45,206`）；地球仪图标已换成星星形。
- 图标：全站无 emoji，集中 `AppIcons.kt`（Material Icons Extended，commit 自称 B 套 Symbols，实为 Material Icons 代码调用，无字体依赖，别去找 Symbols 包）。
- 工作区是 `ModalBottomSheet`（`WorkspaceDrawer.kt:84`），不是 dialog；TabRow 对齐＋`NoActionBar` 主题已修正。
- 抽屉：宽 80%（身后聊天可见）；分组**默认折叠**＋ DataStore 持久化，退出重进保持展开态；行内 `+` 锁定本组建会话，新项目置顶；**无“安装 APP”菜单项**（`d035eb1` 删了，别加回来）。
- 草稿是**内存态**（`ChatViewModel.kt:547 DraftBox`，`sessionId→(text,images)`，切会话/dispose 存、发送清）：进程被杀即丢，别承诺磁盘留存（README FAQ 待改）。
- 输入框：模型名单开一行在上、动作为下；agent 切换＋思考档位下行靠左。小起步、随内容长到 **1/2 封顶**再内部滚动；
  回到底部箭头收进输入框跟发送键并排、不悬浮压 chip，只有不在底部才出现。
- 顶栏：40dp 紧凑贴顶、布局顶部对齐；左开抽屉、右上下文圆环；状态跟随 runtime/streaming。
- 文件 chips 横向排（`c95729f`）；pinned 监控条与父卡去重（`cd4dfc6`）。配色默认网页复刻 zinc（`WebTheme.kt` 来自 `web-D16H8S27.css`），旧 Latte/OneDark 已让位。
- 单测对照：`PairingParserTest` 锁 `parseBaseUrl`（非 http(s) 返回 null）；`ThemeContrastTest` 锁 WCAG AA 4.5；别写死颜色，全跟主题走。

## 4. 构建与验证（缺一不可）

- 改完在 `android-native/` 下跑 `./gradlew :app:testDebugUnitTest`（56 个全过）+ 相关单测同步更新；`archive-flutter/` 只读参考，不跑 `flutter analyze/test`。
- 打包：`./gradlew :app:assembleRelease`，产物 `android-native/app/build/outputs/apk/release/app-release.apk`
  （release 约 36M，正式签名 `CN=XRBBM`；签名用根 `android/key.properties` + `android/keystore/*.jks`（Flutter 归档后仅剩的签名材料），无则 debug 兜底，`minify/shrink=false`）。
  **keystore 与密码自行备份，丢了无法覆盖安装。**（`android-native/build/` 未跟踪，注意别把构建产物带进提交。）
- 真机验证：MuMu12 Nx（`adb connect 127.0.0.1:16384`）：`install → launch → screencap 看图 → input 操控 → logcat 无 error/FATAL`；
  内存参考 PSS 约 140M；审批卡需服务端真实弹出请求时再点。
- GitHub：`https://github.com/xrbbm/PocketDeck.git`（公开库，PocketDeck 口袋舰桥，MIT）。
  发版说明不写云打包细节；push 曾因 `refs/pi-checkpoints/*` 把包撑大到推不动，清法：`gc` 瘦身后只推 `main`（checkpoint refs 不推）。
- README 按用户定稿结构维护（核心亮点 / 功能总览 / 工作原理 / 下载安装 / 快速开始 / FAQ / 开发指南），别写 AI 腔；版本号只跟桌面端 `vX`，以 `versionName` 为准。

## 5. 接着做（对照网页版查漏，以契约为准）

- 原生已落地：扫码配对、聊天流式、审批、监控三条、Git/文件工作区、搜索/技能/扩展 sheets、主题切换、远端跟随。
- 缺口判定方法：打开 `http://<host>:8765/` 网页版逐项点，凡网页有、APP 无或行为不一致的，记一条再动手；别凭印象加功能。
- 别做：iOS / 双端复用（只做 Android）、`lib/` Flutter 旧代码任何改动、安装/卸载技能与扩展（只做启停，网页亦如此）。
