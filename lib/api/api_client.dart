import 'dart:convert';
import 'dart:math';

import 'package:http/http.dart' as http;

import '../pairing_store.dart';
import 'chat.dart';
import 'chat_stream.dart';
import 'models.dart';

/// PiDeck Web API 客户端（对照 docs/api-contract.md）。
///
/// 无 token 的局域网服务不发任何鉴权头；token 字段只为将来预留。
class ApiException implements Exception {
  ApiException(this.message, [this.statusCode]);

  final String message;
  final int? statusCode;

  @override
  String toString() => 'ApiException($statusCode): $message';
}

class ApiClient {
  ApiClient(this.pairing, [http.Client? client])
      : _client = client ?? http.Client();

  final Pairing pairing;
  final http.Client _client;

  Map<String, String> get _headers => {
        if (pairing.token.isNotEmpty)
          'Authorization': 'Bearer ${pairing.token}',
      };

  Uri _uri(String path, [Map<String, String>? query]) {
    final base = Uri.parse(pairing.baseUrl);
    return base.replace(
      path: '${base.path}$path'.replaceAll('//', '/'),
      queryParameters: {
        ...?query,
        if (pairing.token.isNotEmpty) 'token': pairing.token,
      },
    );
  }

  Future<Map<String, dynamic>> _getJson(String path, {Map<String, String>? query}) async {
    late final http.Response res;
    try {
      res = await _client.get(_uri(path, query), headers: _headers);
    } catch (e) {
      throw ApiException('无法连接服务：$e');
    }
    return _decode(res, path);
  }

  Future<Map<String, dynamic>> _postJson(
    String path,
    Map<String, dynamic> body,
  ) async {
    late final http.Response res;
    try {
      res = await _client.post(
        _uri(path),
        headers: {..._headers, 'content-type': 'application/json'},
        body: jsonEncode(body),
      );
    } catch (e) {
      throw ApiException('无法连接服务：$e');
    }
    return _decode(res, path);
  }

  Future<http.Response> _rawPost(String path, Map<String, dynamic> body) async {
    try {
      return await _client.post(
        _uri(path),
        headers: {..._headers, 'content-type': 'application/json'},
        body: jsonEncode(body),
      );
    } catch (e) {
      throw ApiException('无法连接服务：$e');
    }
  }

  Map<String, dynamic> _decode(http.Response res, String path) {
    if (res.statusCode < 200 || res.statusCode >= 300) {
      throw ApiException('请求失败：$path', res.statusCode);
    }
    final decoded = jsonDecode(res.body);
    if (decoded is Map<String, dynamic>) return decoded;
    throw ApiException('服务端返回格式异常：$path');
  }

  Future<bool> health() async {
    try {
      await _getJson('/api/health');
      return true;
    } on ApiException {
      return false;
    }
  }

  Future<WebState> fetchState() async =>
      WebState.fromJson(await _getJson('/api/state'));

  /// 历史分页：首次调用不传 before（拿最新一页）；续页传上一页的 nextBefore。
  /// nextBefore 为 null 表示已到顶。
  Future<MessagePage> fetchMessagePage(
    String sessionId, {
    int? before,
    int pageSize = 30,
  }) async {
    final query = <String, String>{'pageSize': '$pageSize'};
    if (before != null) query['before'] = '$before';
    final result = await _getJson(
      '/api/sessions/${Uri.encodeComponent(sessionId)}/messages/page',
      query: query,
    );
    // unavailable 专态：messages 为空 + 原因（如 dsh-host-stopped），
    // 不能按空会话渲染，必须进错误态。
    return MessagePage(
      messages: ((result['messages'] as List? ?? []))
          .map((e) => mapChatMessage(e as Map<String, dynamic>))
          .toList(),
      nextBefore: (result['nextBefore'] as num?)?.toInt(),
      unavailable: result['unavailable'] as String?,
    );
  }

  /// 审批问答应答（POST /api/ui-response）：response 形如
  /// {value} / {confirmed} / {cancelled}，照抄 handleRespondUi。
  Future<void> respondToUi({
    required String sessionId,
    required String requestId,
    required String agentId,
    required int runtimeGeneration,
    required Map<String, dynamic> response,
  }) async {
    await _postJson('/api/ui-response', {
      'sessionId': sessionId,
      'requestId': requestId,
      'agentId': agentId,
      'runtimeGeneration': runtimeGeneration,
      'response': response,
    });
  }

  /// 读取可用模型列表（草稿会话也可先选模型再发第一条消息）。
  /// `force=1` 绕服务端缓存（模型刚发布时不读旧表）。
  Future<List<AvailableModel>> fetchModels({bool force = true}) async {
    final json = await _getJson(
      '/api/models',
      query: force ? const {'force': '1'} : null,
    );
    final list = json['models'];
    if (list is! List) return [];
    return list
        .map((e) => AvailableModel.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  /// P0 打断（立即）：流式中停止按钮走这里，pi 丢弃未完成回合。
  /// runtime 已退出时服务端报错，调用方静默即可（下次轮询收敛状态）。
  Future<void> abortRuntime(
    String sessionId,
    String agentId,
    int runtimeGeneration,
  ) async {
    await _runtimeCommand(sessionId, agentId, runtimeGeneration, 'abort');
  }

  /// 运行中模型切换（立即发给 pi，主进程同步会话记录）。
  Future<void> setRuntimeModel(
    String sessionId,
    String agentId,
    int runtimeGeneration,
    String provider,
    String modelId, [
    String? modelName,
  ]) async {
    final body = <String, dynamic>{
      'provider': provider,
      'modelId': modelId,
    };
    if (modelName != null) body['modelName'] = modelName;
    await _runtimeCommand(sessionId, agentId, runtimeGeneration, 'model', body);
  }

  /// 运行中思考档位切换（立即发给 pi，主进程同步会话记录）。
  Future<void> setRuntimeThinking(
    String sessionId,
    String agentId,
    int runtimeGeneration,
    String level,
  ) async {
    await _runtimeCommand(
        sessionId, agentId, runtimeGeneration, 'thinking', {'level': level});
  }

  /// 会话记录偏好（POST /api/sessions/{id}/update）：idle 会话的模型/档位
  /// 只写记录、下次运行生效；运行中会话走 runtime 命令即时应用。
  /// 对齐网页 handleModelChange/handleThinkingChange 的无 runtime 分支。
  Future<void> updateSessionRecord(
    String sessionId, {
    String? modelProvider,
    String? modelId,
    String? modelName,
    String? thinkingLevel,
  }) async {
    final patch = <String, dynamic>{};
    if (modelProvider != null && modelId != null) {
      final model = <String, dynamic>{
        'provider': modelProvider,
        'modelId': modelId,
      };
      if (modelName != null) model['modelName'] = modelName;
      patch['model'] = model;
    }
    if (thinkingLevel != null) patch['thinkingLevel'] = thinkingLevel;
    if (patch.isEmpty) return;
    await _postJson(
      '/api/sessions/${Uri.encodeComponent(sessionId)}/update',
      patch,
    );
  }

  /// 上下文用量（runtime state 直出，供头部圆环轮询；无 runtime 时不调）。
  /// 对齐网页 fetchRuntimeContextUsage。
  Future<WebContextUsage> fetchRuntimeContextUsage(
    String sessionId,
    String agentId,
    int runtimeGeneration,
  ) async {
    final value =
        await _runtimeCommand(sessionId, agentId, runtimeGeneration, 'state');
    if (value is Map<String, dynamic>) return WebContextUsage.fromJson(value);
    return const WebContextUsage();
  }

  /// P0-P3 运行时命令：POST /api/sessions/{id}/runtime/{action}，
  /// body {target, ...}，返回 envelope result.value.value。
  Future<dynamic> _runtimeCommand(
    String sessionId,
    String agentId,
    int runtimeGeneration,
    String action, [
    Map<String, dynamic> body = const {},
  ]) async {
    final json = await _postJson(
      '/api/sessions/${Uri.encodeComponent(sessionId)}/runtime/$action',
      {
        'target': {
          'sessionId': sessionId,
          'agentId': agentId,
          'runtimeGeneration': runtimeGeneration,
        },
        ...body,
      },
    );
    final result = json['result'];
    if (result is! Map<String, dynamic> || result['ok'] != true) {
      final error = result is Map ? result['error'] : null;
      final code =
          error is Map ? error['code'] as String? : null;
      throw ApiException(code ?? 'runtime $action failed');
    }
    final value = result['value'];
    return value is Map ? value['value'] : null;
  }

  /// 发送消息并产出 SSE 帧流（UIMessageStream，帧集合见 chat_stream.dart）。
  ///
  /// messageId 必须每轮唯一（服务端幂等键）：复用会导致本轮无响应。
  /// 遇到 `error` 帧或 HTTP 失败时抛 ApiException；正常结束于 finish/[DONE]。
  ///
  /// 图片：data URL 数组（`data:mime;base64,…`），服务端 extractChatImages 只读
  /// parts 里 `type=file` 的节点（mediaType + url），最多 4 张、单图 base64 ≤2MB，
  /// 超限由服务端静默丢弃。agentMessage：计划模式隐藏指令，透传给 pi、不进时间线。
  Stream<Map<String, dynamic>> streamChat(
    String sessionId,
    String text, {
    String? messageId,
    List<String> images = const [],
    String? agentMessage,
  }) async* {
    final turnId = messageId ??
        '${DateTime.now().microsecondsSinceEpoch}-${Random().nextInt(1 << 32)}';
    final parts = <Map<String, dynamic>>[
      {'type': 'text', 'text': text},
      for (final src in images.take(4))
        {
          'type': 'file',
          'mediaType': RegExp(r'^data:([^;]+);').firstMatch(src)?.group(1) ?? 'image/jpeg',
          'url': src,
        },
    ];
    late final http.StreamedResponse res;
    try {
      final uri = _uri('/api/chat');
      final req = http.Request('POST', uri)
        ..headers.addAll({..._headers, 'content-type': 'application/json'})
        ..body = jsonEncode({
          'id': sessionId,
          'messages': [
            {
              'role': 'user',
              'parts': parts,
            },
          ],
          'trigger': 'submit-message',
          'messageId': turnId,
          if (agentMessage != null && agentMessage.isNotEmpty)
            'agentMessage': agentMessage,
        });
      res = await _client.send(req);
    } catch (e) {
      throw ApiException('无法连接服务：$e');
    }
    if (res.statusCode < 200 || res.statusCode >= 300) {
      final body = await res.stream.bytesToString();
      throw ApiException('发送失败（${res.statusCode}）：$body');
    }
    final splitter = SseSplitter();
    await for (final chunk in res.stream.transform(utf8.decoder)) {
      final split = splitter.push(chunk);
      for (final frame in split.frames) {
        if (frame['type'] == 'error') {
          throw ApiException('${frame['errorText'] ?? 'Agent 运行失败'}');
        }
        yield frame;
      }
      if (split.done) break;
    }
  }

  /// 编辑历史用户消息（runtime 必须存活；耗时数秒，调用方先做乐观更新）。
  Future<void> editRuntimeMessage(
    String sessionId,
    String agentId,
    int runtimeGeneration,
    String messageId,
    String newText,
  ) async {
    await _runtimeCommand(sessionId, agentId, runtimeGeneration,
        'edit-message', {'messageId': messageId, 'newText': newText});
  }

  /// 删除单条历史消息（只摘目标一条，回复保留）。
  Future<void> deleteRuntimeMessage(
    String sessionId,
    String agentId,
    int runtimeGeneration,
    String messageId,
  ) async {
    await _runtimeCommand(sessionId, agentId, runtimeGeneration,
        'delete-message', {'messageId': messageId});
  }

  /// 准备重发：取回该消息文本（+图片 data URL），由调用方填入输入框。
  Future<({String text, List<String> images})> prepareResend(
    String sessionId,
    String agentId,
    int runtimeGeneration,
    String messageId,
  ) async {
    final value = await _runtimeCommand(sessionId, agentId,
        runtimeGeneration, 'prepare-resend', {'messageId': messageId});
    if (value is! Map<String, dynamic>) {
      return (text: '', images: <String>[]);
    }
    final text = value['text'] as String? ?? '';
    final List<String> images = [];
    final raw = value['images'];
    if (raw is List<dynamic>) {
      for (final dynamic img in raw) {
        if (img is Map<String, dynamic>) {
          final mime = img['mimeType'] as String?;
          final data = img['data'] as String?;
          if (mime == null || data == null) continue;
          images.add(
              data.startsWith('data:') ? data : 'data:$mime;base64,$data');
        }
      }
    }
    return (text: text, images: <String>[...images]);
  }

  /// 提示词库搜索（中文提示词精选；search/category 均可选）。
  Future<PromptList> fetchPrompts({String? search, String? category}) async {
    final query = <String, String>{'page': '1', 'pageSize': '20'};
    if (search != null && search.isNotEmpty) query['search'] = search;
    if (category != null && category.isNotEmpty) query['category'] = category;
    final json = await _getJson('/api/prompts', query: query);
    return PromptList.fromJson(json);
  }

  /// 提示词正文（插入输入框用）。
  Future<String> fetchPromptContent(String slug, String category) async {
    final json = await _getJson(
      '/api/prompts/${Uri.encodeComponent(slug)}',
      query: {'category': category},
    );
    final detail = json['detail'];
    if (detail is Map<String, dynamic>) {
      return detail['promptContent'] as String? ?? '';
    }
    return '';
  }

  /// 会话活动监控（对齐网页 fetchSessionFileChanges/Subagents/Todo）：
  /// GET /api/sessions/{id}/{file-changes,subagents,todo}。
  Future<List<SessionFileChange>> fetchSessionFileChanges(
      String sessionId) async {
    final json = await _getJson(
        '/api/sessions/${Uri.encodeComponent(sessionId)}/file-changes');
    final raw = json['changes'];
    if (raw is! List) return const [];
    return [
      for (final e in raw)
        if (e is Map<String, dynamic>) SessionFileChange.fromJson(e)
    ];
  }

  Future<List<SubagentEntry>> fetchSessionSubagents(String sessionId) async {
    final json = await _getJson(
        '/api/sessions/${Uri.encodeComponent(sessionId)}/subagents');
    final raw = json['subagents'];
    if (raw is! List) return const [];
    return [
      for (final e in raw)
        if (e is Map<String, dynamic>) SubagentEntry.fromJson(e)
    ];
  }

  Future<SessionTodoSnapshot?> fetchSessionTodo(String sessionId) async {
    final json = await _getJson(
        '/api/sessions/${Uri.encodeComponent(sessionId)}/todo');
    final todo = json['todo'];
    if (todo == null) return null;
    if (todo is Map<String, dynamic>) {
      return SessionTodoSnapshot.fromJson(todo);
    }
    return null;
  }

  /// 注册本地项目，返回项目记录 id。
  Future<String> createProject(String path) async {
    final result = await _postJson('/api/projects', {'path': path});
    final project = result['project'];
    final id = project is Map ? project['id'] as String? : result['id'] as String?;
    if (id == null || id.isEmpty) throw ApiException('新建项目无返回 id');
    return id;
  }

  /// 新建会话，返回新会话 id。
  Future<String> createSession(
    String projectId, {
    Map<String, dynamic>? preferences,
  }) async {
    final result = await _postJson('/api/sessions', {
      'projectId': projectId,
      ...?preferences,
    });
    final id = (result['session'] as Map?)?['id'] as String?;
    if (id == null || id.isEmpty) throw ApiException('新建会话无返回 id');
    return id;
  }

  /// 重命名会话（走 catalog update，不需要 runtime；对齐 renameSession）。
  Future<void> renameSession(String sessionId, String title) async {
    final res = await _rawPost(
        '/api/sessions/${Uri.encodeComponent(sessionId)}/update',
        {'title': title});
    if (res.statusCode < 200 || res.statusCode >= 300) {
      throw ApiException('重命名失败（${res.statusCode}）');
    }
  }

  /// 删除会话记录（含会话文件；对齐 deleteSession）。
  Future<void> deleteSession(String sessionId) async {
    final res = await _rawPost(
        '/api/sessions/${Uri.encodeComponent(sessionId)}/delete', {});
    if (res.statusCode < 200 || res.statusCode >= 300) {
      throw ApiException('删除失败（${res.statusCode}）');
    }
  }

  /// 复制会话（浅拷贝生成新会话记录，返回新 id；对齐 copySession）。
  Future<String?> copySession(String sessionId) async {
    final json = await _postJson(
        '/api/sessions/${Uri.encodeComponent(sessionId)}/copy', {});
    final session = json['result'] is Map
        ? (json['result'] as Map)['session']
        : json['session'];
    final id = session is Map ? session['id'] as String? : null;
    return (id == null || id.isEmpty) ? null : id;
  }

  /// 导出会话 HTML（GET 附件流，返回字节；下载/分享由调用方决定）。
  Future<List<int>> exportSessionHtml(String sessionId) async {
    final uri = _uri(
        '/api/sessions/${Uri.encodeComponent(sessionId)}/export-html');
    http.Response res;
    try {
      res = await _client.get(uri, headers: _headers);
    } catch (e) {
      throw ApiException('无法连接服务：$e');
    }
    if (res.statusCode < 200 || res.statusCode >= 300) {
      throw ApiException('导出失败（${res.statusCode}）');
    }
    return res.bodyBytes;
  }

  /// 重启会话进程（会话身份不变；对齐 restartRuntime）。
  Future<void> restartRuntime(
      String sessionId, String agentId, int runtimeGeneration) async {
    await _runtimeCommand(sessionId, agentId, runtimeGeneration, 'restart');
  }

  /// 压缩上下文（可选自定义 prompt；对齐 compactRuntime）。
  Future<void> compactRuntime(
    String sessionId,
    String agentId,
    int runtimeGeneration, [
    String? prompt,
  ]) async {
    await _runtimeCommand(sessionId, agentId, runtimeGeneration, 'compact',
        prompt == null || prompt.isEmpty ? {} : {'prompt': prompt});
  }

  /// 克隆会话（fork 标记；返回新会话 id；对齐 cloneRuntime）。
  Future<String?> cloneRuntime(
      String sessionId, String agentId, int runtimeGeneration) async {
    final value =
        await _runtimeCommand(sessionId, agentId, runtimeGeneration, 'clone');
    final session = value is Map ? value['session'] : null;
    final id = session is Map ? session['id'] as String? : null;
    return (id == null || id.isEmpty) ? null : id;
  }

  /// 工作区 Git 状态（P3 只读：分支 + 四分组；无仓库时 repo=false）。
  /// 对齐网页 fetchGitStatus。
  Future<({bool repo, GitBranchInfo? branch, GitResourceGroups? groups})>
      fetchGitStatus(String projectId) async {
    final json = await _getJson('/api/git/status',
        query: {'projectId': projectId});
    if (json['repo'] != true) return (repo: false, branch: null, groups: null);
    return (
      repo: true,
      branch: GitBranchInfo.fromJson(
          (json['branch'] as Map<String, dynamic>?) ?? const {}),
      groups: GitResourceGroups.fromJson(
          json['groups'] as Map<String, dynamic>?),
    );
  }

  /// 工作区文件 diff（组内路径；无 diff 返回 null）。对齐 fetchGitDiff。
  Future<({String original, String modified})?> fetchGitDiff(
      String projectId, String group, String path) async {
    final json = await _getJson('/api/git/diff', query: {
      'projectId': projectId,
      'group': group,
      'path': path,
    });
    final diff = json['diff'];
    if (diff is! Map<String, dynamic>) return null;
    return (
      original: diff['originalContent'] as String? ?? '',
      modified: diff['modifiedContent'] as String? ?? '',
    );
  }

  /// 最近提交（默认 12 条，对齐网页 GitPanel）。对齐 fetchGitLog。
  Future<List<CommitEntry>> fetchGitLog(String projectId,
      [int limit = 12]) async {
    final json = await _getJson('/api/git/log',
        query: {'projectId': projectId, 'limit': '$limit'});
    final raw = json['commits'];
    if (raw is! List) return const [];
    return [
      for (final e in raw)
        if (e is Map<String, dynamic>) CommitEntry.fromJson(e)
    ];
  }

  /// 项目内目录列表（dir 为相对路径，空 = 根）。对齐 fetchFileList。
  Future<List<WebFileNodeLite>> fetchFileList(String projectId,
      [String? dir]) async {
    final query = {'projectId': projectId};
    if (dir != null && dir.isNotEmpty) query['dir'] = dir;
    final json = await _getJson('/api/files', query: query);
    final raw = json['nodes'];
    if (raw is! List) return const [];
    return [
      for (final e in raw)
        if (e is Map<String, dynamic>) WebFileNodeLite.fromJson(e)
    ];
  }

  /// 文件内容（512KB 有界；超限/二进制走结构化标记）。对齐 fetchFileContent。
  Future<FileContentResult> fetchFileContent(
      String projectId, String path) async {
    final json = await _getJson('/api/file-content',
        query: {'projectId': projectId, 'path': path});
    return FileContentResult.fromJson(json);
  }

  void close() => _client.close();
}
