package dev.dsh.mobile.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface UpdateCheckResult {
    data class UpToDate(val versionName: String) : UpdateCheckResult
    data class Available(val update: AvailableUpdate) : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
}

/**
 * Fetches the release manifest with OkHttp and compares it against the running build.
 * Blocking work runs on [Dispatchers.IO]; callers only receive a typed result.
 */
class UpdateChecker(
    private val feedUrl: String = UpdateConfig.DEFAULT_FEED_URL,
    private val http: OkHttpClient = defaultHttp(),
) {

    suspend fun check(
        currentVersionCode: Long,
        currentVersionName: String,
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder()
                .url(feedUrl)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .get()
                .build()
        } catch (invalid: IllegalArgumentException) {
            return@withContext UpdateCheckResult.Failed("The update feed URL is invalid.")
        }

        val body = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateCheckResult.Failed(
                        "Update feed returned HTTP ${response.code}.",
                    )
                }
                response.body?.string()
            }
        } catch (io: IOException) {
            return@withContext UpdateCheckResult.Failed(
                "Couldn't reach the update feed. Check your connection and try again.",
            )
        }

        val manifest = UpdateManifestParser.parse(body)
            ?: return@withContext UpdateCheckResult.Failed("The update feed is malformed.")

        if (!VersionCompare.isNewer(manifest.versionCode, currentVersionCode)) {
            UpdateCheckResult.UpToDate(currentVersionName)
        } else {
            UpdateCheckResult.Available(UpdateManifestParser.toAvailable(manifest))
        }
    }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
