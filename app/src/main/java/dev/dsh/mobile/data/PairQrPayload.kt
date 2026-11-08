package dev.dsh.mobile.data

import java.net.URLDecoder

/**
 * Parsed `dshmobile://pair?base=...&code=...&host=...` payload from a host QR code.
 *
 * The host plugin builds this payload in `POST /mobile/v1/pair/new` (`qrPayload`), so the QR
 * scanner and the `dshmobile://pair` deep link share exactly one parser. Everything here is pure
 * Kotlin (no `android.net.Uri`) so the grammar can be unit tested on the JVM.
 */
data class PairQrPayload(
    /** Normalised host root URL, e.g. `https://pc.tailnet.ts.net`. */
    val baseUrl: String,
    /** One-use 6-digit pairing code. */
    val code: String,
    /** Host display name from the payload, may be empty. */
    val hostLabel: String,
)

sealed interface PairQrParseResult {
    data class Valid(val payload: PairQrPayload) : PairQrParseResult
    data class Invalid(val message: String) : PairQrParseResult
}

object PairQrPayloadParser {

    const val SCHEME = "dshmobile"
    const val HOST = "pair"

    private val SIX_DIGITS = Regex("^\\d{6}$")

    /**
     * Parses a scanned or linked pairing payload.
     *
     * Accepts the exact host-generated link (`dshmobile://pair?base=...&code=...&host=...`),
     * tolerates case differences in scheme/host, surrounding whitespace, a trailing slash and
     * additional query parameters. Rejects every other link and any payload whose base URL or
     * code is unusable before the caller touches the network.
     */
    fun parse(raw: String?): PairQrParseResult {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) {
            return PairQrParseResult.Invalid("Scan the QR code shown by the host to pair.")
        }
        val prefix = "$SCHEME://"
        if (!text.lowercase().startsWith(prefix)) {
            return PairQrParseResult.Invalid("That code is not a DSH pairing code.")
        }
        val rest = text.substring(prefix.length)
        val target = rest.substringBefore('?').substringBefore('#').trim()
        if (!target.trimEnd('/').equals(HOST, ignoreCase = true)) {
            return PairQrParseResult.Invalid("That code is not a DSH pairing code.")
        }
        val queryStart = rest.indexOf('?')
        if (queryStart < 0) {
            return PairQrParseResult.Invalid("The pairing code is missing the host URL.")
        }
        val query = rest.substring(queryStart + 1).substringBefore('#')
        val params = parseQuery(query)

        val baseRaw = params["base"].orEmpty().trim()
        if (baseRaw.isEmpty()) {
            return PairQrParseResult.Invalid("The pairing code is missing the host URL.")
        }
        val baseUrl = when (val parsed = HostUrl.parse(baseRaw)) {
            is HostUrlResult.Valid -> parsed.root
            is HostUrlResult.Invalid -> return PairQrParseResult.Invalid(parsed.message)
        }

        val code = params["code"].orEmpty().trim()
        if (!SIX_DIGITS.matches(code)) {
            return PairQrParseResult.Invalid("The pairing code is missing its 6-digit code.")
        }

        val hostLabel = params["host"].orEmpty().trim().take(80)
        return PairQrParseResult.Valid(PairQrPayload(baseUrl, code, hostLabel))
    }

    private fun parseQuery(query: String): Map<String, String> {
        val params = linkedMapOf<String, String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val index = part.indexOf('=')
            if (index <= 0) continue
            val key = decode(part.substring(0, index))
            if (key.isEmpty() || params.containsKey(key)) continue
            params[key] = decode(part.substring(index + 1))
        }
        return params
    }

    /**
     * Decodes one query component. `URLDecoder` turns `+` into a space, but a plus sign is
     * literal in a hand-built deep link (host names and labels may contain one), so protect it
     * first. Malformed escapes keep the raw text instead of failing the whole payload.
     */
    private fun decode(value: String): String = try {
        URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        value
    }
}
