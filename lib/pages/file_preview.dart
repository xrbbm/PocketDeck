import 'package:flutter/material.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';

import '../api/api_client.dart';
import '../api/models.dart';

/// 文件内容上限（与服务端 512KB 有界对齐；改上限只改这里）。
const kFilePreviewLimitKb = 512;

/// md 渲染后缀（.md/.markdown/.mdown，其余走行号文本）。
bool _isMarkdown(String path) {
  final lower = path.toLowerCase();
  return lower.endsWith('.md') ||
      lower.endsWith('.markdown') ||
      lower.endsWith('.mdown');
}

/// 文件/改动全屏预览（对齐网页 WebFilePreview：file 与 diff 两种入口）。
///
/// - file：md 走 Markdown 渲染，其余文本带行号；引用行高亮定位。
/// - diff：先查 status 定位分组（merge>index>workingTree>untracked），再取
///   双栏；文件已提交/超限/状态脱节时回退文件预览（带提示条）。
/// - 越界/缺失/二进制/超大：只给提示，不发多余请求。
class FilePreviewPage extends StatefulWidget {
  const FilePreviewPage({
    super.key,
    required this.api,
    required this.projectId,
    required this.path,
    this.line,
    this.isDiff = false,
  });

  final ApiClient api;
  final String projectId;
  final String path;
  final int? line;
  final bool isDiff;

  @override
  State<FilePreviewPage> createState() => _FilePreviewPageState();
}

class _FilePreviewPageState extends State<FilePreviewPage> {
  FileContentResult? _file;
  bool _failed = false;
  // diff 态：tab 切换 original/modified（移动端全高滚动，不做双栏并排）。
  String? _diffOriginal;
  String? _diffModified;
  bool _diffMissing = false;
  bool _showOriginal = false;

  @override
  void initState() {
    super.initState();
    if (widget.isDiff) {
      _loadDiff();
    } else {
      _loadFile();
    }
  }

  Future<void> _loadFile() async {
    try {
      final result =
          await widget.api.fetchFileContent(widget.projectId, widget.path);
      if (!mounted) return;
      setState(() => _file = result);
    } on ApiException {
      if (!mounted) return;
      setState(() => _failed = true);
    }
  }

  Future<void> _loadDiff() async {
    try {
      final status = await widget.api.fetchGitStatus(widget.projectId);
      final group = status.groups?.groupOf(widget.path);
      if (!mounted) return;
      if (group == null) {
        // 回退文件预览（对齐网页 diffFallbackToFile）。
        setState(() => _diffMissing = true);
        await _loadFile();
        return;
      }
      final diff =
          await widget.api.fetchGitDiff(widget.projectId, group, widget.path);
      if (!mounted) return;
      if (diff == null) {
        setState(() => _diffMissing = true);
        await _loadFile();
        return;
      }
      setState(() {
        _diffOriginal = diff.original;
        _diffModified = diff.modified;
      });
    } on ApiException {
      if (!mounted) return;
      setState(() => _diffMissing = true);
      await _loadFile();
    }
  }

  String _basename(String path) {
    final parts = path.split(RegExp(r'[/\\]'));
    return parts.isEmpty ? path : parts.last;
  }

  @override
  Widget build(BuildContext context) {
    // 新文件标：改前空 + 改后非空。空文件被改（改前本来就空）不挂绿标。
    final isNew = _diffOriginal != null &&
        _diffOriginal!.isEmpty &&
        (_diffModified?.isNotEmpty ?? false) &&
        !_diffMissing;
    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(_basename(widget.path),
                maxLines: 1, overflow: TextOverflow.ellipsis),
            Text(widget.path,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.bodySmall),
          ],
        ),
      ),
      body: _failed
          ? const Center(child: Text('预览加载失败'))
          : widget.isDiff && !_diffMissing && _diffModified == null
              ? const Center(child: CircularProgressIndicator())
              : widget.isDiff && !_diffMissing
                  ? Column(
                      children: [
                        // tab 切换（original 空 = 新文件时禁用）。
                        Row(
                          children: [
                            TextButton(
                              onPressed: isNew
                                  ? null
                                  : () => setState(
                                      () => _showOriginal = true),
                              child: Text('改前',
                                  style: TextStyle(
                                      fontWeight: _showOriginal
                                          ? FontWeight.bold
                                          : null)),
                            ),
                            TextButton(
                              onPressed: () => setState(
                                  () => _showOriginal = false),
                              child: Text('改后',
                                  style: TextStyle(
                                      fontWeight: !_showOriginal
                                          ? FontWeight.bold
                                          : null)),
                            ),
                            if (isNew)
                              Container(
                                margin:
                                    const EdgeInsets.only(left: 4),
                                padding: const EdgeInsets.symmetric(
                                    horizontal: 8, vertical: 2),
                                decoration: BoxDecoration(
                                  // 新文件徽标跟主题 secondary，不写死绿（深色绿糊）。
                                  color: Theme.of(context)
                                      .colorScheme
                                      .secondary
                                      .withValues(alpha: 0.15),
                                  borderRadius:
                                      BorderRadius.circular(10),
                                ),
                                child: Text('新文件',
                                    style: TextStyle(
                                        fontSize: 11,
                                        color: Theme.of(context)
                                            .colorScheme
                                            .secondary)),
                              ),
                          ],
                        ),
                        const Divider(height: 1),
                        Expanded(
                          child: _NumberedText(
                            content: _showOriginal
                                ? (_diffOriginal ?? '')
                                : (_diffModified ?? ''),
                          ),
                        ),
                      ],
                    )
                  : _FileBody(
                      file: _file,
                      path: widget.path,
                      line: widget.line,
                      fallbackNotice:
                          _diffMissing ? '找不到该改动，已回退为文件预览' : null,
                    ),
    );
  }
}

/// 文件正文：tooLarge/binary/失败给提示；md 渲染，其余行号文本。
class _FileBody extends StatelessWidget {
  const _FileBody(
      {required this.file,
      required this.path,
      this.line,
      this.fallbackNotice});

  final FileContentResult? file;
  final String path;
  final int? line;
  final String? fallbackNotice;

  @override
  Widget build(BuildContext context) {
    final f = file;
    if (f == null) {
      return const Center(child: CircularProgressIndicator());
    }
    if (f.tooLarge) {
      return Center(
          child: Padding(
              padding: const EdgeInsets.all(24),
              // 常量插值不生效：用 ${} 拼出真实上限（否则用户看到 '>kFilePreviewLimitKb KB'）。
              child: Text(
                  '文件过大，无法预览（>${kFilePreviewLimitKb}KB），请回桌面端查看',
                  textAlign: TextAlign.center)));
    }
    if (f.binary) {
      return const Center(child: Text('二进制文件无法预览'));
    }
    final content = f.content;
    if (content == null) {
      return const Center(child: Text('预览加载失败'));
    }
    return Column(
      children: [
        if (fallbackNotice != null)
          Container(
            width: double.infinity,
            padding:
                const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            color: Theme.of(context).colorScheme.surfaceContainerHighest,
            child: Text(fallbackNotice!,
                style: const TextStyle(fontSize: 12)),
          ),
        Expanded(
          child: _isMarkdown(path)
              ? SingleChildScrollView(
                  padding: const EdgeInsets.all(12),
                  child: MarkdownBody(data: content),
                )
              : _NumberedText(content: content, line: line),
        ),
        if (f.truncated)
          Container(
            width: double.infinity,
            padding:
                const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            color: Theme.of(context).colorScheme.surfaceContainerHighest,
            child: Text('内容过长，仅显示前 ${kFilePreviewLimitKb}KB，完整内容请回桌面端',
                style: const TextStyle(fontSize: 12)),
          ),
      ],
    );
  }
}

/// 行号文本：引用行高亮并滚到可见（md 渲染不做行定位，对齐网页）。
class _NumberedText extends StatefulWidget {
  const _NumberedText({required this.content, this.line});

  final String content;
  final int? line;

  @override
  State<_NumberedText> createState() => _NumberedTextState();
}

class _NumberedTextState extends State<_NumberedText> {
  final _scroll = ScrollController();
  // 行定位用 GlobalKey 实测目标行偏移，不估算行高（字体/行高一变就飘）。
  final _lineKeys = <int, GlobalKey>{};

  @override
  void initState() {
    super.initState();
    // 首帧 maxScrollExtent 可能还是 0：等布局稳定再跳，失败重试一次。
    WidgetsBinding.instance.addPostFrameCallback((_) => _jump(retry: true));
  }

  void _jump({bool retry = false}) {
    final target = widget.line;
    if (target == null || target < 1 || !_scroll.hasClients) return;
    // 越界钳位：diff 带的行号超文件总行数时不空滚，直接停在末尾附近。
    final total = widget.content.split('\n').length;
    if (target > total) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (_scroll.hasClients) _scroll.jumpTo(_scroll.position.maxScrollExtent);
      });
      return;
    }
    final ctx = _lineKeys[target]?.currentContext;
    if (ctx == null) {
      if (retry) {
        WidgetsBinding.instance.addPostFrameCallback((_) => _jump());
      }
      return;
    }
    final box = ctx.findRenderObject();
    if (box is! RenderBox || !box.attached) return;
    // 目标行全局 Y − ListView 全局 Y + 已滚偏移 − 预留 200 上边距。
    final listBox = _scroll.position.context.storageContext
        .findRenderObject();
    final listY = listBox is RenderBox
        ? listBox.localToGlobal(Offset.zero).dy
        : 0.0;
    final offset = box.localToGlobal(Offset.zero).dy - listY + _scroll.offset - 200;
    if (offset > 0) {
      _scroll.jumpTo(offset.clamp(0.0, _scroll.position.maxScrollExtent));
    }
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final lines = widget.content.split('\n');
    return ListView.builder(
      controller: _scroll,
      padding: const EdgeInsets.all(8),
      itemCount: lines.length,
      itemBuilder: (_, i) {
        final n = i + 1;
        final hit = widget.line == n;
        // 目标行挂 key 供 _jump 实测偏移（只给目标行挂，不全量建 key）。
        final key = widget.line == n ? (_lineKeys[n] ??= GlobalKey()) : null;
        return Container(
          key: key,
          padding:
              const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
          decoration: hit
              ? BoxDecoration(
                  color: Theme.of(context)
                      .colorScheme
                      .primaryContainer
                      .withValues(alpha: 0.5),
                  borderRadius: BorderRadius.circular(4),
                )
              : null,
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SizedBox(
                width: 40,
                child: Text('$n',
                    textAlign: TextAlign.right,
                    style: TextStyle(
                        fontSize: 12,
                        color: Theme.of(context)
                            .colorScheme
                            .onSurfaceVariant)),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(lines[i].isEmpty ? ' ' : lines[i],
                    style: const TextStyle(fontSize: 12)),
              ),
            ],
          ),
        );
      },
    );
  }
}
