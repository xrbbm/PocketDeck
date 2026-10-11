import 'package:flutter_test/flutter_test.dart';
import 'package:pideck/api/branch_family.dart';
import 'package:pideck/api/models.dart';

WebSession s(String id,
        {String? parent, int? createdAt, bool forked = false}) =>
    WebSession(
        id: id,
        projectId: 'p',
        title: id,
        status: 'idle',
        createdAt: createdAt,
        parentSessionId: parent,
        forked: forked);

void main() {
  test('单会话返回自身（调用方隐藏）', () {
    expect(deriveBranchFamily([s('a')], 'a').map((e) => e.id), ['a']);
  });

  test('不存在的 id 返回空', () {
    expect(deriveBranchFamily([s('a')], 'nope'), isEmpty);
  });

  test('向上找根、向下收后代，按 createdAt 升序', () {
    final sessions = [
      s('root', createdAt: 1),
      s('child1', parent: 'root', createdAt: 3, forked: true),
      s('child2', parent: 'root', createdAt: 2, forked: true),
      s('grand', parent: 'child1', createdAt: 4),
      s('other', createdAt: 0),
    ];
    final family = deriveBranchFamily(sessions, 'grand');
    expect(family.map((e) => e.id), ['root', 'child2', 'child1', 'grand']);
  });

  test('环形 parent 不死循环', () {
    final sessions = [s('a', parent: 'b'), s('b', parent: 'a')];
    final family = deriveBranchFamily(sessions, 'a');
    expect(family.map((e) => e.id).toSet(), {'a', 'b'});
  });
}
