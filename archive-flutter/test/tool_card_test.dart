import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pideck/api/chat.dart';
import 'package:pideck/pages/tool_card.dart';

void main() {
  test('prettyToolValue：字符串原样，对象 pretty JSON', () {
    expect(prettyToolValue('raw'), 'raw');
    expect(prettyToolValue(null), '');
    expect(prettyToolValue({'b': 1, 'a': [1, 2]}),
        '{\n  "b": 1,\n  "a": [\n    1,\n    2\n  ]\n}');
  });

  test('prettyToolValue：超长截断带字符数', () {
    final long = 'x' * 5000;
    final out = prettyToolValue(long);
    expect(out.endsWith('… (5000 chars)'), isTrue);
    expect(out.length, lessThan(long.length));
  });

  testWidgets('ToolCard：状态行与展开内容', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: ToolCard(
            part: ToolPart(
              toolName: 'read',
              callId: 'c1',
              isError: false,
              input: {'path': 'a.txt'},
              output: 'hello',
            ),
          ),
        ),
      ),
    );
    expect(find.text('read'), findsOneWidget);
    // 折叠态显示一行输入摘要，完整输入/输出展开后才可见。
    expect(find.text('a.txt'), findsOneWidget);
    expect(find.text('hello'), findsNothing);
    await tester.tap(find.text('read'));
    await tester.pumpAndSettle();
    expect(find.textContaining('a.txt'), findsWidgets);
    expect(find.text('hello'), findsOneWidget);
  });

  testWidgets('ToolCard：报错态标红且无内容时不可展开', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: ToolCard(
            part: ToolPart(
              toolName: 'bash',
              callId: 'c2',
              isError: true,
              errorText: '炸了',
            ),
          ),
        ),
      ),
    );
    expect(find.text('执行失败'), findsOneWidget);
    await tester.tap(find.text('bash'));
    await tester.pumpAndSettle();
    expect(find.text('炸了'), findsOneWidget);
  });
}
