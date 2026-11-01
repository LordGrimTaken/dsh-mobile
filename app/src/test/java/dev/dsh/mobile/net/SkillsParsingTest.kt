package dev.dsh.mobile.net

import dev.dsh.mobile.data.SKILLS_CACHE_TTL_MS
import dev.dsh.mobile.data.SKILLS_EMPTY_COPY
import dev.dsh.mobile.data.SKILLS_SESSION_UNAVAILABLE_COPY
import dev.dsh.mobile.data.SKILLS_UNSUPPORTED_COPY
import dev.dsh.mobile.data.isSessionSkillsCacheStale
import dev.dsh.mobile.data.skillPrefillDraft
import dev.dsh.mobile.data.skillPromptPrefill
import dev.dsh.mobile.data.sanitizedSkillDescription
import dev.dsh.mobile.data.sanitizedSkillName
import dev.dsh.mobile.data.shouldLoadSessionSkills
import dev.dsh.mobile.data.HostSkills
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GET /sessions/{id}/skills contract + the pure skills-browser helpers. */
class SkillsParsingTest {

    @Test
    fun `skills response decodes supported and unsupported hosts`() {
        val supported = DshJson.decodeFromString(
            SkillsResponse.serializer(),
            """{"supported":true,"skills":[
                 {"name":"cordis","description":"Cordis composition help"},
                 {"name":"review","description":""}
               ]}""",
        )
        assertTrue(supported.supported)
        assertEquals(2, supported.skills.size)
        assertEquals("cordis", supported.skills[0].name)
        assertEquals("Cordis composition help", supported.skills[0].description)

        val unsupported = DshJson.decodeFromString(
            SkillsResponse.serializer(),
            """{"supported":false,"skills":[]}""",
        )
        assertFalse(unsupported.supported)
        assertTrue(unsupported.skills.isEmpty())
    }

    @Test
    fun `old hosts and missing supported default to unsupported`() {
        val empty = DshJson.decodeFromString(
            SkillsResponse.serializer(),
            """{}""",
        )
        assertFalse(empty.supported)
        assertTrue(empty.skills.isEmpty())

        // A payload with skills but no `supported` flag is still an old/unknown host.
        val withoutFlag = DshJson.decodeFromString(
            SkillsResponse.serializer(),
            """{"skills":[{"name":"x"}]}""",
        )
        assertFalse(withoutFlag.supported)
    }

    @Test
    fun `skill names and descriptions are sanitized`() {
        assertEquals("cordis", sanitizedSkillName("  cordis\u0000 "))
        assertEquals("a b", sanitizedSkillName("a\nb"))
        assertNull(sanitizedSkillName(""))
        assertNull(sanitizedSkillName("   "))
        assertNull(sanitizedSkillName(null))
        assertEquals(80, sanitizedSkillName("n".repeat(200))?.length)

        assertEquals("Cordis help", sanitizedSkillDescription("  Cordis\n\nhelp  "))
        assertEquals("a b c", sanitizedSkillDescription("a\t b\r\nc"))
        assertNull(sanitizedSkillDescription(""))
        assertEquals(240, sanitizedSkillDescription("d".repeat(500))?.length)
    }

    @Test
    fun `prompt prefill matches the frozen template and is never sent`() {
        assertEquals("Use the cordis skill: ", skillPromptPrefill("cordis"))
        assertEquals("Use the cordis skill: ", skillPromptPrefill("  cordis  "))
        assertEquals("Use the a b skill: ", skillPromptPrefill("a\nb"))
        assertNull(skillPromptPrefill("   "))
        assertNull(skillPromptPrefill(null))
        assertTrue(skillPromptPrefill("cordis").orEmpty().endsWith(": "))
        assertFalse(skillPromptPrefill("cordis").orEmpty().contains("send"))
    }

    @Test
    fun `A3 prefill appends with a blank line and ends at the cursor target`() {
        val prefill = skillPromptPrefill("cordis").orEmpty()
        assertEquals(prefill, skillPrefillDraft("", prefill))
        assertEquals(prefill, skillPrefillDraft("   ", prefill))
        assertEquals("existing prompt\n\n$prefill", skillPrefillDraft("existing prompt", prefill))
        assertEquals("existing\n\n$prefill", skillPrefillDraft("existing\n\n", prefill))
        assertTrue(skillPrefillDraft("x", prefill).endsWith(prefill))
    }

    @Test
    fun `A1 cache decision respects the five minute TTL`() {
        val cached = HostSkills(supported = true, skills = emptyList())
        val now = 1_800_000_000_000L
        // Fresh cache: no fetch needed (even on sheet open); force still refreshes.
        assertFalse(
            shouldLoadSessionSkills(cached, loading = false, force = false, fetchedAtMs = now - 1_000, nowMs = now),
        )
        assertTrue(
            shouldLoadSessionSkills(cached, loading = false, force = true, fetchedAtMs = now - 1_000, nowMs = now),
        )
        // Stale cache refreshes but is kept in the UI while loading.
        assertTrue(
            shouldLoadSessionSkills(
                cached,
                loading = false,
                force = false,
                fetchedAtMs = now - SKILLS_CACHE_TTL_MS,
                nowMs = now,
            ),
        )
        assertFalse(
            shouldLoadSessionSkills(cached, loading = true, force = true, fetchedAtMs = now, nowMs = now),
        )
        assertTrue(shouldLoadSessionSkills(null, loading = false, force = false, nowMs = now))
        assertTrue(
            "cache without a timestamp is treated as stale",
            shouldLoadSessionSkills(cached, loading = false, force = false, fetchedAtMs = null, nowMs = now),
        )
        assertTrue(isSessionSkillsCacheStale(now - SKILLS_CACHE_TTL_MS, now))
        assertFalse(isSessionSkillsCacheStale(now - 1, now))
        assertEquals(300_000L, SKILLS_CACHE_TTL_MS)
    }

    @Test
    fun `empty, unsupported and session-unavailable copy are distinct`() {
        assertEquals("No skills available on this host.", SKILLS_EMPTY_COPY)
        assertEquals("This host does not expose skills.", SKILLS_UNSUPPORTED_COPY)
        assertEquals("This session is no longer available.", SKILLS_SESSION_UNAVAILABLE_COPY)
        assertFalse(SKILLS_EMPTY_COPY == SKILLS_UNSUPPORTED_COPY)
        assertTrue(HostSkills.Unsupported.supported.not())
        assertFalse(HostSkills.Unsupported.sessionUnavailable)
        assertTrue(HostSkills.SessionUnavailable.sessionUnavailable)
    }
}
