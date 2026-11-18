package org.agentos.agent.home.ui

import org.agentos.agent.home.design.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import org.agentos.agent.home.feed.CardSurface
import org.agentos.agent.home.feed.FeedCard
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/**
 * 0-3: the long-pressed card redrawn in place above a 24 % black scrim (the scene below is
 * blurred σ4 by [HomeScreen]); Remove / Pin to top / Ask Taku 8 dp below the card.
 * Opened from a pinned pill the card is shown live below the pinned row with
 * Unpin / Ask Taku.
 */
@Composable
fun CardMenu(target: MenuTarget, controller: HomeController, scrim: Float, screenHeight: Float, topInset: Float) {
  val density = LocalDensity.current
  val card = controller.repo.card(target.cardId)
  BackHandler { controller.closeMenu() }
  if (card == null) { LaunchedEffect(Unit) { controller.closeMenu() }; return }
  val appear = rememberAppear(target)
  val bounds = if (target.fromPin) null else controller.bounds(target.cardId)
  val layer = if (target.fromPin) null else controller.layer(target.cardId)
  val primary = if (target.fromPin) MenuAction("Unpin", { controller.unpin(card.id) }, ButtonStyle.Frosted)
  else MenuAction("Remove", { controller.remove(card.id) }, ButtonStyle.Frosted)
  val actions = buildList {
    if (!target.fromPin) add(MenuAction("Pin to top", { controller.pinToTop(card.id) }))
    add(MenuAction("Ask Taku", { controller.askTaku(card.id) }, icon = TakuIcons.Sparkle))
  }

  Scrim(scrim, onDismiss = { controller.closeMenu() }) {
    if (bounds != null && layer != null) {
      // The live card, redrawn above the scrim without scale or shadow.
      Canvas(Modifier.fillMaxSize()) { translate(bounds.left, bounds.top) { drawLayer(layer) } }
      AnchoredMenu(bounds, actions, appear, leading = listOf(primary), bottomReserve = 120.dp)
    } else {
      // Tapped pin: show the card itself (its React body) under the pinned row.
      val top = topInset + with(density) { (40 + 16 + 64 + 16).dp.toPx() }
      Box(Modifier.offset { IntOffset(0, top.roundToInt()) }.padding(horizontal = TakuSpacing.gutter).fillMaxWidth()
        .graphicsLayer { alpha = appear; scaleX = 0.96f + 0.04f * appear; scaleY = scaleX }) {
        Column(verticalArrangement = Arrangement.spacedBy(TakuSpacing.sm)) {
          PinnedCardPreview(card, controller)
          Row(horizontalArrangement = Arrangement.spacedBy(TakuSpacing.sm), modifier = Modifier.fillMaxWidth()) {
            TakuButton(primary.label, primary.onClick, Modifier.staggeredAppear(appear, 0), primary.style)
            Spacer(Modifier.weight(1f))
            actions.forEachIndexed { i, a -> TakuButton(a.label, a.onClick, Modifier.staggeredAppear(appear, i + 1), a.style, icon = a.icon) }
          }
        }
      }
    }
  }
}

@Composable
private fun PinnedCardPreview(card: FeedCard, controller: HomeController) {
  val height = controller.repo.state.value.heights[card.id]
  val control = card.surface == CardSurface.CONTROL
  val pad = if (control) TakuSpacing.xs else TakuSpacing.md
  Box(
    Modifier
      .fillMaxWidth()
      .height(if (control) TakuSize.pill else ((height ?: 180f).dp + pad * 2))
      .clip(if (control) CircleShape else RoundedCornerShape(TakuRadius.card))
      .background(TakuTheme.colors.surfaceCard)
      .clickable(remember { MutableInteractionSource() }, indication = null) { }
      .padding(pad),
  ) {
    if (controller.pool.host != null) {
      AndroidView(factory = { controller.pool.acquire(card.id) ?: android.widget.Space(it) }, onRelease = { controller.pool.release(card.id) }, modifier = Modifier.fillMaxSize())
    } else BasicText(card.summary, style = TakuTheme.type.bodyMd)
  }
}
