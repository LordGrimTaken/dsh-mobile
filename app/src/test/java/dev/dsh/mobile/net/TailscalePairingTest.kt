package dev.dsh.mobile.net

import dev.dsh.mobile.data.HostUrl
import dev.dsh.mobile.data.HostUrlResult
import dev.dsh.mobile.data.PairMethods
import dev.dsh.mobile.data.TAILSCALE_IDENTITY_MISSING_CODE
import dev.dsh.mobile.data.TAILSCALE_SIGNIN_COPY
import dev.dsh.mobile.data.TailscaleFailureKind
import dev.dsh.mobile.data.tailscaleFailureMessage
import dev.dsh.mobile.data.tailscaleMethodsHint
import dev.dsh.mobile.data.tailscaleSignInAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Tailscale one-tap pairing contract: methods decode/defaults, request shape,
 * response mapping (pairingMethod/identity), error buckets and host URL normalization.
 */
class TailscalePairingTest {

    @Test
    fun `pair methods decode all flags and defaults`() {
        val full = DshJson.decodeFromString(
            PairMethodsDto.serializer(),
            """{"desktopCode":true,"recovery":true,"tailscaleAutoPair":true,
               "tailscaleLoginRequired":false}""",
        )
        assertTrue(full.desktopCode)
        assertTrue(full.recovery)
        assertTrue(full.tailscaleAutoPair)
        assertFalse(full.tailscaleLoginRequired)

        val defaults = DshJson.decodeFromString(PairMethodsDto.serializer(), """{}""")
        assertTrue(defaults.desktopCode)
        assertFalse(defaults.recovery)
        assertFalse(defaults.tailscaleAutoPair)
        assertFalse(defaults.tailscaleLoginRequired)
    }

    @Test
    fun `tailscale pair request carries device metadata`() {
        val full = DshJson.encodeToString(
            TailscalePairRequest.serializer(),
            TailscalePairRequest(deviceName = "Pixel 9", platform = "android"),
        )
        assertEquals("""{"deviceName":"Pixel 9","platform":"android"}""", full)

        val minimal = DshJson.encodeToString(
            TailscalePairRequest.serializer(),
            TailscalePairRequest(),
        )
        assertFalse(minimal.contains("deviceName"))
        assertFalse(minimal.contains("platform"))
    }

    @Test
    fun `tailscale pair response decodes pairing method and identity`() {
        val json = """
            {"token":"tok_9","deviceId":"dev_3",
             "host":{"name":"Studio","version":"0.2.111","apiVersion":1},
             "pairingMethod":"tailscale","identity":"phone@tailnet.ts.net"}
        """.trimIndent()
        val response = DshJson.decodeFromString(TailscalePairResponse.serializer(), json)
        assertEquals("tok_9", response.token)
        assertEquals("dev_3", response.deviceId)
        assertEquals("Studio", response.host?.name)
        assertEquals("0.2.111", response.host?.version)
        assertEquals("tailscale", response.pairingMethod)
        assertEquals("phone@tailnet.ts.net", response.identity)
        assertTrue(response.workspaces.isEmpty())

        val defaults = DshJson.decodeFromString(TailscalePairResponse.serializer(), """{}""")
        assertEquals("tailscale", defaults.pairingMethod)
        assertNull(defaults.identity)
        assertNull(defaults.host)
    }

    @Test
    fun `tailscale failure buckets map to clear copy`() {
        TailscaleFailureKind.entries.forEach { kind ->
            assertTrue(kind.name, tailscaleFailureMessage(kind).isNotBlank())
        }
        val identity = tailscaleFailureMessage(TailscaleFailureKind.IDENTITY_MISSING)
        assertTrue(identity.contains("Tailscale Serve"))
        assertTrue(identity.contains("pairing code"))
        assertTrue(
            tailscaleFailureMessage(TailscaleFailureKind.FORBIDDEN).contains("doesn't allow"),
        )
        assertTrue(
            tailscaleFailureMessage(TailscaleFailureKind.RATE_LIMITED).contains("Too many"),
        )
        assertTrue(tailscaleFailureMessage(TailscaleFailureKind.NETWORK).contains("Tailscale is on"))
        assertEquals(
            "tailscale_identity_missing",
            TAILSCALE_IDENTITY_MISSING_CODE,
        )
    }

    @Test
    fun `methods hint explains unavailable and login-required hosts`() {
        assertNull(tailscaleMethodsHint(null))
        assertNull(
            tailscaleMethodsHint(
                PairMethods(desktopCode = true, tailscaleAutoPair = true),
            ),
        )
        val unavailable = tailscaleMethodsHint(
            PairMethods(tailscaleAutoPair = false, recovery = true),
        )
        assertTrue(unavailable.orEmpty().contains("isn't set up for Tailscale sign-in"))
        assertTrue(unavailable.orEmpty().contains("recovery link"))
        val loginRequired = tailscaleMethodsHint(
            PairMethods(tailscaleAutoPair = true, tailscaleLoginRequired = true),
        )
        assertTrue(loginRequired.orEmpty().contains("Sign in to the Tailscale app"))
        assertTrue(TAILSCALE_SIGNIN_COPY.contains("No pairing code"))
        assertTrue(TAILSCALE_SIGNIN_COPY.contains("tailnet verifies this device"))
    }

    @Test
    fun `add-host sign-in gate allows unknown methods but blocks known-unsupported hosts`() {
        assertTrue("preflight failure should still allow an attempt", tailscaleSignInAvailable(null))
        assertTrue(tailscaleSignInAvailable(PairMethods(tailscaleAutoPair = true)))
        assertTrue(
            tailscaleSignInAvailable(
                PairMethods(tailscaleAutoPair = true, tailscaleLoginRequired = true),
            ),
        )
        assertFalse(tailscaleSignInAvailable(PairMethods(tailscaleAutoPair = false)))
    }

    @Test
    fun `host URL normalization trims trailing slashes and tolerates mobile root`() {
        assertEquals(
            "https://host.tailnet.ts.net",
            HostUrl.rootOrNull("  https://host.tailnet.ts.net/// "),
        )
        assertEquals(
            "https://host.tailnet.ts.net:8443",
            HostUrl.rootOrNull("https://host.tailnet.ts.net:8443/mobile/v1"),
        )
        assertEquals(
            "http://127.0.0.1:7777",
            HostUrl.rootOrNull("http://127.0.0.1:7777"),
        )
        assertTrue(HostUrl.parse("host.tailnet.ts.net") is HostUrlResult.Invalid)
        assertTrue(HostUrl.parse("https://host/path/deeper") is HostUrlResult.Invalid)
        assertTrue(HostUrl.parse("") is HostUrlResult.Invalid)
    }
}
