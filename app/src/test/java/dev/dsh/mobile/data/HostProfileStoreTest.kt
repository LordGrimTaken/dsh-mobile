package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory stand-in for SharedPreferences so the store can be tested on the JVM. */
private class InMemoryHostKeyValueStore : HostKeyValueStore {
    val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

/** Deterministic reversible cipher; stands in for the Android Keystore implementation. */
private class FakeTokenCipher : TokenCipher {
    override fun encrypt(plaintext: String): String = "enc:" + plaintext.reversed()

    override fun decrypt(ciphertext: String): String {
        require(ciphertext.startsWith("enc:")) { "unreadable token" }
        return ciphertext.removePrefix("enc:").reversed()
    }
}

class HostProfileStoreTest {

    private val kv = InMemoryHostKeyValueStore()
    private val store = HostProfileStore(kv, FakeTokenCipher())

    private fun profile(
        id: String,
        label: String = "Desktop",
        baseUrl: String = "https://desk.example.com",
        token: String = "secret-token-$id",
    ) = HostProfile(
        id = id,
        label = label,
        baseUrl = baseUrl,
        deviceId = "dev_$id",
        token = token,
        pairedAt = "2026-10-01T00:00:00Z",
        lastUsedAt = "2026-10-02T00:00:00Z",
    )

    @Test
    fun `persists profiles and the active id`() {
        val desktop = profile("a", label = "Desktop")
        val cloud = profile("b", label = "Cloud", baseUrl = "https://cloud.example.com")
        store.save(listOf(desktop, cloud), activeId = "b")

        val loaded = store.load()
        assertEquals(2, loaded.profiles.size)
        assertEquals("b", loaded.activeId)
        assertEquals("Cloud", loaded.profiles[1].label)
        assertEquals("https://cloud.example.com", loaded.profiles[1].baseUrl)
        assertEquals("secret-token-b", loaded.profiles[1].token)
    }

    @Test
    fun `tokens are only stored as ciphertext`() {
        store.save(listOf(profile("a", token = "super-secret-token")), "a")
        val raw = kv.values[HostProfileStore.DOC_KEY].orEmpty()
        assertTrue(raw.isNotBlank())
        assertFalse("plaintext token must never hit prefs", raw.contains("super-secret-token"))
        assertTrue("ciphertext should be present", raw.contains("enc:"))
    }

    @Test
    fun `active id falls back to first profile when stored id is stale`() {
        store.save(listOf(profile("a"), profile("b")), activeId = "does-not-exist")
        assertEquals("a", store.load().activeId)
    }

    @Test
    fun `active id is persisted across store instances`() {
        store.save(listOf(profile("a"), profile("b")), "b")
        val reloaded = HostProfileStore(kv, FakeTokenCipher()).load()
        assertEquals("b", reloaded.activeId)
    }

    @Test
    fun `corrupt or undecryptable tokens are dropped instead of crashing`() {
        val badCipher = object : TokenCipher {
            override fun encrypt(plaintext: String): String = "not-a-real-ciphertext"
            override fun decrypt(ciphertext: String): String = error("cannot decrypt")
        }
        HostProfileStore(kv, badCipher).save(listOf(profile("broken")), "broken")

        val loaded = store.load()
        assertTrue(loaded.profiles.isEmpty())
        assertNull(loaded.activeId)
    }

    @Test
    fun `empty store loads empty snapshot`() {
        val loaded = store.load()
        assertTrue(loaded.profiles.isEmpty())
        assertNull(loaded.activeId)
    }

    @Test
    fun `welcome dismissed flag persists and survives saves`() {
        assertFalse(store.isWelcomeDismissed())
        store.dismissWelcome()
        assertTrue(store.isWelcomeDismissed())
        assertTrue(HostProfileStore(kv, FakeTokenCipher()).isWelcomeDismissed())

        store.save(listOf(profile("a")), "a")
        assertTrue("saving hosts must not reset onboarding", store.isWelcomeDismissed())
    }

    @Test
    fun `removing a profile through save keeps the rest`() {
        val a = profile("a")
        val b = profile("b", label = "Cloud")
        store.save(listOf(a, b), "a")
        store.save(listOf(b), "b")

        val loaded = store.load()
        assertEquals(1, loaded.profiles.size)
        assertEquals("b", loaded.activeId)
        assertEquals("Cloud", loaded.profiles.first().label)
    }

    // region legacy single-pairing migration

    private fun legacy(
        hostName: String = "",
        baseUrl: String = "https://desk.example.com/",
        token: String = "legacy-token-123",
    ) = PairingConfig(
        baseUrl = baseUrl,
        token = token,
        deviceId = "dev_legacy",
        hostName = hostName,
        pairedAt = "2026-09-30T12:00:00Z",
    )

    @Test
    fun `legacy pairing migrates to a desktop profile`() {
        val profile = HostProfileMigration.fromLegacy(legacy())!!
        assertTrue(profile.id.startsWith("host-"))
        assertEquals("Desktop", profile.label)
        assertEquals("https://desk.example.com", profile.baseUrl)
        assertEquals("legacy-token-123", profile.token)
        assertEquals("dev_legacy", profile.deviceId)
        assertEquals("2026-09-30T12:00:00Z", profile.pairedAt)
    }

    @Test
    fun `migration reuses the legacy host name as label`() {
        val profile = HostProfileMigration.fromLegacy(legacy(hostName = "pc"))!!
        assertEquals("pc", profile.label)
    }

    @Test
    fun `migrated profile is persisted encrypted and becomes active`() {
        val migrated = HostProfileMigration.fromLegacy(legacy())!!
        store.save(listOf(migrated), migrated.id)

        val loaded = store.load()
        assertEquals(1, loaded.profiles.size)
        assertEquals(migrated.id, loaded.activeId)
        assertEquals("legacy-token-123", loaded.profiles.first().token)
        val raw = kv.values[HostProfileStore.DOC_KEY].orEmpty()
        assertFalse(raw.contains("legacy-token-123"))
    }

    @Test
    fun `migration rejects unusable legacy configs`() {
        assertNull(HostProfileMigration.fromLegacy(null))
        assertNull(HostProfileMigration.fromLegacy(legacy().copy(token = "   ")))
        assertNull(HostProfileMigration.fromLegacy(legacy().copy(baseUrl = "not a url")))
        assertNull(HostProfileMigration.fromLegacy(legacy().copy(token = "")))
    }

    @Test
    fun `migration preserves a non-blank pairedAt or falls back to now`() {
        val withPairedAt = HostProfileMigration.fromLegacy(legacy())!!
        assertEquals("2026-09-30T12:00:00Z", withPairedAt.pairedAt)

        val withoutPairedAt = HostProfileMigration.fromLegacy(
            legacy().copy(pairedAt = ""),
            now = { "2026-10-30T10:00:00Z" },
        )!!
        assertEquals("2026-10-30T10:00:00Z", withoutPairedAt.pairedAt)
    }

    // endregion
}
