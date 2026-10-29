# PocketDeck API 契约（PiDeck 桌面端 Web 服务）

> 唯一真相源：改接口先改本文档。实现见 `android-native/app/src/main/java/com/xrbbm/pideck/core/net/ApiClient.kt`。
> 上游参照：PiDeck 桌面端网页实现（<https://github.com/ayuayue/PiDeck>，`/assets/web-*.js` 里的 `apiFetch`/`getJson`）。

Base URL = 扫码配对得到的地址，如 `http://<host>:8765`（缺端口默认 8765，路径均为相对路径）。

## 鉴权

局域网绑定不校验 token，无需任何请求头。
公网/带 token 的服务端：所有请求加 `Authorization: Bearer <token>`，
SSE 的 GET 允许 `?token=` 查询参数（`Pairing.token` 字段预留）。

## 端点

| 方法 | 路径 | 请求体 | 返回 | 用途 |
|---|---|---|---|---|
| GET | /api/health | — | `{ok:true}` | 存活检查 |
| GET | /api/state | — | `WebState` | 项目/会话/运行态/待审批轮询 |
| GET | /api/models | `?force=1` 可绕缓存 | `{models: AvailableModel[]}` | 模型选择器 |
| POST | /api/projects | `{path}` | `{project}` | 注册本地项目 |
| POST | /api/sessions | `{projectId, ...preferences}` | `{session:{id}}` | 新建会话 |
| POST | /api/sessions/:id/update | `{title,...}` | — | 重命名/改偏好；idle 会话的模型档位经此写 `{model:{provider,modelId,modelName?},thinkingLevel?}`，下次运行生效 |
| POST | /api/sessions/:id/delete | — | — | 删除会话 |
| POST | /api/sessions/:id/copy | — | `{result:{session:{id}}}` | 复制会话 |
| GET | /api/sessions/:id/messages/page | `?before=&pageSize=` | `SessionMessagePage` | 历史分页（`before` 为上页 `nextBefore`，null=到顶；`unavailable` 为专用错误态） |
| POST | /api/chat | 见下 | SSE 流（同响应） | 发消息 |
| GET | /api/sessions/:id/stream | — | SSE 流 | 独立订阅（远程运行时跟随：忙时订阅、落定/15s刷历史；本地发送时关闭避双写） |
| POST | /api/ui-response | `{sessionId,requestId,agentId,runtimeGeneration,response}` | — | 审批问答应答（只取 `sessionId == 当前会话` 的请求） |
| POST | /api/sessions/:id/runtime/stop | `{target}` | — | 优雅停止 |
| POST | /api/sessions/:id/runtime/abort | `{target}` | — | 立即打断（流式中停止按钮走这个） |
| POST | /api/sessions/:id/runtime/model | `{target,provider,modelId,modelName?}` | — | 运行中换模型 |
| POST | /api/sessions/:id/runtime/thinking | `{target,level}` | — | 换思考档位 |
| POST | /api/sessions/:id/runtime/restart | `{target}` | 心跳+结果 | 重启（5~10s） |
| POST | /api/sessions/:id/runtime/compact | `{target,prompt?}` | — | 压缩上下文 |
| POST | /api/sessions/:id/runtime/clone | `{target}` | `{session:{id}}` | fork 会话 |
| GET | /api/sessions/:id/file-changes | — | 快照 | 文件修改监控 |
| GET | /api/sessions/:id/subagents | — | 快照 | 子代理监控 |
| GET | /api/sessions/:id/todo | — | 快照 | todo 监控 |
| GET | /api/git/status\|diff\|log | `?projectId=` | — | Git 状态/差异/历史（全带 `projectId`） |
| GET | /api/files\|file-content | `?projectId=` | — | 文件浏览/内容（512KB 有界，超限/二进制走结构化标记） |
| GET | /api/sessions/:id/export-html | — | 字节 | 导出会话 HTML |

`target` = `SessionRuntimeTarget`，必须与 URL 中的 sessionId 一致；
可用值从 `/api/state` 的 `runtimes[]`（`{sessionId, agentId, status, runtimeGeneration?}`）里取，
`status` 为 `running`/`starting` 即忙。

## 发消息（POST /api/chat）

```json
{
  "id": "<sessionId>",
  "messages": [{"role": "user", "parts": [{"type": "text", "text": "..."}]}],
  "trigger": "submit-message",
  "messageId": "<每轮唯一的 UUID>"
}
```

- 服务端只取最后一条 user 消息的 text parts 拼接（`content` 字符串亦可），图片走
  `type=file` 的 part（data URL，单图 base64 ≤2MB，最多 4 张）。
- `messageId` 必须每轮唯一（幂等键）：复用会导致服务端去重，本轮无响应。
- 响应即 SSE 流（AI SDK UIMessageStream 协议分块 + `[DONE]` 收尾，未知 type 忽略，
  孤儿 output 丢弃）。预检拒绝时流内写 error+finish，客户端进 error 状态可重试。
- `stop()` 只断本地 SSE；真正的打断是 `runtime/abort`。

## 历史消息映射

- user/assistant：`text` → 文本段；`thinking` → 推理段；`images[]`
  （`{type:"image",mimeType,data}`，单张 >4MB 跳过）→ 文件段。
- tool（`role:"tool"` + `meta.toolName`）：段类型为动态名 `tool-${toolName}`，
  `toolCallId = hist-${id}`，`state = output-available/output-error`。
- assistant 正文（历史 + 流式）剥 `<thinking>…</thinking>`；思考卡只用 reasoning 通道。

## 客户端实现要点

- `http.Request.send()` 拿 SSE 字节流，手写分块解析（`lib/api/chat_stream.dart`）。
- `StreamDraft` 时间线分段：文本/思考/工具按到达顺序交错，同 kind 连续 delta 才合并。
- 轮询：`GET /api/state` 5s；聊天页 3s 取本会话待审批；监控条独立 5s（失败保快照）。
- 上下文用量：`runtime/state` 取 `WebContextUsage`，有 runtime 且 `window>0` 才显示。
