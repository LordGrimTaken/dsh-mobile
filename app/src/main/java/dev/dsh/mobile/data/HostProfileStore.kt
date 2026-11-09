package dev.dsh.mobile.data

import dev.dsh.mobile.net.DshJson
import kotlinx.serialization.Serializable

/** The persisted host list plus the active host id. */
data class HostProfilesSnapshot(
    val profiles: List<HostProfile>,
    val activeId: String?,
)

@Serializable
internal data class StoredHostRoute(
    val id: String = "",
    val label: String = "",
    val baseUrl: String = "",
    val kind: String = HostRoute.KIND_CUSTOM,
)

@Serializable
internal data class StoredHostProfile(
    val id: String = "",
    val label: String = "",
    val baseUrl: String = "",
    val deviceId: String = "",
    val tokenCiphertext: String = "",
    val pairedAt: String = "",
    val lastUsedAt: String = "",
    /** Optional: legacy documents have no routes and migrate to a single route from [baseUrl]. */
    val routes: List<StoredHostRoute> = emptyList(),
    val activeRouteId: String? = null,
)

@Serializable
internal data class HostProfilesDocument(
    val profiles: List<StoredHostProfile> = emptyList(),
    val activeId: String? = null,
    val welcomeDismissed: Boolean = false,
)

/**
 * Persists multiple [HostProfile]s and the active host id. Tokens are stored only as ciphertext
 * from a [TokenCipher] (Android Keystore on device); a profile whose token can no longer be
 * decrypted (e.g. Keystore key lost after a restore) is dropped from the load result rather than
 * crashing the app - the user just pairs that host again.
 */
class HostProfileStore(
    private val store: HostKeyValueStore,
    private val cipher: TokenCipher,
) {

    /** Profiles + active id; active falls back to the first profile when the stored id is stale. */
    fun load(): HostProfilesSnapshot {
        val document = readDocument()
        val profiles = document.profiles.mapNotNull { stored -> decryptProfile(stored) }
        val activeId = document.activeId
            ?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.firstOrNull()?.id
        return HostProfilesSnapshot(profiles = profiles, activeId = activeId)
    }

    /** Encrypts every token and writes the whole list. The welcome flag is preserved. */
    fun save(profiles: List<HostProfile>, activeId: String?) {
        val stored = profiles.mapNotNull { rawProfile ->
            val profile = rawProfile.ensureRoutes()
            if (profile.id.isBlank() || profile.baseUrl.isBlank() || profile.token.isBlank()) {
                return@mapNotNull null
            }
            val ciphertext = runCatching { cipher.encrypt(profile.token) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            StoredHostProfile(
                id = profile.id,
                label = profile.label,
                baseUrl = profile.baseUrl,
                deviceId = profile.deviceId,
                tokenCiphertext = ciphertext,
                pairedAt = profile.pairedAt,
                lastUsedAt = profile.lastUsedAt,
                routes = profile.routes.map { route ->
                    StoredHostRoute(
                        id = route.id,
                        label = route.label,
                        baseUrl = route.baseUrl,
                        kind = route.kind,
                    )
                },
                activeRouteId = profile.activeRouteId,
            )
        }
        val safeActiveId = activeId?.takeIf { id -> stored.any { it.id == id } }
            ?: stored.firstOrNull()?.id
        val document = HostProfilesDocument(
            profiles = stored,
            activeId = safeActiveId,
            welcomeDismissed = readDocument().welcomeDismissed,
        )
        writeDocument(document)
    }

    fun isWelcomeDismissed(): Boolean = readDocument().welcomeDismissed

    fun dismissWelcome() {
        writeDocument(readDocument().copy(welcomeDismissed = true))
    }

    private fun decryptProfile(stored: StoredHostProfile): HostProfile? {
        if (stored.id.isBlank() || stored.baseUrl.isBlank()) return null
        val token = runCatching { cipher.decrypt(stored.tokenCiphertext) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val routes = stored.routes.mapNotNull { route ->
            val baseUrl = (HostUrl.parse(route.baseUrl) as? HostUrlResult.Valid)?.root
                ?: return@mapNotNull null
            HostRoute(
                id = route.id.ifBlank { HostRoute.newId() },
                label = route.label.ifBlank { HostRoute.labelForKind(route.kind) },
                baseUrl = baseUrl,
                kind = route.kind.takeIf { it in HostRoute.KINDS } ?: HostRoute.KIND_CUSTOM,
            )
        }.distinctBy { it.baseUrl.lowercase() }
        // Legacy documents (no `routes`) and corrupted route lists fall back to the single
        // saved base URL, so old pairings keep working without re-pairing.
        val safeRoutes = routes.ifEmpty { RoutePlanner.defaultRoutes(stored.baseUrl) }
        val active = safeRoutes.firstOrNull { it.id == stored.activeRouteId } ?: safeRoutes.first()
        return HostProfile(
            id = stored.id,
            label = stored.label.ifBlank { "Host" },
            baseUrl = active.baseUrl,
            deviceId = stored.deviceId,
            token = token,
            pairedAt = stored.pairedAt,
            lastUsedAt = stored.lastUsedAt,
            routes = safeRoutes,
            activeRouteId = active.id,
        )
    }

    private fun readDocument(): HostProfilesDocument {
        val raw = store.getString(DOC_KEY) ?: return HostProfilesDocument()
        return runCatching {
            DshJson.decodeFromString(HostProfilesDocument.serializer(), raw)
        }.getOrElse { HostProfilesDocument() }
    }

    private fun writeDocument(document: HostProfilesDocument) {
        store.putString(
            DOC_KEY,
            DshJson.encodeToString(HostProfilesDocument.serializer(), document),
        )
    }

    internal companion object {
        /** Single prefs key holding the JSON document (internal for unit tests). */
        internal const val DOC_KEY = "hosts_v1"
    }
}
