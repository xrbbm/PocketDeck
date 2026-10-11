import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pideck/api/models.dart';

void main() {
  test('WebState 解析 /api/state 结构', () {
    final json = jsonDecode('''
{
  "projects": [{"id": "p1", "name": "demo", "path": "/tmp/demo", "lastOpenedAt": 1751726400000}],
  "sessions": [{"id": "s1", "projectId": "p1", "title": "hello", "status": "active",
                "backend": "pi", "updatedAt": 1751726400000}],
  "runtimes": [{"sessionId": "s1", "agentId": "a1", "status": "running"}],
  "pendingUiRequests": [{"sessionId": "s1", "agentId": "a1", "runtimeGeneration": 3,
    "requestId": "r1", "method": "confirm", "title": "继续吗？", "options": ["继续", "取消"]}]
}''') as Map<String, dynamic>;

    final state = WebState.fromJson(json);
    expect(state.projects, hasLength(1));
    expect(state.projects.first.name, 'demo');
    expect(state.sessions.first.title, 'hello');
    expect(state.runtimeOf('s1')?.isBusy, isTrue);
    expect(state.runtimeOf('nope'), isNull);
    final pending = state.pendingUiOf('s1');
    expect(pending?.method, 'confirm');
    expect(pending?.options, ['继续', '取消']);
  });

  test('缺字段时不抛异常', () {
    final state = WebState.fromJson({});
    expect(state.projects, isEmpty);
    expect(state.pendingUiOf('x'), isNull);
  });

  test('模型可选字段与 ctx 展示', () {
    final full = AvailableModel.fromJson({
      'id': 'muse-spark',
      'provider': 'pi',
      'name': 'Muse Spark 1.3',
      'contextWindow': 1049000,
      'reasoning': true,
      'thinkingLevels': ['Default', 'High', 'Xhigh'],
    });
    expect(full.display, 'Muse Spark 1.3');
    expect(full.contextLabel, '104.9万 ctx');
    expect(full.levels, ['Default', 'High', 'Xhigh']);

    // reasoningEfforts 回退 + 整万去尾零。
    final efforts = AvailableModel.fromJson({
      'id': 'deepseek',
      'provider': 'dsh',
      'contextWindow': 200000,
      'reasoningEfforts': [
        {'id': 'off'},
        {'id': 'high'},
      ],
    });
    expect(efforts.display, 'deepseek');
    expect(efforts.contextLabel, '20万 ctx');
    expect(efforts.levels, ['off', 'high']);

    // 缺字段：不显示 ctx/档位，不抛异常。
    final bare = AvailableModel.fromJson({'id': 'x', 'provider': 'y'});
    expect(bare.contextLabel, '');
    expect(bare.levels, isEmpty);
  });

  test('提示词库列表解析', () {
    final list = PromptList.fromJson({
      'categories': [
        {'slug': 'code', 'name': '编程', 'count': 3},
      ],
      'prompts': [
        {
          'slug': 'review',
          'title': '代码评审',
          'category': 'code',
          'subcategory': 'review',
          'tags': ['cr'],
          'description': '审代码',
        },
      ],
    });
    expect(list.categories.first.name, '编程');
    expect(list.prompts.first.slug, 'review');
    expect(list.prompts.first.tags, ['cr']);
    // 缺字段不抛异常。
    expect(PromptList.fromJson({}).prompts, isEmpty);
  });

  test('strips 模型解析：缺字段不抛异常', () {
    final change = SessionFileChange.fromJson({'path': 'a/b.ts', 'count': 3});
    expect(change.basename, 'b.ts');
    expect(change.originalContent, '');
    final win = SessionFileChange.fromJson({'path': r'a\b.ts'});
    expect(win.basename, 'b.ts');
    final sub = SubagentEntry.fromJson(
        {'id': '1', 'type': 'Explore', 'status': 'running'});
    expect(sub.description, '');
    expect(sub.result, isNull);
    final todo = SessionTodoSnapshot.fromJson({
      'planId': 7,
      'todos': [
        {'id': 1, 'text': '做 A', 'status': 'completed'},
        {'id': 2, 'text': '做 B', 'status': 'in_progress'},
      ],
    });
    expect(todo.planId, 7);
    expect(todo.todos.first.completed, isTrue);
    expect(todo.todos.last.inProgress, isTrue);
    expect(SessionTodoSnapshot.fromJson(null).todos, isEmpty);
    expect(SessionFileChange.fromJson({}).path, '');
  });

  test('会话记录解析模型/档位偏好', () {
    final withPref = WebSession.fromJson({
      'id': 's1',
      'projectId': 'p',
      'title': 't',
      'status': 'idle',
      'model': {'provider': 'pi', 'modelId': 'm1', 'modelName': 'M One'},
      'thinkingLevel': 'Xhigh',
    });
    expect(withPref.modelId, 'm1');
    expect(withPref.modelProvider, 'pi');
    expect(withPref.modelName, 'M One');
    expect(withPref.thinkingLevel, 'Xhigh');

    final bare = WebSession.fromJson({'id': 's2'});
    expect(bare.modelId, isNull);
    expect(bare.thinkingLevel, isNull);
  });
}
