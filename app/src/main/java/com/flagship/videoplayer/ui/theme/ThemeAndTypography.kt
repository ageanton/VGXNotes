package com.flagship.videoplayer.ui.theme

import android.app.Activity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.flagship.videoplayer.R
import com.flagship.videoplayer.data.AppThemeMode
import com.flagship.videoplayer.data.SubtitleFontChoice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_bold, FontWeight.Bold)
)

val SpaceGroteskFontFamily = FontFamily(
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold)
)

val SpaceMonoFontFamily = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold)
)

val PlusJakartaFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold)
)

val OpenSansFontFamily = FontFamily(
    Font(R.font.open_sans, FontWeight.Normal),
    Font(R.font.open_sans, FontWeight.Medium),
    Font(R.font.open_sans, FontWeight.Bold)
)

val DmSansFontFamily = FontFamily(
    Font(R.font.dm_sans, FontWeight.Normal),
    Font(R.font.dm_sans, FontWeight.Medium),
    Font(R.font.dm_sans, FontWeight.Bold)
)

fun resolveSubtitleFontFamily(choice: SubtitleFontChoice): FontFamily {
    return when (choice) {
        SubtitleFontChoice.INTER -> InterFontFamily
        SubtitleFontChoice.ROBOTO -> FontFamily.SansSerif
        SubtitleFontChoice.PLUS_JAKARTA -> PlusJakartaFontFamily
        SubtitleFontChoice.OPEN_SANS -> OpenSansFontFamily
        SubtitleFontChoice.DM_SANS -> DmSansFontFamily
    }
}

val TabularNumeralStyle = TextStyle(
    fontFamily = InterFontFamily,
    fontFeatureSettings = "tnum"
)

val TabularMonoStyle = TextStyle(
    fontFamily = SpaceMonoFontFamily,
    fontFeatureSettings = "tnum"
)

val FlagshipTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = SpaceMonoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = SpaceMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = "tnum"
    ),
    labelMedium = TextStyle(
        fontFamily = SpaceMonoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum"
    ),
    labelSmall = TextStyle(
        fontFamily = SpaceMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontFeatureSettings = "tnum"
    )
)

@Immutable
data class VdxThemeTokens(
    val mode: AppThemeMode,
    val paper: Color,
    val card: Color,
    val ink: Color,
    val muted: Color,
    val onColor: Color,
    val tickerBg: Color,
    val onTicker: Color,
    val tickerSlash: Color,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val quaternary: Color,
    val danger: Color = Color(0xFFFF5C5C),
    val scrim: Color = Color(0x80000000),
    val isDark: Boolean = false
)

fun resolveVdxTokens(mode: AppThemeMode): VdxThemeTokens {
    return when (mode) {
        AppThemeMode.OCEAN -> VdxThemeTokens(
            mode = AppThemeMode.OCEAN,
            paper = Color(0xFFE6F2FF),
            card = Color(0xFFFFFFFF),
            ink = Color(0xFF111111),
            muted = Color(0xFF55554F),
            onColor = Color(0xFF111111),
            tickerBg = Color(0xFF111111),
            onTicker = Color(0xFFE6F2FF),
            tickerSlash = Color(0xFF8A8A85),
            primary = Color(0xFF4D9CFF),
            secondary = Color(0xFF5CE1E6),
            tertiary = Color(0xFFFFE45C),
            quaternary = Color(0xFFFF8A3D),
            isDark = false
        )
        AppThemeMode.FOREST_EMERALD -> VdxThemeTokens(
            mode = AppThemeMode.FOREST_EMERALD,
            paper = Color(0xFFE6F4EA),
            card = Color(0xFFFFFFFF),
            ink = Color(0xFF0F1E16),
            muted = Color(0xFF476053),
            onColor = Color(0xFF0F1E16),
            tickerBg = Color(0xFF0F1E16),
            onTicker = Color(0xFFE6F4EA),
            tickerSlash = Color(0xFF759282),
            primary = Color(0xFF10B981),
            secondary = Color(0xFF2DD4BF),
            tertiary = Color(0xFFA3E635),
            quaternary = Color(0xFFFBBF24),
            isDark = false
        )
        AppThemeMode.ROYAL_VIOLET -> VdxThemeTokens(
            mode = AppThemeMode.ROYAL_VIOLET,
            paper = Color(0xFF171324),
            card = Color(0xFF221C35),
            ink = Color(0xFFF3EEFF),
            muted = Color(0xFFA69BC2),
            onColor = Color(0xFF141020),
            tickerBg = Color(0xFF0E0B18),
            onTicker = Color(0xFFF3EEFF),
            tickerSlash = Color(0xFF7A6F96),
            primary = Color(0xFFA78BFA),
            secondary = Color(0xFFF472B6),
            tertiary = Color(0xFF38BDF8),
            quaternary = Color(0xFF34D399),
            isDark = true
        )
        AppThemeMode.BANANA -> VdxThemeTokens(
            mode = AppThemeMode.BANANA,
            paper = Color(0xFFFFF4D6),
            card = Color(0xFFFFFFFF),
            ink = Color(0xFF111111),
            muted = Color(0xFF55554F),
            onColor = Color(0xFF111111),
            tickerBg = Color(0xFF111111),
            onTicker = Color(0xFFFFF4D6),
            tickerSlash = Color(0xFF8A8A85),
            primary = Color(0xFFFFD83D),
            secondary = Color(0xFF8EC5FF),
            tertiary = Color(0xFFFF9CCB),
            quaternary = Color(0xFF7CE0B5),
            isDark = false
        )
        AppThemeMode.TERRACOTTA -> VdxThemeTokens(
            mode = AppThemeMode.TERRACOTTA,
            paper = Color(0xFFFBE9E7),
            card = Color(0xFFFFFFFF),
            ink = Color(0xFF111111),
            muted = Color(0xFF5A4E4C),
            onColor = Color(0xFF111111),
            tickerBg = Color(0xFF111111),
            onTicker = Color(0xFFFBE9E7),
            tickerSlash = Color(0xFF8A8A85),
            primary = Color(0xFFFF8A65),
            secondary = Color(0xFFA5D6A7),
            tertiary = Color(0xFFFFF59D),
            quaternary = Color(0xFF90CAF9),
            isDark = false
        )
        AppThemeMode.SLATE_DUSK -> VdxThemeTokens(
            mode = AppThemeMode.SLATE_DUSK,
            paper = Color(0xFF212630),
            card = Color(0xFF2C3340),
            ink = Color(0xFFF0F4F8),
            muted = Color(0xFFA9B4C2),
            onColor = Color(0xFF111111),
            tickerBg = Color(0xFF15181E),
            onTicker = Color(0xFFF0F4F8),
            tickerSlash = Color(0xFF8A8A85),
            primary = Color(0xFF64B5F6),
            secondary = Color(0xFF81C784),
            tertiary = Color(0xFF4DD0E1),
            quaternary = Color(0xFFBA68C8),
            isDark = true
        )
        AppThemeMode.MIDNIGHT -> VdxThemeTokens(
            mode = AppThemeMode.MIDNIGHT,
            paper = Color(0xFF121216),
            card = Color(0xFF1C1D24),
            ink = Color(0xFFF2F3F7),
            muted = Color(0xFF9BA0AE),
            onColor = Color(0xFF111111),
            tickerBg = Color(0xFF070709),
            onTicker = Color(0xFFF2F3F7),
            tickerSlash = Color(0xFF707582),
            primary = Color(0xFF5CE1E6),
            secondary = Color(0xFF7C9EFF),
            tertiary = Color(0xFF69F0AE),
            quaternary = Color(0xFFB388FF),
            isDark = true
        )
    }
}

val LocalVdxTokens = staticCompositionLocalOf { resolveVdxTokens(AppThemeMode.OCEAN) }

@Composable
fun FlagshipVideoPlayerTheme(
    themeMode: AppThemeMode,
    content: @Composable () -> Unit
) {
    val tokens = remember(themeMode) { resolveVdxTokens(themeMode) }
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = tokens.paper.toArgb()
            window.navigationBarColor = tokens.paper.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !tokens.isDark
            controller.isAppearanceLightNavigationBars = !tokens.isDark
        }
    }

    val colorScheme = if (tokens.isDark) {
        darkColorScheme(
            primary = tokens.primary,
            onPrimary = tokens.onColor,
            primaryContainer = tokens.primary,
            onPrimaryContainer = tokens.onColor,
            secondary = tokens.secondary,
            onSecondary = tokens.onColor,
            secondaryContainer = tokens.secondary,
            onSecondaryContainer = tokens.onColor,
            tertiary = tokens.tertiary,
            onTertiary = tokens.onColor,
            background = tokens.paper,
            onBackground = tokens.ink,
            surface = tokens.card,
            onSurface = tokens.ink,
            surfaceVariant = tokens.card,
            onSurfaceVariant = tokens.muted,
            error = tokens.danger
        )
    } else {
        lightColorScheme(
            primary = tokens.primary,
            onPrimary = tokens.onColor,
            primaryContainer = tokens.primary,
            onPrimaryContainer = tokens.onColor,
            secondary = tokens.secondary,
            onSecondary = tokens.onColor,
            secondaryContainer = tokens.secondary,
            onSecondaryContainer = tokens.onColor,
            tertiary = tokens.tertiary,
            onTertiary = tokens.onColor,
            background = tokens.paper,
            onBackground = tokens.ink,
            surface = tokens.card,
            onSurface = tokens.ink,
            surfaceVariant = tokens.paper,
            onSurfaceVariant = tokens.muted,
            error = tokens.danger
        )
    }

    CompositionLocalProvider(LocalVdxTokens provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FlagshipTypography,
            content = content
        )
    }
}

data class VideoDynamicPalette(
    val primaryAccent: Color = Color(0xFF4D9CFF),
    val onPrimaryAccent: Color = Color(0xFF111111),
    val primaryGlow: Color = Color(0x664D9CFF),
    val tertiaryLoopAccent: Color = Color(0xFF5CE1E6),
    val osdSurface: Color = Color(0xFF0E0F12).copy(alpha = 0.84f),
    val osdElevatedSurface: Color = Color(0xFF1E1F24).copy(alpha = 0.92f),
    val specularBorder: Color = Color.White.copy(alpha = 0.15f)
)

object DynamicVideoAccentExtractor {
    fun buildFromThemeTokens(tokens: VdxThemeTokens): VideoDynamicPalette {
        return VideoDynamicPalette(
            primaryAccent = tokens.primary,
            onPrimaryAccent = tokens.onColor,
            primaryGlow = tokens.primary.copy(alpha = 0.42f),
            tertiaryLoopAccent = tokens.secondary
        )
    }
}

object FormatUtils {
    fun formatDurationMs(durationMs: Long): String {
        val safeMs = durationMs.coerceAtLeast(0L)
        val totalSec = safeMs / 1000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun formatPreciseCentiseconds(timeMs: Long): String {
        val safeMs = timeMs.coerceAtLeast(0L)
        val totalSec = safeMs / 1000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        val centis = (safeMs % 1000L) / 10L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d.%02d", hours, minutes, seconds, centis)
        } else {
            String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, centis)
        }
    }

    fun formatSignedSeconds(offsetMs: Long): String {
        val seconds = offsetMs / 1000.0
        val sign = if (offsetMs > 0) "+" else ""
        return String.format(Locale.US, "%s%.2fs", sign, seconds)
    }

    fun formatFileSize(sizeBytes: Long): String {
        if (sizeBytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            sizeBytes >= gb -> String.format(Locale.US, "%.2f GB", sizeBytes / gb)
            sizeBytes >= mb -> String.format(Locale.US, "%.1f MB", sizeBytes / mb)
            sizeBytes >= kb -> String.format(Locale.US, "%.0f KB", sizeBytes / kb)
            else -> "$sizeBytes B"
        }
    }

    fun formatDateModified(epochSec: Long): String {
        if (epochSec <= 0L) return "Unknown"
        return runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            sdf.format(Date(epochSec * 1000L))
        }.getOrDefault("Unknown")
    }

    fun computeResolutionBadge(width: Int, height: Int): String {
        val maxDim = maxOf(width, height)
        val minDim = minOf(width, height)
        return when {
            maxDim >= 7600 || minDim >= 4300 -> "8K"
            maxDim >= 3800 || minDim >= 2100 -> "4K"
            maxDim >= 2500 || minDim >= 1400 -> "1440p"
            maxDim >= 1900 || minDim >= 1000 -> "1080p"
            maxDim >= 1200 || minDim >= 700 -> "720p"
            maxDim >= 800 || minDim >= 480 -> "480p"
            maxDim > 0 -> "${minDim}p"
            else -> "Video"
        }
    }
}
