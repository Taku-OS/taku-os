package org.agentos.agent.home.ui

import org.agentos.agent.home.design.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import org.agentos.agent.home.feed.CardSpan
import org.agentos.agent.home.feed.CardSurface
import org.agentos.agent.home.feed.FeedCard
import org.agentos.agent.home.feed.FeedState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A feed row: one full card, or up to two half cards side by side. */
private data class FeedRow(val key: String, val cards: List<FeedCard>)

private fun rows(cards: List<FeedCard>): List<FeedRow> {
  val out = ArrayList<FeedRow>()
  var pending: FeedCard? = null
  for (card in cards) {
    if (card.span == CardSpan.HALF) {
      val first = pending
      if (first == null) pending = card else { out += FeedRow("${first.id}|${card.id}", listOf(first, card)); pending = null }
    } else {
      pending?.let { out += FeedRow(it.id, listOf(it)) }; pending = null
      out += FeedRow(card.id, listOf(card))
    }
  }
  pending?.let { out += FeedRow(it.id, listOf(it)) }
  return out
}

/** The feed list index of [cardId]'s row (after the header and the pinned row), or null when not loaded. */
fun feedIndex(cards: List<FeedCard>, cardId: String): Int? = rows(cards).indexOfFirst { row -> row.cards.any { it.id == cardId } }.takeIf { it >= 0 }?.plus(2)

/** Feed page (0, 0-0…0-5): date header, pinned row and the infinite card list. */
@Composable
fun FeedPage(state: FeedState, controller: HomeController, backdrop: Backdrop?, listState: LazyListState, topInset: Dp, bottomInset: Dp) {
  val feedRows = remember(state.cards) { rows(state.cards) }
  var pinnedBounds by remember { mutableStateOf(Rect.Zero) }
  LaunchedEffect(listState, feedRows.size, state.loading, state.endReached, state.error) {
    // A page may contain only pinned cards, leaving the visible row count unchanged.
    // Recheck after loading completes so an initial in-flight request cannot stall paging.
    if (state.loading || state.endReached || state.error != null) return@LaunchedEffect
    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
      .distinctUntilChanged()
      .filter { it >= listState.layoutInfo.totalItemsCount - 4 }
      .collect { controller.repo.loadMore() }
  }
  Box(
    Modifier
      .fillMaxSize()
      .pointerInput(controller.editingPins) {
        if (!controller.editingPins) return@pointerInput
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
          if (!pinnedBounds.contains(down.position)) controller.editingPins = false
        }
      },
  ) {
    LazyColumn(
      state = listState,
      contentPadding = PaddingValues(top = topInset, bottom = bottomInset),
      verticalArrangement = Arrangement.spacedBy(TakuSpacing.lg),
      modifier = Modifier.fillMaxSize(),
    ) {
      item(key = "header", contentType = "header") { Header(Modifier.padding(horizontal = TakuSpacing.gutter)) { controller.openProfile() } }
      item(key = "pins", contentType = "pins") {
        PinnedRow(state.pins, controller, backdrop, Modifier.onGloballyPositioned { pinnedBounds = it.boundsInRoot() })
      }
      items(feedRows, key = { it.key }, contentType = { if (it.cards.size == 2) "pair" else it.cards[0].surface.name }) { row ->
        Row(
          horizontalArrangement = Arrangement.spacedBy(TakuSpacing.lg),
          modifier = Modifier
            .padding(horizontal = TakuSpacing.gutter)
            .fillMaxWidth()
            .animateItem(fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow), placementSpec = spring(dampingRatio = 0.82f, stiffness = 380f)),
        ) {
          for (card in row.cards) FeedCardItem(card, controller, backdrop, leaving = card.id in state.leaving, reported = state.heights[card.id], modifier = Modifier.weight(1f))
          if (row.cards.size == 1 && row.cards[0].span == CardSpan.HALF) Spacer(Modifier.weight(1f))
        }
      }
      if (state.emptyState != null) item(key = "welcome", contentType = "welcome") {
        FeedWelcomeCard(backdrop, Modifier.padding(horizontal = TakuSpacing.gutter), controller::openTextInput)
      }
      // Keep a failed request distinguishable from a successfully loaded, empty Feed.
      if (state.cards.isEmpty() && !state.loading && state.error != null) item(key = "empty", contentType = "empty") {
        FeedLoadError(Modifier.padding(horizontal = TakuSpacing.gutter)) { controller.repo.loadMore() }
      }
      if (!state.endReached) item(key = "more", contentType = "more") { Spacer(Modifier.height(1.dp)) }
    }
  }
}

@Composable
private fun FeedLoadError(modifier: Modifier, onRetry: () -> Unit) {
  val type = TakuTheme.type
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(TakuSpacing.md), modifier = modifier.fillMaxWidth().padding(vertical = 32.dp)) {
    BasicText("Your feed couldn’t load.", style = type.bodyLg.copy(color = TakuTheme.colors.textSecondary))
    Box(Modifier.clip(CircleShape).background(TakuTheme.colors.fillControl).clickable(onClick = onRetry).padding(horizontal = 16.dp, vertical = 10.dp)
      .semantics { role = Role.Button }) { BasicText("Try again", style = type.bodyLg.copy(color = TakuTheme.colors.textPrimary)) }
  }
}

@Composable
private fun Header(modifier: Modifier = Modifier, onProfile: () -> Unit) {
  val type = TakuTheme.type
  val date = remember { SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date()) }
  val avatar = rememberAssetImage("asset:feed/home/avatar-user.webp", 144)
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = modifier.fillMaxWidth().height(40.dp)) {
    BasicText(date.replaceFirstChar { it.titlecase(Locale.getDefault()) }, style = type.headingXl)
    // Issue #32 F1: the avatar is a control; it opens Taku AI (the account and AI settings).
    Box(Modifier.size(40.dp).clip(CircleShape).background(TakuTheme.colors.fillPlaceholder).clickable(onClick = onProfile).semantics { contentDescription = "Taku AI settings"; role = Role.Button }) {
      if (avatar != null) Image(avatar, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
  }
}

/**
 * One feed card: the shared [CardFrame] registered with the controller, so the long-press menu
 * can redraw the card above the scrim and the dissolve can snapshot it.
 */
@Composable
fun FeedCardItem(card: FeedCard, controller: HomeController, backdrop: Backdrop?, leaving: Boolean, reported: Float?, modifier: Modifier = Modifier) {
  val haptics = LocalHapticFeedback.current
  val layer = rememberGraphicsLayer()
  DisposableEffect(card.id, layer) {
    controller.register(card.id, layer)
    onDispose { controller.unregister(card.id, layer) }
  }
  CardFrame(card, controller.pool, backdrop, cardSizing(card, reported, squareHalf = true), modifier, layer, leaving,
    onPlaced = { controller.place(card.id, it) },
    onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); controller.openMenu(card.id) })
}

/** The card's frame height: control 64, half square (feed) or the reported body plus padding. */
fun cardSizing(card: FeedCard, reported: Float?, squareHalf: Boolean): Modifier {
  val padding = cardPadding(card.surface)
  return when {
    card.surface == CardSurface.CONTROL -> Modifier.fillMaxWidth().height(64.dp)
    card.span == CardSpan.HALF && squareHalf -> Modifier.fillMaxWidth().aspectRatio(1f)
    else -> Modifier.fillMaxWidth().height(((reported ?: ESTIMATE) + 2 * padding.value).dp)
  }
}

fun cardPadding(surface: CardSurface): Dp = when (surface) {
  CardSurface.CONTROL -> TakuSpacing.xs
  CardSurface.MEDIA -> 0.dp
  CardSurface.CARD -> TakuSpacing.md
}

/**
 * The native card frame shared by the feed and the panel pages: glass surface (blurred
 * wallpaper + Surface/900, radius 24, padding 12; the fully-rounded Surface/600 control track;
 * or no glass for a media card whose body draws a photo) hosting the card's React Native body.
 * Everything is recorded into [layer] (menus and dissolves redraw it).
 */
@Composable
fun CardFrame(
  card: FeedCard, pool: ReactCardPool, backdrop: Backdrop?, sizing: Modifier, modifier: Modifier = Modifier,
  layer: GraphicsLayer = rememberGraphicsLayer(), leaving: Boolean = false,
  onPlaced: (Rect) -> Unit = {}, onLongPress: (() -> Unit)? = null,
) {
  var origin by remember { mutableStateOf(Offset.Zero) }
  val control = card.surface == CardSurface.CONTROL
  val media = card.surface == CardSurface.MEDIA
  val shape = if (control) CircleShape else RoundedCornerShape(TakuRadius.card)
  val fill = if (control) TakuTheme.colors.surfaceGlass else TakuTheme.colors.surfaceCard
  Box(
    modifier
      .then(sizing)
      .onGloballyPositioned { origin = it.positionInRoot(); onPlaced(it.boundsInRoot()) }
      .graphicsLayer { alpha = if (leaving) 0f else 1f }
      .drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
      }
      .clip(shape)
      .drawBehind {
        if (media) return@drawBehind
        if (backdrop != null) with(backdrop) { drawBlurredAt(origin) }
        drawRect(fill)
      }
      .then(if (onLongPress != null) Modifier.longPressOver(enabled = !leaving) { onLongPress() } else Modifier)
      .semantics { contentDescription = "${card.app}: ${card.summary}" }
      .padding(cardPadding(card.surface)),
  ) {
    CardBody(card, pool)
  }
}

@Composable
fun CardBody(card: FeedCard, pool: ReactCardPool, modifier: Modifier = Modifier) {
  val type = TakuTheme.type
  val probe = remember(card.id) { pool.host != null }
  if (!probe) {
    BasicText(card.summary, style = type.bodyMd, modifier = modifier.fillMaxWidth())
    return
  }
  AndroidView(
    factory = { pool.acquire(card.id) ?: android.widget.Space(it) },
    onRelease = { pool.release(card.id) },
    modifier = modifier.fillMaxSize(),
  )
}

private const val ESTIMATE = 180f
