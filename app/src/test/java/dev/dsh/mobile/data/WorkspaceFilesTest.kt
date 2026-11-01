package dev.dsh.mobile.data

import dev.dsh.mobile.net.DshJson
import dev.dsh.mobile.net.FileContentDto
import dev.dsh.mobile.net.FilesResponse
import dev.dsh.mobile.net.filesQueryParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Workspace file endpoints + pure browser/navigation/formatting helpers. */
class WorkspaceFilesTest {

    @Test
    fun `file listing decodes dirs files and optional metadata`() {
        val response = DshJson.decodeFromString(
            FilesResponse.serializer(),
            """{"supported":true,"path":"src/main","parent":"src","entries":[
                 {"name":"app","kind":"dir","path":"src/main/app"},
                 {"name":"README.md","kind":"file","path":"src/main/README.md","bytes":2048,
                  "mimeType":"text/markdown","modifiedAt":"2026-11-01T10:00:00Z"},
                 {"name":"empty.txt","kind":"file","path":"src/main/empty.txt","bytes":0},
                 {"name":"blob","kind":"file","path":"src/main/blob"}]}""",
        )
        assertTrue(response.supported)
        assertEquals("src/main", response.path)
        assertEquals("src", response.parent)
        assertEquals(4, response.entries.size)
        assertEquals("dir", response.entries[0].kind)
        assertEquals(2048L, response.entries[1].bytes)
        assertEquals("text/markdown", response.entries[1].mimeType)
        assertEquals(0L, response.entries[2].bytes)
        assertNull(response.entries[3].mimeType)
    }

    @Test
    fun `old hosts default to unsupported and root path`() {
        val defaults = DshJson.decodeFromString(FilesResponse.serializer(), """{}""")
        assertFalse(defaults.supported)
        assertEquals("", defaults.path)
        assertNull(defaults.parent)
        assertTrue(defaults.entries.isEmpty())

        val file = DshJson.decodeFromString(
            FileContentDto.serializer(),
            """{"name":"a.txt","path":"a.txt","bytes":12,"mimeType":"text/plain",
               "text":"hello","truncated":true}""",
        )
        assertEquals("a.txt", file.name)
        assertEquals(12L, file.bytes)
        assertTrue(file.truncated)
    }

    @Test
    fun `query params omits an empty root path`() {
        assertEquals(emptyMap<String, String>(), filesQueryParams(""))
        assertEquals(emptyMap<String, String>(), filesQueryParams(null))
        assertEquals(mapOf("path" to "src/main"), filesQueryParams("src/main"))
    }

    @Test
    fun `paths are host-relative and traversal tokens are stripped`() {
        assertEquals("src/main", sanitizedRelativePath("/src//main/"))
        assertEquals("src/main", sanitizedRelativePath("src\\main"))
        assertEquals("src/main", sanitizedRelativePath("./src/../src/main"))
        assertEquals("", sanitizedRelativePath(".."))
        assertEquals("", sanitizedRelativePath(null))
        assertEquals("src/main", joinWorkspacePath("src", "main"))
        assertEquals("src/main", joinWorkspacePath("src/", "/main"))
        assertEquals("main", joinWorkspacePath("", "main"))
        assertEquals("src/main", joinWorkspacePath("..", "./src/main"))
        assertEquals(FILES_MAX_PATH_CHARS, sanitizedRelativePath("x/".repeat(400)).length)
    }

    @Test
    fun `parent breadcrumb and label stay relative`() {
        assertEquals("", workspaceParentPath(""))
        assertEquals("", workspaceParentPath("top"))
        assertEquals("src", workspaceParentPath("src/main"))
        assertEquals("src/main", workspaceParentPath("src/main/app"))
        assertEquals(emptyList<String>(), workspaceBreadcrumbSegments(""))
        assertEquals(listOf("src", "main"), workspaceBreadcrumbSegments("src/main"))
        assertEquals("Workspace", workspacePathLabel(""))
        assertEquals("Workspace / src / main", workspacePathLabel("src/main"))
    }

    @Test
    fun `size labels are readable and safe`() {
        assertEquals("", formatFileSize(null))
        assertEquals("", formatFileSize(-4))
        assertEquals("0 B", formatFileSize(0))
        assertEquals("999 B", formatFileSize(999))
        assertEquals("1.0 KB", formatFileSize(1024))
        assertEquals("1.5 KB", formatFileSize(1536))
        assertEquals("2.0 MB", formatFileSize(2L * 1024 * 1024))
        assertEquals("1.5 GB", formatFileSize((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `dirs sort first then by name case-insensitively`() {
        val entries = listOf(
            WorkspaceEntry("b.txt", WorkspaceEntryKind.File, "b.txt", bytes = 1),
            WorkspaceEntry("Zoo", WorkspaceEntryKind.Dir, "Zoo"),
            WorkspaceEntry("a.txt", WorkspaceEntryKind.File, "a.txt", bytes = 2),
            WorkspaceEntry("apps", WorkspaceEntryKind.Dir, "apps"),
        )
        val ordered = dirFirstEntries(entries)
        assertEquals(listOf("apps", "Zoo", "a.txt", "b.txt"), ordered.map { it.name })
        assertTrue(ordered.take(2).all { it.isDirectory })
    }

    @Test
    fun `text preview choice covers known mimes extensions and odd names`() {
        assertTrue(isTextPreviewable("text/plain", "anything"))
        assertTrue(isTextPreviewable("application/json", "x"))
        assertTrue(isTextPreviewable("application/octet-stream", "README.md"))
        assertTrue(isTextPreviewable(null, "Dockerfile"))
        assertTrue(isTextPreviewable(null, "script.kt"))
        assertFalse(isTextPreviewable("image/png", "photo.png"))
        assertFalse(isTextPreviewable("application/pdf", "doc.pdf"))
        assertFalse(isTextPreviewable("application/octet-stream", "archive.bin"))
        assertFalse(isTextPreviewable(null, "no-extension"))
        // non-ASCII names and weird extensions are safe
        assertTrue(isTextPreviewable("text/plain", "メモ.txt"))
        assertFalse(isTextPreviewable(null, "weird.???"))
    }

    @Test
    fun `preview text is bounded empty-safe and truncation-aware`() {
        assertEquals(FILES_EMPTY_FILE_COPY to false, previewTextFor("", hostTruncated = false))
        assertEquals(FILES_EMPTY_FILE_COPY to true, previewTextFor(null, hostTruncated = true))
        assertEquals("hello" to false, previewTextFor("hello", hostTruncated = false))
        assertEquals("hello" to true, previewTextFor("hello", hostTruncated = true))
        val long = "x".repeat(100)
        assertEquals("x".repeat(10) to true, previewTextFor(long, hostTruncated = false, maxChars = 10))
        assertEquals(200_000, FILES_MAX_PREVIEW_CHARS)
    }

    @Test
    fun `cache and navigation guards`() {
        val page = HostFilesPage(
            supported = true,
            path = "src",
            entries = listOf(WorkspaceEntry("a.kt", WorkspaceEntryKind.File, "src/a.kt")),
        )
        val now = 1_800_000_000_000L
        assertFalse(
            shouldFetchWorkspacePath(page, "src", "src", loading = false, force = false, fetchedAtMs = now - 1_000, nowMs = now),
        )
        assertTrue(
            shouldFetchWorkspacePath(page, "src", "src", loading = false, force = true, fetchedAtMs = now, nowMs = now),
        )
        assertTrue(
            shouldFetchWorkspacePath(page, "src", "lib", loading = false, force = false, fetchedAtMs = now - 1_000, nowMs = now),
        )
        assertTrue(
            shouldFetchWorkspacePath(page, "src", "src", loading = false, force = false, fetchedAtMs = now - FILES_CACHE_TTL_MS, nowMs = now),
        )
        assertFalse(
            shouldFetchWorkspacePath(page, "src", "src", loading = true, force = true, fetchedAtMs = now, nowMs = now),
        )
        assertTrue(shouldFetchWorkspacePath(null, null, "src", loading = false, force = false, nowMs = now))
        assertTrue(isWorkspaceNavigationStale("src", "lib"))
        assertTrue(isWorkspaceNavigationStale("src", ""))
        assertFalse(isWorkspaceNavigationStale("src/", "src"))
        assertFalse(isWorkspaceNavigationStale("./src", "src"))
    }

    @Test
    fun `external mime prefers host value then extension`() {
        assertEquals("image/png", workspaceMimeType("photo.png", "image/png"))
        assertEquals("image/png", workspaceMimeType("photo.png", null))
        assertEquals("application/json", workspaceMimeType("data.json", null))
        assertEquals("application/pdf", workspaceMimeType("doc.pdf", null))
        assertEquals("*/*", workspaceMimeType("mystery", null))
        assertEquals("*/*", workspaceMimeType("mystery.xyz", null))
        assertEquals("text/plain", workspaceMimeType("README.md", "  "))
    }

    @Test
    fun `copy and models are distinct`() {
        assertEquals("Files", FILES_ACTION_LABEL)
        assertEquals("Files", FILES_SHEET_TITLE)
        assertEquals("Workspace", FILES_ROOT_LABEL)
        assertEquals("This folder is empty.", FILES_EMPTY_COPY)
        assertEquals("This host does not expose workspace files.", FILES_UNSUPPORTED_COPY)
        assertEquals("Couldn't load files.", FILES_LOAD_FAILED_COPY)
        assertEquals("Loading files…", FILES_LOADING_COPY)
        assertEquals("Loading preview…", FILES_PREVIEW_LOADING_COPY)
        assertEquals("This file can't be previewed as text.", FILES_PREVIEW_NOT_TEXT_COPY)
        assertEquals("Open externally", FILES_OPEN_EXTERNAL_LABEL)
        assertEquals("Up", FILES_UP_LABEL)
        assertFalse(HostFilesPage.Unsupported.supported)
        assertTrue(HostFilesPage.Empty.supported)
        assertEquals(25L * 1024L * 1024L, FILES_RAW_MAX_BYTES)
        assertEquals(30_000L, FILES_CACHE_TTL_MS)
    }
}
