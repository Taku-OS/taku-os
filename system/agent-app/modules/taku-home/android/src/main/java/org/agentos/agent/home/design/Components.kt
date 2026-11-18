package org.agentos.agent.home.design

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// ---- Surfaces -----------------------------------------------------------------------

/**
 * Glass: the wallpaper blurred σ16 at this element's screen position under [fill], clipped to
 * [shape]. Cards, widgets, pills and app plates all use it; only [fill] differs by role.
 */
@Composable
fun GlassSurface(
  backdrop: Backdrop?,
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(TakuRadius.card),
  fill: Color = TakuTheme.colors.surfaceCard,
  content: @Composable BoxScope.() -> Unit,
) {
  var origin by remember { mutableStateOf(Offset.Zero) }
  Box(
    modifier
      .onGloballyPositioned { origin = it.positionInRoot() }
      .clip(shape)
      .drawBehind {
        if (backdrop != null) with(backdrop) { drawBlurredAt(origin) }
        drawRect(fill)
      },
    content = content,
  )
}

/** Full-screen dimming layer that dismisses on tap; [strength] 0..1 animates it in. */
@Composable
fun Scrim(strength: Float, onDismiss: () -> Unit, modifier: Modifier = Modifier, color: Color = TakuTheme.colors.scrim, content: @Composable BoxScope.() -> Unit = {}) {
  Box(
    modifier
      .fillMaxSize()
      .drawBehind { drawRect(color, alpha = strength.coerceIn(0f, 1f)) }
      .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
      .semantics { contentDescription = "Close" },
    content = content,
  )
}

/** Scale/fade entrance for item [index] of a group appearing with [progress] 0..1. */
fun Modifier.staggeredAppear(progress: Float, index: Int, from: Float = 0.9f): Modifier = graphicsLayer {
  val start = index * TakuMotion.STAGGER
  val t = ((progress - start) / (1f - start)).coerceIn(0f, 1f)
  alpha = t
  scaleX = from + (1f - from) * t
  scaleY = scaleX
}

/** Animatable that runs 0 → 1 with [TakuMotion.appear] whenever [key] changes. */
@Composable
fun rememberAppear(key: Any?): Float {
  val anim = remember(key) { Animatable(0f) }
  LaunchedEffect(key) { anim.animateTo(1f, TakuMotion.appear()) }
  return anim.value
}

// ---- Buttons ------------------------------------------------------------------------

enum class ButtonStyle {
  /** Agent action: Main/Foreground fill, white label (and sparkle). */
  Primary,
  /** User action: Content/200 fill. */
  Secondary,
  /** Floating menu action: Surface/900 + glass rim + shadow (0-3 "Pin to top"). */
  Glass,
  /** Quiet destructive action: Surface/500 + Surface/400 stroke (0-3 "Remove"). */
  Frosted,
}

enum class ButtonSize(val height: Dp) { Large(40.dp), Medium(32.dp) }

/** Pill button for native chrome. Card bodies use the React Native equivalent. */
@Composable
fun TakuButton(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  style: ButtonStyle = ButtonStyle.Secondary,
  size: ButtonSize = ButtonSize.Large,
  icon: TakuIcons? = null,
  enabled: Boolean = true,
  leading: (@Composable () -> Unit)? = null,
) {
  val c = TakuTheme.colors
  val (fill, ink) = when (style) {
    ButtonStyle.Primary -> c.surfaceInverse to c.textOnInverse
    ButtonStyle.Secondary -> c.fillControl to c.icon
    ButtonStyle.Glass -> c.surfaceCard to c.icon
    ButtonStyle.Frosted -> c.surfaceFrosted to c.textTertiary
  }
  val text = if (size == ButtonSize.Large) TakuTheme.type.bodyLg else TakuTheme.type.bodyMd
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(TakuSpacing.xs, Alignment.CenterHorizontally),
    modifier = modifier
      .height(size.height)
      .then(if (style == ButtonStyle.Glass) Modifier.shadow(TakuElevation.menu, CircleShape, ambientColor = c.shadowMenu, spotColor = c.shadowMenu) else Modifier)
      .clip(CircleShape)
      .background(fill)
      .then(when (style) {
        ButtonStyle.Glass -> Modifier.border(1.dp, c.glassRim, CircleShape)
        ButtonStyle.Frosted -> Modifier.border(1.dp, c.strokeFrosted, CircleShape)
        else -> Modifier
      })
      .clickable(enabled = enabled, onClick = onClick)
      .semantics { role = Role.Button }
      .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
      .padding(horizontal = TakuSpacing.lg),
  ) {
    if (leading != null) leading() else if (icon != null) TakuIcon(icon, TakuSize.iconSm, tint = ink)
    BasicText(label, style = text.copy(color = ink), maxLines = 1)
  }
}

enum class IconButtonStyle {
  /** Main/Foreground circle, white glyph (play). */
  Inverse,
  /** Content/50 circle, dark glyph (previous / next). */
  Subtle,
  /** 24 dp Surface/800 glass badge with halo (edit "−"). */
  Badge,
}

/** Circular icon button; 40 dp unless [IconButtonStyle.Badge]. */
@Composable
fun TakuIconButton(
  icon: TakuIcons,
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  style: IconButtonStyle = IconButtonStyle.Subtle,
  iconSize: Dp = if (style == IconButtonStyle.Inverse) 20.dp else TakuSize.iconMd,
  enabled: Boolean = true,
) {
  val c = TakuTheme.colors
  val size = if (style == IconButtonStyle.Badge) TakuSize.badge else TakuSize.control
  val fill = when (style) { IconButtonStyle.Inverse -> c.surfaceInverse; IconButtonStyle.Subtle -> c.fillSubtle; IconButtonStyle.Badge -> c.surfaceRaised }
  val tint = when {
    style == IconButtonStyle.Inverse -> c.iconOnInverse
    !enabled -> c.iconDisabled
    else -> c.icon
  }
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .size(size)
      .then(if (style == IconButtonStyle.Badge) Modifier.shadow(TakuElevation.badge, CircleShape, ambientColor = c.shadowBadge, spotColor = c.shadowBadge) else Modifier)
      .clip(CircleShape)
      .background(fill)
      .then(if (style == IconButtonStyle.Badge) Modifier.border(0.75.dp, c.glassRim, CircleShape) else Modifier)
      .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)
      .semantics { this.contentDescription = contentDescription; role = Role.Button },
  ) { TakuIcon(icon, if (style == IconButtonStyle.Badge) 13.5.dp else iconSize, tint = tint) }
}

/** 40×24 switch with a 16 dp knob inset 4 (4-1 alarm). [onCheckedChange] null = display only. */
@Composable
fun TakuSwitch(checked: Boolean, modifier: Modifier = Modifier, onCheckedChange: ((Boolean) -> Unit)? = null) {
  val c = TakuTheme.colors
  val position by animateFloatAsState(if (checked) 1f else 0f, TakuMotion.settle(), label = "switch")
  val travel = TakuSize.switchWidth - TakuSize.switchKnob - TakuSpacing.xs * 2
  Box(
    modifier
      .width(TakuSize.switchWidth)
      .height(TakuSize.switchHeight)
      .clip(CircleShape)
      .background(if (checked) c.surfaceInverse else c.fillControl)
      .then(if (onCheckedChange != null) Modifier.toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange) else Modifier)
      .padding(TakuSpacing.xs),
    contentAlignment = Alignment.CenterStart,
  ) {
    Box(Modifier.graphicsLayer { translationX = travel.toPx() * position }.size(TakuSize.switchKnob).clip(CircleShape).background(c.surfaceIcon))
  }
}

// ---- Text field -----------------------------------------------------------------------

/** Single-line pill text field on the control fill (folder names and other short input). */
@Composable
fun TakuTextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = "",
  maxLength: Int = 64,
  onDone: () -> Unit = {},
) {
  val c = TakuTheme.colors
  val style = TakuTheme.type.bodyLg
  BasicTextField(
    value = value,
    onValueChange = { onValueChange(it.take(maxLength)) },
    singleLine = true,
    textStyle = style.copy(color = c.textPrimary),
    cursorBrush = SolidColor(c.icon),
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(onDone = { onDone() }),
    modifier = modifier,
    decorationBox = { inner ->
      Box(
        Modifier.height(TakuSize.control).clip(CircleShape).background(c.fillControl).padding(horizontal = TakuSpacing.lg),
        contentAlignment = Alignment.CenterStart,
      ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) BasicText(placeholder, style = style.copy(color = c.textPlaceholder))
        inner()
      }
    },
  )
}

// ---- Menu -----------------------------------------------------------------------------

data class MenuAction(val label: String, val onClick: () -> Unit, val style: ButtonStyle = ButtonStyle.Glass, val icon: TakuIcons? = null)

/**
 * Row of pill actions anchored to [anchor] (root coordinates): below it, or above when
 * there is no room. [leading] actions sit at the anchor's left edge and [actions] at its right
 * (the 0-3 layout); with no leading actions the row is centred on the anchor.
 */
@Composable
fun AnchoredMenu(
  anchor: Rect,
  actions: List<MenuAction>,
  appear: Float,
  modifier: Modifier = Modifier,
  leading: List<MenuAction> = emptyList(),
  spanAnchor: Boolean = leading.isNotEmpty(),
  bottomReserve: Dp = 140.dp,
) {
  Layout(modifier = modifier.fillMaxSize(), content = {
    Row(horizontalArrangement = Arrangement.spacedBy(TakuSpacing.sm), verticalAlignment = Alignment.CenterVertically,
      modifier = if (spanAnchor) Modifier.width(with(androidx.compose.ui.platform.LocalDensity.current) { anchor.width.toDp() }) else Modifier) {
      leading.forEachIndexed { i, a -> TakuButton(a.label, a.onClick, Modifier.staggeredAppear(appear, i), a.style, icon = a.icon) }
      if (spanAnchor) Spacer(Modifier.weight(1f))
      actions.forEachIndexed { i, a -> TakuButton(a.label, a.onClick, Modifier.staggeredAppear(appear, leading.size + i), a.style, icon = a.icon) }
    }
  }) { measurables, constraints ->
    val row = measurables.first().measure(Constraints())
    val gap = TakuSpacing.sm.roundToPx()
    val margin = TakuSpacing.gutter.roundToPx()
    val below = anchor.bottom + gap + row.height < constraints.maxHeight - bottomReserve.roundToPx()
    val x = if (spanAnchor) anchor.left.roundToInt()
    else (anchor.center.x - row.width / 2f).roundToInt().coerceIn(margin, (constraints.maxWidth - margin - row.width).coerceAtLeast(margin))
    val y = if (below) anchor.bottom.roundToInt() + gap else anchor.top.roundToInt() - gap - row.height
    layout(constraints.maxWidth, constraints.maxHeight) { row.place(x, y) }
  }
}

// ---- Dialog ---------------------------------------------------------------------------

/** Centred frosted panel over a scrim; back and outside taps dismiss. */
@Composable
fun TakuDialog(onDismiss: () -> Unit, appear: Float, modifier: Modifier = Modifier, backdrop: Backdrop? = null, content: @Composable ColumnScope.() -> Unit) {
  BackHandler(onBack = onDismiss)
  var origin by remember { mutableStateOf(Offset.Zero) }
  Scrim(appear, onDismiss, modifier) {
    Column(
      verticalArrangement = Arrangement.spacedBy(TakuSpacing.lg),
      content = content,
      modifier = Modifier
        .align(Alignment.Center)
        .padding(horizontal = TakuSpacing.xl)
        .fillMaxWidth()
        .graphicsLayer { alpha = appear; scaleX = 0.9f + 0.1f * appear; scaleY = scaleX }
        .onGloballyPositioned { origin = it.positionInRoot() }
        .clip(RoundedCornerShape(TakuRadius.dialog))
        // With a backdrop the card is frosted wallpaper, so what lies under the scrim does not show through.
        .drawBehind { if (backdrop != null) with(backdrop) { drawBlurredAt(origin) } }
        .background(TakuTheme.colors.surfaceCard)
        .clickable(remember { MutableInteractionSource() }, indication = null) {}
        .padding(20.dp),
    )
  }
}

// ---- Media ----------------------------------------------------------------------------

/** Record peeking behind album art: Main/Foreground disc with a Surface/Secondary label (16/56 of its size). */
@Composable
fun Vinyl(modifier: Modifier = Modifier) {
  val c = TakuTheme.colors
  androidx.compose.foundation.Canvas(modifier) {
    drawCircle(c.surfaceInverse)
    drawCircle(c.surfaceMuted, radius = size.minDimension * 8f / 56f)
  }
}

// ---- Page dots --------------------------------------------------------------------------

/** Launcher page dots: 6 dp Content/200, active 12×6 Main/Foreground, gap 8. */
@Composable
fun PageDots(count: Int, active: Int, modifier: Modifier = Modifier) {
  val c = TakuTheme.colors
  Row(horizontalArrangement = Arrangement.spacedBy(TakuSpacing.sm), verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
    repeat(count) { i ->
      val selected = i == active
      val width by animateFloatAsState(if (selected) 12f else 6f, label = "dot")
      Box(Modifier.width(width.dp).height(6.dp).clip(CircleShape).background(if (selected) c.surfaceInverse else c.fillControl))
    }
  }
}
