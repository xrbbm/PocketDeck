import 'package:flutter_test/flutter_test.dart';
import 'package:pideck/api/chat.dart';
import 'package:pideck/api/chat_stream.dart';

Map<String, dynamic> frame(String type, [Map<String, dynamic>? extra]) =>
    {'type': type, ...?extra};

void main() {
  test('文本与思考增量拼接且保序', () {
    final draft = StreamDraft();
    draft.applyFrame(frame('start', {'messageId': 'm1'}));
    draft.applyFrame(frame('text-start', {'id': 't1'}));
    draft.applyFrame(frame('text-delta', {'id': 't1', 'delta': '你好'}));
    draft.applyFrame(frame('reasoning-start', {'id': 'r1'}));
    draft.applyFrame(frame('reasoning-delta', {'id': 'r1', 'delta': '思考中'}));
    draft.applyFrame(frame('text-delta', {'id': 't1', 'delta': '！'}));
    draft.applyFrame(frame('text-end', {'id': 't1'}));
    draft.applyFrame(frame('finish'));

    final msg = draft.toUiMessage('m1');
    // 时间线语义：被思考打断的文本切成两段，顺序即到达顺序。
    expect(msg.parts, hasLength(3));
    expect(msg.parts[0], isA<TextPart>());
    expect(msg.parts[1], isA<ReasoningPart>());
    expect(msg.parts[2], isA<TextPart>());
    expect((msg.parts[0] as TextPart).text, '你好');
    expect((msg.parts[2] as TextPart).text, '！');
  });

  test('回归：文本→工具→文本保持时间线，工具卡不被顶到回复下面', () {
    final draft = StreamDraft();
    draft.applyFrame(frame('text-delta', {'delta': '我看一下'}));
    draft.applyFrame(frame('tool-input-start',
        {'toolCallId': 'c1', 'toolName': 'read', 'input': 'a.txt'}));
    draft.applyFrame(
        frame('tool-output-available', {'toolCallId': 'c1', 'output': 'ok'}));
    draft.applyFrame(frame('text-delta', {'delta': '结果是这样'}));

    final msg = draft.toUiMessage('m1');
    expect(msg.parts, hasLength(3));
    expect(msg.parts[0], isA<TextPart>());
    expect((msg.parts[0] as TextPart).text, '我看一下');
    expect(msg.parts[1], isA<ToolPart>());
    expect((msg.parts[1] as ToolPart).callId, 'c1');
    expect(msg.parts[2], isA<TextPart>());
    expect((msg.parts[2] as TextPart).text, '结果是这样');
  });

  test('工具生命周期：start → 可用 → 输出结算', () {
    final draft = StreamDraft();
    draft.applyFrame(
        frame('tool-input-start', {'toolCallId': 'c1', 'toolName': 'read'}));
    draft.applyFrame(frame('tool-input-available',
        {'toolCallId': 'c1', 'toolName': 'read', 'input': {'path': 'a'}}));
    var msg = draft.toUiMessage('m1');
    var tool = msg.parts.single as ToolPart;
    expect(tool.streaming, isTrue);
    expect(tool.input, {'path': 'a'});

    draft.applyFrame(
        frame('tool-output-available', {'toolCallId': 'c1', 'output': 'hi'}));
    msg = draft.toUiMessage('m1');
    tool = msg.parts.single as ToolPart;
    expect(tool.streaming, isFalse);
    expect(tool.output, 'hi');
    expect(tool.isError, isFalse);
  });

  test('工具报错与孤儿 output 丢弃', () {
    final draft = StreamDraft();
    // 未见证 start 的孤儿 output：丢弃，不崩。
    draft.applyFrame(
        frame('tool-output-available', {'toolCallId': 'ghost', 'output': 1}));
    expect(draft.isEmpty, isTrue);

    draft.applyFrame(
        frame('tool-input-available', {'toolCallId': 'c2', 'toolName': 'bash'}));
    draft.applyFrame(frame(
        'tool-output-error', {'toolCallId': 'c2', 'errorText': '炸了'}));
    final tool = draft.toUiMessage('m1').parts.single as ToolPart;
    expect(tool.isError, isTrue);
    expect(tool.errorText, '炸了');
  });

  test('SseSplitter 处理拦腰截断与 [DONE]', () {
    final splitter = SseSplitter();
    var split = splitter.push('data: {"type": "text-delta", "id": "t1", "del');
    expect(split.frames, isEmpty);
    expect(split.done, isFalse);
    split = splitter.push('ta": "hi"}\n\ndata: [DONE]\n\n');
    expect(split.done, isTrue);
    expect(split.frames, hasLength(1));
    expect(split.frames.single['delta'], 'hi');
  });

  test('SseSplitter 忽略心跳与残帧', () {
    final splitter = SseSplitter();
    final split = splitter.push(': ping\n\ndata: not-json\n\n');
    expect(split.frames, isEmpty);
    expect(split.done, isFalse);
  });

  test('SseSplitter 兼容 CRLF 换行', () {
    final splitter = SseSplitter();
    final split = splitter.push(
        'data: {"type": "text-delta", "delta": "hi"}\r\n\r\n');
    expect(split.frames, hasLength(1));
    expect(split.frames.single['delta'], 'hi');
    expect(split.done, isFalse);
  });

  test('thinking 块被切到两段仍剥干净', () {
    final draft = StreamDraft();
    draft.applyFrame({'type': 'text-delta', 'delta': '结论<think'});
    // 工具打断后新开文本段，残尾拼下段再剥。
    draft.applyFrame({
      'type': 'tool-input-available',
      'toolCallId': 'c9',
      'toolName': 'bash',
    });
    draft.applyFrame(
        {'type': 'text-delta', 'delta': 'ing>内部推导</thinking>继续'});
    final msg = draft.toUiMessage('d2');
    final texts =
        msg.parts.whereType<TextPart>().map((p) => p.text).join();
    expect(texts, '结论继续');
    expect(texts.contains('think'), isFalse);
  });

  test('流式正文 thinking 块逐段剥除', () {
    final draft = StreamDraft();
    draft.applyFrame({
      'type': 'text-delta',
      'delta': '结论<thinking>内部推导</thinking>继续',
    });
    final msg = draft.toUiMessage('d1');
    expect(msg.parts, hasLength(1));
    final body = (msg.parts[0] as TextPart).text;
    expect(body, '结论继续');
  });
}
