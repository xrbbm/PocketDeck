package com.xrbbm.pideck.core.net

/**
 * Branch family derivation (mirrors webBranchFamily.ts deriveWebBranchFamily):
 * from the active session walk parentSessionId up to the root, then collect
 * all descendants; return the chain sorted by createdAt ascending (old→new).
 * A lone session returns [session] (caller hides UI when size <= 1).
 * Both walks carry visited guards; missing createdAt sorts last.
 */
fun deriveBranchFamily(sessions: List<WebSession>, sessionId: String): List<WebSession> {
    val byId = sessions.associateBy { it.id }
    var root = byId[sessionId] ?: return emptyList()

    val visitedUp = mutableSetOf(root.id)
    while (root.parentSessionId != null) {
        val parent = byId[root.parentSessionId] ?: break
        if (!visitedUp.add(parent.id)) break
        root = parent
    }

    val childrenOf = mutableMapOf<String, MutableList<WebSession>>()
    for (s in sessions) {
        val parentId = s.parentSessionId ?: continue
        childrenOf.getOrPut(parentId) { mutableListOf() }.add(s)
    }
    val family = mutableListOf<WebSession>()
    val queue = ArrayDeque<WebSession>()
    queue.add(root)
    val visitedDown = mutableSetOf<String>()
    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        if (!visitedDown.add(node.id)) continue
        family.add(node)
        queue.addAll(childrenOf[node.id].orEmpty())
    }

    family.sortWith { a, b ->
        val ca = a.createdAt
        val cb = b.createdAt
        when {
            ca == null && cb == null -> a.id.compareTo(b.id)
            ca == null -> 1
            cb == null -> -1
            else -> {
                val c = ca.compareTo(cb)
                if (c != 0) c else a.id.compareTo(b.id)
            }
        }
    }
    return family
}

/** Desktop builtin chat project (sessions grouped + pinned on top). */
const val CHAT_PROJECT_ID = "builtin-chat"
const val CHAT_PROJECT_NAME = "Chat"

data class SessionGroup(
    val id: String,
    val title: String,
    val isChat: Boolean,
    val sessions: List<WebSession>,
)

/** Grouping pure function: chat group pinned, rest by latest activity desc. */
fun groupSessions(
    sessions: List<WebSession>,
    projectName: Map<String, String>,
): List<SessionGroup> {
    val chat = mutableListOf<WebSession>()
    val byProject = mutableMapOf<String, MutableList<WebSession>>()
    for (s in sessions) {
        if (s.projectId == CHAT_PROJECT_ID) {
            chat.add(s)
            continue
        }
        byProject.getOrPut(s.projectId) { mutableListOf() }.add(s)
    }
    fun byUpdated(a: WebSession, b: WebSession): Int =
        (b.updatedAt ?: 0).compareTo(a.updatedAt ?: 0)
    chat.sortWith(::byUpdated)
    val rest = byProject.entries.map { (key, list) ->
        list.sortWith(::byUpdated)
        val name = projectName[key]
        val title = if (name.isNullOrEmpty()) "(未分组 $key)" else name
        SessionGroup(id = key, title = title, isChat = false, sessions = list)
    }.sortedByDescending { it.sessions.firstOrNull()?.updatedAt ?: 0 }
    return listOf(
        SessionGroup(id = CHAT_PROJECT_ID, title = CHAT_PROJECT_NAME, isChat = true, sessions = chat),
    ) + rest
}
