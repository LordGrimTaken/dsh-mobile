package dev.dsh.mobile.data

import java.util.UUID

/**
 * A paired host the phone can talk to. A profile is just "base URL + device token" plus display
 * metadata; nothing about Tailscale, a desktop, or a specific OS is assumed.
 */
data class HostProfile(
    val id: String,
    /** User-facing name, e.g. "Desktop" or "Cloud". */
    val label: String,
    val baseUrl: String,
    val deviceId: String,
    val token: String,
    val pairedAt: String,
    val lastUsedAt: String,
)

internal object HostIds {
    fun newId(): String = "host-" + UUID.randomUUID().toString()
}

/** Result of planning a host switch (pure so the transition can be unit tested). */
sealed interface HostSwitchOutcome {
    /** A different, known host should become active: rebuild the client/repository. */
    data class Rebuild(val profile: HostProfile) : HostSwitchOutcome

    /** The requested host is already active: nothing to rebuild. */
    data object AlreadyActive : HostSwitchOutcome

    /** The id does not belong to any persisted profile. */
    data object UnknownHost : HostSwitchOutcome
}

object HostSwitcher {
    fun plan(
        profiles: List<HostProfile>,
        activeId: String?,
        targetId: String,
    ): HostSwitchOutcome {
        val target = profiles.firstOrNull { it.id == targetId }
            ?: return HostSwitchOutcome.UnknownHost
        return if (target.id == activeId) {
            HostSwitchOutcome.AlreadyActive
        } else {
            HostSwitchOutcome.Rebuild(target)
        }
    }
}
