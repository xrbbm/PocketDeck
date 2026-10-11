/// 聊天消息模型与历史映射（对照 webApi.chatMessagesToUiMessages）。
library;

import 'dart:convert';

import 'models.dart';

sealed class UiPart {
  const UiPart();
}

class TextPart extends UiPart {
  const TextPart(this.text);
  final String text;
}

class ReasoningPart extends UiPart {
  const ReasoningPart(this.text);
  final String text;
}

class ToolPart extends UiPart {
  const ToolPart({
    required this.toolName,
    required this.callId,
    required this.isError,
    this.input,
    this.output,
    this.errorText,
    this.streaming = false,
  });

  final String toolName;
  final String callId;
  final bool isError;
  final dynamic input;
  final dynamic output;
  final String? errorText;
  /// 流式中尚未 settle（output 未到）的工具，渲染转圈。
  final bool streaming;
}

class FilePart extends UiPart {
  const FilePart({required this.mimeType, required this.dataUrl});
  final String mimeType;
  final String dataUrl;
}

/// 渲染用消息：role 只分 user / assistant（tool 归并为 assistant）。
class UiMessage {
  const UiMessage({required this.id, required this.role, required this.parts});

  final String id;
  final String role;
  final List<UiPart> parts;

  bool get isUser => role == 'user';
}

/// 工具参数/结果格式化：字符串原样，对象 pretty JSON，超长截断。
/// 照抄 WebTimeline.prettyToolValue（默认 4000 字符）。
String prettyToolValue(dynamic value, [int maxChars = 4000]) {
  if (value == null) return '';
  String text = value is String ? value : '';
  if (text.isEmpty) {
    try {
      text = const JsonEncoder.withIndent('  ').convert(value);
    } catch (_) {
      text = '$value';
    }
  }
  return text.length > maxChars
      ? '${text.substring(0, maxChars)}\n… (${text.length} chars)'
      : text;
}

/// 剥除正文里的 `<thinking>…</thinking>` 块（思考内容由 reasoning part
/// 单独渲染为思考过程卡片）。照抄 web 端 stripThinkingTags 同款正则。
String stripThinkingTags(String text) => text
    .replaceAll(
      RegExp(r'<thinking>[\s\S]*?</thinking>', caseSensitive: false),
      '',
    )
    .trim();
/// 照抄 utils/askUi.splitAskOption。
({String label, String? description}) splitAskOption(String option) {
  final pipe = option.indexOf('|');
  if (pipe > 0) {
    final label = option.substring(0, pipe).trim();
    final description = option.substring(pipe + 1).trim();
    return (label: label, description: description.isEmpty ? null : description);
  }
  final dash = RegExp(r'^(.+?)\s+—\s+(.+)$', unicode: true).firstMatch(option);
  if (dash != null) {
    return (label: dash.group(1)!.trim(), description: dash.group(2)!.trim());
  }
  return (label: option, description: null);
}

/// 工具调用一句话摘要（折叠态副标题）：优先取 path/command 等常见键
/// 的首行，否则无摘要（返回空字符串）。超长截断 80 字。
String toolSummary(ToolPart part) {
  final input = part.input;
  String first = '';
  if (input is Map) {
    for (final k in [
      'path',
      'file',
      'command',
      'cmd',
      'query',
      'url',
      'pattern',
      'text',
      'prompt'
    ]) {
      final v = input[k];
      if (v is String && v.trim().isNotEmpty) {
        first = v.trim().split('\n').first.trim();
        break;
      }
    }
  } else if (input is String && input.trim().isNotEmpty) {
    first = input.trim().split('\n').first.trim();
  }
  if (first.isEmpty) return '';
  return first.length > 80 ? '${first.substring(0, 80)}…' : first;
}

/// 连续同名工具调用分段：同名且相邻的 ToolPart 并入一段；
/// 其余 part 各独占一段。渲染时长度 >1 的工具段合并为一组卡。
List<List<UiPart>> groupToolRuns(List<UiPart> parts) {
  final runs = <List<UiPart>>[];
  for (final p in parts) {
    if (p is ToolPart && runs.isNotEmpty) {
      final last = runs.last;
      if (last.isNotEmpty &&
          last.first is ToolPart &&
          (last.first as ToolPart).toolName == p.toolName) {
        last.add(p);
        continue;
      }
    }
    runs.add([p]);
  }
  return runs;
}

/// 跨消息合并连续同名纯工具消息：服务端常把每个 tool call 存成一条
/// 仅含单个 ToolPart 的 assistant 消息，相邻同名者并为一组渲染。
/// 返回显示段：每段为一条原消息，或 N 条可合并工具消息。
List<List<UiMessage>> groupToolMessages(List<UiMessage> messages) {
  ToolPart? singleTool(UiMessage m) => (!m.isUser &&
          m.parts.length == 1 &&
          m.parts.first is ToolPart)
      ? m.parts.first as ToolPart
      : null;

  final runs = <List<UiMessage>>[];
  for (final m in messages) {
    final tool = singleTool(m);
    if (tool != null && runs.isNotEmpty) {
      // 段首为纯工具即整段皆纯工具（下方只向此类段追加）。
      final firstTool = singleTool(runs.last.first);
      if (firstTool != null && firstTool.toolName == tool.toolName) {
        runs.last.add(m);
        continue;
      }
    }
    runs.add([m]);
  }
  return runs;
}

String batchAnswerLabel(dynamic value) {
  if (value is bool) return value ? 'true' : 'false';
  if (value is List) return value.map((e) => '$e').join('、');
  return value?.toString() ?? '';
}

/// 序列化批量答案（BatchAskInlineBar 提交给主进程的 envelope 格式）。
String serializeBatchAnswers(
  List<WebBatchQuestion> questions,
  Map<String, dynamic> answers,
) {
  final result = [
    for (final q in questions)
      {
        'id': q.id,
        'type': q.type,
        'value': answers[q.id],
        'label': batchAnswerLabel(answers[q.id]),
        'wasCustom': false,
      },
  ];
  return jsonEncode({'answers': result});
}

/// 历史分页结果（对照 SessionMessagePage）。
class MessagePage {
  const MessagePage({required this.messages, this.nextBefore, this.unavailable});

  final List<UiMessage> messages;
  final int? nextBefore;
  final String? unavailable;

  bool get hasMore => nextBefore != null;
}

/// ChatMessage → UiMessage（照抄 chatMessagesToUiMessages）。
UiMessage mapChatMessage(Map<String, dynamic> m) {
  final id = m['id'] as String? ?? 'hist-${m['timestamp'] ?? ''}';
  final role = m['role'] as String?;
  final text = m['text'] as String? ?? '';
  final thinking = m['thinking'] as String?;
  final meta = m['meta'] as Map<String, dynamic>?;

  if (role == 'tool') {
    final toolName = meta?['toolName'] as String? ?? '';
    if (toolName.isNotEmpty) {
      final isError =
          meta?['isError'] == true || text.startsWith('✗');
      dynamic input = meta?['args'];
      if (input is String && input.isNotEmpty) {
        // 已截断的非 JSON 字符串保持原样。
        try {
          input = jsonDecode(input);
        } catch (_) {
          input = meta?['args'];
        }
      }
      final detail = meta?['detailText'];
      final detailText = detail is String ? detail : null;
      return UiMessage(
        id: id,
        role: 'assistant',
        parts: [
          ToolPart(
            toolName: toolName,
            callId: 'hist-$id',
            isError: isError,
            input: input,
            output: meta?['result'],
            errorText: isError ? (detailText ?? text) : null,
          ),
        ],
      );
    }
    if (text.isNotEmpty) {
      return UiMessage(id: id, role: 'assistant', parts: [TextPart(text)]);
    }
    // 无名 tool 且无文本：压成思考占位（调用方按空过滤），不占 ListView 一行空白。
    if (thinking != null && thinking.isNotEmpty) {
      return UiMessage(
          id: id, role: 'assistant', parts: [ReasoningPart(thinking)]);
    }
    return UiMessage(id: id, role: 'assistant', parts: const []);
  }

  final parts = <UiPart>[];
  if (thinking != null && thinking.isNotEmpty) {
    parts.add(ReasoningPart(thinking));
  }
  // 服务端 text 里可能夹带 <thinking> 块（与 thinking 字段重复）：
  // web 端正文统一剥除，只留 reasoning part 进思考过程卡片。用户原文不动。
  final body = role == 'user' ? text : stripThinkingTags(text);
  if (body.isNotEmpty) parts.add(TextPart(body));
  // 历史用户图片 → file part；单张 >4MB 跳过。
  if (role == 'user' && m['images'] is List) {
    for (final img in (m['images'] as List)) {
      if (img is! Map<String, dynamic>) continue;
      if (img['type'] != 'image') continue;
      final mime = img['mimeType'] as String?;
      final data = img['data'] as String?;
      if (mime == null || data == null) continue;
      if (data.length > 4 * 1024 * 1024) continue;
      parts.add(FilePart(mimeType: mime, dataUrl: 'data:$mime;base64,$data'));
    }
  }
  return UiMessage(
    id: id,
    role: role == 'user' ? 'user' : 'assistant',
    parts: parts,
  );
}
