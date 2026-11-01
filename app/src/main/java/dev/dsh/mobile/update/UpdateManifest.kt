package dev.dsh.mobile.update

import dev.dsh.mobile.net.DshJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Sideload update feed configuration.
 *
 * The app is distributed as a debug APK via GitHub Releases (not Play), so it checks a small JSON
 * manifest maintained by the release tooling. The URL is a plain constant for now so it is easy to
 * point at a staging feed while testing; a Settings override can come later.
 */
object UpdateConfig {
    /** Published update manifest (GitHub Releases repo, `main` branch). */
    const val DEFAULT_FEED_URL =
        "https://raw.githubusercontent.com/LordGrimTaken/dsh-mobile/main/latest.json"

    /** Launch-time auto check is throttled to once per day. */
    const val CHECK_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L
}

/**
 * Manifest document published by the release tooling:
 * `{ "versionCode": 3, "versionName": "0.2.1-preview", "apkUrl": "https://...", "sha256": "...",
 *    "notes": "...", "publishedAt": "..." }`
 */
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
    val publishedAt: String,
)

/** An update the app can offer/install. */
data class AvailableUpdate(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
    val publishedAt: String,
)

/** Sideload updates are ordered strictly by versionCode; versionName is display only. */
object VersionCompare {
    fun isNewer(remoteVersionCode: Long, currentVersionCode: Long): Boolean =
        remoteVersionCode > currentVersionCode
}

/**
 * Tolerant manifest parser. Unknown fields are ignored, `notes`/`publishedAt` are optional, and a
 * numeric versionCode encoded as a JSON string is accepted. A manifest without a usable version,
 * HTTPS apk URL or valid SHA-256 is rejected (we never install an unverifiable APK).
 */
object UpdateManifestParser {

    fun parse(raw: String?): UpdateManifest? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { DshJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return null

        fun text(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
            val element = root[key] ?: return@firstNotNullOfOrNull null
            runCatching { element.jsonPrimitive.contentOrNull?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        }.orEmpty()

        val versionCode = text("versionCode", "version_code").toLongOrNull()?.takeIf { it > 0 }
            ?: return null
        val versionName = text("versionName", "version_name").takeIf { it.isNotBlank() }
            ?: return null
        val apkUrl = text("apkUrl", "apk_url", "downloadUrl", "download_url")
            .takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: return null
        val sha256 = text("sha256", "sha256Hex", "sha").lowercase()
            .takeIf { Sha256.isValidHex(it) }
            ?: return null

        return UpdateManifest(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            sha256 = sha256,
            notes = text("notes"),
            publishedAt = text("publishedAt", "published_at"),
        )
    }

    fun toAvailable(manifest: UpdateManifest): AvailableUpdate = AvailableUpdate(
        versionCode = manifest.versionCode,
        versionName = manifest.versionName,
        apkUrl = manifest.apkUrl,
        sha256 = manifest.sha256,
        notes = manifest.notes,
        publishedAt = manifest.publishedAt,
    )
}
