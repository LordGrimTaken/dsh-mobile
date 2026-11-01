package dev.dsh.mobile.data

import dev.dsh.mobile.net.DshJson
import dev.dsh.mobile.net.MessageDto
import dev.dsh.mobile.net.ToolDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frozen `tool.output` contract: DTO decode, mapping and the pure viewer helpers. */
class ToolOutputTest {

    private val tool = ToolActivity(
        name = "Bash",
        detail = "pwsh -File tools/test.ps1",
        state = ToolState.Done,
        output = "test results\n3 passed",
    )

    @Test
    fun `tool output decodes and defaults to blank`() {
        val withOutput = DshJson.decodeFromString(
            ToolDto.serializer(),
            """{"name":"Bash","detail":"ls","state":"done","output":"a.txt\nb.txt"}""",
        )
        assertEquals("a.txt\nb.txt", withOutput.output)

        val oldHost = DshJson.decodeFromString(
            ToolDto.serializer(),
            """{"name":"Bash","detail":"ls","state":"done"}""",
        )
        assertEquals("", oldHost.output)

        val message = DshJson.decodeFromString(
            MessageDto.serializer(),
            """{"id":"m","role":"tool","createdAt":"2026-11-01T10:00:00Z",
               "tool":{"name":"Read","detail":"file","state":"done",
                       "output":"hello"}}""",
        )
        assertEquals("hello", message.tool?.output)
    }

    @Test
    fun `mapping carries output into ToolActivity and messages`() {
        val dto = ToolDto(
            name = "Bash",
            detail = "ls",
            state = "failed",
            output = "error: nope",
        )
        val mapped = dto.toUi()
        assertEquals("Bash", mapped.name)
        assertEquals(ToolState.Failed, mapped.state)
        assertEquals("error: nope", mapped.output)

        val message = MessageDto(
            id = "m",
            role = "tool",
            createdAt = "2026-11-01T10:00:00Z",
            tool = dto,
        ).toUi()
        val assistant = message as AssistantMessage
        assertEquals("error: nope", assistant.tool?.output)

        val legacy = ToolActivity("Read", "file", ToolState.Done)
        assertEquals("", legacy.output)
    }

    @Test
    fun `viewer affordance only exists for nonblank output`() {
        assertTrue(toolOutputAvailable(tool))
        assertFalse(toolOutputAvailable(tool.copy(output = "")))
        assertFalse(toolOutputAvailable(tool.copy(output = "   \n\t ")))
    }

    @Test
    fun `viewer title and share subject use the tool name with safe fallbacks`() {
        assertEquals("Tool output · Bash", toolOutputViewerTitle(tool))
        assertEquals("Bash output", toolOutputShareSubject(tool))
        assertEquals("Tool output", toolOutputViewerTitle(tool.copy(name = "  ")))
        assertEquals("Tool output", toolOutputViewerTitle(tool.copy(name = "")))
        assertEquals("Tool output", toolOutputShareSubject(tool.copy(name = "  ")))
    }

    @Test
    fun `truncation marker is detected honestly`() {
        assertFalse(toolOutputIsTruncated(tool.output))
        assertTrue(toolOutputIsTruncated("line\n… (output truncated)"))
        assertTrue(toolOutputIsTruncated("line\n(OUTPUT TRUNCATED)"))
        assertTrue(toolOutputIsTruncated("(output truncated)"))
        assertFalse(toolOutputIsTruncated(""))
    }

    @Test
    fun `copy and share payload is exactly the tool output`() {
        assertEquals("test results\n3 passed", toolOutputCopyText(tool.output))
        assertTrue(TOOL_OUTPUT_VIEW_LABEL.isNotBlank())
        assertEquals("View output", TOOL_OUTPUT_VIEW_LABEL)
        assertEquals("Tool output", TOOL_OUTPUT_VIEWER_TITLE)
        assertTrue(TOOL_OUTPUT_TRUNCATED_NOTE.contains("capped"))
        assertNotNull(TOOL_OUTPUT_COPY_SUCCESS)
        assertEquals("Output copied", TOOL_OUTPUT_COPY_SUCCESS)
        assertEquals("Couldn't copy the output.", TOOL_OUTPUT_COPY_FAILURE)
        assertEquals("No app can share the output.", TOOL_OUTPUT_SHARE_FAILURE)
    }
}
