import 'package:flutter_test/flutter_test.dart';
import 'package:pideck/api/models.dart';
import 'package:pideck/pages/sessions_drawer.dart';

WebSession sess(String id, String projectId, int updatedAt) => WebSession(
      id: id,
      projectId: projectId,
      title: 't-$id',
      status: 'idle',
      updatedAt: updatedAt,
    );

void main() {
  test('聊天组置顶，其余组按最新活跃倒序，组内按更新倒序', () {
    final groups = groupSessions(
      [
        sess('a', 'p1', 1),
        sess('b', 'builtin-chat', 2),
        sess('c', 'p2', 3),
        sess('d', 'builtin-chat', 4),
        sess('e', 'p1', 5),
      ],
      {'builtin-chat': 'Chat', 'p1': 'P1', 'p2': 'P2'},
    );
    expect(groups.map((g) => g.id), ['builtin-chat', 'p1', 'p2']);
    expect(groups.first.isChat, isTrue);
    expect(groups.first.title, 'Chat');
    expect(groups.first.sessions.map((s) => s.id), ['d', 'b']);
    expect(groups[1].sessions.map((s) => s.id), ['e', 'a']);
  });

  test('分支字段解析：parent/createdAt/forked 缺省安全', () {
    final plain = WebSession.fromJson({'id': 'a'});
    expect(plain.parentSessionId, isNull);
    expect(plain.createdAt, isNull);
    expect(plain.forked, isFalse);
    final forked = WebSession.fromJson({
      'id': 'b',
      'parentSessionId': 'a',
      'createdAt': 42,
      'forked': true,
    });
    expect(forked.parentSessionId, 'a');
    expect(forked.createdAt, 42);
    expect(forked.forked, isTrue);
  });

  test('同名 Chat 项目不吸入聊天组，未知项目回退未分组+id', () {
    final groups = groupSessions(
      [
        sess('a', 'other-id', 1),
        sess('b', 'x', 2),
      ],
      {'other-id': 'Chat'},
    );
    // 只认内建 id：取名叫 Chat 的真实项目也留在普通组。
    expect(groups.first.sessions, isEmpty);
    final ungrouped =
        groups.firstWhere((g) => g.id == 'x', orElse: () => groups.last);
    expect(ungrouped.id, 'x');
    expect(ungrouped.title, '(未分组 x)');
  });
}
