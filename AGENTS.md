# PocketDeck 口袋舰桥 — AGENTS.md

> PiDeck 桌面端 Web 服务的 Flutter 原生客户端：去掉 WebView，用原生 UI 全量对齐网页版
> （上游参照：PiDeck v0.8.0 的 `webApi.ts` / `WebChatApp.tsx` / `WebTimeline.tsx`）。
> 功能按 A→F 六批对齐网页版：A 聊天核心 / B 会话管理 / C 监控条 / D 工作区 / E DSH / F 全局，
> A–D 已完工，E/F 只建了模型类（见 §6 未完工）。

## 1. 目录（仓库根即 Flutter 根）

- `docs/api-contract.md`：接口契约，**唯一真相源**，改接口先改它。
- `lib/main.dart`：`PiDeckApp` + `Gate`（无配对→扫码页，有配对→抽屉壳）+ 深浅两套主题。
- `lib/pairing_store.dart`：单配对持久化（`pair_base_url` / `pair_token` / `last_session_id` / `app_dark_theme`）+ `parseBaseUrl`。
- `lib/api/models.dart`：服务端形状直译。聊天基础（`WebProject/WebSession/WebRuntime/WebContextUsage/AvailableModel/WebPendingUiRequest/WebState`）+
  A 批（`PromptItem/PromptCategory/PromptList`）+ B 批（`WebSession.createdAt/parentSessionId/forked`）+
  C 批（`SessionFileChange/SubagentEntry/SessionTodoSnapshot`）+ D 批（`GitBranchInfo/GitResource/GitResourceGroups/CommitEntry/WebFileNodeLite/FileContentResult`）+
  E/F 批模型类（`DshGoal/DshSubagent/DshSkill/DshPluginPackage/DshPlugin/DshStaticPlugin/SkillSummary/ExtensionSummary`，**暂无对应 ApiClient 接口与 UI**）。
- `lib/api/api_client.dart`：REST + SSE（`http.Request.send()` 拿字节流）+ `_runtimeCommand` 信封 + `_rawPost`（返回原始 `http.Response`，给 copy/createSession 这类要读特殊信封的用）。
- `lib/api/chat.dart`：`UiMessage/UiPart`（Text/Reasoning/Tool/File）+ `mapChatMessage` + `prettyToolValue` + `stripThinkingTags` + `splitAskOption` + `toolSummary`。
- `lib/api/chat_stream.dart`：`SseSplitter`（增量切帧）+ `StreamDraft`（帧折叠成 UiMessage，时间线分段）。
- `lib/api/branch_family.dart`：`deriveBranchFamily`（B 批，分支家族推导，与实现同测）。
- `lib/pages/shell_page.dart`：抽屉壳（左抽屉导航 + 正文聊天，进场即聊天；`state.projects` 透传给会话页和新建会话框）。
- `lib/pages/chat_page.dart`：聊天页（历史 + 流式 + 审批 + 中断 + 模型签 + 上下文圆环 + A–D 批全部功能，最大文件）。
- `lib/pages/sessions_drawer.dart`：分组会话抽屉。`tool_card.dart`：工具卡（含组卡）。`ask_card.dart`：审批卡。
- `lib/pages/workspace_drawer.dart`：D 批工作区弹窗（`WorkspaceDrawer` + `_GitPanel`/`_FilesPanel` 两个 tab）。
- `lib/pages/file_preview.dart`：D 批文件预览页（`_FileBody` 按 file/diff 双态 + `_NumberedText` 行号文本）。
- `test/`：`api/chat_map/stream/tool_card/ask_card/runtime/session_group/pairing_store/branch_family` 单测，与实现一一对应。
- 依赖：`http` + `flutter_markdown_plus`（`flutter_markdown` 已停更，别换回去）+ `shared_preferences` + `mobile_scanner` + `image_picker`（A 批图片用）；`webview_flutter` 是重写前残留，完工后删。

## 2. 服务端契约（详见 `docs/api-contract.md`）

- Base URL = 扫码得到，如 `http://<host>:8765`。`parseBaseUrl` 接受 `http(s)://host:port` 或裸 `host[:port]`，缺端口默认 **8765**，归一化成 `scheme://host:port`。非 http(s) 返回 null。
- 局域网**不发任何鉴权头**；`token` 只预留（公网才加 `Authorization: Bearer` / `?token=`）。
- 轮询：抽屉壳 `GET /api/state` **5s**；聊天页顺带 3s 取本会话待审批 + 同步会话记录的模型/档位；C 批监控条 `_ActivityStrips` 独立 5s 轮询（失败保快照）。
- 历史：`GET /api/sessions/:id/messages/page?before=&pageSize=30`，首次不传 `before`，续页传上页 `nextBefore`，null=到顶。`unavailable` 是专态（非空会话），必须进错误态。
- 发送：`POST /api/chat`，body 含**每轮唯一的 `messageId`（UUID）**，复用会被服务端去重吞掉。响应即 SSE 流（`text-start/delta/end`、`reasoning-*`、`tool-input-start/available`、`tool-output-available/error`、`error`、`finish`，`[DONE]` 收尾）。未知 type 忽略，孤儿 output 丢弃。
  图片走 `parts` 里 `type=file` 节点（`mediaType` + data URL `url`），**不是**顶层 `body.images`（服务端只读 parts，送错字段会导致图片丢失）。上限 4 张、单图 base64 ≤2MB，超限前端压缩/截断。
- 中断：先停本地订阅（保留已流出部分），再 `POST runtime/abort`（`target={sessionId,agentId,runtimeGeneration}`，失败静默）。`stop` 只断本地，不断服务端。
- 模型：运行中→ `runtime/model` + `runtime/thinking` 即时下发；**idle 会话→ `POST /api/sessions/:id/update` 写 `{model:{provider,modelId,modelName?},thinkingLevel?}`，下次运行生效**，本地签立即更新。进聊天页从会话记录同步已存模型/档位。
- 审批：`state.pendingUiRequests` 只取 `sessionId==当前会话` 的那条，作答 `POST /api/ui-response`（原样回传 `sessionId/requestId/agentId/runtimeGeneration` + `response`）。
- 上下文圆环：`runtime/state` 命令取 `WebContextUsage`，**有 runtime 且 `window>0` 才显示**，与网页 `ContextRing` 同源。
- B 批：重命名/偏好走 `POST /api/sessions/:id/update`（catalog 层，无需 runtime）；复制 `POST :id/copy` 回 `{result:{session:{id}}}`（兼容顶层 `session`），克隆 `POST :id/runtime/clone` 回 `{session:{id}}`；重启 `runtime/restart`、压缩 `runtime/compact`（可选 prompt）；导出是 `GET :id/export-html` 拿字节，下载/分享由调用方决定。
- C 批：`GET /api/sessions/:id/{file-changes,subagents,todo}`，全是只读监控快照。
- D 批：Git（`GET /api/git/status|diff|log`）+ 文件（`GET /api/files|file-content`）**全带 `projectId` 查询参数**；diff 按组内路径取原文/改后；文件内容 512KB 有界，超限/二进制走结构化标记。
- E/F（网页协议已摸清，实现时对照上游实现）：DSH 工具 `GET :id/dsh/{goal,subagents,skills}` + 全局 `GET /api/dsh/plugins` 与安装/动作 POST；权限运行中走 `runtime/permission {preset}`；技能开关 `POST /api/skills/toggle {name,sourceId,enabled}`、扩展 `POST /api/extensions/toggle {source,enabled,scope}`。

## 3. 实现铁律（都是 bug 换来的，别改）

- `StreamDraft` 必须是**时间线分段**：文本/思考/工具按到达顺序交错，同 kind 连续 delta 才合并，被打断即开新段。禁止把一轮文本合并成一段（卡片会被后到的文字顶到回复下面=浮空 bug）。
- 消息行加 `ValueKey(id)`，每个 part 加 `ValueKey(id:index)`，否则 `ExpansionTile` 展开态串位。
- assistant 正文（历史映射 + 流式）都要过 `stripThinkingTags` 剥 `<thinking>…</thinking>`；思考卡只用 reasoning 通道；用户输入和工具卡原文不动。
- 历史读失败时客户端只做降级：inline 错误 + 重试/忽略，**输入框保留**（chat 与读历史是独立通道）。
- 模型表交互：点行只选中不关表，当场展开该模型档位 chips；点档位=模型+档位一次带回；底部"确定"=只换模型（档位归零），没换模型时禁用。工具行另有独立思考档位 chip（`_pickThinking`，候选=当前模型 levels，无则回退 `kThinkingLevelFallback` 7 档=网页 `WEB_THINKING_LEVELS`，与模型解耦，单测锁死）。
- 工具卡：折叠态 compact（一行标题+`toolSummary` 摘要，读 path/command 首行，超长 80 字截断）。连续同名纯单工具消息**跨消息并组**（`bash × N`），用户消息/正文自动打断；子卡各带输入摘要和报错态。
- 审批卡：单选点选项只选中（提交灰→亮），点"提交"才发；批量单选点选项只选中、末题提交才整体发；多选/是-否-输入行为不变。**最后动哪里就提交哪里**（点选项→只发选项，点输入框/打字→只发手输），两者不拼接。版式：右下角"取消 提交"同一行。换题按 request 重建选中态。
- 思考卡标题左边必须有图标，与正文区分。
- 输入框是圆角悬浮卡：上输入（附件缩略图行在上，横滑单行不撑高，缩略图 cover 裁满）+ 双行工具区（上行左图库+提示词、右发送/中断；下行左思考档位+模型、右计划 chip；模型名超 12 字截断，计划 chip 永不被挤）+ `minLines:3` 起步高度；发送键空态禁用 + 图片压缩忙时禁用（`_send` 内双保险）；边框用 `outlineVariant` + 顶部投影，浅深色都跟主题走。
- 用户消息长按菜单：复制 / 编辑（`_EditRow` 原地改后调 `editRuntimeMessage`，耗时数秒先乐观更新）/ 重发（`prepareResend` 回填文字+图片再走正常发送）/ 删除（`deleteRuntimeMessage`）。
- 计划模式：开关在输入框，发送时 `agentMessage` 带计划指令（`_planAgentMessage`），发完自动关开关；提示词库是 `_PromptSheet`（250ms 防抖搜索 + 分类），点条目插入正文。
- 分支条 `_BranchBar`：家族 ≤1 条不渲染；i>0 的 chip 点切换会话；B 批家族推导在 `branch_family.dart`（root 向上、children 向下）。
- 监控条 `_ActivityStrips`（C 批）：文件修改（path + count）/ 子代理（状态 + 结果/报错展开）/ todo（planId + 完成态），5s 轮询失败保快照。
- 工作区（D 批）：聊天页 AppBar 文件夹按钮 → `showDialog` 弹 `WorkspaceDrawer`（Git/文件双 tab；`_ResourceGroup` 按 merge>index>workingTree>untracked 分组，`_DiffBlock` 看 diff）。`_projectId` 取数顺序：会话列表按 id 匹配 > `session.projectId` > 单项目回退；多项目且匹配不上时工作区入口隐藏。文件点开进 `FilePreviewPage`（md 渲染 vs 行号文本双态 + diff 双栏）。
- 导航（有界）：`Gate` 按有无配对分流（扫码页 / 抽屉壳），返回键 `PopScope` 先关抽屉；顶栏左 ☰ 开抽屉、右上下文圆环；抽屉 Chat 组（`builtin-chat`）置顶展开，其余按项目分组折叠，带搜索/新建/断开配对。**刻意不做**：标题中间切换器、右侧 changes/files 抽屉（D 批做成了弹窗而非抽屉）、空态 pills、收藏/最近。
- Mock 单测注意：`MockClient` 回调参数是 `http.Request`（基类 cast 会炸，`as http.Request` 别写）；`copySession` 判 `result.session`，别和 clone 的顶层 `session` 搞混；restart/compact 直接走 `_runtimeCommand` 无返回值；`exportSessionHtml` 是 GET 不是 POST。

## 4. 主题

- 浅色 = Catppuccin Latte（`main.dart:buildLightTheme`）：底 base `#EFF1F5` / 卡 mantle `#E6E9EF` / 最深 crust `#DCE0E8` / 字 text `#4C4F69` / 次字 `#5C5F77` / 气泡 surface0 `#CCD0DA` 配 text（5.2）/ 主 blue `#0B57D0` / 副 teal `#0E7377` / 错 red `#D20F39`。槽位与深色一一对应，页面代码零改动。
- 深色 = One Dark Pro（`main.dart:buildDarkTheme`）：底 `#282C34` / 深 `#21252B` / 字 `#ABB2BF` / 面板 `#2C313C` / 气泡 `#3E4451`+白字 / 蓝 `#61AFEF` / 青 `#56B6C2` / 红 `#E57A7D`。开关在抽屉底部断开配对右侧，`PairingStore` 持久化，默认浅色。
- 对比度单测锁住（`pairing_store_test.dart`）：审批卡、用户气泡两组底 vs 字必须 ≥ WCAG AA 4.5（深浅各一组，共 4 个断言）。别写死颜色，全跟主题走（用量环绿/橙/红状态色除外）。

## 5. 构建与验证（缺一不可）

- 每次改完跑 `flutter analyze`（必须干净）+ 全测试（改渲染/映射/审批必须同步加用例）。
- 打包：`flutter build apk --release`，产物 `build/app/outputs/flutter-apk/app-release.apk`。
- Release 签名：`android/key.properties` + keystore 均已 gitignore，照 `android/key.properties.example` 配好后自动签名；无签名文件时 debug key 自动兜底（见 `android/app/build.gradle.kts`）。**keystore 与密码自行备份，丢了无法覆盖安装。**
- 真机/模拟器验证：标准 Android 调试链（`install → launch → screencap 看图 → input 操控 → logcat 无 error/FATAL`）；审批卡需服务端真实弹出请求时再点。

## 6. 未完工（E/F 批，接着做）

- 现状：`models.dart` 里 `DshGoal/DshSubagent/DshSkill/DshPluginPackage/DshPlugin/DshStaticPlugin/SkillSummary/ExtensionSummary` 只有模型类；`api_client.dart` 无对应接口，`chat_page.dart` 无入口（顶栏只有工作区按钮 + 会话操作菜单）。
- 待做（协议见 §2 最后一条）：ApiClient 加 DSH/技能/扩展/权限接口 → 新建 `dsh_tools_panel.dart` / `assets_dialog.dart` / `search_dialog.dart` → 聊天页顶栏加 DSH/搜索/资产按钮 + 后端只读 pill（APP 创建即定后端，无切换写入口，只显示）。
- 上游对照：`WebDshToolsPanel.tsx`（4 tab：goals/subagents/skills/plugins）/ `WebBackendSelector.tsx` / `WebSearchDialog.tsx`（只搜已加载消息）/ `WebSkillsExtensionsDialog.tsx`（只启停，不装卸）。
