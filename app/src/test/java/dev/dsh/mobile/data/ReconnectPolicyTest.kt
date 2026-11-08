package dev.dsh.mobile.data

import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun `dns failure on a tailnet host while vpn is absent is a tailnet wait`() {
        assertTrue(
            isTailnetDnsFailure(
                UnknownHostException("xianwuli.tailce9fda.ts.net"),
                isTailnetHost = true,
                networkTransport = "Wi-Fi",
            ),
        )
    }

    @Test
    fun `dns failure on a tailnet host with vpn active is a normal retry`() {
        assertFalse(
            isTailnetDnsFailure(
                UnknownHostException("xianwuli.tailce9fda.ts.net"),
                isTailnetHost = true,
                networkTransport = "VPN",
            ),
        )
    }

    @Test
    fun `dns failure on a non-tailnet host is a normal retry`() {
        assertFalse(
            isTailnetDnsFailure(
                UnknownHostException("example.test"),
                isTailnetHost = false,
                networkTransport = "Wi-Fi",
            ),
        )
    }

    @Test
    fun `non-dns failures are not tailnet waits`() {
        assertFalse(
            isTailnetDnsFailure(
                IllegalStateException("stream broke"),
                isTailnetHost = true,
                networkTransport = "Wi-Fi",
            ),
        )
    }

    @Test
    fun `backoff doubles and caps at the reconnect ceiling`() {
        assertEquals(2_000L, nextReconnectBackoff(1_000L))
        assertEquals(4_000L, nextReconnectBackoff(2_000L))
        assertEquals(32_000L, nextReconnectBackoff(16_000L))
        assertEquals(MAX_RECONNECT_BACKOFF_MILLIS, nextReconnectBackoff(32_000L))
        assertEquals(MAX_RECONNECT_BACKOFF_MILLIS, nextReconnectBackoff(MAX_RECONNECT_BACKOFF_MILLIS))
    }
}
