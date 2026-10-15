import 'dart:convert';

import 'chat.dart';

/// UIMessageStream 流式草稿：把服务端帧折叠成一条 UiMessage。
///
/// 服务端帧集合（WebEventStream.ts 手写发出，非 ai 包标准全集）：
/// start / start-step / text-start|delta|end / reasoning-start|delta|end /
/// tool-input-start / tool-input-available / tool-output-available /
/// tool-output-error / error / finish。未知 type 直接忽略。
class ToolDraft {
  ToolDraft({required this.name, this.input});

  final String name;
  dynamic input;
  bool settled = false;
  dynamic output;
  String? errorText;
}

/// 流式时间线段：文本/思考/工具按到达顺序交错排列。
///
/// 假设：同一轮内同 kind 的连续 delta 追加进同一段（不按 text-start 的 id
/// 拆分，避免依赖服务端 part-id 的稳定性）；一旦被其他 kind 打断，下一次
/// 同 kind 到达即开新段。这与 web 端 interim segments 语义一致：文本被工具
/// 打断时会渲染成两段正文，工具卡永远停在它实际出现的位置，不会被合并后
/// 的全文顶到回复下面（浮空 bug 的根因）。
enum _SegKind { text, reasoning, tool }

class _Seg {
  _Seg.text() : kind = _SegKind.text, callId = null, draft = null;
  _Seg.reasoning() : kind = _SegKind.reasoning, callId = null, draft = null;
  _Seg.tool(this.callId, this.draft) : kind = _SegKind.tool;

  final _SegKind kind;
  var buf = '';
  final String? callId;
  final ToolDraft? draft;
}

class StreamDraft {
  final List<_Seg> _segs = [];
  final Map<String, _Seg> _toolIndex = {};
  // thinking 残尾：半个 <thinking> 块被切到两段时，拼起来再剥。
  String _thinkCarry = '';

  void _appendKind(_SegKind kind, String delta) {
    final last = _segs.isEmpty ? null : _segs.last;
    if (last != null && last.kind == kind) {
      last.buf += delta;
    } else {
      final seg = kind == _SegKind.text ? _Seg.text() : _Seg.reasoning();
      seg.buf = delta;
      _segs.add(seg);
    }
  }

  _Seg _toolSeg(String callId, String name) {
    // 同 callId 异名（重连/重试导致）：后到的名字为准，不让首名获胜。
    final old = _toolIndex[callId];
    if (old != null) {
      final draft = old.draft;
      if (draft != null && draft.name != name && !draft.settled) {
        final seg = _Seg.tool(callId, ToolDraft(name: name));
        final idx = _segs.indexOf(old);
        if (idx >= 0) _segs[idx] = seg;
        _toolIndex[callId] = seg;
        return seg;
      }
      return old;
    }
    final seg = _Seg.tool(callId, ToolDraft(name: name));
    _segs.add(seg);
    _toolIndex[callId] = seg;
    return seg;
  }

  void applyFrame(Map<String, dynamic> frame) {
    switch (frame['type']) {
      case 'text-delta':
        final delta = frame['delta'];
        if (delta is String && delta.isNotEmpty) {
          _appendKind(_SegKind.text, delta);
        }
      case 'reasoning-delta':
        final delta = frame['delta'];
        if (delta is String && delta.isNotEmpty) {
          _appendKind(_SegKind.reasoning, delta);
        }
      case 'tool-input-start':
      case 'tool-input-available':
        final callId = frame['toolCallId'];
        if (callId is! String || callId.isEmpty) break;
        final name = frame['toolName'] as String? ?? 'tool';
        final seg = _toolSeg(callId, name);
        if (frame['input'] != null) seg.draft!.input = frame['input'];
      case 'tool-output-available':
        final callId = frame['toolCallId'];
        final seg = callId is String ? _toolIndex[callId] : null;
        // 孤儿 output（中途建流未见证 start）直接丢弃，与服务端过滤一致。
        if (seg?.draft == null) break;
        seg!.draft!.settled = true;
        seg.draft!.output = frame['output'];
      case 'tool-output-error':
        final callId = frame['toolCallId'];
        final seg = callId is String ? _toolIndex[callId] : null;
        if (seg?.draft == null) break;
        seg!.draft!.settled = true;
        seg.draft!.errorText = frame['errorText'] as String? ?? 'Tool failed';
      // start / start-step / text-start / text-end / reasoning-start /
      // reasoning-end / finish：无需累积状态。
      default:
        break;
    }
  }

  bool get isEmpty => _segs.isEmpty;

  UiMessage toUiMessage(String id) {
    final parts = <UiPart>[];
    for (final seg in _segs) {
      switch (seg.kind) {
        case _SegKind.text:
          // 流式正文同样可能夹带 <thinking> 块：逐段剥除（与历史映射一致）。
          // 跨段切断（上段残 '<think' + 下段 'ing>…'）：残尾拼起来再剥，
          // 剥完还有未闭合 '<…' 残尾则留到下段（不渲染半个标签）。
          final raw = '$_thinkCarry${seg.buf}';
          _thinkCarry = '';
          final body = stripThinkingTags(raw);
          final tail = RegExp(r'<[a-zA-Z/]*$').firstMatch(body);
          if (tail != null && body.length - tail.start < 12) {
            _thinkCarry = body.substring(tail.start);
            final head = body.substring(0, tail.start).trimRight();
            if (head.isNotEmpty) parts.add(TextPart(head));
          } else if (body.isNotEmpty) {
            parts.add(TextPart(body));
          }
        case _SegKind.reasoning:
          if (seg.buf.isNotEmpty) parts.add(ReasoningPart(seg.buf));
        case _SegKind.tool:
          final draft = seg.draft;
          if (draft == null) continue;
          parts.add(
            ToolPart(
              toolName: draft.name,
              callId: seg.callId ?? '',
              isError: draft.errorText != null,
              input: draft.input,
              output: draft.output,
              errorText: draft.errorText,
              streaming: !draft.settled,
            ),
          );
      }
    }
    return UiMessage(id: id, role: 'assistant', parts: parts);
  }
}

/// SSE 增量切帧器：TCP 块可能把一帧拦腰截断，残尾留到下一块。
/// push 返回本块凑出的完整帧；`done` 为 true 表示见到 [DONE]/finish。
class SseSplit {
  const SseSplit(this.frames, this.done);
  final List<Map<String, dynamic>> frames;
  final bool done;
}

class SseSplitter {
  var _buffer = '';

  /// 换行兼容：服务端经代理可能吐 \r\n，先归一化再切帧。
  String _norm(String s) => s.replaceAll('\r\n', '\n').replaceAll('\r', '\n');

  SseSplit push(String chunk) {
    _buffer += _norm(chunk);
    final frames = <Map<String, dynamic>>[];
    var done = false;
    while (true) {
      final sep = _buffer.indexOf('\n\n');
      if (sep < 0) break;
      final raw = _buffer.substring(0, sep);
      _buffer = _buffer.substring(sep + 2);
      for (final line in raw.split('\n')) {
        final trimmed = line.trim();
        if (trimmed.isEmpty) continue;
        if (trimmed == '[DONE]' || trimmed == 'data:[DONE]' || trimmed == 'data: [DONE]') {
          done = true;
          break;
        }
        if (!trimmed.startsWith('data:')) continue;
        final payload = trimmed.substring(5).trim();
        if (payload.isEmpty) continue;
        try {
          final decoded = jsonDecode(payload);
          if (decoded is Map<String, dynamic>) {
            if (decoded['type'] == 'finish') done = true;
            frames.add(decoded);
          }
        } catch (_) {
          // 心跳/残帧直接丢弃。
        }
      }
      if (done) break;
    }
    return SseSplit(frames, done);
  }
}
