import 'package:flutter/material.dart';

import '../api/api_client.dart';
import '../api/models.dart';

/// 桌面端内建聊天工程（对齐 ProjectStore CHAT_PROJECT_ID/CHAT_PROJECT_NAME，
/// 聊天会话单独成组并置顶）。
const chatProjectId = 'builtin-chat';
const chatProjectName = 'Chat';

/// 会话分组：聊天组置顶，其余按项目（组内按 updatedAt 倒序）。
class SessionGroup {
  const SessionGroup({
    required this.id,
    required this.title,
    required this.isChat,
    required this.sessions,
  });

  final String id;
  final String title;
  final bool isChat;
  final List<WebSession> sessions;
}

/// 分组纯函数（便于单测）：聊天组恒置顶，其余组按组内最新活跃倒序。
List<SessionGroup> groupSessions(
  List<WebSession> sessions,
  Map<String, String> projectName,
) {
  final chat = <WebSession>[];
  final byProject = <String, List<WebSession>>{};
  for (final s in sessions) {
    // 只认内建 id：真实项目取名叫 "Chat" 也不得吸入聊天组。
    if (s.projectId == chatProjectId) {
      chat.add(s);
      continue;
    }
    byProject.putIfAbsent(s.projectId, () => []).add(s);
  }
  int byUpdated(WebSession a, WebSession b) =>
      (b.updatedAt ?? 0).compareTo(a.updatedAt ?? 0);
  chat.sort(byUpdated);
  final rest = byProject.entries.map((e) {
    final list = e.value..sort(byUpdated);
    final name = projectName[e.key];
    // 未知项目各自成组但标题同名：带 id 后缀区分。
    final title = (name == null || name.isEmpty) ? '(未分组 ${e.key})' : name;
    return SessionGroup(
      id: e.key,
      title: title,
      isChat: false,
      sessions: list,
    );
  }).toList()
    ..sort((a, b) => (b.sessions.first.updatedAt ?? 0)
        .compareTo(a.sessions.first.updatedAt ?? 0));
  return [
    SessionGroup(
      id: chatProjectId,
      title: chatProjectName,
      isChat: true,
      sessions: chat,
    ),
    ...rest,
  ];
}

/// 左侧会话导航抽屉（对齐 OpenChamber 会话抽屉）：聊天组置顶 + 按项目分组。
/// 数据由抽屉壳轮询持有，本组件只做展示与回调。
class SessionsDrawer extends StatefulWidget {
  const SessionsDrawer({
    super.key,
    required this.state,
    required this.loading,
    this.error,
    required this.selectedId,
    required this.onSelect,
    required this.onNewSession,
    required this.onRename,
    required this.onCopy,
    required this.onExport,
    required this.onDelete,
    required this.onDisconnect,
    required this.onRefresh,
    required this.isDark,
    required this.onToggleTheme,
  });

  final WebState? state;
  final bool loading;
  final String? error;
  final String? selectedId;
  final ValueChanged<WebSession> onSelect;
  final VoidCallback onNewSession;
  final ValueChanged<WebSession> onRename;
  final ValueChanged<WebSession> onCopy;
  final ValueChanged<WebSession> onExport;
  final ValueChanged<WebSession> onDelete;
  final VoidCallback onDisconnect;
  final VoidCallback onRefresh;
  final bool isDark;
  final VoidCallback onToggleTheme;

  @override
  State<SessionsDrawer> createState() => _SessionsDrawerState();
}

class _SessionsDrawerState extends State<SessionsDrawer> {
  String _query = '';

  /// 会话行操作（对齐网页 runSessionAction：重命名/复制/导出/删除确认）。
  Future<void> _sessionMenu(BuildContext context, WebSession s) async {
    final action = await showModalBottomSheet<String>(
      context: context,
      builder: (_) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.edit_outlined),
              title: const Text('重命名'),
              onTap: () => Navigator.pop(context, 'rename'),
            ),
            ListTile(
              leading: const Icon(Icons.copy_outlined),
              title: const Text('复制'),
              onTap: () => Navigator.pop(context, 'copy'),
            ),
            ListTile(
              leading: const Icon(Icons.share_outlined),
              title: const Text('导出 HTML'),
              onTap: () => Navigator.pop(context, 'export'),
            ),
            ListTile(
              leading: Icon(Icons.delete_outline,
                  color: Theme.of(context).colorScheme.error),
              title: Text('删除',
                  style: TextStyle(
                      color: Theme.of(context).colorScheme.error)),
              onTap: () => Navigator.pop(context, 'delete'),
            ),
          ],
        ),
      ),
    );
    if (!mounted || action == null) return;
    switch (action) {
      case 'rename':
        widget.onRename(s);
      case 'copy':
        widget.onCopy(s);
      case 'export':
        widget.onExport(s);
      case 'delete':
        widget.onDelete(s);
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = widget.state;
    final projectName = {
      for (final p in state?.projects ?? <WebProject>[]) p.id: p.name,
    };
    final q = _query.trim().toLowerCase();
    final sessions = [...?state?.sessions];
    if (q.isNotEmpty) {
      sessions.retainWhere((s) {
        final title = s.title.isEmpty ? '(无标题)' : s.title;
        return title.toLowerCase().contains(q) ||
            s.id.toLowerCase().contains(q) ||
            (projectName[s.projectId] ?? '').toLowerCase().contains(q);
      });
    }
    final groups = groupSessions(sessions, projectName)
        // 空 Chat 组不渲染（恒置顶的空组占一行，误导以为有会话）。
        .where((g) => g.sessions.isNotEmpty)
        .toList();

    return SafeArea(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 8, 0),
            child: Row(
              children: [
                const Expanded(
                  child: Text('会话',
                      style:
                          TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                ),
                IconButton(
                  icon: const Icon(Icons.refresh),
                  tooltip: '刷新',
                  onPressed: widget.onRefresh,
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 4, 12, 0),
            child: TextField(
              decoration: const InputDecoration(
                hintText: '搜索会话',
                prefixIcon: Icon(Icons.search),
                border: OutlineInputBorder(),
                isDense: true,
              ),
              onChanged: (v) => setState(() => _query = v),
            ),
          ),
          Expanded(
            child: widget.loading
                ? const Center(child: CircularProgressIndicator())
                : widget.error != null && state == null
                    ? ListView(
                        children: [
                          Padding(
                            padding: const EdgeInsets.all(24),
                            child: Column(
                              children: [
                                Text(widget.error!),
                                const SizedBox(height: 12),
                                FilledButton(
                                  onPressed: widget.onRefresh,
                                  child: const Text('重试'),
                                ),
                              ],
                            ),
                          ),
                        ],
                      )
                    : ListView.builder(
                        itemCount: groups.length,
                        itemBuilder: (_, gi) {
                          final g = groups[gi];
                          return ExpansionTile(
                            key: PageStorageKey('group-${g.id}'),
                            initiallyExpanded:
                                g.isChat || q.isNotEmpty,
                            leading: Icon(g.isChat
                                ? Icons.chat_bubble_outline
                                : Icons.folder_outlined),
                            title: Text(g.title),
                            subtitle: g.sessions.isEmpty
                                ? null
                                : Text('${g.sessions.length} 个会话'),
                            children: [
                              if (g.sessions.isEmpty)
                                const ListTile(
                                  dense: true,
                                  title: Text('暂无会话，可新建一个',
                                      style: TextStyle(fontSize: 13)),
                                ),
                              for (final s in g.sessions)
                                Builder(builder: (_) {
                                  final busy = state?.runtimeOf(s.id)?.isBusy ??
                                      false;
                                  return ListTile(
                                    dense: true,
                                    selected: s.id == widget.selectedId,
                                    leading: Icon(
                                      s.forked
                                          ? Icons.fork_right
                                          : Icons.chat_bubble_outline,
                                      color: busy
                                          ? Theme.of(context)
                                              .colorScheme
                                              .primary
                                          : null,
                                    ),
                                    title: Text(
                                      s.title.isEmpty ? '(无标题)' : s.title,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                    ),
                                    subtitle:
                                        busy ? const Text('运行中') : null,
                                    trailing: busy
                                        // 运行中只给转圈：重命名/复制/删除会打断 pi，
                                        // 等 idle 再操作。
                                        ? const SizedBox(
                                            width: 16,
                                            height: 16,
                                            child: CircularProgressIndicator(
                                                strokeWidth: 2),
                                          )
                                        : IconButton(
                                            icon: const Icon(Icons.more_vert,
                                                size: 18),
                                            tooltip: '会话操作',
                                            visualDensity:
                                                VisualDensity.compact,
                                            onPressed: () =>
                                                _sessionMenu(context, s),
                                          ),
                                    onTap: () => widget.onSelect(s),
                                    // 运行中长按也不弹菜单（同转圈逻辑）。
                                    onLongPress:
                                        busy ? null : () => _sessionMenu(context, s),
                                  );
                                }),
                            ],
                          );
                        },
                      ),
          ),
          const Divider(height: 1),
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
            child: FilledButton.tonal(
              onPressed: state == null ? null : widget.onNewSession,
              child: const Text('+ 新会话'),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 4, 4),
            child: Row(
              children: [
                Expanded(
                  child: TextButton(
                    onPressed: widget.onDisconnect,
                    child: const Text('断开配对'),
                  ),
                ),
                IconButton(
                  tooltip: '切换浅色/深色',
                  icon: Icon(widget.isDark
                      ? Icons.light_mode_outlined
                      : Icons.dark_mode_outlined),
                  onPressed: widget.onToggleTheme,
                ),
              ],
            ),
          ),
          const SizedBox(height: 4),
        ],
      ),
    );
  }
}

/// 新建会话对话框：选项目（无项目时输入路径注册）+ 可选标题。
/// 成功返回新建会话 id，取消返回 null。
class NewSessionDialog extends StatefulWidget {
  const NewSessionDialog({super.key, required this.api, required this.projects});

  final ApiClient api;
  final List<WebProject> projects;

  @override
  State<NewSessionDialog> createState() => _NewSessionDialogState();
}

class _NewSessionDialogState extends State<NewSessionDialog> {
  String? _projectId;
  final _pathController = TextEditingController();
  final _titleController = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    // 单项目自动选中；多项目强制手选（轮询顺序一变默认跟着变，易误建）。
    if (widget.projects.length == 1) _projectId = widget.projects.first.id;
  }

  @override
  void dispose() {
    _pathController.dispose();
    _titleController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      var projectId = _projectId;
      if (projectId == null) {
        if (widget.projects.isNotEmpty) throw ApiException('请先选择项目');
        final path = _pathController.text.trim();
        if (path.isEmpty) throw ApiException('请先输入项目路径');
        projectId = await widget.api.createProject(path);
      }
      final id = await widget.api.createSession(
        projectId,
        preferences: _titleController.text.trim().isEmpty
            ? null
            : {'title': _titleController.text.trim()},
      );
      if (!mounted) return;
      Navigator.of(context).pop(id);
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = e.toString();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('新建会话'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (widget.projects.isNotEmpty)
            DropdownButtonFormField<String>(
              initialValue: _projectId,
              decoration: const InputDecoration(labelText: '项目'),
              items: [
                for (final p in widget.projects)
                  DropdownMenuItem(value: p.id, child: Text(p.name)),
              ],
              onChanged: (v) => setState(() => _projectId = v),
            )
          else
            TextField(
              controller: _pathController,
              decoration: const InputDecoration(
                labelText: '项目路径（服务端本地路径）',
                hintText: '/home/user/my-project',
              ),
            ),
          TextField(
            controller: _titleController,
            decoration: const InputDecoration(labelText: '标题（可选）'),
          ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                _error!,
                style: TextStyle(
                    color: Theme.of(context).colorScheme.error, fontSize: 12),
              ),
            ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: _busy ? null : _submit,
          child: _busy
              ? const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('创建'),
        ),
      ],
    );
  }
}
