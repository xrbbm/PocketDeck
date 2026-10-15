import 'package:flutter/material.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import 'file_preview.dart';

/// 工作区抽屉（对齐网页 WebWorkspaceDrawer：只读 Git / 文件双面板）。
///
/// - Git：分支 + 变更分组（merge/index/workingTree/untracked）+ 点文件看
///   original/modified 两栏 diff + 最近提交12条。写入操作回桌面完成。
/// - 文件：项目内目录浏览（面包屑）+ 点文件进全屏预览（FilePreviewPage）。
/// 打开时加载，关闭即弃（不常驻轮询，Git 状态点开才取）。
class WorkspaceDrawer extends StatefulWidget {
  const WorkspaceDrawer({
    super.key,
    required this.api,
    required this.projectId,
  });

  final ApiClient api;
  final String projectId;

  @override
  State<WorkspaceDrawer> createState() => _WorkspaceDrawerState();
}

class _WorkspaceDrawerState extends State<WorkspaceDrawer> {
  @override
  Widget build(BuildContext context) {
    return Drawer(
      child: SafeArea(
        child: DefaultTabController(
          length: 2,
          child: Column(
            children: [
              AppBar(
                automaticallyImplyLeading: false,
                title: const Text('工作区', style: TextStyle(fontSize: 16)),
                actions: [
                  IconButton(
                    icon: const Icon(Icons.close),
                    tooltip: '关闭',
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
                bottom: const TabBar(
                  tabs: [Tab(text: 'Git'), Tab(text: '文件')],
                ),
              ),
              Expanded(
                child: TabBarView(
                  children: [
                    _GitPanel(
                      api: widget.api,
                      projectId: widget.projectId,
                    ),
                    _FilesPanel(
                      api: widget.api,
                      projectId: widget.projectId,
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ── Git 面板 ─────────────────────────────────────────────────────────

class _GitPanel extends StatefulWidget {
  const _GitPanel({required this.api, required this.projectId});

  final ApiClient api;
  final String projectId;

  @override
  State<_GitPanel> createState() => _GitPanelState();
}

class _GitPanelState extends State<_GitPanel> {
  bool _loading = true;
  bool _noRepo = false;
  GitBranchInfo? _branch;
  GitResourceGroups? _groups;
  List<CommitEntry> _commits = const [];
  String? _diffPath;
  String _diffGroup = 'workingTree';
  String? _diffOriginal;
  String? _diffModified;
  bool _diffLoading = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  String? _loadError;

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _noRepo = false;
      _loadError = null;
    });
    try {
      // status/log 并行：串行等两倍 RTT，弱网下抽屉转圈翻倍。
      final results = await Future.wait([
        widget.api.fetchGitStatus(widget.projectId),
        widget.api.fetchGitLog(widget.projectId),
      ]);
      if (!mounted) return;
      final status = results[0] as ({bool repo, GitBranchInfo? branch, GitResourceGroups? groups});
      if (!status.repo) {
        setState(() {
          _noRepo = true;
          _loading = false;
        });
        return;
      }
      final commits = results[1] as List<CommitEntry>;
      setState(() {
        _branch = status.branch;
        _groups = status.groups;
        _commits = commits;
        _loading = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _loadError = e.message;
        _loading = false;
      });
    }
  }

  Future<void> _openDiff(String group, String path) async {
    if (_diffPath == path && _diffGroup == group) {
      setState(() => _diffPath = null);
      return;
    }
    setState(() {
      _diffPath = path;
      _diffGroup = group;
      _diffLoading = true;
      _diffOriginal = null;
      _diffModified = null;
    });
    try {
      final diff =
          await widget.api.fetchGitDiff(widget.projectId, group, path);
      if (!mounted) return;
      setState(() {
        _diffOriginal = diff?.original ?? '';
        _diffModified = diff?.modified ?? '';
        _diffLoading = false;
      });
    } on ApiException {
      if (!mounted) return;
      setState(() => _diffLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_noRepo) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text('当前项目不是 Git 仓库'),
            TextButton(onPressed: _load, child: const Text('重试')),
          ],
        ),
      );
    }
    if (_loadError != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('工作区加载失败：$_loadError'),
            TextButton(onPressed: _load, child: const Text('重试')),
          ],
        ),
      );
    }
    final groups = _groups;
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        // 内容不足一屏也允许下拉：默认 physics 不可滚时手势被吃。
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(12),
        children: [
          if (_branch?.current != null)
            Row(
              children: [
                const Icon(Icons.account_tree, size: 14),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    _branch!.current!,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                ),
                Text('变更 ${groups?.total ?? 0}',
                    style: Theme.of(context).textTheme.bodySmall),
              ],
            ),
          if (groups != null) ...[
            _ResourceGroup(
              label: '合并冲突',
              group: 'merge',
              resources: groups.merge,
              diffPath: _diffPath,
              diffGroup: _diffGroup,
              diffLoading: _diffLoading,
              diffOriginal: _diffOriginal,
              diffModified: _diffModified,
              onOpenDiff: _openDiff,
            ),
            _ResourceGroup(
              label: '已暂存',
              group: 'index',
              resources: groups.index,
              diffPath: _diffPath,
              diffGroup: _diffGroup,
              diffLoading: _diffLoading,
              diffOriginal: _diffOriginal,
              diffModified: _diffModified,
              onOpenDiff: _openDiff,
            ),
            _ResourceGroup(
              label: '工作区修改',
              group: 'workingTree',
              resources: groups.workingTree,
              diffPath: _diffPath,
              diffGroup: _diffGroup,
              diffLoading: _diffLoading,
              diffOriginal: _diffOriginal,
              diffModified: _diffModified,
              onOpenDiff: _openDiff,
            ),
            _ResourceGroup(
              label: '未跟踪',
              group: 'untracked',
              resources: groups.untracked,
              diffPath: _diffPath,
              diffGroup: _diffGroup,
              diffLoading: _diffLoading,
              diffOriginal: _diffOriginal,
              diffModified: _diffModified,
              onOpenDiff: _openDiff,
            ),
          ],
          if (_commits.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text('最近提交', style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 6),
            // 服务端无视 limit 回 1000 条即卡：封顶 50，超了给提示。
            for (final c in _commits.take(50))
              Card(
                margin: const EdgeInsets.only(bottom: 6),
                child: ListTile(
                  dense: true,
                  leading: Text(c.shortHash,
                      style: TextStyle(
                          fontFamily: 'monospace',
                          color: Theme.of(context).colorScheme.primary)),
                  title: Text(c.message,
                      maxLines: 1, overflow: TextOverflow.ellipsis),
                  subtitle: c.authorName.isEmpty
                      ? (formatGitDate(c.authorDate).isEmpty
                          ? null
                          : Text(formatGitDate(c.authorDate)))
                      : Text(
                          [c.authorName, formatGitDate(c.authorDate)]
                              .where((s) => s.isNotEmpty)
                              .join(' · '),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                ),
              ),
            if (_commits.length > 50)
              Padding(
                padding: const EdgeInsets.only(top: 2, left: 4),
                child: Text('仅显示前 50 条，共 ${_commits.length} 条',
                    style: Theme.of(context).textTheme.bodySmall),
              ),
          ],
        ],
      ),
    );
  }
}

/// Git 提交日期格式化：服务端 authorDate 是毫秒；<1e12 视为秒再转毫秒。
/// 越界/远未来（服务端脏数据）回空串，不让 DateTime 抛 RangeError 红屏。
String formatGitDate(int ts) {
  if (ts <= 0) return '';
  final ms = ts < 1000000000000 ? ts * 1000 : ts;
  // 钳位到 1970~2100：毫秒 13 位内，超了就是脏数据。
  if (ms < 0 || ms > 4102444800000) return '';
  try {
    final d = DateTime.fromMillisecondsSinceEpoch(ms);
    return '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
  } on ArgumentError {
    return '';
  }
}

/// 变更分组：字母徽标 + 文件名 + 路径，点开看 diff 两栏。
class _ResourceGroup extends StatelessWidget {
  const _ResourceGroup({
    required this.label,
    required this.group,
    required this.resources,
    required this.diffPath,
    required this.diffGroup,
    required this.diffLoading,
    required this.diffOriginal,
    required this.diffModified,
    required this.onOpenDiff,
  });

  final String label;
  final String group;
  final List<GitResource> resources;
  final String? diffPath;
  final String diffGroup;
  final bool diffLoading;
  final String? diffOriginal;
  final String? diffModified;
  final void Function(String group, String path) onOpenDiff;

  Color _letterColor(String letter, BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    switch (letter) {
      case 'D':
        return scheme.error;
      case 'A':
        // 新增走主题 secondary（teal/cyan 系），不写死 Colors.green。
        return scheme.secondary;
      default:
        // 修改走主题 primary（blue 系），不写死 Colors.orange。
        return scheme.primary;
    }
  }

  @override
  Widget build(BuildContext context) {
    if (resources.isEmpty) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 10),
        Text('$label (${resources.length})',
            style: Theme.of(context).textTheme.titleSmall),
        for (final r in resources.take(30))
          Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              ListTile(
                dense: true,
                contentPadding:
                    const EdgeInsets.symmetric(horizontal: 4),
                leading: Text(r.letter,
                    style: TextStyle(
                        fontWeight: FontWeight.bold,
                        color: _letterColor(r.letter, context))),
                title: Text(r.basename,
                    maxLines: 1, overflow: TextOverflow.ellipsis),
                subtitle: Text(r.path,
                    maxLines: 1, overflow: TextOverflow.ellipsis),
                selected: diffPath == r.path && diffGroup == group,
                onTap: () => onOpenDiff(group, r.path),
              ),
              if (diffPath == r.path && diffGroup == group)
                Padding(                  padding: const EdgeInsets.only(bottom: 8),
                  child: diffLoading
                      ? const Center(
                          child: SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(
                                  strokeWidth: 2)))
                      : (diffOriginal == null && diffModified == null)
                          // 服务端无 diff（纯改名/超限）与网络失败压同文案：
                          // 失败时 diffLoading 已收但 _diffModified 留空，点重试重拉。
                          ? (diffLoading
                              ? const SizedBox.shrink()
                              : const Text('暂无 diff（纯改名或内容超限）',
                                  style: TextStyle(fontSize: 12)))
                          : Column(
                              children: [
                                _DiffBlock(
                                    label: 'original',
                                    content: diffOriginal ?? ''),
                                _DiffBlock(
                                    label: 'modified',
                                    content: diffModified ?? ''),
                              ],
                            ),
                ),
            ],
          ),
        // 组内超 30 条截断时给提示，不让用户以为只有 30 个变更。
        if (resources.length > 30)
          Padding(
            padding: const EdgeInsets.only(left: 4, top: 2),
            child: Text('仅显示前 30 条，共 ${resources.length} 条',
                style: Theme.of(context).textTheme.bodySmall),
          ),
      ],
    );
  }
}

class _DiffBlock extends StatelessWidget {
  const _DiffBlock({required this.label, required this.content});

  final String label;
  final String content;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(top: 4),
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label,
              style: TextStyle(
                  fontSize: 11,
                  color: Theme.of(context).colorScheme.onSurfaceVariant)),
          const SizedBox(height: 4),
          Text(content.isEmpty ? '—' : content,
              style: const TextStyle(fontSize: 12)),
        ],
      ),
    );
  }
}

// ── 文件面板 ─────────────────────────────────────────────────────────

class _FilesPanel extends StatefulWidget {
  const _FilesPanel({required this.api, required this.projectId});

  final ApiClient api;
  final String projectId;

  @override
  State<_FilesPanel> createState() => _FilesPanelState();
}

class _FilesPanelState extends State<_FilesPanel> {
  String _dir = '';
  List<WebFileNodeLite> _nodes = const [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      final nodes =
          await widget.api.fetchFileList(widget.projectId, _dir);
      if (!mounted) return;
      setState(() {
        _nodes = nodes;
        _loading = false;
      });
    } on ApiException {
      if (!mounted) return;
      setState(() {
        _nodes = const [];
        _loading = false;
      });
    }
  }

  void _enter(String dir) {
    setState(() => _dir = dir);
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final crumbs = _dir.split('/').where((e) => e.isNotEmpty).toList();
    return Column(
      children: [
        // 面包屑：根 / 一级 / …
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
          child: Row(
            children: [
              TextButton(
                onPressed: () => _enter(''),
                child: const Text('文件'),
              ),
              for (var i = 0; i < crumbs.length; i++) ...[
                const Text(' / '),
                TextButton(
                  onPressed: () =>
                      _enter(crumbs.sublist(0, i + 1).join('/')),
                  child: Text(crumbs[i]),
                ),
              ],
            ],
          ),
        ),
        Expanded(
          child: _loading
              ? const Center(child: CircularProgressIndicator())
              : RefreshIndicator(
                  onRefresh: _load,
                  child: ListView.builder(
                    // 同上：文件少也允许下拉刷新。
                    physics: const AlwaysScrollableScrollPhysics(),
                    itemCount: _nodes.length,
                    itemBuilder: (_, i) {
                      final n = _nodes[i];
                      return ListTile(
                        dense: true,
                        leading: Icon(n.isDirectory
                            ? Icons.folder
                            : Icons.description_outlined),
                        title: Text(n.name,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis),
                        onTap: n.isDirectory
                            ? () => _enter(n.relativePath)
                            : () => Navigator.of(context).push(
                                  MaterialPageRoute(
                                    builder: (_) => FilePreviewPage(
                                      api: widget.api,
                                      projectId: widget.projectId,
                                      path: n.relativePath,
                                    ),
                                  ),
                                ),
                      );
                    },
                  ),
                ),
        ),
      ],
    );
  }
}
