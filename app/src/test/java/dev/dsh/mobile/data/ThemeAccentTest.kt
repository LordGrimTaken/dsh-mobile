package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Persisted accent packs: parsing/codec/copy + readable contrast for every palette role. */
class ThemeAccentTest {

    @Test
    fun `six accents in stable order with keys and labels`() {
        assertEquals(
            listOf("hadal", "ocean", "amethyst", "ember", "sakura", "slate"),
            ThemeAccent.entries.map { it.key },
        )
        assertEquals(
            listOf("Hadal", "Ocean", "Amethyst", "Ember", "Sakura", "Slate"),
            ThemeAccent.entries.map { it.label },
        )
        assertEquals(ThemeAccent.Hadal, ThemeAccent.Default)
        assertEquals(6, ThemeAccent.entries.size)
    }

    @Test
    fun `default and unknown accent values fall back safely`() {
        assertEquals(ThemeAccent.Hadal, ThemeAccent.fromStorage(null))
        assertEquals(ThemeAccent.Hadal, ThemeAccent.fromStorage(""))
        assertEquals(ThemeAccent.Hadal, ThemeAccent.fromStorage("   "))
        assertEquals(ThemeAccent.Hadal, ThemeAccent.fromStorage("hadal"))
        assertEquals(ThemeAccent.Ocean, ThemeAccent.fromStorage(" OCEAN "))
        assertEquals(ThemeAccent.Amethyst, ThemeAccent.fromStorage("amethyst"))
        assertEquals(ThemeAccent.Hadal, ThemeAccent.fromStorage("unknown-pack"))
        assertEquals(ThemeAccent.Hadal, ThemeAccent.fromStorage("ocean-blue"))
        assertEquals(ThemeAccent.Hadal, ThemeAccent.fromStorage("\u0000"))
        // AppState reads this through the same helper, so an old/missing pref keeps today's look.
        assertEquals(ThemeAccent.Hadal, themeAccentFromStorage(null))
    }

    @Test
    fun `persistence codec round-trips every accent`() {
        assertEquals("theme_accent", ACCENT_PREF_KEY)
        assertNotEquals("dark_theme", ACCENT_PREF_KEY)
        assertNotEquals("amoled_dark", ACCENT_PREF_KEY)
        ThemeAccent.entries.forEach { accent ->
            val stored = themeAccentStorageValue(accent)
            assertEquals(accent.key, stored)
            assertEquals(accent, themeAccentFromStorage(stored))
        }
    }

    @Test
    fun `chip content description announces label and selection`() {
        assertEquals("Hadal accent", themeAccentChipContentDescription(ThemeAccent.Hadal, false))
        assertEquals("Hadal accent, selected", themeAccentChipContentDescription(ThemeAccent.Hadal, true))
        assertEquals("Sakura accent", themeAccentChipContentDescription(ThemeAccent.Sakura, false))
        assertEquals("Slate accent, selected", themeAccentChipContentDescription(ThemeAccent.Slate, true))
    }

    @Test
    fun `hadal keeps the exact current scheme and every other pack overrides`() {
        assertNull("default renders the current Hadal scheme exactly", accentPalette(ThemeAccent.Hadal, dark = true))
        assertNull(accentPalette(ThemeAccent.Hadal, dark = false))
        val darkPrimaries = mutableListOf<Long>()
        val lightPrimaries = mutableListOf<Long>()
        ThemeAccent.entries.filter { it != ThemeAccent.Hadal }.forEach { accent ->
            val dark = accentPalette(accent, dark = true)
            val light = accentPalette(accent, dark = false)
            assertNotNull("$accent dark palette", dark)
            assertNotNull("$accent light palette", light)
            darkPrimaries += dark!!.primary
            lightPrimaries += light!!.primary
        }
        assertEquals(5, darkPrimaries.distinct().size)
        assertEquals(5, lightPrimaries.distinct().size)
        // Hadal's swatch stays the current mint even when another accent is active.
        assertEquals(0xFF4EE59B, accentSwatchArgb(ThemeAccent.Hadal, dark = true))
        assertEquals(0xFF1F8A5B, accentSwatchArgb(ThemeAccent.Hadal, dark = false))
        assertEquals(
            6,
            ThemeAccent.entries.map { accentSwatchArgb(it, dark = true) }.distinct().size,
        )
    }

    @Test
    fun `accent text roles keep readable contrast in dark and light`() {
        ThemeAccent.entries.forEach { accent ->
            listOf(true, false).forEach { dark ->
                val palette = accentPalette(accent, dark) ?: return@forEach
                val label = "$accent dark=$dark"
                assertTrue(
                    "$label primary/onPrimary contrast",
                    contrastRatioArgb(palette.primary, palette.onPrimary) >= 4.5,
                )
                assertTrue(
                    "$label primaryContainer/onPrimaryContainer contrast",
                    contrastRatioArgb(palette.primaryContainer, palette.onPrimaryContainer) >= 4.5,
                )
                assertTrue(
                    "$label secondary/onSecondary contrast",
                    contrastRatioArgb(palette.secondary, palette.onSecondary) >= 4.5,
                )
                assertTrue(
                    "$label secondaryContainer/onSecondaryContainer contrast",
                    contrastRatioArgb(palette.secondaryContainer, palette.onSecondaryContainer) >= 4.5,
                )
            }
        }
    }

    @Test
    fun `A1 swatch follows the active dark or light scheme`() {
        ThemeAccent.entries.forEach { accent ->
            val darkSwatch = accentSwatchArgb(accent, dark = true)
            val lightSwatch = accentSwatchArgb(accent, dark = false)
            val expectedDark = accentPalette(accent, dark = true)?.primary
                ?: 0xFF4EE59B
            val expectedLight = accentPalette(accent, dark = false)?.primary
                ?: 0xFF1F8A5B
            assertEquals("$accent dark swatch", expectedDark, darkSwatch)
            assertEquals("$accent light swatch", expectedLight, lightSwatch)
        }
        // Non-default packs visibly differ between light and dark palettes.
        listOf(
            ThemeAccent.Ocean,
            ThemeAccent.Amethyst,
            ThemeAccent.Ember,
            ThemeAccent.Sakura,
            ThemeAccent.Slate,
        ).forEach { accent ->
            assertNotEquals(
                "$accent swatch must change with theme brightness",
                accentSwatchArgb(accent, dark = true),
                accentSwatchArgb(accent, dark = false),
            )
        }
    }

    @Test
    fun `A7 chip grid uses two columns on narrow sheets`() {
        assertEquals(2, accentChipColumns(240f))
        assertEquals(2, accentChipColumns(320f))
        assertEquals(2, accentChipColumns(379.9f))
        assertEquals(3, accentChipColumns(380f))
        assertEquals(3, accentChipColumns(520f))
    }

    @Test
    fun `contrast helper is symmetric and bounded`() {
        assertEquals(
            contrastRatioArgb(0xFFFFFFFF, 0xFF000000),
            contrastRatioArgb(0xFF000000, 0xFFFFFFFF),
            0.0001,
        )
        assertEquals(1.0, contrastRatioArgb(0xFF808080, 0xFF808080), 0.0001)
        assertTrue(contrastRatioArgb(0xFFFFFFFF, 0xFF000000) > 20.0)
        assertFalse(contrastRatioArgb(0xFFFFFFFF, 0xFFFFFFFF) > 1.0)
    }

    private fun assertNotNull(message: String, value: Any?) {
        org.junit.Assert.assertNotNull(message, value)
    }
}
