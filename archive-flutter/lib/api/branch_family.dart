import 'models.dart';

/// 分支家族推导（对齐网页 webBranchFamily.ts deriveWebBranchFamily）：
/// 给定会话列表与当前会话 id，沿 parentSessionId 向上找根，
/// 再向下收集全部后代；返回按 createdAt 升序（老→新）的家族链。
/// 无父无后代时返回 [session]（单会话，调用方按长度 ≤1 隐藏 UI）。
/// 上下遍历均带 visited 防环，脏数据不会死循环；缺 createdAt 视为最早。
List<WebSession> deriveBranchFamily(
    List<WebSession> sessions, String sessionId) {
  final byId = {for (final s in sessions) s.id: s};
  final active = byId[sessionId];
  if (active == null) return [];

  final visitedUp = <String>{active.id};
  var root = active;
  while (root.parentSessionId != null) {
    final parent = byId[root.parentSessionId];
    if (parent == null || visitedUp.contains(parent.id)) break;
    visitedUp.add(parent.id);
    root = parent;
  }

  final childrenOf = <String, List<WebSession>>{};
  for (final s in sessions) {
    final parentId = s.parentSessionId;
    if (parentId == null) continue;
    childrenOf.putIfAbsent(parentId, () => []).add(s);
  }
  final family = <WebSession>[];
  final queue = <WebSession>[root];
  final visitedDown = <String>{};
  while (queue.isNotEmpty) {
    final node = queue.removeAt(0);
    if (visitedDown.contains(node.id)) continue;
    visitedDown.add(node.id);
    family.add(node);
    queue.addAll(childrenOf[node.id] ?? const []);
  }

  // 缺 createdAt 排最后（不冒充最老），同为缺失按 id 稳定排。
  family.sort((a, b) {
    final ca = a.createdAt;
    final cb = b.createdAt;
    if (ca == null && cb == null) return a.id.compareTo(b.id);
    if (ca == null) return 1;
    if (cb == null) return -1;
    final c = ca.compareTo(cb);
    return c != 0 ? c : a.id.compareTo(b.id);
  });
  return family;
}
