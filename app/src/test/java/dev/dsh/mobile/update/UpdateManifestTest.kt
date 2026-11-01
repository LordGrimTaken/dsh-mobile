package dev.dsh.mobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing/tolerance rules for the sideload update manifest, plus version ordering. */
class UpdateManifestTest {

    private val validSha = "ab".repeat(32) // 64 hex chars

    @Test
    fun `parses a full manifest and ignores unknown fields`() {
        val json = """
            {
              "versionCode": 3,
              "versionName": "0.2.1-preview",
              "apkUrl": "https://github.com/example/dsh-mobile/releases/download/v0.2.1/app-debug.apk",
              "sha256": "$validSha",
              "notes": "Sideload updater, question cards.",
              "publishedAt": "2026-10-30T10:00:00Z",
              "futureField": {"nested": true},
              "draft": false
            }
        """.trimIndent()

        val manifest = requireNotNull(UpdateManifestParser.parse(json)) {
            "full manifest should parse"
        }
        assertEquals(3L, manifest.versionCode)
        assertEquals("0.2.1-preview", manifest.versionName)
        assertTrue(manifest.apkUrl.startsWith("https://"))
        assertEquals(validSha, manifest.sha256)
        assertEquals("Sideload updater, question cards.", manifest.notes)
        assertEquals("2026-10-30T10:00:00Z", manifest.publishedAt)

        val update = UpdateManifestParser.toAvailable(manifest)
        assertEquals(manifest.versionCode, update.versionCode)
        assertEquals(manifest.apkUrl, update.apkUrl)
        assertEquals(manifest.sha256, update.sha256)
    }

    @Test
    fun `notes and publishedAt are optional`() {
        val json = """
            {"versionCode":3,"versionName":"0.2.1-preview",
             "apkUrl":"https://example.com/app.apk","sha256":"$validSha"}
        """.trimIndent()
        val manifest = requireNotNull(UpdateManifestParser.parse(json))
        assertEquals("", manifest.notes)
        assertEquals("", manifest.publishedAt)
    }

    @Test
    fun `numeric versionCode encoded as string is tolerated`() {
        val json = """
            {"versionCode":"3","versionName":"0.2.1-preview",
             "apkUrl":"https://example.com/app.apk","sha256":"$validSha"}
        """.trimIndent()
        val manifest = requireNotNull(UpdateManifestParser.parse(json))
        assertEquals(3L, manifest.versionCode)
    }

    @Test
    fun `utf8 byte order mark is tolerated`() {
        val json = "\uFEFF" + """
            {"versionCode":3,"versionName":"0.2.1-preview",
             "apkUrl":"https://example.com/app.apk","sha256":"$validSha"}
        """.trimIndent()
        assertEquals(3L, requireNotNull(UpdateManifestParser.parse(json)).versionCode)
    }

    @Test
    fun `sha256 is normalised to lowercase`() {
        val json = """
            {"versionCode":3,"versionName":"0.2.1-preview",
             "apkUrl":"https://example.com/app.apk","sha256":"${validSha.uppercase()}"}
        """.trimIndent()
        val manifest = requireNotNull(UpdateManifestParser.parse(json))
        assertEquals(validSha, manifest.sha256)
    }

    @Test
    fun `accepts http apk urls for local dev feeds`() {
        val json = """
            {"versionCode":3,"versionName":"0.2.1-preview",
             "apkUrl":"http://10.0.2.2:3548/dsh-mobile.apk","sha256":"$validSha"}
        """.trimIndent()
        assertNotNull(UpdateManifestParser.parse(json))
    }

    @Test
    fun `rejects manifests that cannot be installed safely`() {
        val missingVersionCode = """{"versionName":"x","apkUrl":"https://e/x.apk","sha256":"$validSha"}"""
        val zeroVersionCode = """{"versionCode":0,"versionName":"x","apkUrl":"https://e/x.apk","sha256":"$validSha"}"""
        val missingName = """{"versionCode":3,"apkUrl":"https://e/x.apk","sha256":"$validSha"}"""
        val badUrl = """{"versionCode":3,"versionName":"x","apkUrl":"not-a-url","sha256":"$validSha"}"""
        val shortSha = """{"versionCode":3,"versionName":"x","apkUrl":"https://e/x.apk","sha256":"abc"}"""
        val nonHexSha = """{"versionCode":3,"versionName":"x","apkUrl":"https://e/x.apk","sha256":"${"z".repeat(64)}"}"""
        val missingSha = """{"versionCode":3,"versionName":"x","apkUrl":"https://e/x.apk"}"""

        for (json in listOf(
            missingVersionCode,
            zeroVersionCode,
            missingName,
            badUrl,
            shortSha,
            nonHexSha,
            missingSha,
        )) {
            assertNull("Should reject: $json", UpdateManifestParser.parse(json))
        }
    }

    @Test
    fun `rejects garbage or empty payloads`() {
        assertNull(UpdateManifestParser.parse(null))
        assertNull(UpdateManifestParser.parse(""))
        assertNull(UpdateManifestParser.parse("   "))
        assertNull(UpdateManifestParser.parse("not json at all"))
        assertNull(UpdateManifestParser.parse("[1,2,3]"))
        assertNull(UpdateManifestParser.parse("{}"))
    }

    @Test
    fun `version comparison is strictly by versionCode`() {
        assertTrue(VersionCompare.isNewer(remoteVersionCode = 3, currentVersionCode = 2))
        assertTrue(VersionCompare.isNewer(remoteVersionCode = 4, currentVersionCode = 3))
        assertFalse(VersionCompare.isNewer(remoteVersionCode = 2, currentVersionCode = 2))
        assertFalse(VersionCompare.isNewer(remoteVersionCode = 1, currentVersionCode = 2))
        assertFalse(VersionCompare.isNewer(remoteVersionCode = 0, currentVersionCode = 0))
    }
}
