package com.xrbbm.pideck.core.net

import kotlinx.serialization.json.JsonObject

/** Session file-change snapshot entry. */
data class SessionFileChange(
    val path: String,
    val count: Int,
    val originalContent: String = "",
) {
    val basename: String get() {
        val parts = path.split('/', '\\')
        return parts.lastOrNull()?.takeIf { it.isNotEmpty() } ?: path
    }

    companion object {
        fun fromJson(json: JsonObject) = SessionFileChange(
            path = json.str("path"),
            count = json.intOrNull("count") ?: 0,
            originalContent = json.str("originalContent"),
        )
    }
}

/** Subagent strip entry. */
data class SubagentEntry(
    val id: String,
    val type: String,
    val description: String,
    val status: String,
    val result: String? = null,
    val error: String? = null,
) {
    companion object {
        fun fromJson(json: JsonObject) = SubagentEntry(
            id = json.str("id"),
            type = json.str("type"),
            description = json.str("description"),
            status = json.str("status"),
            result = json.strOrNull("result"),
            error = json.strOrNull("error"),
        )
    }
}

/** Session todo snapshot. */
data class SessionTodoSnapshot(val planId: Int, val todos: List<SessionTodoItem>) {
    companion object {
        fun fromJson(json: JsonObject?) = SessionTodoSnapshot(
            planId = json?.intOrNull("planId") ?: 0,
            todos = json?.arr("todos").orEmpty().map {
                SessionTodoItem.fromJson(it as JsonObject)
            },
        )
    }
}

data class SessionTodoItem(val id: Int, val text: String, val status: String) {
    val completed: Boolean get() = status == "completed"
    val inProgress: Boolean get() = status == "in_progress"

    companion object {
        fun fromJson(json: JsonObject) = SessionTodoItem(
            id = json.intOrNull("id") ?: 0,
            text = json.str("text"),
            status = json.str("status"),
        )
    }
}

/** Git branch info. */
data class GitBranchInfo(
    val current: String? = null,
    val branches: List<String> = emptyList(),
) {
    companion object {
        fun fromJson(json: JsonObject) = GitBranchInfo(
            current = json.strOrNull("current"),
            branches = json.arr("branches").map { "$it".trim('"') },
        )
    }
}

/** Single git change resource. */
data class GitResource(
    val path: String,
    val letter: String,
    val oldPath: String? = null,
) {
    val basename: String get() {
        val parts = path.split('/', '\\')
        return parts.lastOrNull()?.takeIf { it.isNotEmpty() } ?: path
    }

    companion object {
        fun fromJson(json: JsonObject) = GitResource(
            path = json.str("path"),
            letter = json.str("letter"),
            oldPath = json.strOrNull("oldPath"),
        )
    }
}

/** Grouped git resources (merge/index/workingTree/untracked). */
data class GitResourceGroups(
    val merge: List<GitResource> = emptyList(),
    val index: List<GitResource> = emptyList(),
    val workingTree: List<GitResource> = emptyList(),
    val untracked: List<GitResource> = emptyList(),
) {
    val total: Int get() = merge.size + index.size + workingTree.size + untracked.size

    /** Diff lookup priority: merge > index > workingTree > untracked. */
    fun groupOf(path: String): String? {
        val want = normPath(path)
        val groups = mapOf(
            "merge" to merge,
            "index" to index,
            "workingTree" to workingTree,
            "untracked" to untracked,
        )
        for ((key, list) in groups) {
            if (list.any {
                    normPath(it.path) == want ||
                        (it.oldPath != null && normPath(it.oldPath) == want)
                }
            ) {
                return key
            }
        }
        return null
    }

    companion object {
        fun fromJson(json: JsonObject?) = GitResourceGroups(
            merge = json?.arr("merge").orEmpty().map { GitResource.fromJson(it as JsonObject) },
            index = json?.arr("index").orEmpty().map { GitResource.fromJson(it as JsonObject) },
            workingTree = json?.arr("workingTree").orEmpty()
                .map { GitResource.fromJson(it as JsonObject) },
            untracked = json?.arr("untracked").orEmpty()
                .map { GitResource.fromJson(it as JsonObject) },
        )

        internal fun normPath(p: String): String {
            var s = p.replace('\\', '/').trim()
            while (s.startsWith("./")) s = s.substring(2)
            while (s.startsWith("/")) s = s.substring(1)
            return s
        }
    }
}

/** Single commit row. */
data class CommitEntry(
    val hash: String,
    val shortHash: String,
    val message: String,
    val authorName: String = "",
    val authorDate: Int = 0,
) {
    companion object {
        fun fromJson(json: JsonObject) = CommitEntry(
            hash = json.str("hash"),
            shortHash = json.str("shortHash"),
            message = json.str("message"),
            authorName = json.str("authorName"),
            authorDate = json.intOrNull("authorDate") ?: 0,
        )
    }
}

/** Workspace file node. */
data class WebFileNodeLite(
    val name: String,
    val relativePath: String,
    val type: String,
) {
    val isDirectory: Boolean get() = type == "directory"

    companion object {
        fun fromJson(json: JsonObject) = WebFileNodeLite(
            name = json.str("name"),
            relativePath = json.str("relativePath"),
            type = json.str("type", "file"),
        )
    }
}

/** File content result (tooLarge/binary/truncated markers). */
data class FileContentResult(
    val content: String? = null,
    val tooLarge: Boolean = false,
    val truncated: Boolean = false,
    val binary: Boolean = false,
) {
    companion object {
        fun fromJson(json: JsonObject?) = FileContentResult(
            content = json?.strOrNull("content"),
            tooLarge = json?.bool("tooLarge") ?: false,
            truncated = json?.bool("truncated") ?: false,
            binary = json?.bool("binary") ?: false,
        )
    }
}
