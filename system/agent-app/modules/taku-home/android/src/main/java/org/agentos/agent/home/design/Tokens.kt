package org.agentos.agent.home.design

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Figma "Taku Mobile OS" variables, by their Figma names. Only [TakuColors] may read these;
 * screens use the semantic roles so a theme change never touches a screen.
 */
internal object TakuPalette {
  fun content(alpha: Float) = Color(0f, 0f, 0f, alpha)
  fun surface(alpha: Float) = Color(1f, 1f, 1f, alpha)
  val content900 = content(0.92f); val content800 = content(0.80f); val content700 = content(0.64f)
  val content600 = content(0.48f); val content400 = content(0.24f); val content300 = content(0.16f)
  val content200 = content(0.08f); val content100 = content(0.06f); val content50 = content(0.04f)
  val surface900 = surface(0.92f); val surface800 = surface(0.80f); val surface700 = surface(0.64f)
  val surface600 = surface(0.48f); val surface500 = surface(0.36f); val surface400 = surface(0.24f)
  val surface200 = surface(0.08f)
  val foreground = Color(0xFF1B1B1B)       // Main/Foreground
  val surfaceDefault = Color(0xFFFFFFFF)   // Main/Surface/Default
  val surfaceSecondary = Color(0xFFF1F1F3) // Main/Surface/Secondary
  val canvas = Color(0xFFE3E3E3)           // Main/Canvas
  val destructive = Color(0xFFCF343E)
  val destructiveDark = Color(0xFFFF7A82)
  val green300 = Color(0xFF64CE74)
  val brand200 = Color(0xFFFF713E)
  val brand = Color(0xFFFF5A1F)            // Main/Brand
  val black = Color(0xFF000000)
  // 1-1 voice aurora blobs (Vector 4/2/5/3) and the 1-4 "working gradient" inset glows.
  val auroraYellow = Color(0xFFFBFF00); val auroraBlue = Color(0xFF002FFF)
  val auroraRed = Color(0xFFFF0000); val auroraGreen = Color(0xFF5ED100)
  val glowGreen = Color(0xFF3CFF00); val glowSky = Color(0xFF82D5FF)
  val glowOrange = Color(0xFFFF663B); val glowIndigo = Color(0xFF4766FF)
  // Dark mode is not designed yet; these are the dark counterparts of the same roles.
  val darkCanvas = Color(0xFF1A1A1C)
  val darkForeground = Color(0xFFF2F2F2)
  val darkSurfaceSecondary = Color(0xFF2A2A2D)
}

/** Colors by purpose. Light values are the Figma frames; [dark] is a first, untuned pass. */
@Immutable
data class TakuColors(
  val isDark: Boolean,
  // Text and icons
  val textPrimary: Color,
  val textSecondary: Color,
  val textTertiary: Color,
  val textPlaceholder: Color,
  val textOnInverse: Color,
  val icon: Color,
  val iconOnInverse: Color,
  val iconDisabled: Color,
  val iconFaint: Color,
  // Surfaces
  val canvas: Color,
  /** Cards, widgets, pills, glass buttons (Surface/900). */
  val surfaceCard: Color,
  /** Badges and raised sub-surfaces (Surface/800). */
  val surfaceRaised: Color,
  /** App and folder plates, control tracks (Surface/600). */
  val surfaceGlass: Color,
  /** Quiet destructive actions such as "Remove" (Surface/500). */
  val surfaceFrosted: Color,
  /** Primary buttons, switch on, play (Main/Foreground). */
  val surfaceInverse: Color,
  /** Media placeholders, the vinyl label (Main/Surface/Secondary). */
  val surfaceMuted: Color,
  /** Backplate of folder miniatures (rounded, like the icons). */
  val surfaceIcon: Color,
  /** Subtle circular controls (Content/50). */
  val fillSubtle: Color,
  /** Image placeholders (Content/100). */
  val fillPlaceholder: Color,
  /** Secondary buttons, switch off, text field track (Content/200). */
  val fillControl: Color,
  // Lines
  val strokeHairline: Color,
  val strokeFrosted: Color,
  val strokeDropTarget: Color,
  val strokeDropTargetActive: Color,
  // Effects
  val scrim: Color,
  val scrimLight: Color,
  val shadowMenu: Color,
  val shadowBadge: Color,
  val textGlow: Color,
  /** Colour the bottom edge fades into. */
  val edgeFade: Color,
  val glassRim: Brush,
  /** Ink for dot charts; dots apply their own alpha ramp. */
  val dataInk: Color,
  // Accents
  val accentPositive: Color,
  val accentBrand: Color,
  /** Send button in the AI text field (Main/Brand). */
  val accentSend: Color,
  // AI entry and Dynamic Island (always dark, in both themes)
  /**
   * Expanded island panel: Content/900 as it reads over Main/Canvas, but opaque, so feed text
   * behind the island never shows through the reply (issue #25 F4).
   */
  val islandSurface: Color,
  /** Collapsed island pills around the camera: solid, so they merge with the cutout. */
  val islandSolid: Color,
  val islandText: Color,
  val islandTextMuted: Color,
  /** Eyes, working dots and icons on the island; dots apply their own alpha. */
  val islandInk: Color,
  val islandChip: Color,
  val islandChipText: Color,
  /** Voice aurora under the 1-1 glass: yellow, blue, red, green. */
  val voiceAurora: List<Color>,
  /** Screen-edge "Taku is working" glows: bottom, left, right, top. */
  val workingGlow: List<Color>,
) {
  val accentDestructive: Color get() = if (isDark) TakuPalette.destructiveDark else TakuPalette.destructive

  companion object {
    val light = with(TakuPalette) {
      TakuColors(
        isDark = false,
        textPrimary = content900, textSecondary = content800, textTertiary = content700, textPlaceholder = content600,
        textOnInverse = surfaceDefault, icon = foreground, iconOnInverse = surfaceDefault, iconDisabled = content300, iconFaint = content300,
        canvas = canvas, surfaceCard = surface900, surfaceRaised = surface800, surfaceGlass = surface600, surfaceFrosted = surface500,
        surfaceInverse = foreground, surfaceMuted = surfaceSecondary, surfaceIcon = surfaceDefault,
        fillSubtle = content50, fillPlaceholder = content100, fillControl = content200,
        strokeHairline = content200, strokeFrosted = surface400, strokeDropTarget = content300, strokeDropTargetActive = content600,
        scrim = content400, scrimLight = Color(0f, 0f, 0f, 0.12f), shadowMenu = content300, shadowBadge = content200, textGlow = surface400,
        edgeFade = canvas,
        glassRim = Brush.verticalGradient(0f to surface(0.95f), 0.35f to surface(0.25f), 1f to surface(0.55f)),
        dataInk = Color.Black, accentPositive = green300, accentBrand = brand200, accentSend = brand,
        islandSurface = content900, islandSolid = black, islandText = surface800, islandTextMuted = surface700,
        islandInk = surfaceDefault, islandChip = surface200, islandChipText = surface900,
        voiceAurora = listOf(auroraYellow, auroraBlue, auroraRed, auroraGreen),
        workingGlow = listOf(glowGreen, glowSky, glowOrange, glowIndigo),
      )
    }

    val dark = with(TakuPalette) {
      TakuColors(
        isDark = true,
        textPrimary = surface900, textSecondary = surface800, textTertiary = surface700, textPlaceholder = surface600,
        textOnInverse = darkCanvas, icon = darkForeground, iconOnInverse = darkCanvas, iconDisabled = surface(0.16f), iconFaint = surface(0.16f),
        canvas = darkCanvas, surfaceCard = content(0.72f), surfaceRaised = content(0.6f), surfaceGlass = content(0.4f), surfaceFrosted = content(0.3f),
        surfaceInverse = darkForeground, surfaceMuted = darkSurfaceSecondary, surfaceIcon = surfaceDefault,
        fillSubtle = surface(0.06f), fillPlaceholder = surface(0.08f), fillControl = surface(0.12f),
        strokeHairline = surface(0.12f), strokeFrosted = surface(0.12f), strokeDropTarget = surface(0.24f), strokeDropTargetActive = surface(0.6f),
        scrim = content(0.48f), scrimLight = content(0.24f), shadowMenu = content(0.4f), shadowBadge = content(0.3f), textGlow = Color.Transparent,
        edgeFade = darkCanvas,
        glassRim = Brush.verticalGradient(0f to surface(0.35f), 0.35f to surface(0.08f), 1f to surface(0.16f)),
        dataInk = Color.White, accentPositive = green300, accentBrand = brand200, accentSend = brand,
        islandSurface = content900, islandSolid = black, islandText = surface800, islandTextMuted = surface700,
        islandInk = surfaceDefault, islandChip = surface200, islandChipText = surface900,
        voiceAurora = listOf(auroraYellow, auroraBlue, auroraRed, auroraGreen),
        workingGlow = listOf(glowGreen, glowSky, glowOrange, glowIndigo),
      )
    }
  }
}

/** Spacing scale (Figma spacing variables). */
object TakuSpacing {
  val xxs: Dp = 2.dp
  val xs: Dp = 4.dp
  val sm: Dp = 8.dp
  val md: Dp = 12.dp
  val lg: Dp = 16.dp
  val xl: Dp = 24.dp
  /** Screen side margin and feed/grid gap. */
  val gutter: Dp = lg
}

/** Corner radii (Figma rounded variables). Fully rounded shapes use CircleShape. */
object TakuRadius {
  val sm: Dp = 4.dp
  val md: Dp = 8.dp
  val lg: Dp = 12.dp
  val card: Dp = 24.dp
  val dialog: Dp = 32.dp
}

/** Recurring component sizes from the frames. */
object TakuSize {
  val control: Dp = 40.dp
  val controlCompact: Dp = 32.dp
  val badge: Dp = 24.dp
  val switchWidth: Dp = 40.dp
  val switchHeight: Dp = 24.dp
  val switchKnob: Dp = 16.dp
  val pill: Dp = 64.dp
  val iconSm: Dp = 16.dp
  val iconMd: Dp = 24.dp
}

/** Backdrop blur σ (Figma Backdrop Blur R = 2σ: lg R32, md R24, sm R8). */
object TakuBlur {
  val lg: Dp = 16.dp
  val md: Dp = 12.dp
  val sm: Dp = 4.dp
}

/** Shadow elevations used by glass controls (colours come from [TakuColors]). */
object TakuElevation {
  val menu: Dp = 12.dp
  val badge: Dp = 8.dp
  val lifted: Dp = 16.dp
}

/** Motion: the frames carry no motion data, so these are the product's chosen curves. */
object TakuMotion {
  fun <T> appear(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
  fun <T> settle(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.82f, stiffness = 380f)
  fun <T> scrim(): FiniteAnimationSpec<T> = tween(220)
  const val STAGGER = 0.12f
  /** Island and input morphs. */
  fun <T> morph(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.78f, stiffness = 300f)
}
