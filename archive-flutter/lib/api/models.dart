/// PiDeck Web API 数据模型（对照 docs/api-contract.md）。
library;

/// GET /api/state 返回的项目记录。
class WebProject {
  const WebProject({
    required this.id,
    required this.name,
    required this.path,
    this.lastOpenedAt,
  });

  factory WebProject.fromJson(Map<String, dynamic> json) => WebProject(
        id: json['id'] as String? ?? '',
        name: json['name'] as String? ?? '',
        path: json['path'] as String? ?? '',
        lastOpenedAt: (json['lastOpenedAt'] as num?)?.toInt(),
      );

  final String id;
  final String name;
  final String path;
  final int? lastOpenedAt;
}

/// GET /api/state 返回的会话记录。
class WebSession {
  const WebSession({
    required this.id,
    required this.projectId,
    required this.title,
    required this.status,
    this.backend,
    this.updatedAt,
    this.createdAt,
    this.parentSessionId,
    this.forked = false,
    this.modelProvider,
    this.modelId,
    this.modelName,
    this.thinkingLevel,
  });

  factory WebSession.fromJson(Map<String, dynamic> json) {
    final model = json['model'];
    return WebSession(
      id: json['id'] as String? ?? '',
      projectId: json['projectId'] as String? ?? '',
      title: json['title'] as String? ?? '',
      status: json['status'] as String? ?? '',
      backend: json['backend'] as String?,
      updatedAt: (json['updatedAt'] as num?)?.toInt(),
      createdAt: (json['createdAt'] as num?)?.toInt(),
      parentSessionId: json['parentSessionId'] as String?,
      forked: json['forked'] as bool? ?? false,
      modelProvider: model is Map ? model['provider'] as String? : null,
      modelId: model is Map ? model['modelId'] as String? : null,
      modelName: model is Map ? model['modelName'] as String? : null,
      thinkingLevel: json['thinkingLevel'] as String?,
    );
  }

  final String id;
  final String projectId;
  final String title;
  final String status;
  final String? backend;
  final int? updatedAt;
  // 分支家族：clone/rewind-fork/桌面分支产生（对齐 webTypes parentSessionId/forked）。
  final int? createdAt;
  final String? parentSessionId;
  final bool forked;
  // 服务端已存的模型/档位偏好（idle 改偏好 + 主进程回写都经这里同步）。
  final String? modelProvider;
  final String? modelId;
  final String? modelName;
  final String? thinkingLevel;
}

/// 会话文件修改汇总（GET /api/sessions/{id}/file-changes changes[]）。
class SessionFileChange {
  const SessionFileChange({
      required this.path, required this.count, this.originalContent = ''});

  factory SessionFileChange.fromJson(Map<String, dynamic> json) =>
      SessionFileChange(
        path: json['path'] as String? ?? '',
        count: (json['count'] as num?)?.toInt() ?? 0,
        originalContent: json['originalContent'] as String? ?? '',
      );

  final String path;
  final int count;
  final String originalContent;

  String get basename {
    final parts = path.split(RegExp(r'[/\\]'));
    return parts.isEmpty ? path : parts.last;
  }
}

/// 子代理条目（GET /api/sessions/{id}/subagents subagents[]）。
class SubagentEntry {
  const SubagentEntry({
    required this.id,
    required this.type,
    required this.description,
    required this.status,
    this.result,
    this.error,
  });

  factory SubagentEntry.fromJson(Map<String, dynamic> json) => SubagentEntry(
        id: json['id'] as String? ?? '',
        type: json['type'] as String? ?? '',
        description: json['description'] as String? ?? '',
        status: json['status'] as String? ?? '',
        result: json['result'] as String?,
        error: json['error'] as String?,
      );

  final String id;
  final String type;
  final String description;
  final String status;
  final String? result;
  final String? error;
}

/// 会话 todo 快照（GET /api/sessions/{id}/todo todo）。
class SessionTodoSnapshot {
  const SessionTodoSnapshot({required this.planId, required this.todos});

  factory SessionTodoSnapshot.fromJson(Map<String, dynamic>? json) {
    if (json == null) return const SessionTodoSnapshot(planId: 0, todos: []);
    final raw = json['todos'];
    final items = <SessionTodoItem>[];
    if (raw is List) {
      for (final e in raw) {
        if (e is Map<String, dynamic>) items.add(SessionTodoItem.fromJson(e));
      }
    }
    return SessionTodoSnapshot(
      planId: (json['planId'] as num?)?.toInt() ?? 0,
      todos: items,
    );
  }

  final int planId;
  final List<SessionTodoItem> todos;
}

class SessionTodoItem {
  const SessionTodoItem(
      {required this.id, required this.text, required this.status});

  factory SessionTodoItem.fromJson(Map<String, dynamic> json) =>
      SessionTodoItem(
        id: (json['id'] as num?)?.toInt() ?? 0,
        text: json['text'] as String? ?? '',
        status: json['status'] as String? ?? '',
      );

  final int id;
  final String text;
  final String status;

  bool get completed => status == 'completed';
  bool get inProgress => status == 'in_progress';
}

/// GET /api/state 返回的运行时记录。
class WebRuntime {
  const WebRuntime({
    required this.sessionId,
    required this.agentId,
    required this.status,
    this.runtimeGeneration,
  });

  factory WebRuntime.fromJson(Map<String, dynamic> json) => WebRuntime(
        sessionId: json['sessionId'] as String? ?? '',
        agentId: json['agentId'] as String? ?? '',
        status: json['status'] as String? ?? '',
        runtimeGeneration: (json['runtimeGeneration'] as num?)?.toInt(),
      );

  final String sessionId;
  final String agentId;
  final String status;
  final int? runtimeGeneration;

  bool get isBusy => status == 'running' || status == 'starting';
}

/// 上下文用量（POST /api/sessions/{id}/runtime/state 的 value 直出）。
/// 对齐网页 WebContextUsage，供聊天页右上圆环显示。
class WebContextUsage {
  const WebContextUsage({
    this.contextTokens,
    this.contextWindow,
    this.contextPercent,
    this.contextOverflow = false,
    this.inputTokens,
    this.outputTokens,
  });

  factory WebContextUsage.fromJson(Map<String, dynamic>? json) {
    if (json == null) return const WebContextUsage();
    return WebContextUsage(
      contextTokens: (json['contextTokens'] as num?)?.toInt(),
      contextWindow: (json['contextWindow'] as num?)?.toInt(),
      contextPercent: (json['contextPercent'] as num?)?.toDouble(),
      contextOverflow: json['contextOverflow'] == true,
      inputTokens: (json['inputTokens'] as num?)?.toInt(),
      outputTokens: (json['outputTokens'] as num?)?.toInt(),
    );
  }

  final int? contextTokens;
  final int? contextWindow;
  final double? contextPercent;
  final bool contextOverflow;
  final int? inputTokens;
  final int? outputTokens;

  /// 显示用百分比：服务端直出优先，否则 tokens/window 换算（对齐网页 ContextRing）。
  double get percent {
    final p = contextPercent;
    if (p != null && p.isFinite) return p.clamp(0.0, 100.0).toDouble();
    final w = contextWindow ?? 0;
    if (w <= 0) return 0;
    return ((contextTokens ?? 0) / w * 100).clamp(0.0, 100.0).toDouble();
  }

  /// 有可用窗口才显示圆环（对齐网页 WebHeader 的 contextWindow > 0 门槛）。
  bool get hasWindow => (contextWindow ?? 0) > 0;
}

/// 批量问答的一道题（ask_question 统一走批量信封，这是主审批路径）。
class WebBatchOption {
  const WebBatchOption({required this.label, this.value, this.description});

  factory WebBatchOption.parse(dynamic raw) {
    if (raw is String) return WebBatchOption(label: raw, value: raw);
    if (raw is Map<String, dynamic>) {
      final label = raw['label'] as String? ?? '';
      return WebBatchOption(
        label: label,
        value: raw['value'] as String? ?? label,
        description: raw['description'] as String?,
      );
    }
    return WebBatchOption(label: '$raw', value: '$raw');
  }

  final String label;
  final String? value;
  final String? description;
}

class WebBatchQuestion {
  const WebBatchQuestion({
    required this.id,
    required this.type,
    required this.question,
    this.options = const [],
    this.allowOther,
    this.placeholder,
    this.prefill,
  });

  factory WebBatchQuestion.fromJson(Map<String, dynamic> json) =>
      WebBatchQuestion(
        id: json['id'] as String? ?? '',
        type: json['type'] as String? ?? 'input',
        question: json['question'] as String? ?? '',
        options: ((json['options'] as List? ?? []))
            .map(WebBatchOption.parse)
            .toList(),
        allowOther: json['allowOther'] as bool?,
        placeholder: json['placeholder'] as String?,
        prefill: json['prefill'] as String?,
      );

  final String id;
  final String type;
  final String question;
  final List<WebBatchOption> options;
  final bool? allowOther;
  final String? placeholder;
  final String? prefill;
}

/// 提示词库条目（YaoPromptItem 最小子集：列表展示 + 拉正文）。
class PromptItem {
  const PromptItem({
    required this.slug,
    required this.title,
    required this.category,
    this.subcategory = '',
    this.tags = const [],
    this.description = '',
  });

  factory PromptItem.fromJson(Map<String, dynamic> json) => PromptItem(
        slug: json['slug'] as String? ?? '',
        title: json['title'] as String? ?? '',
        category: json['category'] as String? ?? '',
        subcategory: json['subcategory'] as String? ?? '',
        tags: ((json['tags'] as List? ?? []).map((e) => '$e')).toList(),
        description: json['description'] as String? ?? '',
      );

  final String slug;
  final String title;
  final String category;
  final String subcategory;
  final List<String> tags;
  final String description;
}

/// 提示词分类。
class PromptCategory {
  const PromptCategory({required this.slug, required this.name, this.count = 0});

  factory PromptCategory.fromJson(Map<String, dynamic> json) =>
      PromptCategory(
        slug: json['slug'] as String? ?? '',
        name: json['name'] as String? ?? '',
        count: (json['count'] as num?)?.toInt() ?? 0,
      );

  final String slug;
  final String name;
  final int count;
}

/// 提示词列表结果。
class PromptList {
  const PromptList({required this.categories, required this.prompts});

  factory PromptList.fromJson(Map<String, dynamic> json) => PromptList(
        categories: ((json['categories'] as List? ?? [])
            .map((e) => PromptCategory.fromJson(e as Map<String, dynamic>))
            .toList()),
        prompts: ((json['prompts'] as List? ?? [])
            .map((e) => PromptItem.fromJson(e as Map<String, dynamic>))
            .toList()),
      );

  final List<PromptCategory> categories;
  final List<PromptItem> prompts;
}

/// Git 分支信息（对齐 shared/types/git.ts GitBranchInfo）。
class GitBranchInfo {
  const GitBranchInfo({this.current, this.branches = const []});

  factory GitBranchInfo.fromJson(Map<String, dynamic> json) => GitBranchInfo(
        current: json['current'] as String?,
        branches: ((json['branches'] as List? ?? []).map((e) => '$e')).toList(),
      );

  final String? current;
  final List<String> branches;
}

/// Git 单个变更资源（对齐 shared/types/git.ts GitResource）。
class GitResource {
  const GitResource({required this.path, required this.letter, this.oldPath});

  factory GitResource.fromJson(Map<String, dynamic> json) => GitResource(
        path: json['path'] as String? ?? '',
        letter: json['letter'] as String? ?? '',
        oldPath: json['oldPath'] as String?,
      );

  final String path;
  final String letter;
  final String? oldPath;

  String get basename {
    final parts = path.split(RegExp(r'[/\\]'));
    return parts.isEmpty ? path : parts.last;
  }
}

/// Git 按组分类的资源（merge/index/workingTree/untracked）。
class GitResourceGroups {
  const GitResourceGroups({
    this.merge = const [],
    this.index = const [],
    this.workingTree = const [],
    this.untracked = const [],
  });

  factory GitResourceGroups.fromJson(Map<String, dynamic>? json) {
    if (json == null) return const GitResourceGroups();
    List<GitResource> group(String key) {
      final raw = json[key];
      if (raw is! List) return const [];
      return [
        for (final e in raw)
          if (e is Map<String, dynamic>) GitResource.fromJson(e)
      ];
    }

    return GitResourceGroups(
      merge: group('merge'),
      index: group('index'),
      workingTree: group('workingTree'),
      untracked: group('untracked'),
    );
  }

  final List<GitResource> merge;
  final List<GitResource> index;
  final List<GitResource> workingTree;
  final List<GitResource> untracked;

  int get total =>
      merge.length + index.length + workingTree.length + untracked.length;

  /// 网页 DiffPreviewBody 的分组定位优先级：merge > index > workingTree > untracked。
  /// 路径归一化（\→/、去 ./ 前缀/首尾空格），重命名同时比 oldPath。
  String? groupOf(String path) {
    final want = _normPath(path);
    const order = ['merge', 'index', 'workingTree', 'untracked'];
    final groups = {
      'merge': merge,
      'index': index,
      'workingTree': workingTree,
      'untracked': untracked,
    };
    for (final key in order) {
      if (groups[key]!.any((r) =>
          _normPath(r.path) == want ||
          (r.oldPath != null && _normPath(r.oldPath!) == want))) {
        return key;
      }
    }
    return null;
  }
}

/// 路径归一化：反斜杠转正、去 ./ 前缀、去首尾空白（服务端/客户端分隔符不一致时仍能定位）。
String _normPath(String p) {
  var s = p.replaceAll('\\', '/').trim();
  while (s.startsWith('./')) {
    s = s.substring(2);
  }
  while (s.startsWith('/')) {
    s = s.substring(1);
  }
  return s;
}

/// Git 单个提交记录（对齐 shared/types/git.ts CommitEntry 的列表用字段）。
class CommitEntry {
  const CommitEntry({
    required this.hash,
    required this.shortHash,
    required this.message,
    this.authorName = '',
    this.authorDate = 0,
  });

  factory CommitEntry.fromJson(Map<String, dynamic> json) => CommitEntry(
        hash: json['hash'] as String? ?? '',
        shortHash: json['shortHash'] as String? ?? '',
        message: json['message'] as String? ?? '',
        authorName: json['authorName'] as String? ?? '',
        authorDate: (json['authorDate'] as num?)?.toInt() ?? 0,
      );

  final String hash;
  final String shortHash;
  final String message;
  final String authorName;
  final int authorDate;
}

/// 工作区文件节点（对齐 webTypes WebFileNodeLite）。
class WebFileNodeLite {
  const WebFileNodeLite(
      {required this.name, required this.relativePath, required this.type});

  factory WebFileNodeLite.fromJson(Map<String, dynamic> json) =>
      WebFileNodeLite(
        name: json['name'] as String? ?? '',
        relativePath: json['relativePath'] as String? ?? '',
        type: json['type'] as String? ?? 'file',
      );

  final String name;
  final String relativePath;
  final String type;

  bool get isDirectory => type == 'directory';
}

/// 文件内容结果（对齐 fetchFileContent 的结构化标记：tooLarge/binary/truncated）。
class FileContentResult {
  const FileContentResult(
      {this.content, this.tooLarge = false, this.truncated = false, this.binary = false});

  factory FileContentResult.fromJson(Map<String, dynamic>? json) {
    if (json == null) return const FileContentResult();
    return FileContentResult(
      content: json['content'] as String?,
      tooLarge: json['tooLarge'] == true,
      truncated: json['truncated'] == true,
      binary: json['binary'] == true,
    );
  }

  final String? content;
  final bool tooLarge;
  final bool truncated;
  final bool binary;
}

/// DSH 当前目标（对齐 webApi WebDshGoal；无目标返回 null）。
class DshGoal {
  const DshGoal({
    required this.objective,
    required this.phase,
    this.roundsStarted = 0,
    this.maxGoalRounds = 0,
  });

  factory DshGoal.fromJson(Map<String, dynamic> json) => DshGoal(
        objective: json['objective'] as String? ?? '',
        phase: json['phase'] as String? ?? '',
        roundsStarted: (json['roundsStarted'] as num?)?.toInt() ?? 0,
        maxGoalRounds: (json['maxGoalRounds'] as num?)?.toInt() ?? 0,
      );

  final String objective;
  final String phase;
  final int roundsStarted;
  final int maxGoalRounds;

  String get phaseLabel {
    switch (phase) {
      case 'active':
        return '进行中';
      case 'paused':
        return '已暂停';
      case 'blocked':
        return '受阻';
      case 'complete':
        return '已完成';
      default:
        return phase;
    }
  }
}

/// DSH 子代理条目（对齐 webApi WebDshSubagent）。
class DshSubagent {
  const DshSubagent({
    required this.id,
    this.label,
    this.activity = 'inactive',
    this.kind = 'child',
  });

  factory DshSubagent.fromJson(Map<String, dynamic> json) => DshSubagent(
        id: json['id'] as String? ?? '',
        label: json['label'] as String?,
        activity: json['activity'] as String? ?? 'inactive',
        kind: json['kind'] as String? ?? 'child',
      );

  final String id;
  final String? label;
  final String activity;
  final String kind;

  bool get running => activity == 'running';
}

/// DSH 技能条目（对齐 webApi WebDshSkill；只读）。
class DshSkill {
  const DshSkill({
    required this.name,
    this.description = '',
    this.whenToUse,
    this.modelInvocable = true,
  });

  factory DshSkill.fromJson(Map<String, dynamic> json) => DshSkill(
        name: json['name'] as String? ?? '',
        description: json['description'] as String? ?? '',
        whenToUse: json['whenToUse'] as String?,
        modelInvocable: json['modelInvocable'] as bool? ?? true,
      );

  final String name;
  final String description;
  final String? whenToUse;
  final bool modelInvocable;
}

/// DSH 动态插件包。
class DshPluginPackage {
  const DshPluginPackage({required this.packageId, this.name = ''});

  factory DshPluginPackage.fromJson(Map<String, dynamic> json) =>
      DshPluginPackage(
        packageId: json['packageId'] as String? ?? '',
        name: json['name'] as String? ?? '',
      );

  final String packageId;
  final String name;
}

/// DSH 动态插件（对齐 webApi WebDshPlugin 的面板用字段）。
class DshPlugin {
  const DshPlugin({
    required this.pluginId,
    this.packages = const [],
    this.running = false,
    this.error,
  });

  factory DshPlugin.fromJson(Map<String, dynamic> json) => DshPlugin(
        pluginId: json['pluginId'] as String? ?? '',
        packages: ((json['packages'] as List? ?? [])
            .map((e) => DshPluginPackage.fromJson(e as Map<String, dynamic>))
            .toList()),
        running: json['activeRun'] != null,
        error: json['error'] as String?,
      );

  final String pluginId;
  final List<DshPluginPackage> packages;
  final bool running;
  final String? error;
}

/// DSH 静态插件行（只读展示）。
class DshStaticPlugin {
  const DshStaticPlugin(
      {required this.entryId, required this.moduleName, this.enabled = true});

  factory DshStaticPlugin.fromJson(Map<String, dynamic> json) =>
      DshStaticPlugin(
        entryId: json['entryId'] as String? ?? '',
        moduleName: json['moduleName'] as String? ?? '',
        enabled: json['enabled'] as bool? ?? true,
      );

  final String entryId;
  final String moduleName;
  final bool enabled;
}

/// 技能资产条目（对齐 PiSkillSummary 去路径字段）。
class SkillSummary {
  const SkillSummary({
    required this.name,
    required this.sourceId,
    this.sourceLabel = '',
    this.description = '',
    this.enabled = true,
    this.warnings = const [],
  });

  factory SkillSummary.fromJson(Map<String, dynamic> json) => SkillSummary(
        name: json['name'] as String? ?? '',
        sourceId: json['sourceId'] as String? ?? '',
        sourceLabel: json['sourceLabel'] as String? ?? '',
        description: json['description'] as String? ?? '',
        enabled: json['enabled'] as bool? ?? true,
        warnings:
            ((json['warnings'] as List? ?? []).map((e) => '$e')).toList(),
      );

  final String name;
  final String sourceId;
  final String sourceLabel;
  final String description;
  final bool enabled;
  final List<String> warnings;

  SkillSummary toggled(bool value) => SkillSummary(
        name: name,
        sourceId: sourceId,
        sourceLabel: sourceLabel,
        description: description,
        enabled: value,
        warnings: warnings,
      );
}

/// 扩展资产条目（对齐 PiExtensionSummary 去路径字段）。
class ExtensionSummary {
  const ExtensionSummary({
    required this.id,
    required this.source,
    required this.scope,
    this.enabled = true,
    this.currentVersion,
    this.builtIn = false,
  });

  factory ExtensionSummary.fromJson(Map<String, dynamic> json) =>
      ExtensionSummary(
        id: json['id'] as String? ?? '',
        source: json['source'] as String? ?? '',
        scope: json['scope'] as String? ?? 'unknown',
        enabled: json['enabled'] as bool? ?? true,
        currentVersion: json['currentVersion'] as String?,
        builtIn: json['builtIn'] as bool? ?? false,
      );

  final String id;
  final String source;
  final String scope;
  final bool enabled;
  final String? currentVersion;
  final bool builtIn;

  ExtensionSummary toggled(bool value) => ExtensionSummary(
        id: id,
        source: source,
        scope: scope,
        enabled: value,
        currentVersion: currentVersion,
        builtIn: builtIn,
      );
}

/// 可用模型（ AvailableModel 的最小子集：切换只需要三元组 ）。
class AvailableModel {
  const AvailableModel({
    required this.id,
    required this.provider,
    this.name,
    this.contextWindow,
    this.reasoning,
    this.images,
    this.levels = const [],
  });

  factory AvailableModel.fromJson(Map<String, dynamic> json) {
    // 档位：thinkingLevels（字符串数组）优先，否则从 reasoningEfforts 取 id。
    // undefined/缺失 = 服务端未报告，不显示档位（与 web 语义一致）。
    final levels = <String>[];
    final rawLevels = json['thinkingLevels'];
    if (rawLevels is List) {
      for (final l in rawLevels) {
        if (l is String && l.isNotEmpty) levels.add(l);
      }
    } else {
      final efforts = json['reasoningEfforts'];
      if (efforts is List) {
        for (final e in efforts) {
          final id = e is Map ? e['id'] : null;
          if (id is String && id.isNotEmpty) levels.add(id);
        }
      }
    }
    return AvailableModel(
      id: json['id'] as String? ?? '',
      provider: json['provider'] as String? ?? '',
      name: json['name'] as String?,
      contextWindow: (json['contextWindow'] as num?)?.toInt(),
      reasoning: json['reasoning'] as bool?,
      images: json['images'] as bool?,
      levels: levels,
    );
  }

  final String id;
  final String provider;
  final String? name;
  final int? contextWindow;
  final bool? reasoning;
  final bool? images;
  final List<String> levels;

  String get display => name?.isNotEmpty == true ? name! : id;

  /// 上下文窗口展示（OpenChamber 风格）：“104.9万 ctx”，缺字段返回空。
  String get contextLabel {
    final ctx = contextWindow;
    if (ctx == null || ctx <= 0) return '';
    if (ctx < 10000) return '$ctx ctx';
    final w = ctx / 10000;
    final s = w == w.truncateToDouble()
        ? w.toInt().toString()
        : w.toStringAsFixed(1);
    return '$s万 ctx';
  }
}

/// 待审批问答（ask_question / confirm / input），只处理本会话的。
class WebPendingUiRequest {
  const WebPendingUiRequest({
    required this.sessionId,
    required this.agentId,
    required this.runtimeGeneration,
    required this.requestId,
    required this.method,
    required this.title,
    this.options,
    this.placeholder,
    this.prefill,
    this.allowOther,
    this.batchQuestions = const [],
  });

  factory WebPendingUiRequest.fromJson(Map<String, dynamic> json) =>
      WebPendingUiRequest(
        sessionId: json['sessionId'] as String? ?? '',
        agentId: json['agentId'] as String? ?? '',
        runtimeGeneration: (json['runtimeGeneration'] as num?)?.toInt() ?? 0,
        requestId: json['requestId'] as String? ?? '',
        method: json['method'] as String? ?? '',
        title: json['title'] as String? ?? '',
        options: (json['options'] as List?)?.map((e) => '$e').toList(),
        placeholder: json['placeholder'] as String?,
        prefill: json['prefill'] as String?,
        allowOther: json['allowOther'] as bool?,
        batchQuestions:
            ((json['batchQuestions'] as List? ?? []))
                .map((e) => WebBatchQuestion.fromJson(e as Map<String, dynamic>))
                .toList(),
      );

  final String sessionId;
  final String agentId;
  final int runtimeGeneration;
  final String requestId;
  final String method;
  final String title;
  final List<String>? options;
  final String? placeholder;
  final String? prefill;
  final bool? allowOther;
  final List<WebBatchQuestion> batchQuestions;

  bool get isBatch => batchQuestions.isNotEmpty;
}

/// GET /api/state 聚合。
class WebState {
  const WebState({
    required this.projects,
    required this.sessions,
    required this.runtimes,
    required this.pendingUiRequests,
  });

  factory WebState.fromJson(Map<String, dynamic> json) => WebState(
        projects: (json['projects'] as List? ?? [])
            .map((e) => WebProject.fromJson(e as Map<String, dynamic>))
            .toList(),
        sessions: (json['sessions'] as List? ?? [])
            .map((e) => WebSession.fromJson(e as Map<String, dynamic>))
            .toList(),
        runtimes: (json['runtimes'] as List? ?? [])
            .map((e) => WebRuntime.fromJson(e as Map<String, dynamic>))
            .toList(),
        pendingUiRequests: (json['pendingUiRequests'] as List? ?? [])
            .map((e) => WebPendingUiRequest.fromJson(e as Map<String, dynamic>))
            .toList(),
      );

  final List<WebProject> projects;
  final List<WebSession> sessions;
  final List<WebRuntime> runtimes;
  final List<WebPendingUiRequest> pendingUiRequests;

  WebRuntime? runtimeOf(String sessionId) {
    for (final r in runtimes) {
      if (r.sessionId == sessionId) return r;
    }
    return null;
  }

  WebPendingUiRequest? pendingUiOf(String sessionId) {
    for (final r in pendingUiRequests) {
      if (r.sessionId == sessionId) return r;
    }
    return null;
  }
}
