package dev.dsh.mobile.net

import dev.dsh.mobile.data.APPROVAL_POLICY_ASK
import dev.dsh.mobile.data.APPROVAL_POLICY_NEVER
import dev.dsh.mobile.data.approvalPolicyLabel
import dev.dsh.mobile.data.approvalPolicyValid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the approval-policy contract: GET/PUT decode, unsupported hosts, request shape and
 * the policy validation/label helpers used by the dialog.
 */
class ApprovalPolicyParsingTest {

    @Test
    fun `policy response decodes ask on a live session`() {
        val dto = DshJson.decodeFromString(
            ApprovalPolicyDto.serializer(),
            """{"supported":true,"live":true,"policy":"ask"}""",
        )
        assertTrue(dto.supported)
        assertTrue(dto.live)
        assertEquals(APPROVAL_POLICY_ASK, dto.policy)
    }

    @Test
    fun `policy response decodes never`() {
        val dto = DshJson.decodeFromString(
            ApprovalPolicyDto.serializer(),
            """{"supported":true,"live":true,"policy":"never"}""",
        )
        assertEquals(APPROVAL_POLICY_NEVER, dto.policy)
    }

    @Test
    fun `policy response defaults are safe and non-live`() {
        val dto = DshJson.decodeFromString(ApprovalPolicyDto.serializer(), """{}""")
        assertTrue(dto.supported)
        assertFalse(dto.live)
        assertEquals(APPROVAL_POLICY_ASK, dto.policy)
    }

    @Test
    fun `unsupported host decodes`() {
        val dto = DshJson.decodeFromString(
            ApprovalPolicyDto.serializer(),
            """{"supported":false,"live":false,"policy":"ask"}""",
        )
        assertFalse(dto.supported)
        assertFalse(dto.live)
    }

    @Test
    fun `put request carries the selected policy`() {
        val never = DshJson.encodeToString(
            ApprovalPolicyUpdateRequest.serializer(),
            ApprovalPolicyUpdateRequest(APPROVAL_POLICY_NEVER),
        )
        assertEquals("""{"policy":"never"}""", never)

        val ask = DshJson.encodeToString(
            ApprovalPolicyUpdateRequest.serializer(),
            ApprovalPolicyUpdateRequest(APPROVAL_POLICY_ASK),
        )
        assertEquals("""{"policy":"ask"}""", ask)
    }

    @Test
    fun `policy validation accepts only ask and never`() {
        assertTrue(approvalPolicyValid(APPROVAL_POLICY_ASK))
        assertTrue(approvalPolicyValid(APPROVAL_POLICY_NEVER))
        assertFalse(approvalPolicyValid(""))
        assertFalse(approvalPolicyValid("Ask"))
        assertFalse(approvalPolicyValid("never "))
        assertFalse(approvalPolicyValid("auto"))
    }

    @Test
    fun `policy labels are friendly`() {
        assertEquals("Ask", approvalPolicyLabel(APPROVAL_POLICY_ASK))
        assertEquals("Never", approvalPolicyLabel(APPROVAL_POLICY_NEVER))
        assertEquals("Ask", approvalPolicyLabel("unknown"))
        assertEquals("ask", APPROVAL_POLICY_ASK)
        assertEquals("never", APPROVAL_POLICY_NEVER)
    }

    @Test
    fun `unsupported model is not live`() {
        val unsupported = dev.dsh.mobile.data.ApprovalPolicy.Unsupported
        assertFalse(unsupported.supported)
        assertFalse(unsupported.live)
        assertEquals(APPROVAL_POLICY_ASK, unsupported.policy)
    }
}
