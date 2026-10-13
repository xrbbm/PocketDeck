package com.xrbbm.pideck.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTest {
    @Test
    fun `markdown suffix detection`() {
        assertTrue(isMarkdown("README.md"))
        assertTrue(isMarkdown("doc.MARKDOWN"))
        assertTrue(isMarkdown("note.mdown"))
        assertFalse(isMarkdown("main.kt"))
        assertFalse(isMarkdown("image.png"))
    }

    @Test
    fun `git date guards dirty data`() {
        assertEquals("", formatGitDate(0))
        assertEquals("", formatGitDate(-5))
        // Seconds input converts to ms (mirrors Flutter <1e12 branch).
        // 1704067200s = 2024-01-01 UTC; day part is timezone-safe enough
        // for the guard test (only emptiness of dirty data is asserted).
        assertFalse(formatGitDate(1704067200).isEmpty())
    }
}
