import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../pairing_store.dart';
import 'chat_page.dart';
import 'sessions_drawer.dart';

/// 抽屉壳：左抽屉=分组会话导航，正文=当前聊天。
/// 对齐 OpenChamber：进场即聊天（冷启动直开上次会话），返回键不退出。
class ShellPage extends StatefulWidget {
  const ShellPage({
    super.key,
    required this.pairing,
    required this.isDark,
    required this.onToggleTheme,
  });

  final Pairing pairing;
  final bool isDark;
  final VoidCallback onToggleTheme;

  @override
  State<ShellPage> createState() => _ShellPageState();
}

class _ShellPageState extends State<ShellPage> {
  final _scaffoldKey = GlobalKey<ScaffoldState>();
  late final ApiClient _api = ApiClient(widget.pairing);
  WebState? _state;
  String? _error;
  bool _loading = true;
  String? _selectedId;
  // 首帧进入决策只做一次（之后只处理“选中会话被删”的回落）。
  bool _entered = false;
  Timer? _poller;
  // 轮询串行守卫：上轮未落定不叠发（弱网下 Timer 触发会堆请求）。
  bool _refreshing = false;

  @override
  void initState() {
    super.initState();
    _refresh();
    // 沿用列表页 5s 节奏，省电。
    _poller = Timer.periodic(const Duration(seconds: 5), (_) => _refresh());
  }

  @override
  void dispose() {
    _poller?.cancel();
    _api.close();
    super.dispose();
  }

  /// 菜单互斥：复制/重命名/删除等写操作进行时，5s 轮询跳过，
  /// 不在中途插一帧旧数据（写完统一 _refresh 收敛，只闪一下）。
  bool _muted = false;

  Future<void> _refresh() async {
    if (_refreshing || _muted) return;
    _refreshing = true;
    try {
      final state = await _api.fetchState();
      if (!mounted) return;
      setState(() {
        _state = state;
        _error = null;
        _loading = false;
      });
      await _enterOnce(state);
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    } catch (e) {
      // 非 ApiException（JSON 形变/类型错）也收敛为错误态，不抛红屏。
      if (!mounted) return;
      setState(() {
        _error = '状态解析失败：$e';
        _loading = false;
      });
    } finally {
      _refreshing = false;
    }
  }

  /// 首帧进入决策：上次会话仍在则直开聊天，否则打开抽屉让用户选。
  Future<void> _enterOnce(WebState state) async {
    if (_entered) {
      if (_selectedId != null &&
          state.sessions.every((s) => s.id != _selectedId)) {
        setState(() => _selectedId = null);
      }
      return;
    }
    _entered = true;
    final saved = await PairingStore.loadLastSessionId();
    if (!mounted) return;
    if (saved != null && state.sessions.any((s) => s.id == saved)) {
      setState(() => _selectedId = saved);
      return;
    }
    _openDrawer();
  }

  void _openDrawer() => _scaffoldKey.currentState?.openDrawer();

  void _closeDrawerIfOpen() {
    if (_scaffoldKey.currentState?.isDrawerOpen ?? false) {
      Navigator.of(context).pop();
    }
  }

  Future<void> _select(WebSession s) async {
    await PairingStore.saveLastSessionId(s.id);
    if (!mounted) return;
    setState(() => _selectedId = s.id);
    _closeDrawerIfOpen();
  }

  Future<void> _newSession() async {
    final state = _state;
    if (state == null) return;
    final id = await showDialog<String>(
      context: context,
      builder: (_) => NewSessionDialog(api: _api, projects: state.projects),
    );
    if (id == null) return;
    await _refresh();
    if (!mounted) return;
    await PairingStore.saveLastSessionId(id);
    if (!mounted) return;
    setState(() => _selectedId = id);
    _closeDrawerIfOpen();
  }

  /// 重命名：弹输入框，catalog update 直写（对齐网页 renameDraft/confirmRename）。
  Future<void> _renameSession(WebSession s) async {
    final controller = TextEditingController(text: s.title);
    final title = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('重命名会话'),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: const InputDecoration(hintText: '新标题'),
          onSubmitted: (_) =>
              Navigator.of(ctx).pop(controller.text.trim()),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('取消')),
          FilledButton(
              onPressed: () =>
                  Navigator.of(ctx).pop(controller.text.trim()),
              child: const Text('确定')),
        ],
      ),
    );
    controller.dispose();
    if (title == null || title.isEmpty || !mounted) return;
    _muted = true;
    try {
      await _api.renameSession(s.id, title);
      await _refresh();
    } on ApiException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('重命名失败：$e')));
    } finally {
      _muted = false;
    }
  }

  /// 复制：浅拷贝后刷新并切到新会话（对齐网页 duplicate 分支）。
  Future<void> _copySession(WebSession s) async {
    _muted = true;
    try {
      final newId = await _api.copySession(s.id);
      await _refresh();
      if (!mounted) return;
      if (newId != null) {
        await PairingStore.saveLastSessionId(newId);
        if (!mounted) return;
        setState(() => _selectedId = newId);
        _closeDrawerIfOpen();
      }
    } on ApiException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('复制失败：$e')));
    } finally {
      _muted = false;
    }
  }

  /// 导出：拉 HTML 字节流，落系统临时后走系统分享（对齐网页下载行为；
  /// systemTemp 是 APP 私有区，用户文件管理器看不见，只 SnackBar 报路径=没导出）。
  Future<void> _exportSession(WebSession s) async {
    try {
      final bytes = await _api.exportSessionHtml(s.id);
      final title =
          s.title.isEmpty ? s.id : s.title.replaceAll(RegExp(r'[^\w\-]+'), '_');
      final path = '${Directory.systemTemp.path}/pideck-$title.html';
      final file = await File(path).writeAsBytes(bytes);
      if (!mounted) return;
      await SharePlus.instance.share(ShareParams(
        files: [XFile(file.path, mimeType: 'text/html', name: 'pideck-$title.html')],
        text: 'PiDeck 会话导出：${s.title.isEmpty ? s.id : s.title}',
      ));
    } on ApiException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('导出失败：$e')));
    }
  }

  /// 删除：二次确认，被删的是当前会话则回落到空态（对齐网页 deleteConfirmId）。
  Future<void> _confirmDeleteSession(WebSession s) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除会话？'),
        content: Text('“${s.title.isEmpty ? s.id : s.title}” 将被删除（含会话文件），无法恢复。'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('删除')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    _muted = true;
    try {
      await _api.deleteSession(s.id);
      // 已删会话的输入草稿同步丢弃，不留孤儿；
      // last_session 若指着它也清掉，下次冷启动不绕路。
      ChatDraftBox.dropFor(s.id);
      if ((await PairingStore.loadLastSessionId()) == s.id) {
        await PairingStore.clearLastSessionId();
      }
      await _refresh();
    } on ApiException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('删除失败：$e')));
    } finally {
      _muted = false;
    }
  }

  Future<void> _disconnect() async {
    // 误触代价高（配对+上次会话全清）：二次确认。
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('断开配对？'),
        content: const Text('将清除配对信息并回到扫码页，需要重新扫码。'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('断开')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    await PairingStore.clear();
    if (!mounted) return;
    Navigator.of(context).pushNamedAndRemoveUntil('/pair', (_) => false);
  }

  @override
  Widget build(BuildContext context) {
    final state = _state;
    WebSession? selected;
    if (state != null && _selectedId != null) {
      for (final s in state.sessions) {
        if (s.id == _selectedId) {
          selected = s;
          break;
        }
      }
    }
    final sel = selected;
    // 返回键：抽屉开着先关抽屉（放行系统返回），关着才拦截（聊天即首页不退出）。
    final drawerOpen = _scaffoldKey.currentState?.isDrawerOpen ?? false;
    return PopScope(
      canPop: drawerOpen,
      child: Scaffold(
        key: _scaffoldKey,
        drawer: Drawer(
          child: SessionsDrawer(
            state: state,
            loading: _loading,
            error: _error,
            selectedId: _selectedId,
            onSelect: _select,
            onNewSession: _newSession,
            onRename: _renameSession,
            onCopy: _copySession,
            onExport: _exportSession,
            onDelete: _confirmDeleteSession,
            onDisconnect: _disconnect,
            onRefresh: _refresh,
            isDark: widget.isDark,
            onToggleTheme: widget.onToggleTheme,
          ),
        ),
        body: sel == null
            ? SafeArea(
                child: Center(
                  child: _loading
                      ? const CircularProgressIndicator()
                      : _error != null
                          ? Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Text(_error!),
                                const SizedBox(height: 12),
                                Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    FilledButton.tonal(
                                      onPressed: _openDrawer,
                                      child: const Text('选择会话'),
                                    ),
                                    const SizedBox(width: 8),
                                    FilledButton(
                                      onPressed: _refresh,
                                      child: const Text('重试'),
                                    ),
                                  ],
                                ),
                              ],
                            )
                          : FilledButton.tonal(
                              onPressed: _openDrawer,
                              child: const Text('选择会话'),
                            ),
                ),
              )
            : ChatPage(
                key: ValueKey(sel.id),
                api: _api,
                session: sel,
                onOpenDrawer: _openDrawer,
                sessions: state?.sessions,
                onSelectSession: _select,
                projects: state?.projects,
              ),
      ),
    );
  }
}
