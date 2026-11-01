package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure switch-host transition planning: rebuild vs no-op vs unknown host. */
class HostSwitcherTest {

    private fun profile(id: String, label: String = id) = HostProfile(
        id = id,
        label = label,
        baseUrl = "https://$id.example.com",
        deviceId = "dev_$id",
        token = "token-$id",
        pairedAt = "2026-10-01T00:00:00Z",
        lastUsedAt = "2026-10-02T00:00:00Z",
    )

    private val profiles = listOf(profile("desktop", "Desktop"), profile("cloud", "Cloud"))

    @Test
    fun `switching to a different saved host rebuilds against that profile`() {
        val outcome = HostSwitcher.plan(profiles, activeId = "desktop", targetId = "cloud")
        assertTrue(outcome is HostSwitchOutcome.Rebuild)
        assertEquals("cloud", (outcome as HostSwitchOutcome.Rebuild).profile.id)
        assertEquals("Cloud", outcome.profile.label)
    }

    @Test
    fun `switching to the already active host is a no-op`() {
        assertEquals(
            HostSwitchOutcome.AlreadyActive,
            HostSwitcher.plan(profiles, activeId = "cloud", targetId = "cloud"),
        )
    }

    @Test
    fun `unknown target ids are rejected`() {
        assertEquals(
            HostSwitchOutcome.UnknownHost,
            HostSwitcher.plan(profiles, activeId = "desktop", targetId = "nope"),
        )
        assertEquals(
            HostSwitchOutcome.UnknownHost,
            HostSwitcher.plan(emptyList(), activeId = null, targetId = "desktop"),
        )
    }

    @Test
    fun `no active host yet means the target should be activated`() {
        val outcome = HostSwitcher.plan(profiles, activeId = null, targetId = "desktop")
        assertTrue(outcome is HostSwitchOutcome.Rebuild)
        assertEquals("desktop", (outcome as HostSwitchOutcome.Rebuild).profile.id)
    }
}
