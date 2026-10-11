import 'package:flutter_test/flutter_test.dart';
import 'package:pideck/main.dart';
import 'package:pideck/pages/chat_page.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  testWidgets('shows pair page when nothing is saved', (tester) async {
    SharedPreferences.setMockInitialValues({});
    await tester.pumpWidget(const PiDeckApp());
    await tester.pumpAndSettle();
    expect(find.text('配对 PiDeck'), findsOneWidget);
  });

  test('ChatDraftBox 存取删：草稿随会话隔离，删会话清孤儿', () {
    ChatDraftBox.dropFor('s1');
    ChatDraftBox.dropFor('s2');
    ChatDraftBox.box['s1'] = (text: '没发完', images: const []);
    expect(ChatDraftBox.box['s1']?.text, '没发完');
    expect(ChatDraftBox.box.containsKey('s2'), isFalse);
    ChatDraftBox.dropFor('s1');
    expect(ChatDraftBox.box.containsKey('s1'), isFalse);
  });

  test('输入条对齐网页：思考档位回退全集与网页一致', () {
    // 对齐网页 WEB_THINKING_LEVELS（off/minimal/low/medium/high/xhigh/max）。
    expect(kThinkingLevelFallback,
        ['off', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max']);
  });
}
