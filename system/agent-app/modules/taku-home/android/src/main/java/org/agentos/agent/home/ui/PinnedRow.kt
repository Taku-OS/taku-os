package org.agentos.agent.home.ui

import org.agentos.agent.home.design.*
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.agentos.agent.home.feed.FeedCard
import org.agentos.agent.home.feed.PinIcon
import org.agentos.agent.home.ui.effects.Shaders

/**
 * The pinned row (Figma pill row): snapping horizontal list of pills over the wallpaper,
 * bleeding to both screen edges where the edge-refraction lens and a progressive blur
 * act. Long press enters edit mode (0-5) with glass "−" badges.
 */
@Composable
fun PinnedRow(pins: List<FeedCard>, controller: HomeController, backdrop: Backdrop?, modifier: Modifier = Modifier) {
  val state = rememberLazyListState()
  val fling = rememberSnapFlingBehavior(state, SnapPosition.Start)
  val density = LocalDensity.current.density
  val haptics = LocalHapticFeedback.current
  if (pins.isEmpty()) return
  LazyRow(
    state = state,
    flingBehavior = fling,
    contentPadding = PaddingValues(horizontal = TakuSpacing.gutter, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(TakuSpacing.sm),
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .fillMaxWidth()
      .height(84.dp)
      .overflowVertical(10.dp)
      .graphicsLayer {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && size.width > 0f) {
          renderEffect = Shaders.edgeRefraction(size.width, size.height, density).asComposeRenderEffect()
        }
      }
      .semantics { contentDescription = "Pinned" },
  ) {
    items(pins, key = { it.id }) { card ->
      Pill(
        card = card,
        editing = controller.editingPins,
        backdrop = backdrop,
        onTap = { if (controller.editingPins) controller.editingPins = false else controller.openMenu(card.id, fromPin = true) },
        onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); controller.editingPins = true },
        onRemove = { controller.unpin(card.id) },
        onTrailing = { action -> controller.pinAction(card.id, action) },
        modifier = Modifier.animateItem(fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow), placementSpec = spring(stiffness = Spring.StiffnessMediumLow)),
      )
    }
  }
}

@Composable
fun Pill(
  card: FeedCard,
  editing: Boolean,
  backdrop: Backdrop?,
  onTap: () -> Unit,
  onLongPress: () -> Unit,
  onRemove: () -> Unit,
  onTrailing: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val type = TakuTheme.type
  val pin = card.pin
  Box(modifier.semantics { contentDescription = "${card.app}: ${pin.label} ${pin.value ?: ""}".trim() }) {
    GlassSurface(backdrop, Modifier.height(TakuSize.pill).longPressOver(onTap = onTap, onLongPress = onLongPress), shape = CircleShape) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TakuSpacing.md),
        modifier = Modifier.fillMaxHeight().padding(horizontal = TakuSpacing.md),
      ) {
        PinIconView(pin.icon, hasVinyl = pin.trailing?.icon == "play")
        if (pin.label.isNotBlank() || pin.detail != null) {
          if (pin.detail != null) {
            Column {
              BasicText(pin.label, style = type.bodyLgMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
              BasicText(pin.detail, style = type.bodySm.copy(color = TakuTheme.colors.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          } else {
            BasicText(pin.label, style = type.bodyXl.copy(color = TakuTheme.colors.textSecondary), maxLines = 1)
          }
        }
        pin.value?.let { BasicText(displayValue(it, type), style = type.headingXl, maxLines = 1) }
        pin.trailing?.let { trailing ->
          TakuIconButton(TakuIcons.PlaySmall, trailing.icon, { onTrailing(trailing.actionId) }, style = IconButtonStyle.Inverse, iconSize = TakuSize.iconSm)
        }
      }
    }
    AnimatedVisibility(
      visible = editing,
      enter = fadeIn() + scaleIn(initialScale = 0.6f),
      exit = fadeOut() + scaleOut(targetScale = 0.6f),
      modifier = Modifier.offset((-4).dp, (-4).dp),
    ) { TakuIconButton(TakuIcons.Minus, "Unpin", onRemove, style = IconButtonStyle.Badge) }
  }
}

@Composable
fun PinIconView(icon: PinIcon, hasVinyl: Boolean = false) {
  when (icon) {
    is PinIcon.Pixel -> PixelGlyph(icon.glyph)
    is PinIcon.Image -> if (!icon.src.startsWith("asset:")) PixelGlyph(icon.src.substringAfterLast('/')) else {
      val image = rememberAssetImage(icon.src)
      val shape = if (icon.circle) CircleShape else RoundedCornerShape(TakuRadius.md)
      val c = TakuTheme.colors
      Box(Modifier.size(width = if (hasVinyl) 56.dp else 40.dp, height = 40.dp)) {
        if (hasVinyl) {
          // NowPlayingArt: a 32 dp disc peeking from behind the cover at (16, 4).
          Vinyl(Modifier.offset(16.dp, 4.dp).size(32.dp))
        }
        Box(Modifier.size(40.dp).clip(shape).background(c.fillPlaceholder).border(0.58.dp, c.strokeHairline, shape)) {
          if (image != null) Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp))
        }
      }
    }
  }
}

fun displayValue(value: String, type: TakuTypography): AnnotatedString = buildAnnotatedString {
  for ((part, big) in splitDisplayValue(value)) {
    if (big) append(part)
    else withStyle(SpanStyle(fontFamily = type.bodyLg.fontFamily, fontSize = type.bodyLg.fontSize)) { append(part) }
  }
}
