package dev.dsh.mobile.data

import java.time.Instant

/**
 * One-time migration from the v0.2.x single pairing ([PairingConfig]) to a [HostProfile].
 *
 * The old config is not deleted here - the caller clears the legacy store only after the profile
 * has been persisted - so a crash mid-migration cannot lose the device token.
 */
object HostProfileMigration {

    /** Label used when the legacy pairing has no host name to reuse. */
    const val DEFAULT_LABEL = "Desktop"

    fun fromLegacy(
        legacy: PairingConfig?,
        labelFallback: String = DEFAULT_LABEL,
        now: () -> String = { Instant.now().toString() },
    ): HostProfile? {
        if (legacy == null) return null
        val token = legacy.token.trim()
        if (token.isEmpty()) return null
        val baseUrl = HostUrl.rootOrNull(legacy.baseUrl) ?: return null
        val timestamp = now()
        val label = legacy.hostName.trim().ifBlank { labelFallback }
        return HostProfile(
            id = HostIds.newId(),
            label = label,
            baseUrl = baseUrl,
            deviceId = legacy.deviceId,
            token = token,
            pairedAt = legacy.pairedAt.ifBlank { timestamp },
            lastUsedAt = timestamp,
        )
    }
}
