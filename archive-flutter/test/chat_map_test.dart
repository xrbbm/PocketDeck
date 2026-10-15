import 'package:flutter_test/flutter_test.dart';
import 'package:pideck/api/chat.dart';

ToolPart tool(String name, [dynamic input]) => ToolPart(
      toolName: name,
      callId: 'c-$name',
      isError: false,
      input: input,
    );

void main() {
  test('工具摘要取常见键首行，超长截断', () {
    expect(toolSummary(tool('read', {'path': '/a/b.txt'})), '/a/b.txt');
    expect(
        toolSummary(tool('bash', {
          'command': 'ls -la\n第二行'
        })),
        'ls -la');
    expect(toolSummary(tool('x', '纯字符串输入')), '纯字符串输入');
    expect(toolSummary(tool('x')), '');
    expect(toolSummary(tool('x', {'a': 1})), '');
    final long = 'a' * 100;
    expect(toolSummary(tool('x', {'path': long})).length, 81);
  });

  test('连续同名工具成组，异名与文本打断', () {
    final runs = groupToolRuns([
      tool('read'),
      tool('read'),
      tool('bash'),
      const TextPart('hi'),
      tool('read'),
    ]);
    expect(runs.length, 4);
    expect(runs[0].length, 2);
    expect(runs[1].length, 1);
    expect(runs[2].length, 1);
    expect(runs[2].first, isA<TextPart>());
    expect(runs[3].length, 1);
  });

  UiMessage toolMsg(String id, String name, [dynamic input]) => UiMessage(
        id: id,
        role: 'assistant',
        parts: [tool(name, input)],
      );

  test('跨消息合并连续同名纯工具消息', () {
    final runs = groupToolMessages([
      toolMsg('m1', 'read'),
      toolMsg('m2', 'read'),
      toolMsg('m3', 'bash'),
      const UiMessage(id: 't', role: 'assistant', parts: [TextPart('hi')]),
      toolMsg('m4', 'read'),
      const UiMessage(id: 'u', role: 'user', parts: [TextPart('q')]),
      toolMsg('m5', 'read'),
    ]);
    expect(runs.length, 6);
    expect(runs[0].map((m) => m.id), ['m1', 'm2']);
    expect(runs[1].map((m) => m.id), ['m3']);
    expect(runs[3].map((m) => m.id), ['m4']);
    expect(runs[5].map((m) => m.id), ['m5']);
  });
  test('用户+助理文本与思考映射', () {
    final user = mapChatMessage({
      'id': 'u1',
      'role': 'user',
      'text': '你好',
      'timestamp': 1,
    });
    expect(user.isUser, isTrue);
    expect((user.parts.single as TextPart).text, '你好');

    final asst = mapChatMessage({
      'id': 'a1',
      'role': 'assistant',
      'text': '在的',
      'thinking': '用户打招呼',
      'timestamp': 2,
    });
    expect(asst.isUser, isFalse);
    expect(asst.parts, hasLength(2));
    expect((asst.parts[0] as ReasoningPart).text, '用户打招呼');
    expect((asst.parts[1] as TextPart).text, '在的');
  });

  test('工具消息映射：正常与报错', () {
    final ok = mapChatMessage({
      'id': 't1',
      'role': 'tool',
      'text': 'read ok',
      'timestamp': 3,
      'meta': {
        'toolName': 'read',
        'args': '{"path": "a.txt"}',
        'result': 'hello',
      },
    });
    final part = ok.parts.single as ToolPart;
    expect(ok.isUser, isFalse);
    expect(part.toolName, 'read');
    expect(part.isError, isFalse);
    expect(part.input, {'path': 'a.txt'});
    expect(part.callId, 'hist-t1');

    // 报错判定：isError 或文本 ✗ 开头；args 非 JSON 原样保留。
    final err = mapChatMessage({
      'id': 't2',
      'role': 'tool',
      'text': '✗ 炸了',
      'timestamp': 4,
      'meta': {'toolName': 'bash', 'args': '已截断...'},
    });
    final errPart = err.parts.single as ToolPart;
    expect(errPart.isError, isTrue);
    expect(errPart.input, '已截断...');
    expect(errPart.errorText, '✗ 炸了');
  });

  test('用户图片映射与超限跳过', () {
    final msg = mapChatMessage({
      'id': 'u2',
      'role': 'user',
      'text': '看图',
      'timestamp': 5,
      'images': [
        {'type': 'image', 'mimeType': 'image/png', 'data': 'QUJD'},
        {'type': 'image', 'mimeType': 'image/png'}, // 无 data 跳过
      ],
    });
    expect(msg.parts, hasLength(2));
    final file = msg.parts[1] as FilePart;
    expect(file.dataUrl, 'data:image/png;base64,QUJD');
  });

  test('正文 thinking 块剥除、思考卡片保留', () {
    final msg = mapChatMessage({
      'id': 'm1',
      'role': 'assistant',
      'thinking': '内部推导',
      'text': '先说结论<thinking>内部推导</thinking>\n正文继续',
    });
    expect(msg.parts, hasLength(2));
    expect((msg.parts[0] as ReasoningPart).text, '内部推导');
    final body = (msg.parts[1] as TextPart).text;
    expect(body.contains('<thinking>'), isFalse);
    expect(body.contains('内部推导'), isFalse);
    expect(body.contains('正文继续'), isTrue);
  });

  test('纯 thinking 块正文不产生空 TextPart', () {
    final msg = mapChatMessage({
      'id': 'm2',
      'role': 'assistant',
      'thinking': '内部推导',
      'text': '<thinking>内部推导</thinking>',
    });
    expect(msg.parts, hasLength(1));
    expect(msg.parts[0], isA<ReasoningPart>());
  });

  test('用户原文不动（含 thinking 字样也保留）', () {
    final msg = mapChatMessage({
      'id': 'u1',
      'role': 'user',
      'text': '讲讲 <thinking> 标签',
    });
    expect((msg.parts[0] as TextPart).text, contains('<thinking>'));
  });
}
