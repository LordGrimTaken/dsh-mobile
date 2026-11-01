package dev.dsh.mobile.net

import dev.dsh.mobile.data.PairedDevice
import dev.dsh.mobile.data.PairedDevices
import dev.dsh.mobile.data.pairedDeviceIsCurrent
import dev.dsh.mobile.data.pairedDeviceMetadataLabel
import dev.dsh.mobile.data.pairedDeviceName
import dev.dsh.mobile.data.pairedDevicesSummary
import dev.dsh.mobile.data.relativeDeviceTime
import dev.dsh.mobile.data.revokeDeviceIdValid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * JVM tests for the paired-devices contract: GET/DELETE decode, current-device tagging, relative
 * time/metadata labels and request validation.
 */
class PairedDevicesParsingTest {

    private val now = Instant.parse("2026-11-02T12:00:00Z")

    private fun device(
        id: String,
        name: String = "Pixel 9",
        platform: String = "Android",
        pairedAt: String? = "2026-10-30T12:00:00Z",
        lastSeenAt: String? = "2026-11-02T11:55:00Z",
    ) = PairedDevice(
        deviceId = id,
        deviceName = name,
        platform = platform,
        pairedAt = pairedAt,
        lastSeenAt = lastSeenAt,
    )

    @Test
    fun `devices response decodes devices and the current id`() {
        val json = """
            {"supported":true,"currentDeviceId":"dev_1","devices":[
              {"deviceId":"dev_1","deviceName":"Pixel 9","platform":"Android",
               "pairedAt":"2026-10-30T12:00:00Z","lastSeenAt":"2026-11-02T11:55:00Z"},
              {"deviceId":"dev_2","deviceName":"MacBook","platform":"macOS",
               "pairedAt":"2026-09-01T09:00:00Z"}
            ]}
        """.trimIndent()
        val response = DshJson.decodeFromString(DevicesResponse.serializer(), json)
        assertTrue(response.supported)
        assertEquals("dev_1", response.currentDeviceId)
        assertEquals(2, response.devices.size)
        assertEquals("Pixel 9", response.devices[0].deviceName)
        assertEquals("2026-10-30T12:00:00Z", response.devices[0].pairedAt)
        assertEquals("2026-11-02T11:55:00Z", response.devices[0].lastSeenAt)
        assertNull("lastSeenAt is optional", response.devices[1].lastSeenAt)
    }

    @Test
    fun `devices response defaults are safe`() {
        val response = DshJson.decodeFromString(DevicesResponse.serializer(), """{}""")
        assertTrue(response.supported)
        assertEquals("", response.currentDeviceId)
        assertTrue(response.devices.isEmpty())
    }

    @Test
    fun `unsupported devices response decodes`() {
        val response = DshJson.decodeFromString(
            DevicesResponse.serializer(),
            """{"supported":false,"devices":[],"currentDeviceId":""}""",
        )
        assertFalse(response.supported)
    }

    @Test
    fun `revoke response decodes`() {
        val revoked = DshJson.decodeFromString(
            RevokeDeviceResponse.serializer(),
            """{"ok":true,"revoked":true}""",
        )
        assertTrue(revoked.ok)
        assertTrue(revoked.revoked)
        val rejected = DshJson.decodeFromString(
            RevokeDeviceResponse.serializer(),
            """{"ok":false,"revoked":false}""",
        )
        assertFalse(rejected.ok)
    }

    @Test
    fun `relative time buckets are stable`() {
        assertEquals("just now", relativeDeviceTime("2026-11-02T11:59:30Z", now))
        assertEquals("5m ago", relativeDeviceTime("2026-11-02T11:55:00Z", now))
        assertEquals("3h ago", relativeDeviceTime("2026-11-02T09:00:00Z", now))
        assertEquals("2d ago", relativeDeviceTime("2026-10-31T12:00:00Z", now))
        assertNull(relativeDeviceTime(null, now))
        assertNull(relativeDeviceTime("", now))
        assertNull(relativeDeviceTime("not-a-timestamp", now))
        // Older than 30 days falls back to a date (zone-dependent), never "ago".
        val old = relativeDeviceTime("2026-08-01T12:00:00Z", now)
        assertNotNull(old)
        assertFalse(old.orEmpty().contains("ago"))
    }

    @Test
    fun `metadata label combines paired and last seen`() {
        assertEquals(
            "paired 3d ago · last seen 5m ago",
            pairedDeviceMetadataLabel(device("dev_1"), now),
        )
        assertEquals(
            "last seen 5m ago",
            pairedDeviceMetadataLabel(device("dev_1", pairedAt = null), now),
        )
        assertEquals(
            "paired 3d ago",
            pairedDeviceMetadataLabel(device("dev_1", lastSeenAt = null), now),
        )
        assertNull(pairedDeviceMetadataLabel(device("dev_1", pairedAt = null, lastSeenAt = null), now))
    }

    @Test
    fun `device name never falls back to an id or token`() {
        assertEquals("Pixel 9", pairedDeviceName(device("dev_1")))
        assertEquals("Android", pairedDeviceName(device("dev_1", name = "  ")))
        assertEquals("Paired device", pairedDeviceName(device("dev_1", name = "", platform = "")))
    }

    @Test
    fun `current device and summary are derived from the list`() {
        val current = device("dev_1")
        val other = device("dev_2")
        val devices = PairedDevices(
            supported = true,
            devices = listOf(current, other),
            currentDeviceId = "dev_1",
        )
        assertTrue(pairedDeviceIsCurrent(devices, current))
        assertFalse(pairedDeviceIsCurrent(devices, other))
        assertFalse(
            pairedDeviceIsCurrent(devices.copy(currentDeviceId = ""), current),
        )
        assertEquals("2 paired devices", pairedDevicesSummary(devices))
        assertEquals(
            "1 paired device",
            pairedDevicesSummary(devices.copy(devices = listOf(current))),
        )
        assertEquals(
            "No paired devices",
            pairedDevicesSummary(devices.copy(devices = emptyList())),
        )
        assertEquals(
            "Not supported by this host",
            pairedDevicesSummary(PairedDevices.Unsupported),
        )
    }

    @Test
    fun `revoke request validation rejects blank ids`() {
        assertTrue(revokeDeviceIdValid("dev_2"))
        assertFalse(revokeDeviceIdValid(""))
        assertFalse(revokeDeviceIdValid("   "))
        assertFalse(PairedDevices.Unsupported.supported)
    }
}
