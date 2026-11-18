package org.agentos.agent.home.design

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Figma Heading and Body (Regular/Medium) text styles; colour defaults to [TakuColors.textPrimary]. */
@Immutable
data class TakuTypography(
  /** Heading/4xl — clock digits. */
  val heading4xl: TextStyle,
  val heading2xl: TextStyle,
  val headingXl: TextStyle,
  val headingLg: TextStyle,
  val bodyXl: TextStyle,
  val bodyXlMedium: TextStyle,
  val bodyLg: TextStyle,
  val bodyLgMedium: TextStyle,
  val bodyMd: TextStyle,
  val bodyMdMedium: TextStyle,
  val bodySm: TextStyle,
  val bodySmMedium: TextStyle,
)

private val LocalTakuColors = staticCompositionLocalOf { TakuColors.light }
private val LocalTakuTypography = staticCompositionLocalOf<TakuTypography> { error("Wrap content in TakuTheme") }

/** Entry point: `TakuTheme.colors.textPrimary`, `TakuTheme.type.bodyMd`. */
object TakuTheme {
  val colors: TakuColors
    @Composable @ReadOnlyComposable get() = LocalTakuColors.current
  val type: TakuTypography
    @Composable @ReadOnlyComposable get() = LocalTakuTypography.current
}

/** The voice transcript uses the bundled design-system Chinese face, never system fallback. */
@Composable
fun voiceTranscriptStyle(text: String): TextStyle {
  val base = TakuTheme.type.bodyXl
  val chinese = text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
  val context = LocalContext.current
  val family = remember(context) { requireNotNull(font(context, "noto_sans_sc_regular", FontWeight.Normal)) }
  return if (chinese) base.copy(fontFamily = family, letterSpacing = 0.em) else base
}

/**
 * Provides colours and type. Home is light-only in the design; pass `dark = true` (e.g.
 * `isSystemInDarkTheme()`) once the dark palette in [TakuColors.dark] is designed.
 */
@Composable
fun TakuTheme(dark: Boolean = false, content: @Composable () -> Unit) {
  val colors = if (dark) TakuColors.dark else TakuColors.light
  val type = rememberTypography(colors.textPrimary)
  CompositionLocalProvider(LocalTakuColors provides colors, LocalTakuTypography provides type, content = content)
}

/** Fonts the app already registers via expo-font (res/font), looked up by name to avoid duplicating binaries. */
@SuppressLint("DiscouragedApi")
private fun font(context: Context, name: String, weight: FontWeight): FontFamily? {
  val id = context.resources.getIdentifier(name, "font", context.packageName)
  return if (id == 0) null else FontFamily(Font(id, weight))
}

private val noTrim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

@Composable
private fun rememberTypography(color: Color): TakuTypography {
  val context = LocalContext.current
  return remember(context, color) {
    val display = font(context, "space_grotesk_regular", FontWeight.Normal)
    val body = font(context, "hanken_grotesk_regular", FontWeight.Normal)
    val medium = font(context, "hanken_grotesk_medium", FontWeight.Medium)
    fun style(family: FontFamily?, weight: FontWeight, size: Float, lineHeight: Float, tracking: Float = -0.01f) = TextStyle(
      fontFamily = family ?: FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp, lineHeight = (size * lineHeight).sp,
      letterSpacing = tracking.em, lineHeightStyle = noTrim, color = color,
    )
    TakuTypography(
      heading4xl = style(display, FontWeight.Normal, 48f, 1.05f, -0.03f),
      heading2xl = style(display, FontWeight.Normal, 32f, 1.05f, -0.02f),
      headingXl = style(display, FontWeight.Normal, 24f, 1.05f),
      headingLg = style(display, FontWeight.Normal, 20f, 1.05f),
      bodyXl = style(body, FontWeight.Normal, 18f, 1.55f),
      bodyXlMedium = style(medium, FontWeight.Medium, 18f, 1.55f),
      bodyLg = style(body, FontWeight.Normal, 16f, 1.5f),
      bodyLgMedium = style(medium, FontWeight.Medium, 16f, 1.5f),
      bodyMd = style(body, FontWeight.Normal, 14f, 1.42f),
      bodyMdMedium = style(medium, FontWeight.Medium, 14f, 1.42f),
      bodySm = style(body, FontWeight.Normal, 12f, 1.3f),
      bodySmMedium = style(medium, FontWeight.Medium, 12f, 1.3f),
    )
  }
}
