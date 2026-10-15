import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pideck/api/api_client.dart';
import 'package:pideck/api/models.dart';
import 'package:pideck/pages/workspace_drawer.dart';
import 'package:pideck/pairing_store.dart';

ApiClient clientWith(Map<String, dynamic> Function(http.BaseRequest req) stub) {
  return ApiClient(
    Pairing(baseUrl: 'http://h:3210'),
    MockClient((req) async {
      final body = stub(req);
      return http.Response(jsonEncode(body), 200);
    }),
  );
}

void main() {
  test('fetchModels 解析模型列表（默认 force=1 绕缓存）', () async {
    final api = clientWith(
      (req) {
        expect(req.url.path, '/api/models');
        expect(req.url.queryParameters['force'], '1');
        return {
          'models': [
            {'id': 'm1', 'provider': 'p1', 'name': 'M One'},
            {'id': 'm2', 'provider': 'p2'},
          ],
        };
      },
    );
    final models = await api.fetchModels();
    expect(models, hasLength(2));
    expect(models[0].display, 'M One');
    expect(models[1].display, 'm2');
  });

  test('abort 走 runtime/abort 并携带 target', () async {
    var sawPath = '';
    Map<String, dynamic>? sawBody;
    final api = ApiClient(
      Pairing(baseUrl: 'http://h:3210'),
      MockClient((req) async {
        sawPath = req.url.path;
        sawBody = jsonDecode(req.body) as Map<String, dynamic>;
        return http.Response(
          jsonEncode({
            'result': {
              'ok': true,
              'value': {'target': {}, 'value': null},
            },
          }),
          200,
        );
      }),
    );
    await api.abortRuntime('s', 'a1', 3);
    expect(sawPath, '/api/sessions/s/runtime/abort');
    expect(sawBody?['target'],
        {'sessionId': 's', 'agentId': 'a1', 'runtimeGeneration': 3});
  });

  test('runtime 命令失败抛 error.code', () async {
    final api = clientWith(
      (_) => {
        'result': {
          'ok': false,
          'error': {'code': 'SESSION_RUNTIME_BUSY'},
        },
      },
    );
    expect(
      () => api.abortRuntime('s', 'a', 0),
      throwsA(
        isA<ApiException>().having(
          (e) => e.message,
          'message',
          'SESSION_RUNTIME_BUSY',
        ),
      ),
    );
  });

  test('updateSessionRecord 只带非空字段', () async {
    var sawPath = '';
    Map<String, dynamic>? sawBody;
    final api = ApiClient(
      Pairing(baseUrl: 'http://h:3210'),
      MockClient((req) async {
        sawPath = req.url.path;
        sawBody = jsonDecode(req.body) as Map<String, dynamic>;
        return http.Response(jsonEncode({}), 200);
      }),
    );
    await api.updateSessionRecord(
      's1',
      modelProvider: 'p',
      modelId: 'm',
      modelName: 'M',
      thinkingLevel: 'High',
    );
    expect(sawPath, '/api/sessions/s1/update');
    expect(sawBody, {
      'model': {'provider': 'p', 'modelId': 'm', 'modelName': 'M'},
      'thinkingLevel': 'High',
    });
    // 空调用不发请求：无断言即覆盖（MockClient 未被调用则无影响）。
    await api.updateSessionRecord('s1');
  });

  test('fetchRuntimeContextUsage 走 runtime/state 并解析用量', () async {
    var sawPath = '';
    final api = clientWith((req) {
      sawPath = req.url.path;
      return {
        'result': {
          'ok': true,
          'value': {
            'target': {},
            'value': {
              'contextTokens': 7200,
              'contextWindow': 10000,
              'inputTokens': 7000,
              'outputTokens': 200,
            },
          },
        },
      };
    });
    final usage = await api.fetchRuntimeContextUsage('s', 'a', 1);
    expect(sawPath, '/api/sessions/s/runtime/state');
    expect(usage.contextTokens, 7200);
    expect(usage.percent, closeTo(72, 0.001));
    expect(usage.hasWindow, isTrue);
  });

  test('edit/delete/prepareResend 走对应 runtime 命令', () async {
    final paths = <String>[];
    final api = clientWith((req) {
      paths.add(req.url.path);
      final body = req is http.Request
          ? jsonDecode(req.body) as Map<String, dynamic>
          : <String, dynamic>{};
      if (req.url.path.endsWith('prepare-resend')) {
        expect(body['messageId'], 'm1');
        return {
          'result': {
            'ok': true,
            'value': {
              'target': {},
              'value': {
                'text': 'hello',
                'images': [
                  {'type': 'image', 'mimeType': 'image/png', 'data': 'QUJD'},
                ],
              },
            },
          },
        };
      }
      return {
        'result': {
          'ok': true,
          'value': {'target': {}, 'value': null},
        },
      };
    });
    await api.editRuntimeMessage('s', 'a', 0, 'm1', 'new');
    await api.deleteRuntimeMessage('s', 'a', 0, 'm1');
    final prepared = await api.prepareResend('s', 'a', 0, 'm1');
    expect(paths, [
      '/api/sessions/s/runtime/edit-message',
      '/api/sessions/s/runtime/delete-message',
      '/api/sessions/s/runtime/prepare-resend',
    ]);
    expect(prepared.text, 'hello');
    expect(prepared.images, ['data:image/png;base64,QUJD']);
  });

  test('rename/delete/copy/clone/restart/compact 命令形状', () async {
    final paths = <String>[];
    final bodies = <Map<String, dynamic>>[];
    final api = ApiClient(
      Pairing(baseUrl: 'http://h:3210'),
      MockClient((req) async {
        paths.add(req.url.path);
        final raw = req.body;
        bodies.add(raw.isEmpty
            ? <String, dynamic>{}
            : jsonDecode(raw) as Map<String, dynamic>);
        if (req.url.path.endsWith('/copy')) {
          return http.Response(
              jsonEncode({
                'result': {
                  'session': {'id': 'copy1'},
                },
              }),
              200);
        }
        if (req.url.path.endsWith('/clone')) {
          return http.Response(
              jsonEncode({
                'result': {
                  'ok': true,
                  'value': {
                    'target': {},
                    'value': {
                      'session': {'id': 'clone1'}
                    },
                  },
                },
              }),
              200);
        }
        return http.Response(
            jsonEncode({
              'result': {
                'ok': true,
                'value': {'target': {}, 'value': null},
              },
            }),
            200);
      }),
    );
    await api.renameSession('s', '新标题');
    await api.deleteSession('s');
    final copyId = await api.copySession('s');
    final cloneId = await api.cloneRuntime('s', 'a', 2);
    await api.restartRuntime('s', 'a', 2);
    await api.compactRuntime('s', 'a', 2, '总结一下');
    expect(paths, [
      '/api/sessions/s/update',
      '/api/sessions/s/delete',
      '/api/sessions/s/copy',
      '/api/sessions/s/runtime/clone',
      '/api/sessions/s/runtime/restart',
      '/api/sessions/s/runtime/compact',
    ]);
    expect(bodies[0]['title'], '新标题');
    expect(copyId, 'copy1');
    expect(cloneId, 'clone1');
    expect(bodies[5]['prompt'], '总结一下');
    expect(bodies[5]['target'],
        {'sessionId': 's', 'agentId': 'a', 'runtimeGeneration': 2});
  });

  test('fetchPrompts/fetchPromptContent 解析', () async {
    final api = clientWith((req) {
      if (req.url.path == '/api/prompts') {
        expect(req.url.queryParameters['page'], '1');
        return {
          'categories': [
            {'slug': 'c', 'name': 'C'},
          ],
          'prompts': [
            {'slug': 's', 'title': 'T', 'category': 'c'},
          ],
        };
      }
      expect(req.url.path, '/api/prompts/s');
      return {
        'detail': {'promptContent': 'body-text'},
      };
    });
    final list = await api.fetchPrompts(search: 'x');
    expect(list.prompts.first.title, 'T');
    final content = await api.fetchPromptContent('s', 'c');
    expect(content, 'body-text');
  });

  test('strips 三接口路径与形状', () async {
    final paths = <String>[];
    final api = clientWith((req) {
      paths.add(req.url.path);
      if (req.url.path.endsWith('/file-changes')) {
        return {
          'changes': [
            {'path': 'src/a.ts', 'count': 2},
          ],
        };
      }
      if (req.url.path.endsWith('/subagents')) {
        return {
          'subagents': [
            {'id': 's1', 'type': 'Explore', 'status': 'completed'},
          ],
        };
      }
      return {
        'todo': {
          'planId': 3,
          'todos': [
            {'id': 1, 'text': 't', 'status': 'pending'},
          ],
        },
      };
    });
    final changes = await api.fetchSessionFileChanges('s');
    final subs = await api.fetchSessionSubagents('s');
    final todo = await api.fetchSessionTodo('s');
    expect(paths, [
      '/api/sessions/s/file-changes',
      '/api/sessions/s/subagents',
      '/api/sessions/s/todo',
    ]);
    expect(changes.first.path, 'src/a.ts');
    expect(subs.first.type, 'Explore');
    expect(todo?.planId, 3);
    // todo 为 null 时返回 null。
    final emptyApi = clientWith((_) => {});
    expect(await emptyApi.fetchSessionTodo('s'), isNull);
    expect(await emptyApi.fetchSessionFileChanges('s'), isEmpty);
  });

  test('工作区 Git/文件五接口路径与形状', () async {
    final paths = <String>[];
    final api = clientWith((req) {
      paths.add(req.url.path);
      if (req.url.path == '/api/git/status') {
        return {
          'repo': true,
          'branch': {'current': 'main', 'branches': ['main']},
          'groups': {
            'merge': [],
            'index': [
              {'path': 'a.ts', 'letter': 'M'},
            ],
            'workingTree': [],
            'untracked': [],
          },
        };
      }
      if (req.url.path == '/api/git/diff') {
        expect(req.url.queryParameters['group'], 'index');
        expect(req.url.queryParameters['path'], 'a.ts');
        return {
          'diff': {'originalContent': 'a', 'modifiedContent': 'b'},
        };
      }
      if (req.url.path == '/api/git/log') {
        expect(req.url.queryParameters['limit'], '12');
        return {
          'commits': [
            {
              'hash': 'abc1234',
              'shortHash': 'abc1234',
              'message': 'init',
              'authorName': 'dev',
              'authorDate': 1700000000,
            },
          ],
        };
      }
      if (req.url.path == '/api/files') {
        return {
          'nodes': [
            {
              'name': 'src',
              'relativePath': 'src',
              'type': 'directory',
            },
          ],
        };
      }
      expect(req.url.path, '/api/file-content');
      expect(req.url.queryParameters['path'], 'a.ts');
      return {'content': 'hello', 'truncated': true};
    });
    final status = await api.fetchGitStatus('p1');
    expect(status.repo, isTrue);
    expect(status.branch?.current, 'main');
    expect(status.groups?.index.first.path, 'a.ts');
    expect(status.groups?.groupOf('a.ts'), 'index');
    expect(status.groups?.groupOf('missing'), isNull);
    final diff = await api.fetchGitDiff('p1', 'index', 'a.ts');
    expect(diff?.modified, 'b');
    final commits = await api.fetchGitLog('p1');
    expect(commits.first.message, 'init');
    final nodes = await api.fetchFileList('p1');
    expect(nodes.first.isDirectory, isTrue);
    final file = await api.fetchFileContent('p1', 'a.ts');
    expect(file.content, 'hello');
    expect(file.truncated, isTrue);
    expect(paths, [
      '/api/git/status',
      '/api/git/diff',
      '/api/git/log',
      '/api/files',
      '/api/file-content',
    ]);
    // 无仓库 + 空负载不抛异常。
    final emptyApi = clientWith((_) => {'repo': false});
    final empty = await emptyApi.fetchGitStatus('p1');
    expect(empty.repo, isFalse);
    expect(GitResourceGroups.fromJson(null).total, 0);
    expect(FileContentResult.fromJson(null).content, isNull);
    expect(const GitResource(path: 'a\\b.ts', letter: 'M').basename, 'b.ts');
  });

  test('usage 空值回退：无窗口不显示圆环', () {
    expect(const WebContextUsage().hasWindow, isFalse);
    expect(WebContextUsage.fromJson(null).percent, 0);
    expect(
      const WebContextUsage(contextPercent: 33.3).percent,
      closeTo(33.3, 0.001),
    );
  });

  test('git 日期：毫秒直用，秒级才x1000', () {
    // 真机 bug 回归：服务端 authorDate 是毫秒，之前 ts*1000 导致年份 5 万多。
    // 只断年份：DateTime 走本地时区，日边界会随机器飘。
    expect(formatGitDate(1778641622000).startsWith('2026-'), isTrue);
    expect(formatGitDate(1700000000).startsWith('2023-'), isTrue);
    expect(formatGitDate(0), '');
    expect(formatGitDate(-1), '');
  });

  test('工作区接口全带 projectId（D 批铁律）', () async {
    final queries = <String, Map<String, String>>{};
    final api = clientWith((req) {
      // 记录每个接口收到的查询参数。
      queries[req.url.path] = Map<String, String>.from(req.url.queryParameters);
      if (req.url.path == '/api/git/status') {
        return {'repo': false};
      }
      if (req.url.path == '/api/git/diff') {
        return {'diff': {'originalContent': '', 'modifiedContent': ''}};
      }
      if (req.url.path == '/api/git/log') return {'commits': []};
      if (req.url.path == '/api/files') return {'nodes': []};
      return {'content': ''};
    });
    await api.fetchGitStatus('p1');
    await api.fetchGitDiff('p1', 'index', 'a.ts');
    await api.fetchGitLog('p1');
    await api.fetchFileList('p1');
    await api.fetchFileContent('p1', 'a.ts');
    for (final e in queries.entries) {
      expect(e.value['projectId'], 'p1', reason: e.key);
    }
  });
}
