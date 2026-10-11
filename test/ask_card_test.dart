import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pideck/api/chat.dart';
import 'package:pideck/api/models.dart';
import 'package:pideck/pages/ask_card.dart';

void main() {
  test('splitAskOption：管道与破折号拆分', () {
    expect(splitAskOption('继续|推荐').label, '继续');
    expect(splitAskOption('继续|推荐').description, '推荐');
    expect(splitAskOption('开始执行 — 先跑测试').label, '开始执行');
    expect(splitAskOption('开始执行 — 先跑测试').description, '先跑测试');
    // 普通连字符不拆。
    expect(splitAskOption('well-known').description, isNull);
    expect(splitAskOption('就这样').label, '就这样');
  });

  test('serializeBatchAnswers：envelope 格式', () {
    final questions = [
      const WebBatchQuestion(id: 'q1', type: 'select', question: '选一个'),
      const WebBatchQuestion(id: 'q2', type: 'confirm', question: '确定？'),
    ];
    final out = serializeBatchAnswers(questions, {
      'q1': 'A',
      'q2': true,
    });
    expect(
      out,
      '{"answers":[{"id":"q1","type":"select","value":"A","label":"A","wasCustom":false},'
      '{"id":"q2","type":"confirm","value":true,"label":"true","wasCustom":false}]}',
    );
  });

  testWidgets('单题 confirm：是/否与取消应答', (tester) async {
    Map<String, dynamic>? got;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AskCard(
            request: const WebPendingUiRequest(
              sessionId: 's',
              agentId: 'a',
              runtimeGeneration: 1,
              requestId: 'r',
              method: 'confirm',
              title: '继续吗？',
            ),
            busy: false,
            onRespond: (r) => got = r,
          ),
        ),
      ),
    );
    await tester.tap(find.text('是'));
    expect(got, {'confirmed': true});
    await tester.tap(find.text('取消'));
    expect(got, {'cancelled': true});
  });

  testWidgets('单题 select：先选中再点提交，✎ 项过滤', (tester) async {
    Map<String, dynamic>? got;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AskCard(
            request: const WebPendingUiRequest(
              sessionId: 's',
              agentId: 'a',
              runtimeGeneration: 1,
              requestId: 'r',
              method: 'select',
              title: '选一个',
              options: ['A|推荐', '✎手势项', 'B'],
            ),
            busy: false,
            onRespond: (r) => got = r,
          ),
        ),
      ),
    );
    expect(find.text('✎手势项'), findsNothing);
    // 点选项只选中，不提交。
    await tester.tap(find.text('A'));
    await tester.pump();
    expect(got, isNull);
    await tester.tap(find.text('提交'));
    expect(got, {'value': 'A|推荐'});
  });

  testWidgets('批量：逐题作答后序列化提交', (tester) async {
    Map<String, dynamic>? got;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AskCard(
            request: WebPendingUiRequest(
              sessionId: 's',
              agentId: 'a',
              runtimeGeneration: 1,
              requestId: 'r',
              method: 'ask_question',
              title: '批量',
              batchQuestions: const [
                WebBatchQuestion(
                    id: 'q1', type: 'confirm', question: '第一题？'),
                WebBatchQuestion(
                    id: 'q2', type: 'input', question: '第二题？'),
              ],
            ),
            busy: false,
            onRespond: (r) => got = r,
          ),
        ),
      ),
    );
    expect(find.text('1/2'), findsOneWidget);
    await tester.tap(find.text('是'));
    await tester.pump();
    expect(find.text('2/2'), findsOneWidget);
    await tester.enterText(find.byType(TextField), '答案');
    await tester.pump();
    await tester.tap(find.text('提交'));
    expect(
      got?['value'],
      '{"answers":[{"id":"q1","type":"confirm","value":true,"label":"true","wasCustom":false},'
      '{"id":"q2","type":"input","value":"答案","label":"答案","wasCustom":false}]}',
    );
  });

  testWidgets('批量 select：选中后点下一题/提交才推进', (tester) async {
    Map<String, dynamic>? got;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AskCard(
            request: const WebPendingUiRequest(
              sessionId: 's',
              agentId: 'a',
              runtimeGeneration: 1,
              requestId: 'r',
              method: 'ask_question',
              title: '批量',
              batchQuestions: [
                WebBatchQuestion(
                  id: 'q1',
                  type: 'select',
                  question: '第一题？',
                  allowOther: false,
                  options: [
                    WebBatchOption(label: '甲', value: 'a'),
                    WebBatchOption(label: '乙', value: 'b'),
                  ],
                ),
                WebBatchQuestion(
                  id: 'q2',
                  type: 'select',
                  question: '第二题？',
                  allowOther: false,
                  options: [
                    WebBatchOption(label: '丙', value: 'c'),
                  ],
                ),
              ],
            ),
            busy: false,
            onRespond: (r) => got = r,
          ),
        ),
      ),
    );
    // 点选项只选中，不推进。
    await tester.tap(find.text('甲'));
    await tester.pump();
    expect(got, isNull);
    expect(find.text('1/2'), findsOneWidget);
    await tester.tap(find.text('下一题'));
    await tester.pump();
    expect(find.text('2/2'), findsOneWidget);
    await tester.tap(find.text('丙'));
    await tester.pump();
    expect(got, isNull);
    await tester.tap(find.text('提交'));
    expect(got?['value'], contains('"id":"q1"'));
    expect(got?['value'], contains('"value":"a"'));
    expect(got?['value'], contains('"value":"c"'));
  });

  testWidgets('批量 multi：多选后点提交才提交', (tester) async {
    Map<String, dynamic>? got;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AskCard(
            request: const WebPendingUiRequest(
              sessionId: 's',
              agentId: 'a',
              runtimeGeneration: 1,
              requestId: 'r',
              method: 'ask_question',
              title: '批量',
              batchQuestions: [
                WebBatchQuestion(
                  id: 'q1',
                  type: 'multi_select',
                  question: '多选题？',
                  options: [
                    WebBatchOption(label: '甲', value: 'a'),
                    WebBatchOption(label: '乙', value: 'b'),
                  ],
                ),
              ],
            ),
            busy: false,
            onRespond: (r) => got = r,
          ),
        ),
      ),
    );
    await tester.tap(find.text('甲'));
    await tester.pump();
    await tester.tap(find.text('乙'));
    await tester.pump();
    expect(got, isNull);
    await tester.tap(find.text('提交'));
    expect(got?['value'], contains('["a","b"]'));
  });

  testWidgets('批量 select：最后动哪里就提交哪里', (tester) async {
    Map<String, dynamic>? got;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AskCard(
            request: const WebPendingUiRequest(
              sessionId: 's',
              agentId: 'a',
              runtimeGeneration: 1,
              requestId: 'r',
              method: 'ask_question',
              title: '批量',
              batchQuestions: [
                WebBatchQuestion(
                  id: 'q1',
                  type: 'select',
                  question: '选一个？',
                  options: [
                    WebBatchOption(label: '甲', value: 'a'),
                  ],
                ),
              ],
            ),
            busy: false,
            onRespond: (r) => got = r,
          ),
        ),
      ),
    );
    // 先选手输后点选项：只发选项，手输的不发。
    await tester.enterText(find.byType(TextField), '自选');
    await tester.pump();
    await tester.tap(find.text('甲'));
    await tester.pump();
    await tester.tap(find.text('提交'));
    expect(got?['value'], contains('"value":"a"'));
    expect(got?['value'], isNot(contains('自选')));
    // 反过来：先点选项，哪怕手输框里有旧字，只要最后点的是输入框，就发手输。
    await tester.tap(find.text('甲'));
    await tester.pump();
    await tester.tap(find.byType(TextField));
    await tester.pump();
    await tester.tap(find.text('提交'));
    expect(got?['value'], contains('"value":"自选"'));
  });

  testWidgets('单题 select：右下角取消+提交同行', (tester) async {
    Map<String, dynamic>? got;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AskCard(
            request: const WebPendingUiRequest(
              sessionId: 's',
              agentId: 'a',
              runtimeGeneration: 1,
              requestId: 'r-single-layout',
              method: 'select',
              title: '选一个',
              options: ['A'],
            ),
            busy: false,
            onRespond: (r) => got = r,
          ),
        ),
      ),
    );
    // 单题 select 底部动作行：取消与提交同一 Row 右下（与旧双提交区分）。
    final row = find
        .ancestor(
          of: find.text('提交'),
          matching: find.byType(Row),
        )
        .first;
    expect(
      find.descendant(of: row, matching: find.text('取消')),
      findsOneWidget,
    );
    // 未选中、未手输时提交禁用：点了也不应答。
    await tester.tap(find.text('提交'));
    await tester.pump();
    expect(got, isNull);
  });

  testWidgets('单题 select：手输后点提交发手输（最后动哪里发哪里）',
      (tester) async {
    Map<String, dynamic>? got;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AskCard(
            request: const WebPendingUiRequest(
              sessionId: 's',
              agentId: 'a',
              runtimeGeneration: 1,
              requestId: 'r-single-custom',
              method: 'select',
              title: '选一个',
              options: ['A'],
            ),
            busy: false,
            onRespond: (r) => got = r,
          ),
        ),
      ),
    );
    await tester.enterText(find.byType(TextField), '自选');
    await tester.pump();
    await tester.tap(find.text('提交'));
    expect(got, {'value': '自选'});
    // 先手输后点选项：只发选项（与批量行为一致）。
    await tester.tap(find.text('A'));
    await tester.pump();
    await tester.tap(find.text('提交'));
    expect(got, {'value': 'A'});
  });

  testWidgets('单题 select：allowOther=false 时无手输口', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AskCard(
            request: const WebPendingUiRequest(
              sessionId: 's',
              agentId: 'a',
              runtimeGeneration: 1,
              requestId: 'r-single-noother',
              method: 'select',
              title: '选一个',
              options: ['A'],
              allowOther: false,
            ),
            busy: false,
            onRespond: (_) {},
          ),
        ),
      ),
    );
    expect(find.byType(TextField), findsNothing);
    expect(find.text('取消'), findsOneWidget);
    expect(find.text('提交'), findsOneWidget);
  });

  testWidgets('单题 input：prefill 预填正文', (tester) async {
    Map<String, dynamic>? got;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AskCard(
            request: const WebPendingUiRequest(
              sessionId: 's',
              agentId: 'a',
              runtimeGeneration: 1,
              requestId: 'r-single-prefill',
              method: 'input',
              title: '填一下',
              prefill: '草稿',
            ),
            busy: false,
            onRespond: (r) => got = r,
          ),
        ),
      ),
    );
    expect(find.text('草稿'), findsOneWidget);
    await tester.tap(find.text('提交'));
    expect(got, {'value': '草稿'});
  });
}
