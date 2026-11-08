package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairQrPayloadParserTest {

    private fun valid(raw: String): PairQrPayload {
        val result = PairQrPayloadParser.parse(raw)
        assertTrue("expected valid payload for $raw, got $result", result is PairQrParseResult.Valid)
        return (result as PairQrParseResult.Valid).payload
    }

    private fun invalid(raw: String?) {
        val result = PairQrPayloadParser.parse(raw)
        assertTrue("expected invalid payload for $raw, got $result", result is PairQrParseResult.Invalid)
    }

    @Test
    fun `parses the host-generated payload`() {
        val payload = valid(
            "dshmobile://pair?base=https%3A%2F%2Fpc.tailnet.ts.net&code=482913&host=pc",
        )
        assertEquals("https://pc.tailnet.ts.net", payload.baseUrl)
        assertEquals("482913", payload.code)
        assertEquals("pc", payload.hostLabel)
    }

    @Test
    fun `accepts a dev http url with a port and a spaced host label`() {
        val payload = valid(
            "dshmobile://pair?base=http://10.0.2.2:3543&code=482913&host=My%20Desktop",
        )
        assertEquals("http://10.0.2.2:3543", payload.baseUrl)
        assertEquals("My Desktop", payload.hostLabel)
    }

    @Test
    fun `tolerates case, whitespace, trailing slash and extra params`() {
        val payload = valid(
            "  DSHMobile://PAIR/?code=482913&base=https://pc.ts.net/&host=pc&extra=1  ",
        )
        assertEquals("https://pc.ts.net", payload.baseUrl)
        assertEquals("482913", payload.code)
    }

    @Test
    fun `keeps literal plus signs in the host label`() {
        val payload = valid("dshmobile://pair?base=https://pc.ts.net&code=482913&host=pc+1")
        assertEquals("pc+1", payload.hostLabel)
    }

    @Test
    fun `allows payloads without a host label`() {
        val payload = valid("dshmobile://pair?base=https://pc.ts.net&code=482913")
        assertEquals("", payload.hostLabel)
    }

    @Test
    fun `rejects other deep links`() {
        invalid("dshmobile://recover?base=https://pc.ts.net&code=ABC")
        invalid("dshmobile://new")
        invalid("https://example.com/pair?code=482913")
        invalid("not a link")
        invalid(null)
        invalid("")
    }

    @Test
    fun `rejects payloads without a usable host url`() {
        invalid("dshmobile://pair?code=482913&host=pc")
        invalid("dshmobile://pair?base=&code=482913")
        invalid("dshmobile://pair?base=pc.ts.net&code=482913")
        invalid("dshmobile://pair?base=https://pc.ts.net/deep/path&code=482913")
    }

    @Test
    fun `rejects a missing or malformed pairing code`() {
        invalid("dshmobile://pair?base=https://pc.ts.net")
        invalid("dshmobile://pair?base=https://pc.ts.net&code=abc")
        invalid("dshmobile://pair?base=https://pc.ts.net&code=12345")
        invalid("dshmobile://pair?base=https://pc.ts.net&code=1234567")
    }

    @Test
    fun `reports a readable message for a missing base url`() {
        val result = PairQrPayloadParser.parse("dshmobile://pair?code=482913")
        assertEquals(
            "The pairing code is missing the host URL.",
            (result as PairQrParseResult.Invalid).message,
        )
    }
}
