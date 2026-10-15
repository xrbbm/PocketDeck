import 'package:flutter/material.dart';

import '../api/chat.dart';
/// 工具调用卡片（对齐 WebToolCard：图标 + 工具名 + 状态，可展开看输入/输出）。
class ToolCard extends StatelessWidget {
  const ToolCard({super.key, required this.part});

  final ToolPart part;

  @override
  Widget build(BuildContext context) {
    final error = part.isError;
    final running = part.streaming;
    final errorText = prettyToolValue(part.errorText);
    final inputText = prettyToolValue(part.input);
    final outputText = prettyToolValue(part.output);
    final hasBody =
        errorText.isNotEmpty || inputText.isNotEmpty || outputText.isNotEmpty;
    final errorColor = Theme.of(context).colorScheme.error;
    final summary = toolSummary(part);

    return Card(
      margin: const EdgeInsets.symmetric(vertical: 2),
      child: ExpansionTile(
        dense: true,
        visualDensity: VisualDensity.compact,
        tilePadding: const EdgeInsets.symmetric(horizontal: 12),
        // 无内容可看时不可展开（与网页端 hasBody 门控一致）。
        enabled: hasBody,
        leading: running
            ? const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : Icon(
                error ? Icons.error_outline : Icons.build_outlined,
                size: 20,
                color: error ? errorColor : null,
              ),
        title: Row(
          children: [
            Expanded(
              child: Text(
                part.toolName,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 13),
              ),
            ),
            if (running)
              const Text('运行中', style: TextStyle(fontSize: 12))
            else if (error)
              Text('执行失败',
                  style: TextStyle(fontSize: 12, color: errorColor)),
          ],
        ),
        subtitle: summary.isEmpty
            ? null
            : Text(
                summary,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 12,
                  color:
                      Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
        children: [
          if (errorText.isNotEmpty)
            _ValueBlock(label: '错误', text: errorText, isError: true),
          if (inputText.isNotEmpty)
            _ValueBlock(label: '输入', text: inputText),
          if (outputText.isNotEmpty)
            _ValueBlock(label: '输出', text: outputText),
        ],
      ),
    );
  }
}

/// 连续同名工具调用合并卡（如 read × 4）：内部逐个复用 [ToolCard]。
class ToolGroup extends StatelessWidget {
  const ToolGroup({
    super.key,
    required this.toolName,
    required this.parts,
  });

  final String toolName;
  final List<ToolPart> parts;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 2),
      child: ExpansionTile(
        dense: true,
        visualDensity: VisualDensity.compact,
        tilePadding: const EdgeInsets.symmetric(horizontal: 12),
        leading: const Icon(Icons.build_outlined, size: 20),
        title: Text(
          '$toolName × ${parts.length}',
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontSize: 13),
        ),
        children: [for (final p in parts) ToolCard(key: ValueKey(p.callId), part: p)],
      ),
    );
  }
}

/// 工具输入/输出展示块（等宽 + 限高滚动）。
class _ValueBlock extends StatelessWidget {
  const _ValueBlock({required this.label, required this.text, this.isError = false});

  final String label;
  final String text;
  final bool isError;

  @override
  Widget build(BuildContext context) {
    final color = isError
        ? Theme.of(context).colorScheme.error
        : Theme.of(context).colorScheme.onSurfaceVariant;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label,
              style: TextStyle(
                  fontSize: 11, color: color, fontWeight: FontWeight.bold)),
          const SizedBox(height: 2),
          ConstrainedBox(
            constraints: const BoxConstraints(maxHeight: 220),
            child: Container(
              width: double.infinity,
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: Theme.of(context)
                    .colorScheme
                    .surfaceContainerHighest
                    .withValues(alpha: 0.5),
                borderRadius: BorderRadius.circular(6),
              ),
              child: SingleChildScrollView(
                child: SelectableText(
                  text,
                  style: TextStyle(
                      fontFamily: 'monospace', fontSize: 12, color: color),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
