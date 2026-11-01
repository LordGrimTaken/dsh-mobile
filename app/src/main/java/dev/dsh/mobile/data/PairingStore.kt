package dev.dsh.mobile.data

import android.content.Context
import dev.dsh.mobile.net.DshJson
import kotlinx.serialization.Serializable

/** Snapshot of a host workspace, persisted with the pairing config for offline display. */
@Serializable
data class PairingWorkspace(
    val id: String = "",
    val name: String = "",
    val path: String = "",
)

/** Everything needed to talk to a paired host after the app restarts. */
@Serializable
data class PairingConfig(
    val baseUrl: String = "",
    val token: String = "",
    val deviceId: String = "",
    val hostName: String = "",
    val hostVersion: String = "",
    val apiVersion: Int = 1,
    val pairedAt: String = "",
    val workspaces: List<PairingWorkspace> = emptyList(),
)

/**
 * Persists the pairing config and the "use demo data" preference in app-private SharedPreferences.
 * The token never leaves the app sandbox; no backup/export path exposes it.
 */
class PairingStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadPairing(): PairingConfig? {
        val stored = prefs.getString(KEY_PAIRING, null) ?: return null
        return runCatching {
            DshJson.decodeFromString(PairingConfig.serializer(), stored)
        }.getOrNull()?.takeIf { it.baseUrl.isNotBlank() && it.token.isNotBlank() }
    }

    fun savePairing(config: PairingConfig) {
        val encoded = DshJson.encodeToString(PairingConfig.serializer(), config)
        prefs.edit().putString(KEY_PAIRING, encoded).apply()
    }

    fun clearPairing() {
        prefs.edit().remove(KEY_PAIRING).apply()
    }

    /** Demo data is the default whenever the app is not paired. */
    fun loadUseDemoData(): Boolean = prefs.getBoolean(KEY_USE_DEMO, true)

    fun saveUseDemoData(value: Boolean) {
        prefs.edit().putBoolean(KEY_USE_DEMO, value).apply()
    }

    private companion object {
        const val PREFS = "dsh_mobile_pairing"
        const val KEY_PAIRING = "pairing"
        const val KEY_USE_DEMO = "use_demo_data"
    }
}
