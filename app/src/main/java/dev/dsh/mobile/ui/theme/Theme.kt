package dev.dsh.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.R
import dev.dsh.mobile.data.ThemeAccent
import dev.dsh.mobile.data.accentPalette

/**
 * HADAL MINT — "a terminal attached to a model".
 *
 * Raw palette tokens for the dark theme. Everything that can adapt to the light
 * "Hadal Day" theme goes through [MaterialTheme.colorScheme] instead; these raw
 * values are for mint glows/washes and for screens that need an exact ink tone.
 */
object Hadal {
    val Ink = Color(0xFF050A08)
    val Sidebar = Color(0xFF07100C)
    val Layer1 = Color(0xFF0A1510)
    val Layer2 = Color(0xFF0C1A14)
    val Layer3 = Color(0xFF10221A)
    val Hover = Color(0xFF142B21)

    /** 12% mint kelp hairline. */
    val Border = Color(0x1F78E5AA)

    /** 24% mint kelp hairline: focused / active edges. */
    val BorderStrong = Color(0x3D78E5AA)

    val Text1 = Color(0xFFDFF5E8)
    val Text2 = Color(0xFFA3CBB6)
    val Text3 = Color(0xFF78A58F)
    val TextDim = Color(0xFF5F8A75)

    val Mint = Color(0xFF4EE59B)
    val MintBright = Color(0xFF7CF0B8)
    val MintDeep = Color(0xFF2FBF7F)

    /** 10% mint wash: icon tiles, chips, active rows. */
    val MintWash = Color(0x1A4EE59B)

    val Amber = Color(0xFFF2C14E)
    val Red = Color(0xFFFF6B6B)
    val Violet = Color(0xFF8B7CF6)
}

/** State accents. Screens import these directly; they are the dark Hadal tones. */
val Success = Hadal.Mint
val Warning = Hadal.Amber
val Danger = Hadal.Red

/** Hadal Day (light) paper tokens; only referenced from [LightColors]. */
private val DayPaper = Color(0xFFF4F1E8)
private val DaySurface = Color(0xFFFAF8F1)
private val DayLayer = Color(0xFFEDE9DC)
private val DayLayerHigh = Color(0xFFE5E0D2)
private val DaySidebar = Color(0xFFEFEBDD)
private val DayInk = Color(0xFF10231A)
private val DayText = Color(0xFF4F6558)
private val DayOutline = Color(0xFFC9C2B0)
private val DayHairline = Color(0xFFDDD7C8)
private val DayGreen = Color(0xFF1F8A5B)
private val DayGreenDeep = Color(0xFF14603F)

private val DarkColors = darkColorScheme(
    primary = Hadal.Mint,
    onPrimary = Hadal.Ink,
    primaryContainer = Hadal.MintDeep.copy(alpha = 0.20f),
    onPrimaryContainer = Hadal.MintBright,
    inversePrimary = Hadal.MintDeep,
    secondary = Hadal.MintBright,
    onSecondary = Hadal.Ink,
    secondaryContainer = Color(0xFF12251C),
    onSecondaryContainer = Hadal.Text2,
    tertiary = Hadal.Text3,
    onTertiary = Hadal.Ink,
    tertiaryContainer = Hadal.Layer3,
    onTertiaryContainer = Hadal.Text2,
    background = Hadal.Ink,
    onBackground = Hadal.Text1,
    surface = Hadal.Layer1,
    onSurface = Hadal.Text1,
    surfaceVariant = Hadal.Layer2,
    onSurfaceVariant = Hadal.Text2,
    inverseSurface = Hadal.Text1,
    inverseOnSurface = Hadal.Ink,
    error = Hadal.Red,
    onError = Hadal.Ink,
    errorContainer = Color(0xFF3A1414),
    onErrorContainer = Color(0xFFFFD9D9),
    outline = Hadal.BorderStrong,
    outlineVariant = Hadal.Border,
    scrim = Color(0xFF000000),
    surfaceBright = Hadal.Layer3,
    surfaceDim = Hadal.Ink,
    surfaceContainer = Hadal.Layer2,
    surfaceContainerHigh = Hadal.Layer3,
    surfaceContainerHighest = Hadal.Hover,
    surfaceContainerLow = Hadal.Sidebar,
    surfaceContainerLowest = Hadal.Ink,
)

private val AmoledColors = DarkColors.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceDim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF050A08),
    surfaceContainer = Color(0xFF07100C),
    surfaceContainerHigh = Color(0xFF0A1510),
    surfaceContainerHighest = Color(0xFF0C1A14),
    surfaceVariant = Color(0xFF0A1510),
)

private val LightColors = lightColorScheme(
    primary = DayGreen,
    onPrimary = Color(0xFFF4FFF8),
    primaryContainer = Color(0xFFCDEBDD),
    onPrimaryContainer = DayGreenDeep,
    inversePrimary = Color(0xFF7CF0B8),
    secondary = DayText,
    onSecondary = Color(0xFFF7FBF8),
    secondaryContainer = DayLayer,
    onSecondaryContainer = DayInk,
    tertiary = Color(0xFF6B7F72),
    onTertiary = Color(0xFFF7FBF8),
    tertiaryContainer = DayLayer,
    onTertiaryContainer = DayInk,
    background = DayPaper,
    onBackground = DayInk,
    surface = DaySurface,
    onSurface = DayInk,
    surfaceVariant = DayLayer,
    onSurfaceVariant = DayText,
    inverseSurface = DayInk,
    inverseOnSurface = DaySurface,
    error = Color(0xFFB33A3A),
    onError = Color(0xFFFFF7F6),
    errorContainer = Color(0xFFF6DBDB),
    onErrorContainer = Color(0xFF4A1414),
    outline = DayOutline,
    outlineVariant = DayHairline,
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFCFAF4),
    surfaceDim = Color(0xFFE8E4D7),
    surfaceContainer = DaySurface,
    surfaceContainerHigh = DayLayer,
    surfaceContainerHighest = DayLayerHigh,
    surfaceContainerLow = DaySidebar,
    surfaceContainerLowest = Color(0xFFFBF9F2),
)

/** JetBrains Mono for the whole UI: the terminal is the brand. */
private val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
)

private fun mono(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Float = 0f,
): TextStyle = TextStyle(
    fontFamily = JetBrainsMono,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

private val DshTypography = Typography(
    displayLarge = mono(34, 42, FontWeight.SemiBold),
    displayMedium = mono(30, 38, FontWeight.SemiBold),
    displaySmall = mono(27, 35, FontWeight.SemiBold),
    headlineLarge = mono(28, 36, FontWeight.SemiBold),
    headlineMedium = mono(25, 32, FontWeight.SemiBold),
    headlineSmall = mono(22, 29, FontWeight.SemiBold),
    titleLarge = mono(20, 27, FontWeight.Medium),
    titleMedium = mono(17, 24, FontWeight.Medium),
    titleSmall = mono(15, 21, FontWeight.Medium),
    bodyLarge = mono(16, 24),
    bodyMedium = mono(14, 22),
    bodySmall = mono(13, 20),
    labelLarge = mono(13, 18, FontWeight.Medium),
    labelMedium = mono(12, 16, FontWeight.Medium),
    labelSmall = mono(11, 16, FontWeight.Medium),
)

/** Terminal-tight radii, slightly softer: 4 / 8 / 12 / 16. */
private val DshShapes = Shapes(
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun DshTheme(
    darkTheme: Boolean,
    amoled: Boolean = false,
    accent: ThemeAccent = ThemeAccent.Default,
    content: @Composable () -> Unit,
) {
    val base = when {
        !darkTheme -> LightColors
        amoled -> AmoledColors
        else -> DarkColors
    }
    // Accent packs override only the listed roles; surfaces, error, typography and shapes stay as
    // they are (AMOLED remains pure black). Hadal returns null = exactly today's scheme.
    val palette = accentPalette(accent, dark = darkTheme)
    val scheme = if (palette == null) {
        base
    } else {
        base.copy(
            primary = Color(palette.primary),
            onPrimary = Color(palette.onPrimary),
            primaryContainer = Color(palette.primaryContainer),
            onPrimaryContainer = Color(palette.onPrimaryContainer),
            secondary = Color(palette.secondary),
            onSecondary = Color(palette.onSecondary),
            secondaryContainer = Color(palette.secondaryContainer),
            onSecondaryContainer = Color(palette.onSecondaryContainer),
            inversePrimary = Color(palette.inversePrimary),
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = DshTypography,
        shapes = DshShapes,
        content = content,
    )
}
