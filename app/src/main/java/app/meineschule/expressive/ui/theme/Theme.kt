package app.meineschule.expressive.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.meineschule.expressive.Prefs

// ---- Fallback-Palette (Teal). Monet/Material You (Android 12+) ist Standard; diese Werte greifen nur ohne Dynamic Color. ----
private val TealLight = lightColorScheme(
    primary = Color(0xFF006A60), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFF9DF2E4), onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A6360), onSecondary = Color(0xFFFFFFFF), secondaryContainer = Color(0xFFCCE8E3), onSecondaryContainer = Color(0xFF05201D),
    tertiary = Color(0xFF456179), onTertiary = Color(0xFFFFFFFF), tertiaryContainer = Color(0xFFCCE5FF), onTertiaryContainer = Color(0xFF001E31),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF4FBF9), onBackground = Color(0xFF161D1C), surface = Color(0xFFF4FBF9), onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFDAE5E2), onSurfaceVariant = Color(0xFF3F4947), outline = Color(0xFF6F7977), outlineVariant = Color(0xFFBEC9C6),
    inverseSurface = Color(0xFF2B3231), inverseOnSurface = Color(0xFFECF2F0), inversePrimary = Color(0xFF7FD5C8), scrim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFE9F1EF), surfaceContainer = Color(0xFFDFE9E6), surfaceContainerHigh = Color(0xFFD5E0DD), surfaceContainerHighest = Color(0xFFCBD7D4),
    surfaceDim = Color(0xFFD5DBDA), surfaceBright = Color(0xFFF4FBF9),
)
private val TealDark = darkColorScheme(
    primary = Color(0xFF7FD5C8), onPrimary = Color(0xFF003732), primaryContainer = Color(0xFF005048), onPrimaryContainer = Color(0xFF9DF2E4),
    secondary = Color(0xFFB0CCC7), onSecondary = Color(0xFF1B3531), secondaryContainer = Color(0xFF324B47), onSecondaryContainer = Color(0xFFCCE8E3),
    tertiary = Color(0xFFADCAE6), onTertiary = Color(0xFF14324A), tertiaryContainer = Color(0xFF2C4961), onTertiaryContainer = Color(0xFFCCE5FF),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1514), onBackground = Color(0xFFDDE4E2), surface = Color(0xFF0E1514), onSurface = Color(0xFFDDE4E2),
    surfaceVariant = Color(0xFF3F4947), onSurfaceVariant = Color(0xFFBEC9C6), outline = Color(0xFF899390), outlineVariant = Color(0xFF3F4947),
    inverseSurface = Color(0xFFDDE4E2), inverseOnSurface = Color(0xFF2B3231), inversePrimary = Color(0xFF006A60), scrim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF090F0F), surfaceContainerLow = Color(0xFF1A2221), surfaceContainer = Color(0xFF232C2B), surfaceContainerHigh = Color(0xFF2D3636), surfaceContainerHighest = Color(0xFF384140),
    surfaceDim = Color(0xFF0E1514), surfaceBright = Color(0xFF343B3A),
)

/** Hue-Rotation (HSL) erhält Sättigung/Helligkeit und damit die Kontraste der Basispalette. */
private fun Color.rotate(deg: Float): Color {
    if (deg == 0f) return this
    val hsv = FloatArray(3); android.graphics.Color.colorToHSV(toArgb(), hsv); hsv[0] = (hsv[0] + deg + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}
private fun ColorScheme.rotated(d: Float): ColorScheme = if (d == 0f) this else copy(
    primary = primary.rotate(d), onPrimary = onPrimary.rotate(d), primaryContainer = primaryContainer.rotate(d), onPrimaryContainer = onPrimaryContainer.rotate(d),
    secondary = secondary.rotate(d), onSecondary = onSecondary.rotate(d), secondaryContainer = secondaryContainer.rotate(d), onSecondaryContainer = onSecondaryContainer.rotate(d),
    tertiary = tertiary.rotate(d), onTertiary = onTertiary.rotate(d), tertiaryContainer = tertiaryContainer.rotate(d), onTertiaryContainer = onTertiaryContainer.rotate(d),
    background = background.rotate(d), surface = surface.rotate(d), onSurface = onSurface.rotate(d), onBackground = onBackground.rotate(d), surfaceVariant = surfaceVariant.rotate(d), onSurfaceVariant = onSurfaceVariant.rotate(d),
    outline = outline.rotate(d), outlineVariant = outlineVariant.rotate(d), inverseSurface = inverseSurface.rotate(d), inverseOnSurface = inverseOnSurface.rotate(d), inversePrimary = inversePrimary.rotate(d),
    surfaceContainerLowest = surfaceContainerLowest.rotate(d), surfaceContainerLow = surfaceContainerLow.rotate(d), surfaceContainer = surfaceContainer.rotate(d),
    surfaceContainerHigh = surfaceContainerHigh.rotate(d), surfaceContainerHighest = surfaceContainerHighest.rotate(d), surfaceDim = surfaceDim.rotate(d), surfaceBright = surfaceBright.rotate(d),
)

object MsdShapes {
    /** Hero-Karte: eine „angeschnittene“ Ecke gibt der wichtigsten Karte Charakter. */
    val Hero = RoundedCornerShape(topStart = 44.dp, topEnd = 44.dp, bottomEnd = 44.dp, bottomStart = 14.dp)
    val BubbleIn = RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)
    val BubbleOut = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
    val Fab = RoundedCornerShape(24.dp)
    val Tile = RoundedCornerShape(16.dp)
    val Cookie = RoundedCornerShape(topStartPercent = 50, topEndPercent = 50, bottomEndPercent = 14, bottomStartPercent = 50)
    val Squircle = RoundedCornerShape(30)
    val ListTop = RoundedCornerShape(28.dp, 28.dp, 6.dp, 6.dp)
    val ListMid = RoundedCornerShape(6.dp)
    val ListBottom = RoundedCornerShape(6.dp, 6.dp, 28.dp, 28.dp)
    val ListSingle = RoundedCornerShape(28.dp)
    fun listShape(i: Int, n: Int) = when { n == 1 -> ListSingle; i == 0 -> ListTop; i == n - 1 -> ListBottom; else -> ListMid }
}

private val Base = FontFamily.Default // Roboto/Google Sans; Roboto Flex: .ttf in res/font ablegen und hier setzen (siehe README)
private fun t(size: Int, line: Int, w: FontWeight, ls: Double = 0.0) = TextStyle(fontFamily = Base, fontSize = size.sp, lineHeight = line.sp, fontWeight = w, letterSpacing = ls.sp)
private val MsdTypography = Typography(
    displayLarge = t(57, 64, FontWeight.Bold, -0.25), displayMedium = t(45, 52, FontWeight.Bold), displaySmall = t(36, 44, FontWeight.Bold),
    headlineLarge = t(32, 40, FontWeight.Bold), headlineMedium = t(28, 36, FontWeight.SemiBold), headlineSmall = t(24, 32, FontWeight.SemiBold),
    titleLarge = t(22, 28, FontWeight.SemiBold), titleMedium = t(16, 24, FontWeight.SemiBold, 0.15), titleSmall = t(14, 20, FontWeight.SemiBold, 0.1),
    bodyLarge = t(16, 24, FontWeight.Normal, 0.5), bodyMedium = t(14, 20, FontWeight.Normal, 0.25), bodySmall = t(12, 16, FontWeight.Normal, 0.4),
    labelLarge = t(14, 20, FontWeight.SemiBold, 0.1), labelMedium = t(12, 16, FontWeight.Medium, 0.5), labelSmall = t(11, 16, FontWeight.Medium, 0.5),
)

@Composable
fun MsdTheme(mode: Prefs.Mode, dynamic: Boolean, seed: Prefs.Seed, content: @Composable () -> Unit) {
    val dark = when (mode) { Prefs.Mode.System -> isSystemInDarkTheme(); Prefs.Mode.Light -> false; Prefs.Mode.Dark -> true }
    val ctx = LocalContext.current
    val scheme = remember(dark, dynamic, seed) {
        if (dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx) } // echtes Monet
        else (if (dark) TealDark else TealLight).rotated(seed.hueShift)
    }
    // Statusleisten-/Navigationsleisten-Symbole folgen dem App-Design (nicht dem Systemmodus): sonst z. B. weiße Uhrzeit auf hellem Hintergrund.
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) androidx.compose.runtime.SideEffect {
        (view.context as? android.app.Activity)?.window?.let { w -> androidx.core.view.WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } }
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(), // Spring-basierte, ausdrucksstarke Bewegung für alle M3-Komponenten
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp)),
        typography = MsdTypography, content = content,
    )
}
