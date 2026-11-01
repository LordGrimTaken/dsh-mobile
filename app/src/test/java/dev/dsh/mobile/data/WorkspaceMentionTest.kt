package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceMentionTest {

    @Test
    fun `simple relative paths become at mentions`() {
        assertEquals("@src/main.kt", workspaceMentionText("src/main.kt"))
        assertEquals("@README.md", workspaceMentionText("./README.md"))
        assertEquals("@a/b.txt", workspaceMentionText("a\\b.txt"))
    }

    @Test
    fun `paths cannot escape the workspace`() {
        assertEquals("@etc/passwd", workspaceMentionText("../../etc/passwd"))
        assertEquals("", workspaceMentionText(".."))
        assertEquals("", workspaceMentionText(""))
        assertEquals("", workspaceMentionText(null))
    }

    @Test
    fun `paths with spaces and quotes are quoted safely`() {
        assertEquals("@\"my file.txt\"", workspaceMentionText("my file.txt"))
        assertEquals("@\"a \\\"b\\\".txt\"", workspaceMentionText("a \"b\".txt"))
    }
}
