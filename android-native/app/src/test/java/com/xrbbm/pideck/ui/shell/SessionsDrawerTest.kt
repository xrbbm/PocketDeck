package com.xrbbm.pideck.ui.shell

import com.xrbbm.pideck.core.net.WebSession
import com.xrbbm.pideck.core.net.groupSessions
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionsDrawerTest {
    @Test
    fun `chat group pinned and empty chat dropped by caller`() {
        val sessions = listOf(
            WebSession(id = "p1s", projectId = "p1", title = "work", status = "", updatedAt = 9),
            WebSession(
                id = "c1",
                projectId = "builtin-chat",
                title = "chat",
                status = "",
                updatedAt = 1,
            ),
        )
        val groups = groupSessions(sessions, mapOf("p1" to "P1"))
        assertEquals("builtin-chat", groups[0].id)
        assertEquals("p1", groups[1].id)
    }

    @Test
    fun `unknown project forms own group with id suffix`() {
        val sessions = listOf(
            WebSession(id = "x", projectId = "zzz", title = "t", status = ""),
        )
        val groups = groupSessions(sessions, emptyMap())
        // [0] is the (empty) chat group; [1] is the unknown project.
        assertEquals("(未分组 zzz)", groups[1].title)
    }

    @Test
    fun `sessions sort by updated desc inside group`() {
        val sessions = listOf(
            WebSession(id = "old", projectId = "p", title = "o", status = "", updatedAt = 1),
            WebSession(id = "new", projectId = "p", title = "n", status = "", updatedAt = 99),
        )
        val groups = groupSessions(sessions, mapOf("p" to "P"))
        assertEquals("new", groups[1].sessions[0].id)
    }
}
