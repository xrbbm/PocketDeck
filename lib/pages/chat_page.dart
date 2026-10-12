import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:image/image.dart' as img;
import 'package:image_picker/image_picker.dart';

import '../api/api_client.dart';
import '../api/branch_family.dart';
import '../api/chat.dart';
import '../api/chat_stream.dart';
import '../api/models.dart';
import 'ask_card.dart';
import 'tool_card.dart';
import 'workspace_drawer.dart';

/// 聊天页：历史分页（步骤 4）+ 发送与流式增量渲染（步骤 5/6）。
/// 中断按钮与模型选择器在步骤 9 接入；工具卡片精修在步骤 7。
class ChatPage extends StatefulWidget {
  const ChatPage(
      {super.key,
      required this.api,
      required this.session,
      this.onOpenDrawer,
      this.sessions,
      this.onSelectSession,
      this.projects});

  final ApiClient api;
  final WebSession session;
  /// 抽屉壳传入：左上菜单按钮开导航；为 null 时保留默认返回箭头。
  final VoidCallback? onOpenDrawer;
  /// 分支条用：全量会话 + 切会话回调（ShellPage 传入）。
  final List<WebSession>? sessions;
  final ValueChanged<WebSession>? onSelectSession;
  /// 工作区抽屉用：项目列表（ShellPage 传入，供查 projectId）。
  final List<WebProject>? projects;

  @override
  State<ChatPage> createState() => _ChatPageState();
}

/// 思考档位回退全集（对齐网页 WEB_THINKING_LEVELS）：当前模型无 levels
/// （瘦对象/服务端未报告）时 _pickThinking 用它直切，顺序即展示顺序。
const kThinkingLevelFallback = [
  'off',
  'minimal',
  'low',
  'medium',
  'high',
  'xhigh',
  'max',
];

/// 后台 isolate 图片解码+缩放（compute 回调必须是顶层函数）：
/// 长边压到 1600 后转 JPEG 82，失败回 null（调用方回退原图直传）。
/// 公开供单测锁死缩放语义，页面内经 compute 调用（不占 UI 线程）。
Uint8List? decodeResizeJpg(List<int> raw) {
  try {
    final decoded = img.decodeImage(Uint8List.fromList(raw));
    if (decoded == null) return null;
    const maxSide = 1600;
    final longest =
        decoded.width > decoded.height ? decoded.width : decoded.height;
    final out = longest > maxSide
        ? img.copyResize(decoded,
            width: decoded.width >= decoded.height ? maxSide : null,
            height: decoded.width >= decoded.height ? null : maxSide)
        : decoded;
    return img.encodeJpg(out, quality: 82);
  } catch (_) {
    return null;
  }
}

/// 输入草稿箱（进程级内存）：切会话重建 ChatPage 时，打一半的字不丢。
/// key=sessionId，value=(正文, 附件dataURL)。发送成功/删会话即清。
class ChatDraftBox {
  static final box = <String, ({String text, List<String> images})>{};

  /// 删会话时同步丢弃其输入草稿（Shell 调用，不留孤儿）。
  static void dropFor(String sessionId) => box.remove(sessionId);
}

class _ChatPageState extends State<ChatPage> with WidgetsBindingObserver {
  final _messages = <UiMessage>[];
  final _scroll = ScrollController();
  final _input = TextEditingController();
  // 回到底部小箭头：只在用户上滑看历史（离底部>160px）时出现。
  bool _showJump = false;
  int? _nextBefore;
  bool _hasMore = false;
  bool _loading = true;
  bool _loadingMore = false;
  String? _error;
  String? _unavailable;

  // 审批：3s 轮询 state，只取本会话的待审批（对齐网页端 pendingUiRequest）。
  // 同时缓存 runtime，供中断按钮与模型切换组装 target。
  Timer? _askPoller;
  WebPendingUiRequest? _pendingUi;
  WebRuntime? _runtime;
  // 已见 runtime 代际：代际推进说明新一轮运行开始，旧用量快照过期。
  int? _runtimeGen;
  AvailableModel? _model;
  String? _thinkingLevel;
  /// 右上上下文圆环：随主轮询刷新，无 runtime 时清空（对齐网页 ContextRing）。
  WebContextUsage? _usage;
  // 用量快照时间：超 30s 未更新视为过期，圆环置灰（不额外发请求，静默即可）。
  DateTime? _usageAt;
  bool _uiResponding = false;

  /// 输入框模型签（OpenChamber 风格）：只显示模型名，档位另有 chip。
  String get _modelName => _model?.display ?? '模型';

  /// 思考档位直切（对齐网页 WebThinkingSelector）：档位与模型独立。
  Future<void> _pickThinking() async {
    // 档位候选：当前模型的 levels；瘦对象无 levels 时退回全集。
    var levels = _model?.levels ?? const <String>[];
    if (levels.isEmpty) levels = kThinkingLevelFallback;
    final picked = await showModalBottomSheet<String>(
      context: context,
      builder: (_) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Padding(
              padding: EdgeInsets.all(12),
              child: Text('思考档位',
                  style: TextStyle(fontWeight: FontWeight.bold)),
            ),
            Flexible(
              child: ListView(
                shrinkWrap: true,
                children: [
                  for (final l in levels)
                    ListTile(
                      title: Text(l,
                          maxLines: 1, overflow: TextOverflow.ellipsis),
                      trailing: l == _thinkingLevel
                          ? const Icon(Icons.check)
                          : null,
                      // 只切档位、模型保持当前。
                      onTap: () => Navigator.pop(context, l),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
    if (picked == null || picked == _thinkingLevel || !mounted) return;
    await _setThinking(picked);
  }

  // 流式状态：一次只跑一轮（服务端单 runtime 串行）。
  StreamSubscription<Map<String, dynamic>>? _streamSub;
  String? _draftId;
  bool get _streaming => _streamSub != null;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _scroll.addListener(_trackScroll);
    // 切回来恢复草稿（本轮没发出去的字）。
    final saved = ChatDraftBox.box[widget.session.id];
    if (saved != null) {
      _input.text = saved.text;
      _attachments.addAll(saved.images);
    }
    _loadFirst();
    // 空转退避：idle（无 runtime、无审批）时 10s 一轮，运行时才 3s 跟紧。
    // 用 tick 计数器实现，不重建 Timer（重建会丢相位还抖）。
    var tick = 0;
    _askPoller = Timer.periodic(const Duration(seconds: 3), (_) {
      tick++;
      final idle = _runtime == null && _pendingUi == null && !_streaming;
      if (idle && tick % 3 != 1) return;
      _pollState();
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // 后台切回：SSE/轮询在后台会被系统挂起，回来时不断流，
    // 强制刷一次 state 追上服务端进度（不断流、不重建订阅）。
    if (state == AppLifecycleState.resumed) {
      _pollState(force: true);
    }
  }

  /// 滚动位置跟踪：离底部>160px 才亮小箭头（阈值>跟随阈值120，避免闪烁）。
  void _trackScroll() {
    if (!_scroll.hasClients) return;
    final pos = _scroll.position;
    if (pos.maxScrollExtent <= 0) return;
    final show = pos.pixels < pos.maxScrollExtent - 160;
    if (show != _showJump && mounted) setState(() => _showJump = show);
  }

  /// 点小箭头：平滑滚到底（300ms easeOut），不跳帧。
  void _smoothToBottom() {
    if (!_scroll.hasClients) return;
    _scroll.animateTo(
      _scroll.position.maxScrollExtent,
      duration: const Duration(milliseconds: 300),
      curve: Curves.easeOut,
    );
  }

  @override
  void dispose() {
    // 没发出去的字留进草稿箱（空草稿清掉，不占着）。
    final text = _input.text;
    final images = List<String>.from(_attachments);
    if (text.isEmpty && images.isEmpty) {
      ChatDraftBox.box.remove(widget.session.id);
    } else {
      ChatDraftBox.box[widget.session.id] = (text: text, images: images);
    }
    _askPoller?.cancel();
    _streamSub?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    _scroll.removeListener(_trackScroll);
    _scroll.dispose();
    _input.dispose();
    // inline 编辑行 controller 同页级持有，切会话重建堆一次漏一个。
    _editController.dispose();
    super.dispose();
  }

  Future<void> _pollState({bool force = false}) async {
    if (_uiResponding && !force) return;
    try {
      final state = await widget.api.fetchState();
      if (!mounted) return;
      final pending = state.pendingUiOf(widget.session.id);
      final had = _pendingUi?.requestId;
      final runtime = state.runtimeOf(widget.session.id);
      setState(() {
        _pendingUi = pending;
        _runtime = runtime;
        _adoptSessionRecord(state);
        // runtime 退出则用量清零；新一轮运行开始也清旧快照（代际变了就过期）。
        if (runtime == null) {
          _usage = null;
          _usageAt = null;
        } else if (runtime.runtimeGeneration != _runtimeGen) {
          _runtimeGen = runtime.runtimeGeneration;
          _usage = null;
          _usageAt = null;
        }
      });
      // 上下文用量随主轮询走（对齐网页 3s 节奏；runtime 退出竞态静默，下轮收敛）。
      if (runtime != null) {
        try {
          final usage = await widget.api.fetchRuntimeContextUsage(
            runtime.sessionId,
            runtime.agentId,
            runtime.runtimeGeneration ?? 0,
          );
          if (!mounted) return;
          setState(() {
            _usage = usage;
            _usageAt = DateTime.now();
          });
        } on ApiException {
          // 下轮收敛。
        }
      }
      // 新审批到达时滚到底，保证卡片可见。
      if (pending != null && had != pending.requestId) _jumpToBottom();
    } on ApiException {
      // 轮询失败静默（不覆盖正文区错误态）。
    }
  }

  /// 采用服务端会话记录里的模型/档位（idle 改偏好 + 主进程回写都经这里同步）。
  /// 只在变化时赋值，避免 3s 轮询空转 setState（setState 已在外层调用）。
  void _adoptSessionRecord(WebState state) {
    WebSession? record;
    for (final s in state.sessions) {
      if (s.id == widget.session.id) {
        record = s;
        break;
      }
    }
    if (record == null) return;
    final cur = _model;
    if (record.modelId != null && record.modelProvider != null) {
      if (cur == null ||
          cur.id != record.modelId ||
          cur.provider != record.modelProvider) {
        _model = AvailableModel(
          id: record.modelId!,
          provider: record.modelProvider!,
          name: record.modelName,
        );
      }
    }
    if (record.thinkingLevel != null &&
        record.thinkingLevel != _thinkingLevel) {
      _thinkingLevel = record.thinkingLevel;
    }
  }

  /// 中断：先停本地流式订阅（保留已流出部分），再发 abort 让 pi 丢弃未完成回合。
  /// runtime 已退出时服务端报错，静默（下次轮询收敛状态）。
  Future<void> _stop() async {
    final sub = _streamSub;
    if (sub == null) return;
    await sub.cancel();
    final runtime = _runtime;
    if (!mounted) return;
    setState(() {
      _streamSub = null;
      _draftId = null;
    });
    if (runtime == null) return;
    try {
      await widget.api.abortRuntime(
        runtime.sessionId,
        runtime.agentId,
        runtime.runtimeGeneration ?? 0,
      );
    } on ApiException {
      // 静默，见上。
    }
  }

  Future<void> _pickModel() async {
    List<AvailableModel> models;
    try {
      models = await widget.api.fetchModels();
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() => _error = e.message);
      return;
    }
    if (!mounted) return;
    final picked = await showModalBottomSheet<_ModelPick>(
      context: context,
      builder: (_) => _ModelSheet(
        models: models,
        current: _model,
        currentLevel: _thinkingLevel,
      ),
    );
    if (picked == null ||
        (picked.model == null && picked.level == null) ||
        !mounted) {
      return;
    }
    // 模型与档位可一次回填：先换模型，再切档位（本地一次写完，不闪中间态）。
    final runtime = _runtime;
    try {
      final model = picked.model;
      if (model != null) {
        if (runtime != null) {
          // 运行中：即时下发 pi。
          await widget.api.setRuntimeModel(
            runtime.sessionId,
            runtime.agentId,
            runtime.runtimeGeneration ?? 0,
            model.provider,
            model.id,
            model.name,
          );
        } else {
          // idle：只写会话记录，下次运行生效（对齐网页 handleModelChange）。
          await widget.api.updateSessionRecord(
            widget.session.id,
            modelProvider: model.provider,
            modelId: model.id,
            modelName: model.name,
          );
        }
        if (!mounted) return;
        // 模型+档位一次回填：先把档位记到本地（不经过 null 中间态），
        // 再下发服务端；下发失败 _setThinking 会弹错，本地保持用户选择。
        if (picked.level != null) _thinkingLevel = picked.level;
        setState(() {
          _model = model;
        });
      }
      if (picked.level != null) {
        await _setThinking(picked.level!);
      }
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() => _error = e.message);
    }
  }

  /// 思考档位切换：运行中即时下发，idle 写会话记录下次生效。
  /// 失败只报错，不改本地显示。
  Future<void> _setThinking(String level) async {
    final runtime = _runtime;
    try {
      if (runtime != null) {
        await widget.api.setRuntimeThinking(
          runtime.sessionId,
          runtime.agentId,
          runtime.runtimeGeneration ?? 0,
          level,
        );
      } else {
        await widget.api.updateSessionRecord(
          widget.session.id,
          thinkingLevel: level,
        );
      }
      if (!mounted) return;
      setState(() => _thinkingLevel = level);
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() => _error = e.message);
    }
  }

  /// 上下文用量明细（点右上圆环）：百分比 · tokens/window，溢出标红。
  Future<void> _showUsage() async {
    final u = _usage;
    if (u == null) return;
    final tokens = u.contextTokens;
    final window = u.contextWindow;
    await showModalBottomSheet<void>(
      context: context,
      builder: (_) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('上下文用量',
                  style: TextStyle(fontWeight: FontWeight.bold)),
              const SizedBox(height: 8),
              Text('${u.percent.toStringAsFixed(1)}% · '
                  '${tokens ?? '–'} / ${window ?? '–'} tokens'),
              if (u.contextOverflow)
                Text(
                  '已溢出，回答可能丢失早段记忆',
                  style: TextStyle(
                      color: Theme.of(context).colorScheme.error),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _respondUi(Map<String, dynamic> response) async {
    final req = _pendingUi;
    if (req == null || _uiResponding) return;
    setState(() => _uiResponding = true);
    try {
      await widget.api.respondToUi(
        sessionId: req.sessionId,
        requestId: req.requestId,
        agentId: req.agentId,
        runtimeGeneration: req.runtimeGeneration,
        response: response,
      );
      await _pollState(force: true);
    } on ApiException catch (e) {
      // 服务端 generation 已推进（STALE_GENERATION）：不是真失败，
      // 强制刷新拿新审批，不弹错误态吓用户。
      final stale = e.message.contains('STALE_GENERATION') ||
          e.message.contains('generation');
      if (stale) {
        await _pollState(force: true);
        return;
      }
      if (!mounted) return;
      setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _uiResponding = false);
    }
  }

  Future<void> _loadFirst() async {
    try {
      final page = await widget.api.fetchMessagePage(widget.session.id);
      if (!mounted) return;
      setState(() {
        _messages
          ..clear()
          ..addAll(page.messages);
        _nextBefore = page.nextBefore;
        _hasMore = page.hasMore;
        _unavailable = page.unavailable;
        _error = null;
        _loading = false;
      });
      _jumpToBottom();
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.message;
        _loading = false;
      });
    }
  }

  Future<void> _loadMore() async {
    final before = _nextBefore;
    if (before == null || _loadingMore) return;
    setState(() => _loadingMore = true);
    try {
      final page = await widget.api.fetchMessagePage(
        widget.session.id,
        before: before,
      );
      if (!mounted) return;
      setState(() {
        _messages.insertAll(0, page.messages);
        _nextBefore = page.nextBefore;
        _hasMore = page.hasMore;
        _loadingMore = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.message;
        _loadingMore = false;
      });
    }
  }

  void _jumpToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) _scroll.jumpTo(_scroll.position.maxScrollExtent);
    });
  }

  /// 流式中如果用户本来就贴着底部，跟随滚动；否则不动（不打断看历史）。
  /// 跟随时顺带灭小箭头（已经到底了，箭头该消失）。
  void _stickToBottom() {
    if (!_scroll.hasClients) return;
    final pos = _scroll.position;
    if (pos.pixels >= pos.maxScrollExtent - 120) {
      if (_showJump && mounted) setState(() => _showJump = false);
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (_scroll.hasClients) {
          _scroll.jumpTo(_scroll.position.maxScrollExtent);
        }
      });
    }
  }

  /// 计划模式标记（对照桌面 composerBehavior.PI_DECK_PLAN_MODE_MARKER）：
  /// 一次性提交流，发完自动回普通模式；斜线命令原样发送不受标记污染。
  static const _planMarker = '__PI_DECK_PLAN_MODE__';
  bool _planMode = false;
  final _attachments = <String>[];
  bool _attaching = false;
  // 用户消息 inline 编辑态（对齐网页 WebUserBubble：仅一条进入编辑）。
  String? _editingId;
  final _editController = TextEditingController();

  String? _planAgentMessage(String text) {
    if (!_planMode) return null;
    final trimmed = text.trim();
    if (trimmed.startsWith('/')) return null;
    final visible =
        trimmed.isEmpty ? '请根据已附加的图片或上下文先制定实施计划。' : trimmed;
    return [
      _planMarker,
      visible,
      '',
      '请先只做只读分析，不要修改文件。最后必须输出以 `Plan:` 开头的编号计划，格式如下：',
      'Plan:',
      '1. 第一步',
      '2. 第二步',
    ].join('\n');
  }

  Future<void> _pickImages() async {
    if (_attaching) return;
    setState(() => _attaching = true);
    try {
      final files = await ImagePicker().pickMultiImage();
      if (!mounted) return;
      // data URL 形态与网页 compressImageToDataUrl 输出一致；服务端 4 张/2MB 兜底。
      // 超限本地先压（image 包解码→长边 1600→JPEG 82），压完还超 2MB 就丢弃并提示。
      final encoded = <String>[];
      var dropped = 0;
      for (final f in files) {
        final raw = await f.readAsBytes();
        final mime = f.mimeType ?? _guessMime(f.name);
        final url = await _compressDataUrlAsync(raw, mime);
        // base64 体积 ≈ 原字节 4/3：按 2MB 上限卡。
        if ((url.length * 3) ~/ 4 > 2 * 1024 * 1024) {
          dropped++;
          continue;
        }
        encoded.add(url);
      }
      setState(() {
        _attachments.addAll(encoded);
        if (_attachments.length > 4) {
          _attachments.removeRange(4, _attachments.length);
        }
        if (dropped > 0) _error = '有 $dropped 张图片过大（>2MB），已跳过';
      });
    } on Exception catch (e) {
      if (!mounted) return;
      setState(() => _error = '添加图片失败：$e');
    } finally {
      if (mounted) setState(() => _attaching = false);
    }
  }

  String _guessMime(String name) {
    final lower = name.toLowerCase();
    if (lower.endsWith('.png')) return 'image/png';
    if (lower.endsWith('.gif')) return 'image/gif';
    if (lower.endsWith('.webp')) return 'image/webp';
    return 'image/jpeg';
  }

  /// 图片本地压缩：小图（≤2MB）/动图（gif/webp）原样直传，大图才解码。
  /// 主线程解码大图会掉帧：包一层 compute 扔后台 isolate（首帧慢半拍，不卡 UI）。
  Future<String> _compressDataUrlAsync(List<int> raw, String mime) async {
    if (raw.length <= 2 * 1024 * 1024) {
      return 'data:$mime;base64,${base64Encode(raw)}';
    }
    final lower = mime.toLowerCase();
    if (lower.contains('gif') || lower.contains('webp')) {
      return 'data:$mime;base64,${base64Encode(raw)}';
    }
    try {
      final out = await compute(decodeResizeJpg, raw);
      if (out == null) return 'data:$mime;base64,${base64Encode(raw)}';
      return 'data:image/jpeg;base64,${base64Encode(out)}';
    } catch (_) {
      return 'data:$mime;base64,${base64Encode(raw)}';
    }
  }

  /// data URL 头里取真实 mime（`data:image/png;base64,…`），取不到回退 jpeg。
  String _mimeOfDataUrl(String src) =>
      RegExp(r'^data:([^;]+);').firstMatch(src)?.group(1) ?? 'image/jpeg';

  Future<void> _openPromptLibrary() async {
    final picked = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      builder: (_) => _PromptSheet(api: widget.api),
    );
    if (picked == null || picked.isEmpty || !mounted) return;
    setState(() {
      _input.text = _input.text.isEmpty ? picked : '${_input.text}\n\n$picked';
    });
  }

  /// 用户消息长按菜单：复制恒有；编辑/重发/删除要 runtime（对齐网页 manage 门）。
  Future<void> _messageMenu(UiMessage m) async {
    if (!m.isUser) return;
    final text = m.parts.whereType<TextPart>().map((p) => p.text).join('\n');
    final runtime = _runtime;
    final action = await showModalBottomSheet<String>(
      context: context,
      builder: (_) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.copy_outlined),
              title: const Text('复制'),
              onTap: () => Navigator.pop(context, 'copy'),
            ),
            if (runtime != null) ...[
              ListTile(
                leading: const Icon(Icons.edit_outlined),
                title: const Text('编辑'),
                onTap: () => Navigator.pop(context, 'edit'),
              ),
              ListTile(
                leading: const Icon(Icons.refresh_outlined),
                title: const Text('重发'),
                onTap: () => Navigator.pop(context, 'resend'),
              ),
              ListTile(
                leading: const Icon(Icons.delete_outline),
                title: const Text('删除'),
                onTap: () => Navigator.pop(context, 'delete'),
              ),
            ],
          ],
        ),
      ),
    );
    if (action == null || !mounted) return;
    switch (action) {
      case 'copy':
        await Clipboard.setData(ClipboardData(text: text));
      case 'edit':
        _editController.text = text;
        setState(() => _editingId = m.id);
      case 'resend':
        await _resendMessage(m.id);
      case 'delete':
        await _deleteMessage(m.id);
    }
  }

  Future<WebRuntimeTarget?> _target() async {
    var runtime = _runtime;
    if (runtime == null) {
      await _pollState();
      runtime = _runtime;
    }
    final r = runtime;
    if (r == null) {
      if (mounted) setState(() => _error = '会话进程未运行，无法操作该消息');
      return null;
    }
    return (sessionId: r.sessionId, agentId: r.agentId,
        generation: r.runtimeGeneration ?? 0);
  }

  /// 重发：prepare-resend 取回文本（+图片）填入输入框（对齐网页 handleResendMessage）。
  Future<void> _resendMessage(String messageId) async {
    final t = await _target();
    if (t == null || !mounted) return;
    try {
      final prepared = await widget.api.prepareResend(
          t.sessionId, t.agentId, t.generation, messageId);
      if (!mounted) return;
      setState(() {
        _input.text = prepared.text;
        _attachments
          ..clear()
          ..addAll(prepared.images.take(4));
        _error = null;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() => _error = e.message);
    }
  }

  /// 顶栏会话菜单：重启/压缩/克隆（对齐网页 P1 会话级命令）。
  /// 流式中禁用（服务端单 runtime 串行，busy 时命令会被拒绝）。
  Future<void> _sessionMenu() async {
    final action = await showModalBottomSheet<String>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.refresh_outlined),
              title: const Text('重启会话进程'),
              subtitle: const Text('会话身份不变'),
              enabled: !_streaming,
              onTap: () => Navigator.pop(ctx, 'restart'),
            ),
            ListTile(
              leading: const Icon(Icons.compress_outlined),
              title: const Text('压缩上下文'),
              enabled: !_streaming,
              onTap: () => Navigator.pop(ctx, 'compact'),
            ),
            ListTile(
              leading: const Icon(Icons.fork_right_outlined),
              title: const Text('克隆为分支'),
              enabled: !_streaming,
              onTap: () => Navigator.pop(ctx, 'clone'),
            ),
          ],
        ),
      ),
    );
    if (action == null || !mounted) return;
    final t = await _target();
    if (t == null || !mounted) return;
    try {
      switch (action) {
        case 'restart':
          await widget.api.restartRuntime(t.sessionId, t.agentId, t.generation);
          if (!mounted) return;
          setState(() => _error = null);
          ScaffoldMessenger.of(context)
              .showSnackBar(const SnackBar(content: Text('已发送重启')));
        case 'compact':
          final prompt = await _compactPrompt();
          if (prompt == null || !mounted) return;
          await widget.api.compactRuntime(
              t.sessionId, t.agentId, t.generation,
              prompt.isEmpty ? null : prompt);
          if (!mounted) return;
          setState(() => _error = null);
          await _loadFirst();
        case 'clone':
          final newId = await widget.api.cloneRuntime(
              t.sessionId, t.agentId, t.generation);
          if (!mounted) return;
          // 分支条只列家族成员：新分支 id 若已在全量会话里，直接切过去，
          // 不在（服务端还没落定）才提示去抽屉找。
          WebSession? target;
          if (newId != null) {
            for (final s in widget.sessions ?? const <WebSession>[]) {
              if (s.id == newId) {
                target = s;
                break;
              }
            }
          }
          final go = target;
          if (go != null) {
            widget.onSelectSession?.call(go);
          } else {
            ScaffoldMessenger.of(context).showSnackBar(SnackBar(
              content: Text(newId == null ? '已克隆' : '已克隆为新会话（$newId），请在抽屉中切换'),
            ));
          }
      }
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() => _error = e.message);
    }
  }

  /// 压缩可选 prompt 输入框（空=默认压缩）。
  Future<String?> _compactPrompt() {
    final controller = TextEditingController();
    return showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('压缩上下文'),
        content: TextField(
          controller: controller,
          maxLines: 3,
          decoration: const InputDecoration(
            hintText: '自定义压缩指令（可选，留空用默认）',
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('取消')),
          FilledButton(
              onPressed: () =>
                  Navigator.of(ctx).pop(controller.text.trim()),
              child: const Text('压缩')),
        ],
      ),
    );
  }

  /// 删除：先本地摘除（墓碑语义：只摘目标一条），失败回滚重载。
  Future<void> _deleteMessage(String messageId) async {
    final t = await _target();
    if (t == null || !mounted) return;
    final index = _messages.indexWhere((m) => m.id == messageId);
    final removed = index >= 0 ? _messages[index] : null;
    setState(() {
      _messages.removeWhere((m) => m.id == messageId);
      _error = null;
    });
    try {
      await widget.api.deleteRuntimeMessage(
          t.sessionId, t.agentId, t.generation, messageId);
      await _loadFirst();
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        if (removed != null && index >= 0) {
          _messages.insert(index.clamp(0, _messages.length), removed);
        }
        _error = e.message;
      });
    }
  }

  /// 提交 inline 编辑：乐观替换文本，失败回滚重载（对齐网页 handleEditMessage）。
  Future<void> _commitEdit(String messageId) async {
    final t = await _target();
    if (t == null || !mounted) return;
    final newText = _editController.text;
    final index = _messages.indexWhere((m) => m.id == messageId);
    if (index < 0) {
      setState(() => _editingId = null);
      return;
    }
    final old = _messages[index];
    UiMessage replaced() {
      final parts = <UiPart>[];
      var done = false;
      for (final p in old.parts) {
        if (!done && p is TextPart) {
          parts.add(TextPart(newText));
          done = true;
        } else {
          parts.add(p);
        }
      }
      return UiMessage(id: old.id, role: old.role, parts: parts);
    }
    setState(() {
      _messages[index] = replaced();
      _editingId = null;
      _error = null;
    });
    try {
      await widget.api.editRuntimeMessage(
          t.sessionId, t.agentId, t.generation, messageId, newText);
      await _loadFirst();
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() => _error = e.message);
      await _loadFirst();
    }
  }

  /// 每轮唯一的 messageId（UUIDv4，服务端幂等键；复用会被去重吞掉）。
  String _uuidV4() {
    final r = DateTime.now().microsecondsSinceEpoch;
    final a = (r & 0xffffffff).toRadixString(16).padLeft(8, '0');
    final b = ((r >> 32) & 0xffff).toRadixString(16).padLeft(4, '0');
    const c = '4fff';
    const d = '8000';
    final e = _messages.length.toRadixString(16).padLeft(12, '0');
    return '$a-$b-$c-$d-$e';
  }

  Future<void> _send() async {
    final text = _input.text.trim();
    final images = List<String>.from(_attachments);
    // 空态/压缩忙时不发（发送键禁用是第一道，键盘回车是第二道）。
    if ((text.isEmpty && images.isEmpty) || _streaming || _attaching) return;
    _input.clear();
    // 发出去了，草稿箱清掉（dispose 时不留残）。
    ChatDraftBox.box.remove(widget.session.id);
    final agentMessage = _planAgentMessage(text);
    final wasPlan = _planMode;
    final turnId = _uuidV4();
    final draft = StreamDraft();
    setState(() {
      _messages.add(UiMessage(
        id: 'user-$turnId',
        role: 'user',
        parts: [
          if (text.isNotEmpty) TextPart(text),
          for (final src in images)
            FilePart(mimeType: _mimeOfDataUrl(src), dataUrl: src),
        ],
      ));
      _attachments.clear();
      if (wasPlan) _planMode = false;
      _draftId = 'asst-$turnId';
      _error = null;
    });
    _jumpToBottom();

    final sub = widget.api
        .streamChat(widget.session.id, text,
            images: images, agentMessage: agentMessage).listen(
      (frame) {
        if (!mounted) return;
        draft.applyFrame(frame);
        setState(() {
          _replaceDraft(draft);
        });
        _stickToBottom();
      },
      onError: (Object e) {
        if (!mounted) return;
        setState(() {
          // 保留已流出的部分文本，只提示错误。
          _replaceDraft(draft);
          _draftId = null;
          _streamSub = null;
          _error = e is ApiException ? e.message : '$e';
        });
      },
      onDone: () {
        if (!mounted) return;
        setState(() {
          _replaceDraft(draft);
          _draftId = null;
          _streamSub = null;
        });
      },
      cancelOnError: true,
    );
    _streamSub = sub;
  }

  /// 用草稿当前快照替换尾部流式消息；空草稿不占位。
  void _replaceDraft(StreamDraft draft) {
    final id = _draftId;
    if (id == null) return;
    _messages.removeWhere((m) => m.id == id);
    if (!draft.isEmpty) _messages.add(draft.toUiMessage(id));
  }

  /// 当前会话所属项目 id（工作区 Git/文件接口用）。
  String? get _projectId {
    final sessions = widget.sessions;
    if (sessions != null) {
      for (final s in sessions) {
        if (s.id == widget.session.id && s.projectId.isNotEmpty) {
          return s.projectId;
        }
      }
    }
    if (widget.session.projectId.isNotEmpty) return widget.session.projectId;
    // 兜底：单项目直用（多项目时工作区入口隐藏）；空 id 不算数。
    final only = widget.projects?.singleOrNull;
    if (widget.projects?.length == 1 && (only?.id.isNotEmpty ?? false)) {
      return only!.id;
    }
    return null;
  }

  void _openWorkspace() {
    final projectId = _projectId;
    if (projectId == null) return;
    showDialog(
      context: context,
      builder: (ctx) {
        // 小屏不溢出：宽度取 400 与屏宽-32 的较小值（360px 手机全宽进）。
        final w = MediaQuery.of(ctx).size.width;
        return Dialog(
          insetPadding: const EdgeInsets.symmetric(horizontal: 16),
          child: SizedBox(
            width: w - 32 > 400 ? 400 : w - 32,
            height: MediaQuery.of(ctx).size.height * 0.9,
            child: WorkspaceDrawer(api: widget.api, projectId: projectId),
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        leading: widget.onOpenDrawer == null
            ? null
            : IconButton(
                icon: const Icon(Icons.menu),
                tooltip: '会话导航',
                onPressed: widget.onOpenDrawer,
              ),
        title: Text(
          widget.session.title.isEmpty ? '(无标题)' : widget.session.title,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        actions: [
          if (_usage?.hasWindow == true)
            _UsageRing(
              usage: _usage!,
              // 快照超 30s 未更新视为过期：圆环置灰，不断请求。
              stale: _usageAt == null ||
                  DateTime.now().difference(_usageAt!).inSeconds > 30,
              onTap: _showUsage,
            ),
          if (_projectId != null)
            IconButton(
              icon: const Icon(Icons.folder_outlined),
              tooltip: '工作区',
              onPressed: _openWorkspace,
            ),
          IconButton(
            icon: const Icon(Icons.more_vert),
            tooltip: '会话操作',
            onPressed: _sessionMenu,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _unavailable != null && _messages.isEmpty
                  ? Center(
                      child: Text(
                        '历史暂时不可读（$_unavailable），请检查桌面端会话进程',
                      ),
                    )
                  : Column(
                      children: [
                        _BranchBar(
                          sessions: widget.sessions,
                          activeId: widget.session.id,
                          onSelect: widget.onSelectSession,
                        ),
                        if (_hasMore)
                          TextButton(
                            onPressed: _loadMore,
                            child: _loadingMore
                                ? const SizedBox(
                                    width: 16,
                                    height: 16,
                                    child: CircularProgressIndicator(
                                        strokeWidth: 2),
                                  )
                                : const Text('查看更早消息'),
                          ),
                        Expanded(
                          child: Builder(builder: (context) {
                            // 跨消息合并连续同名纯工具消息（服务端每 call 存一条时常见）。
                            final runs = groupToolMessages(_messages);
                            return ListView.builder(
                              controller: _scroll,
                              itemCount: runs.length,
                              itemBuilder: (_, i) {
                                final run = runs[i];
                                if (run.length > 1) {
                                  return ToolGroup(
                                    // 稳定 key：首条 id 唯一决定组身份，不带长度，
                                    // 流式追加工具时展开态不重置。
                                    key: ValueKey('g:${run.first.id}'),
                                    toolName:
                                        (run.first.parts.first as ToolPart)
                                            .toolName,
                                    parts: [
                                      for (final m in run)
                                        m.parts.first as ToolPart
                                    ],
                                  );
                                }
                                final m = run.first;
                                if (m.isUser && m.id == _editingId) {
                                  return _EditRow(
                                    key: ValueKey('${m.id}:edit'),
                                    controller: _editController,
                                    onSave: () => _commitEdit(m.id),
                                    onCancel: () => setState(
                                        () => _editingId = null),
                                  );
                                }
                                return GestureDetector(
                                  onLongPress: m.isUser
                                      ? () => _messageMenu(m)
                                      : null,
                                  child: _MessageRow(
                                    key: ValueKey(m.id),
                                    message: m,
                                    live: m.id == _draftId,
                                  ),
                                );
                              },
                            );
                          }),
                        ),
                        _ActivityStrips(
                          api: widget.api,
                          sessionId: widget.session.id,
                        ),
                        if (_pendingUi != null)
                          AskCard(
                            // 按请求重建：换题时选中态不串台。
                            key: ValueKey(_pendingUi!.requestId),
                            request: _pendingUi!,
                            busy: _uiResponding,
                            onRespond: _respondUi,
                          ),
                        if (_error != null)
                          Padding(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 12, vertical: 4),
                            child: Row(
                              children: [
                                Expanded(
                                  child: Text(
                                    _error!,
                                    style: TextStyle(
                                      color:
                                          Theme.of(context).colorScheme.error,
                                      fontSize: 12,
                                    ),
                                  ),
                                ),
                                // 历史首载失败时本页可重试（发送/流式错误一定带消息，
                                // 只有首载失败会空消息；重试成功即清错）。
                                if (_messages.isEmpty)
                                  TextButton(
                                    onPressed: _loadFirst,
                                    child: const Text('重试'),
                                  ),
                                TextButton(
                                  onPressed: () =>
                                      setState(() => _error = null),
                                  child: const Text('忽略'),
                                ),
                              ],
                            ),
                          ),
                        _Composer(
                          controller: _input,
                          streaming: _streaming,
                          showJump: _showJump,
                          onJumpToBottom: _smoothToBottom,
                          modelLabel: _modelName,
                          thinkingLevel: _thinkingLevel,
                          planMode: _planMode,
                          onPlanToggle: () =>
                              setState(() => _planMode = !_planMode),
                          attachments: _attachments,
                          attaching: _attaching,
                          onPickImages: _pickImages,
                          onRemoveImage: (i) => setState(
                              () => _attachments.removeAt(i)),
                          onPromptTap: _openPromptLibrary,
                          onSend: _send,
                          onStop: _stop,
                          onModelTap: _pickModel,
                          onThinkingTap: _pickThinking,
                          onChanged: () => setState(() {}),
                        ),
                      ],
                    ),
    );
  }
}

/// 右上上下文圆环（对齐网页 ContextRing）：用量弧 + 百分比，点开看明细。
/// 快照超 30s 未更新视为过期，圆环置灰（不额外发请求，静默即可）。
class _UsageRing extends StatelessWidget {
  const _UsageRing({required this.usage, required this.onTap, this.stale = false});

  final WebContextUsage usage;
  final VoidCallback onTap;
  final bool stale;

  @override
  Widget build(BuildContext context) {
    final percent = usage.percent;
    final scheme = Theme.of(context).colorScheme;
    // 用量色跟主题走：正常 secondary、警告 tertiary、危险 error。
    final color = stale
        ? scheme.outline
        : percent < 70
            ? scheme.secondary
            : percent < 90
                ? scheme.tertiary
                : scheme.error;
    return Padding(
      padding: const EdgeInsets.only(right: 4),
      child: InkWell(
        customBorder: const CircleBorder(),
        onTap: onTap,
        child: SizedBox(
          width: 40,
          height: 40,
          child: Stack(
            alignment: Alignment.center,
            children: [
              CircularProgressIndicator(
                value: percent / 100,
                strokeWidth: 3,
                valueColor: AlwaysStoppedAnimation<Color>(color),
              ),
              Text('${percent.round()}%',
                  style: const TextStyle(fontSize: 10)),
            ],
          ),
        ),
      ),
    );
  }
}

/// 会话活动监控条（对齐网页 WebSessionStrips：文件修改/子代理/todo 三条）。
/// 默认折叠（移动端正屏占位大，标题行即摘要）；失败保留上一份快照。
class _ActivityStrips extends StatefulWidget {
  const _ActivityStrips({required this.api, required this.sessionId});

  final ApiClient api;
  final String sessionId;

  @override
  State<_ActivityStrips> createState() => _ActivityStripsState();
}

class _ActivityStripsState extends State<_ActivityStrips> {
  Timer? _poller;
  List<SessionFileChange> _changes = const [];
  List<SubagentEntry> _subagents = const [];
  SessionTodoSnapshot? _todo;

  @override
  void initState() {
    super.initState();
    _load();
    // 空转退避：三条全空（无活动）时 15s 一轮，有活动才 5s 跟紧。
    var tick = 0;
    _poller = Timer.periodic(const Duration(seconds: 5), (_) {
      tick++;
      final idle = _changes.isEmpty &&
          _subagents.isEmpty &&
          (_todo?.todos.isEmpty ?? true);
      if (idle && tick % 3 != 1) return;
      _load();
    });
  }

  @override
  void didUpdateWidget(_ActivityStrips old) {
    super.didUpdateWidget(old);
    if (old.sessionId != widget.sessionId) {
      _changes = const [];
      _subagents = const [];
      _todo = null;
      _load();
    }
  }

  @override
  void dispose() {
    _poller?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    // 逐个 try/catch：一个接口形变/失败只丢自己那条，另两条照保
    // （原来 Future.wait 一坏全丢，三条全闪没）。
    List<SessionFileChange>? changes;
    List<SubagentEntry>? subagents;
    SessionTodoSnapshot? todo;
    var todoFailed = false;
    try {
      changes = await widget.api.fetchSessionFileChanges(widget.sessionId);
    } catch (_) {
      // 保快照，下轮重试。
    }
    try {
      subagents = await widget.api.fetchSessionSubagents(widget.sessionId);
    } catch (_) {
      // 同上。
    }
    try {
      todo = await widget.api.fetchSessionTodo(widget.sessionId);
    } catch (_) {
      todoFailed = true;
    }
    if (!mounted) return;
    // 全挂才静默（保快照）；todo 挂了但返回 null 是合法空态，别误伤。
    if (changes == null && subagents == null && todoFailed) return;
    setState(() {
      if (changes != null) _changes = changes;
      if (subagents != null) _subagents = subagents;
      if (!todoFailed) _todo = todo;
    });
  }

  @override
  Widget build(BuildContext context) {
    final todos = _todo?.todos ?? const <SessionTodoItem>[];
    if (_changes.isEmpty && _subagents.isEmpty && todos.isEmpty) {
      return const SizedBox.shrink();
    }
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (_changes.isNotEmpty)
          _StripShell(
            icon: Icons.description_outlined,
            title: '文件修改',
            count: _changes.length,
            child: Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                for (final c in _changes.take(30))
                  ActionChip(
                    label: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Flexible(
                          child: Text(c.basename,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis),
                        ),
                        Text(' ×${c.count}',
                            style: const TextStyle(fontSize: 11)),
                      ],
                    ),
                    tooltip: c.path,
                    visualDensity: VisualDensity.compact,
                    onPressed: () => Clipboard.setData(
                        ClipboardData(text: c.path)),
                  ),
              ],
            ),
          ),
        if (_subagents.isNotEmpty)
          _StripShell(
            icon: Icons.auto_awesome_outlined,
            title: '子代理',
            count: _subagents.length,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (final entry in _subagents)
                  _SubagentRow(entry: entry),
              ],
            ),
          ),
        if (todos.isNotEmpty)
          _StripShell(
            icon: Icons.list_alt_outlined,
            title: '任务清单',
            count: todos.length,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (final item in todos)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 2),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Icon(
                          item.completed
                              ? Icons.check_circle
                              : Icons.circle_outlined,
                          size: 14,
                          color: item.completed
                              ? Theme.of(context).colorScheme.secondary
                              : item.inProgress
                                  ? Theme.of(context).colorScheme.primary
                                  : Theme.of(context).colorScheme.outline,
                        ),
                        const SizedBox(width: 6),
                        Expanded(
                          child: Text(
                            item.text,
                            style: TextStyle(
                              fontSize: 12,
                              decoration: item.completed
                                  ? TextDecoration.lineThrough
                                  : null,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ),
      ],
    );
  }
}

/// strip 折叠壳（默认折叠，标题行=可点摘要）。
class _StripShell extends StatefulWidget {
  const _StripShell(
      {required this.icon,
      required this.title,
      required this.count,
      required this.child});

  final IconData icon;
  final String title;
  final int count;
  final Widget child;

  @override
  State<_StripShell> createState() => _StripShellState();
}

class _StripShellState extends State<_StripShell> {
  bool _open = false;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        InkWell(
          onTap: () => setState(() => _open = !_open),
          child: Padding(
            padding:
                const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
            child: Row(
              children: [
                Icon(widget.icon,
                    size: 14,
                    color: Theme.of(context).colorScheme.onSurfaceVariant),
                const SizedBox(width: 6),
                Text(widget.title,
                    style: TextStyle(
                        fontSize: 12,
                        color:
                            Theme.of(context).colorScheme.onSurfaceVariant)),
                const SizedBox(width: 6),
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                  decoration: BoxDecoration(
                    color: Theme.of(context)
                        .colorScheme
                        .surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Text('${widget.count}',
                      style: const TextStyle(fontSize: 11)),
                ),
                const Spacer(),
                Icon(
                    _open ? Icons.expand_less : Icons.expand_more, size: 16),
              ],
            ),
          ),
        ),
        if (_open)
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
            child: widget.child,
          ),
      ],
    );
  }
}

/// 子代理行：类型徽标 + 描述 + 状态，可展开看 result/error。
class _SubagentRow extends StatefulWidget {
  const _SubagentRow({required this.entry});

  final SubagentEntry entry;

  @override
  State<_SubagentRow> createState() => _SubagentRowState();
}

class _SubagentRowState extends State<_SubagentRow> {
  bool _open = false;

  Color _statusColor(String status, BuildContext context) {
    switch (status) {
      case 'running':
        return Theme.of(context).colorScheme.primary;
      case 'completed':
        return Theme.of(context).colorScheme.secondary;
      case 'steered':
        return Theme.of(context).colorScheme.tertiary;
      case 'error':
        return Theme.of(context).colorScheme.error;
      default:
        return Theme.of(context).colorScheme.onSurfaceVariant;
    }
  }

  String _statusLabel(String status) {
    switch (status) {
      case 'queued':
        return '排队中';
      case 'running':
        return '运行中';
      case 'completed':
        return '已完成';
      case 'steered':
        return '已接管';
      case 'aborted':
        return '已中止';
      case 'stopped':
        return '已停止';
      case 'error':
        return '出错';
      default:
        return status;
    }
  }

  @override
  Widget build(BuildContext context) {
    final e = widget.entry;
    final detail = e.error ?? e.result;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                decoration: BoxDecoration(
                  color: Theme.of(context)
                      .colorScheme
                      .primaryContainer,
                  borderRadius: BorderRadius.circular(4),
                ),
                child: Text(e.type, style: const TextStyle(fontSize: 11)),
              ),
              const SizedBox(width: 6),
              Expanded(
                child: Text(e.description,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 12)),
              ),
              Text(_statusLabel(e.status),
                  style: TextStyle(
                      fontSize: 11, color: _statusColor(e.status, context))),
              if (detail != null && detail.isNotEmpty)
                InkWell(
                  onTap: () => setState(() => _open = !_open),
                  child: Icon(_open ? Icons.expand_less : Icons.expand_more,
                      size: 16),
                ),
            ],
          ),
          if (_open && detail != null)
            Container(
              width: double.infinity,
              margin: const EdgeInsets.only(top: 4),
              padding: const EdgeInsets.all(6),
              decoration: BoxDecoration(
                color: Theme.of(context)
                    .colorScheme
                    .surfaceContainerHighest,
                borderRadius: BorderRadius.circular(4),
              ),
              child: Text(detail, style: const TextStyle(fontSize: 11)),
            ),
        ],
      ),
    );
  }
}

/// 分支家族导航条（对齐网页 WebBranchBar：家族 ≤1 不渲染，chip 点切换）。
class _BranchBar extends StatelessWidget {
  const _BranchBar(
      {required this.sessions, required this.activeId, required this.onSelect});

  final List<WebSession>? sessions;
  final String activeId;
  final ValueChanged<WebSession>? onSelect;

  @override
  Widget build(BuildContext context) {
    final all = sessions;
    final tap = onSelect;
    if (all == null || tap == null) return const SizedBox.shrink();
    final family = deriveBranchFamily(all, activeId);
    if (family.length <= 1) return const SizedBox.shrink();
    final byId = {for (final s in all) s.id: s};
    return Container(
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerLow,
        border: Border(
          bottom:
              BorderSide(color: Theme.of(context).colorScheme.outlineVariant),
        ),
      ),
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        child: Row(
          children: [
            Icon(Icons.fork_right,
                size: 16,
                color: Theme.of(context).colorScheme.onSurfaceVariant),
            const SizedBox(width: 4),
            for (var i = 0; i < family.length; i++) ...[
              if (i > 0)
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 2),
                  child: Text('›',
                      style: TextStyle(
                          color: Theme.of(context)
                              .colorScheme
                              .onSurfaceVariant)),
                ),
              Builder(builder: (_) {
                final s = byId[family[i].id] ?? family[i];
                final active = s.id == activeId;
                return ChoiceChip(
                  label: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (s.forked) const Icon(Icons.fork_right, size: 12),
                      Flexible(
                        child: Text(
                          s.title.isEmpty ? '(无标题)' : s.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ],
                  ),
                  selected: active,
                  onSelected: (_) {
                    if (!active) tap(s);
                  },
                  visualDensity: VisualDensity.compact,
                );
              }),
            ],
          ],
        ),
      ),
    );
  }
}

/// 用户消息 inline 编辑行（对齐网页 WebUserBubble：Ctrl+Enter 保存，Esc 取消）。
class _EditRow extends StatelessWidget {
  const _EditRow(
      {super.key, required this.controller, required this.onSave, required this.onCancel});

  final TextEditingController controller;
  final VoidCallback onSave;
  final VoidCallback onCancel;

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: Alignment.centerRight,
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.primaryContainer,
          borderRadius: BorderRadius.circular(16),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            ConstrainedBox(
              constraints: const BoxConstraints(minHeight: 60),
              child: TextField(
                controller: controller,
                minLines: 2,
                maxLines: 6,
                autofocus: true,
                decoration: const InputDecoration(
                  border: InputBorder.none,
                  hintText: '编辑消息',
                ),
              ),
            ),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextButton(onPressed: onCancel, child: const Text('取消')),
                FilledButton(onPressed: onSave, child: const Text('保存')),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// 提示词库 BottomSheet（对齐网页 WebPromptPicker：搜索防抖 + 分类 + 点条回填）。
class _PromptSheet extends StatefulWidget {
  const _PromptSheet({required this.api});

  final ApiClient api;

  @override
  State<_PromptSheet> createState() => _PromptSheetState();
}

class _PromptSheetState extends State<_PromptSheet> {
  final _search = TextEditingController();
  Timer? _debounce;
  PromptList? _list;
  String? _category;
  bool _loading = true;
  String? _error;
  String? _inserting;
  // 点条目代际：只认最后一次点击的回包。
  int _insertSeq = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _search.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final list = await widget.api.fetchPrompts(
        search: _search.text.trim().isEmpty ? null : _search.text.trim(),
        category: _category,
      );
      if (!mounted) return;
      setState(() {
        _list = list;
        _loading = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.message;
        _loading = false;
      });
    }
  }

  void _onSearchChanged(String v) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 250), _load);
  }

  Future<void> _insert(PromptItem item) async {
    // 代际守卫：点第二条时第一条还没回，回来只认最后一次点击（不对错条目）。
    final stamp = _insertSeq++;
    setState(() => _inserting = item.slug);
    try {
      final content =
          await widget.api.fetchPromptContent(item.slug, item.category);
      if (!mounted || stamp != _insertSeq - 1) return;
      Navigator.pop(context, content);
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.message;
        _inserting = null;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final list = _list;
    return SafeArea(
      child: Padding(
        padding:
            EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Padding(
              padding: EdgeInsets.all(12),
              child: Text('提示词库',
                  style: TextStyle(fontWeight: FontWeight.bold)),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
              child: TextField(
                controller: _search,
                decoration: const InputDecoration(
                  hintText: '搜索提示词',
                  prefixIcon: Icon(Icons.search),
                  border: OutlineInputBorder(),
                  isDense: true,
                ),
                onChanged: _onSearchChanged,
              ),
            ),
            if (list != null && list.categories.isNotEmpty)
              SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
                child: Row(
                  children: [
                    ChoiceChip(
                      label: const Text('全部'),
                      selected: _category == null,
                      onSelected: (_) {
                        _category = null;
                        _load();
                      },
                    ),
                    for (final c in list.categories)
                      Padding(
                        padding: const EdgeInsets.only(left: 6),
                        child: ChoiceChip(
                          label: Text(c.name),
                          selected: _category == c.slug,
                          onSelected: (_) {
                            _category = c.slug;
                            _load();
                          },
                        ),
                      ),
                  ],
                ),
              ),
            Flexible(
              child: _loading && list == null
                  ? const Padding(
                      padding: EdgeInsets.all(24),
                      child: CircularProgressIndicator(),
                    )
                  : _error != null && list == null
                      ? Padding(
                          padding: const EdgeInsets.all(24),
                          child: Text(_error!,
                              style: TextStyle(
                                  color:
                                      Theme.of(context).colorScheme.error)),
                        )
                      : ListView.builder(
                          shrinkWrap: true,
                          itemCount: list?.prompts.length ?? 0,
                          itemBuilder: (_, i) {
                            final item = list!.prompts[i];
                            final busy = _inserting == item.slug;
                            return ListTile(
                              title: Text(item.title,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis),
                              subtitle: item.description.isEmpty
                                  ? null
                                  : Text(item.description,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis),
                              trailing: busy
                                  ? const SizedBox(
                                      width: 16,
                                      height: 16,
                                      child: CircularProgressIndicator(
                                          strokeWidth: 2),
                                    )
                                  : null,
                              onTap: busy ? null : () => _insert(item),
                            );
                          },
                        ),
            ),
          ],
        ),
      ),
    );
  }
}

/// runtime 命令 target 三元组。
typedef WebRuntimeTarget = ({
  String sessionId,
  String agentId,
  int generation,
});

class _Composer extends StatelessWidget {
  const _Composer({
    required this.controller,
    required this.streaming,
    required this.showJump,
    required this.onJumpToBottom,
    required this.modelLabel,
    required this.thinkingLevel,
    required this.planMode,
    required this.onPlanToggle,
    required this.attachments,
    required this.attaching,
    required this.onPickImages,
    required this.onRemoveImage,
    required this.onPromptTap,
    required this.onSend,
    required this.onStop,
    required this.onModelTap,
    required this.onThinkingTap,
    required this.onChanged,
  });

  final TextEditingController controller;
  final bool streaming;
  // 回到底部箭头：离底部>160px 才亮（_trackScroll 控制），收进输入框
  // 上行、跟发送键并排，不再悬浮压住下行的计划 chip。
  final bool showJump;
  final VoidCallback onJumpToBottom;
  final String modelLabel;
  final String? thinkingLevel;
  final bool planMode;
  final VoidCallback onPlanToggle;
  final List<String> attachments;
  final bool attaching;
  final VoidCallback onPickImages;
  final void Function(int index) onRemoveImage;
  final VoidCallback onPromptTap;
  final VoidCallback onSend;
  final VoidCallback onStop;
  final VoidCallback onModelTap;
  final VoidCallback onThinkingTap;
  // 输入变化回调用（空态禁用发送键：父级 setState 重建）。
  final VoidCallback onChanged;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 4, 12, 8),
        child: Container(
          decoration: BoxDecoration(
            color: scheme.surfaceContainerHighest,
            borderRadius: BorderRadius.circular(20),
            // 与内容区分：描边 + 上方投影（深浅色都跟主题走）。
            border: Border.all(color: scheme.outlineVariant),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.35),
                blurRadius: 12,
                offset: const Offset(0, -2),
              ),
            ],
          ),
          padding: const EdgeInsets.fromLTRB(12, 4, 8, 8),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // 附件缩略条（对齐网页 composer 顶部预览：横滑单行，不撑高输入区）。
              if (attachments.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: Row(
                      children: [
                        for (var i = 0; i < attachments.length; i++)
                          Padding(
                            padding: EdgeInsets.only(
                                right: i == attachments.length - 1 ? 0 : 8),
                            // 删除钮定位在缩略图右上（-8,-8）：横滑行高 56+8
                            // 刚好吃掉上溢，列内 Clip.none 即可。
                            child: Stack(
                              clipBehavior: Clip.none,
                              children: [
                                ClipRRect(
                                  borderRadius: BorderRadius.circular(8),
                                  child: _DataImage(
                                    dataUrl: attachments[i],
                                    thumb: true,
                                  ),
                                ),
                                Positioned(
                                  top: -8,
                                  right: -8,
                                  child: InkWell(
                                    onTap: () => onRemoveImage(i),
                                    child: Container(
                                      decoration: BoxDecoration(
                                        color: scheme.surface,
                                        shape: BoxShape.circle,
                                        border: Border.all(
                                            color: scheme.outline),
                                      ),
                                      child:
                                          const Icon(Icons.close, size: 14),
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              TextField(
                controller: controller,
                // 对齐网页 min-h 7rem：起步3行高度，窄屏不瘪。
                minLines: 3,
                maxLines: 5,
                textInputAction: TextInputAction.send,
                // 空态/忙时 onSend 内部守（发送键同时禁用，双保险）。
                onSubmitted: (_) => onSend(),
                onChanged: (_) => onChanged(),
                decoration: InputDecoration(
                  hintText:
                      planMode ? '描述需求，先出计划（只读分析）' : '发送消息到当前会话',
                  border: InputBorder.none,
                ),
              ),
              // 工具区双行（对齐例图）：上行左图库+提示词、右发送；
              // 下行左思考+模型横滑、右计划。
              Row(
                children: [
                  // 图片附件。
                  IconButton(
                    onPressed: attaching || attachments.length >= 4
                        ? null
                        : onPickImages,
                    icon: attaching
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child:
                                CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.image_outlined),
                    tooltip: '添加图片',
                    visualDensity: VisualDensity.compact,
                  ),
                  // 提示词库。
                  IconButton(
                    onPressed: onPromptTap,
                    icon: const Icon(Icons.auto_awesome_outlined),
                    tooltip: '提示词库',
                    visualDensity: VisualDensity.compact,
                  ),
                  const Spacer(),
                  // 回到底部：只在离底部>160px 时出现，点后 300ms 平滑滚到底。
                  // 跟发送键并排，中间留 12px，不贴太近。
                  AnimatedScale(
                    scale: showJump ? 1.0 : 0.0,
                    duration: const Duration(milliseconds: 200),
                    child: showJump
                        ? IconButton(
                            onPressed: onJumpToBottom,
                            icon: const Icon(Icons.arrow_downward),
                            tooltip: '回到底部',
                            visualDensity: VisualDensity.compact,
                          )
                        : const SizedBox.shrink(),
                  ),
                  if (showJump) const SizedBox(width: 12),
                  // 发送键动效：流式↔空闲切换图标时做缩放过渡（发送/中断不跳变）。
                  IconButton.filled(
                    onPressed: streaming
                        ? onStop
                        : (attaching ||
                                (controller.text.trim().isEmpty &&
                                    attachments.isEmpty)
                            ? null
                            : onSend),
                    icon: AnimatedSwitcher(
                      duration: const Duration(milliseconds: 200),
                      transitionBuilder: (child, anim) => ScaleTransition(
                        scale: anim,
                        child: child,
                      ),
                      child: Icon(
                        streaming ? Icons.stop : Icons.send,
                        key: ValueKey(streaming ? 'stop' : 'send'),
                      ),
                    ),
                    tooltip: streaming ? '中断' : '发送',
                  ),
                ],
              ),
              // 下行：思考+模型左 Flexible 横滑（只压模型 chip，不挤计划）。
              Row(
                children: [
                  // 思考档位（对齐网页 WebThinkingSelector，与模型独立）。
                  ActionChip(
                    label: Text(
                      thinkingLevel ?? 'off',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    avatar: const Icon(Icons.psychology_outlined, size: 16),
                    onPressed: onThinkingTap,
                    visualDensity: VisualDensity.compact,
                  ),
                  const SizedBox(width: 8),
                  Flexible(
                    child: ActionChip(
                      label: Text(
                        // 下行三 chip 同行：模型名超 12 字截断（例图语义），
                        // 不横滑、不挤计划 chip。
                        modelLabel.length > 12
                            ? '${modelLabel.substring(0, 12)}…'
                            : modelLabel,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      avatar:
                          const Icon(Icons.smart_toy_outlined, size: 16),
                      onPressed: onModelTap,
                      visualDensity: VisualDensity.compact,
                    ),
                  ),
                  const SizedBox(width: 8),
                  // 计划模式（一次性提交流）。
                  FilterChip(
                    label: const Text('计划'),
                    avatar: const Icon(Icons.list_alt_outlined, size: 16),
                    selected: planMode,
                    onSelected: (_) => onPlanToggle(),
                    visualDensity: VisualDensity.compact,
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 模型选择结果：点模型行只选中（不出表），点档位 chip 或按确定才回填。
/// [model] 为 null 表示只切档位、模型保持当前。
class _ModelPick {
  const _ModelPick({this.model, this.level});
  final AvailableModel? model;
  final String? level;
}

class _ModelSheet extends StatefulWidget {
  const _ModelSheet({
    required this.models,
    required this.current,
    required this.currentLevel,
  });

  final List<AvailableModel> models;
  final AvailableModel? current;
  final String? currentLevel;

  @override
  State<_ModelSheet> createState() => _ModelSheetState();
}

class _ModelSheetState extends State<_ModelSheet> {
  var _query = '';
  AvailableModel? _sel;

  @override
  void initState() {
    super.initState();
    _sel = widget.current;
  }

  /// 是否同一模型（_sel 可能是轮询回填的瘦对象，只比对 id+provider）。
  bool _same(AvailableModel? a, AvailableModel? b) =>
      a != null && b != null && a.id == b.id && a.provider == b.provider;

  @override
  Widget build(BuildContext context) {
    final q = _query.trim().toLowerCase();
    final models = q.isEmpty
        ? widget.models
        : widget.models
            .where((m) =>
                m.display.toLowerCase().contains(q) ||
                m.id.toLowerCase().contains(q) ||
                m.provider.toLowerCase().contains(q))
            .toList();
    // 选中态回贴到本次 fetch 的完整条目（轮询回填的 _model 是瘦对象、无 levels）。
    var sel = _sel;
    final s = sel;
    if (s != null) {
      for (final m in widget.models) {
        if (m.id == s.id && m.provider == s.provider) {
          sel = m;
          break;
        }
      }
    }
    final levels = sel?.levels ?? const <String>[];
    final isCurrent = _same(sel, widget.current);
    return SafeArea(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Padding(
            padding: EdgeInsets.all(12),
            child: Text('选择模型',
                style: TextStyle(fontWeight: FontWeight.bold)),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: TextField(
              decoration: const InputDecoration(
                hintText: '搜索提供商或模型',
                prefixIcon: Icon(Icons.search),
                border: OutlineInputBorder(),
                isDense: true,
              ),
              onChanged: (v) => setState(() => _query = v),
            ),
          ),
          if (levels.isNotEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
              child: Wrap(
                spacing: 8,
                runSpacing: 4,
                children: [
                  for (final l in levels)
                    ChoiceChip(
                      label: Text(l),
                      selected: isCurrent && l == widget.currentLevel,
                      visualDensity: VisualDensity.compact,
                      // 看中别的模型时顺手点档位：一次把模型+档位都带回去。
                      onSelected: (_) => Navigator.pop(
                        context,
                        _ModelPick(
                          model: isCurrent ? null : sel,
                          level: l,
                        ),
                      ),
                    ),
                ],
              ),
            ),
          Flexible(
            child: ListView.builder(
              shrinkWrap: true,
              itemCount: models.length,
              itemBuilder: (_, i) {
                final m = models[i];
                final selected = _same(m, sel);
                final sub = [
                  m.provider,
                  if (m.contextLabel.isNotEmpty) m.contextLabel,
                  if (m.reasoning == true) '推理',
                  if (m.images == true) '图片',
                ].join(' · ');
                return ListTile(
                  title: Text(m.display,
                      maxLines: 1, overflow: TextOverflow.ellipsis),
                  subtitle: Text(sub,
                      maxLines: 1, overflow: TextOverflow.ellipsis),
                  trailing: selected ? const Icon(Icons.check) : null,
                  // 只选中、当场展开该模型档位，不关表。
                  onTap: () => setState(() => _sel = m),
                );
              },
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
            child: SizedBox(
              width: double.infinity,
              child: FilledButton(
                // 与当前一致时禁用：避免无意义重下发；想走人直接下滑关闭。
                onPressed: _sel == null || _same(_sel, widget.current)
                    ? null
                    : () => Navigator.pop(
                          context,
                          _ModelPick(model: sel),
                        ),
                child: const Text('确定'),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _MessageRow extends StatelessWidget {
  const _MessageRow({super.key, required this.message, this.live = false});

  final UiMessage message;
  /// 是否为正在流式的消息（思考/工具显示进行态）。
  final bool live;

  @override
  Widget build(BuildContext context) {
    if (message.isUser) {
      return Align(
        alignment: Alignment.centerRight,
        child: Container(
          margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.primaryContainer,
            borderRadius: BorderRadius.circular(16),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              // 气泡字显式跟随 onPrimaryContainer：亮蓝底配白字、薰衣草底配深字。
              for (var i = 0; i < message.parts.length; i++)
                DefaultTextStyle.merge(
                  style: TextStyle(
                    color:
                        Theme.of(context).colorScheme.onPrimaryContainer,
                  ),
                  child: _UserPart(
                      key: ValueKey('${message.id}:$i'),
                      part: message.parts[i]),
                ),
            ],
          ),
        ),
      );
    }
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 连续同名工具调用合并为一组卡，其余 part 逐个渲染。
          for (final (ri, run) in groupToolRuns(message.parts).indexed)
            if (run.length > 1 && run.first is ToolPart)
              ToolGroup(
                key: ValueKey('${message.id}:$ri'),
                toolName: (run.first as ToolPart).toolName,
                parts: run.cast<ToolPart>(),
              )
            else
              for (var i = 0; i < run.length; i++)
                _AssistantPart(
                    key: ValueKey('${message.id}:$ri:$i'),
                    part: run[i],
                    live: live),
        ],
      ),
    );
  }
}

class _UserPart extends StatelessWidget {
  const _UserPart({super.key, required this.part});

  final UiPart part;

  @override
  Widget build(BuildContext context) {
    return switch (part) {
      TextPart(text: final t) => SelectableText(t),
      FilePart(dataUrl: final u) => Padding(
          padding: const EdgeInsets.only(top: 6),
          child: _DataImage(dataUrl: u),
        ),
      _ => const SizedBox.shrink(),
    };
  }
}

class _AssistantPart extends StatelessWidget {
  const _AssistantPart({super.key, required this.part, this.live = false});

  final UiPart part;
  final bool live;

  @override
  Widget build(BuildContext context) {
    return switch (part) {
      TextPart(text: final t) => MarkdownBody(data: t, selectable: true),
      ReasoningPart(text: final t) => ExpansionTile(
          leading: const Icon(Icons.psychology_outlined, size: 18),
          title: Row(
            children: [
              if (live)
                const Padding(
                  padding: EdgeInsets.only(right: 8),
                  child: SizedBox(
                    width: 14,
                    height: 14,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                ),
              const Text('思考过程', style: TextStyle(fontSize: 13)),
            ],
          ),
          dense: true,
          children: [MarkdownBody(data: t, selectable: true)],
        ),
      ToolPart() => ToolCard(part: part as ToolPart),
      FilePart(dataUrl: final u) => _DataImage(dataUrl: u),
    };
  }
}

class _DataImage extends StatelessWidget {
  const _DataImage({required this.dataUrl, this.thumb = false});

  final String dataUrl;
  final bool thumb;

  @override
  Widget build(BuildContext context) {
    try {
      final base64 = dataUrl.split(',').last;
      // 缩略图对齐网页 object-cover 裁满（contain 留白边不好点删）。
      final image = Image.memory(base64Decode(base64), fit: BoxFit.cover);
      if (thumb) {
        return SizedBox(width: 56, height: 56, child: image);
      }
      return ConstrainedBox(
        constraints: const BoxConstraints(maxHeight: 200),
        child: Image.memory(base64Decode(base64), fit: BoxFit.contain),
      );
    } catch (_) {
      return const Text('[图片解码失败]');
    }
  }
}
