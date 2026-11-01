package dev.dsh.mobile.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppState starts its live connection from an `init` block when a host is already paired.
 * Kotlin initializes `by mutableStateOf(...)` delegates in declaration order, so any delegate the
 * init path writes must be declared *before* that init block. Violating this crashed every paired
 * launch with an NPE (0.2.86–0.2.114); keep this source-level guard so the regression cannot return.
 */
class AppStateInitializationOrderTest {

    @Test
    fun `init path delegated state is declared before the init block`() {
        val source = readAppStateSource()
        val initIndex = source.indexOf("init {")
        assertTrue("AppState.kt must contain the constructor init block", initIndex > 0)

        // startRemote() runs from init and writes these delegated properties.
        listOf("lastGoalChange", "presetRoster", "presetsLoading", "messageJumpTarget").forEach { name ->
            val declaration = Regex("""\bvar\s+$name\b[^\n]*\bby\s+mutableStateOf\b""")
                .find(source)
            requireNotNull(declaration) { "AppState.kt must declare var $name by mutableStateOf(...)" }
            assertTrue(
                "$name must be declared before AppState's init block (startRemote writes it during construction)",
                declaration.range.first < initIndex,
            )
        }
    }

    private fun readAppStateSource(): String {
        val relative = "src/main/java/dev/dsh/mobile/data/AppState.kt"
        val userDir = System.getProperty("user.dir")
        val candidates = listOf(
            File(relative),
            File("app", relative),
            File(userDir, relative),
            File(File(userDir, ".."), "app/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
        requireNotNull(file) {
            "Could not locate AppState.kt relative to ${System.getProperty("user.dir")}; looked in $candidates"
        }
        return file.readText()
    }
}
