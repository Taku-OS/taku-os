package org.agentos.agent.home.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.agentos.agent.home.assistant.AssistantSession
import org.agentos.agent.home.assistant.AssistantState
import org.agentos.agent.home.assistant.OptionFact
import org.agentos.agent.home.assistant.canCreatePoll
import org.agentos.agent.home.assistant.IslandMode
import org.agentos.agent.home.design.TakuTheme

/** Figma 98:5170: content inside the shared top island; swipe to compare candidates. */
@Composable
internal fun ColumnScope.ReplyOptionsIslandBody(state: AssistantState, session: AssistantSession) {
  val options = state.options ?: return
  state.share?.let { OptionShareIslandBody(it, session); return }
  val c = TakuTheme.colors
  val type = TakuTheme.type
  val selected = options.items.indexOfFirst { it.id == state.optionsItemId }.coerceAtLeast(0)
  key(options.sourceIdentity) {
    val pager = rememberPagerState(initialPage = selected, pageCount = { options.items.size })
    val expandedContext = state.island == IslandMode.Full
    LaunchedEffect(selected) {
      if (!pager.isScrollInProgress && pager.currentPage != selected) pager.scrollToPage(selected)
    }
    LaunchedEffect(pager) {
      snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
        options.items.getOrNull(page)?.let { runCatching { session.selectOption(options.replyId, it.id) } }
      }
    }
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)) {
      BasicText(options.title.ifBlank { "${options.items.size} options" }, style = type.bodyXl.copy(color = c.islandText))
      if (options.summary.isNotBlank()) {
        // The text's own trailing ellipsis is the affordance; no extra explanation row.
        BasicText(options.summary, style = type.bodySm.copy(color = c.islandTextMuted),
          maxLines = if (expandedContext) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
          modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { session.expand(!expandedContext) }
            .semantics { contentDescription = if (expandedContext) "Hide details" else "Show details" })
      }
      HorizontalPager(state = pager, key = { options.items[it].id }, verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()) { page ->
        val item = options.items[page]
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.islandChip).padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              item.fact("rating")?.let { FactTag("★ ${it.text}", it) }
              val categories = item.facts.filter { it.role.startsWith("category-") }
                .ifEmpty { listOfNotNull(item.fact("cuisine")) }
              categories.forEach { FactTag(it.text, it) }
            }
            // The badge follows the real provider; historical/mixed-source cards must not acquire a Yelp brand.
            if (item.facts.isNotEmpty() && item.facts.all { it.source.equals("Yelp", ignoreCase = true) }) YelpSourceLabel()
          }
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            BasicText(item.label, style = type.bodyXl.copy(color = c.islandInk), modifier = Modifier.weight(1f))
            item.fact("price")?.let { price ->
              BasicText(price.text, style = type.bodyXl.copy(color = c.islandInk, textAlign = TextAlign.End),
                modifier = Modifier.widthIn(max = 120.dp).semantics { contentDescription = "${price.source} price range: ${price.text}。${price.note}" })
            }
          }
          item.fact("address")?.let { BasicText(it.text, style = type.bodySm.copy(color = c.islandTextMuted)) }
          item.fact("description")?.let { BasicText(it.text, style = type.bodySm.copy(color = c.islandTextMuted)) }
          Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().heightIn(min = 40.dp).clip(RoundedCornerShape(50))
              .background(c.islandInk.copy(alpha = 0.08f))
              .clickable(role = Role.Button) { session.dismissOptions(options.replyId) }
              .padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
              BasicText("Dismiss", style = type.bodyLg.copy(color = c.islandText))
            }
            Box(Modifier.weight(1f).fillMaxHeight().heightIn(min = 40.dp).clip(RoundedCornerShape(50))
              .background(c.islandInk)
              .clickable(enabled = options.canCreatePoll, role = Role.Button) { session.openOptionShare(options.replyId) }
              .padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
              BasicText("Share as poll", style = type.bodyLg.copy(color = c.islandSurface))
            }
          }
        }
      }
      Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
          options.items.forEachIndexed { index, item ->
            Box(Modifier.size(14.dp).clip(CircleShape).clickable(role = Role.Button) { session.selectOption(options.replyId, item.id) }
              .semantics { contentDescription = "Option ${index + 1}：${item.label}" }, contentAlignment = Alignment.Center) {
              Box(Modifier.size(6.dp).clip(CircleShape).background(if (index == selected) c.islandInk else c.islandChip))
            }
          }
        }
      }

    }
  }
}

@Composable
internal fun YelpSourceLabel() {
  BasicText("Yelp", style = TakuTheme.type.bodySm.copy(color = TakuTheme.colors.islandTextMuted))
}

@Composable
private fun FactTag(label: String, fact: OptionFact) {
  val c = TakuTheme.colors
  BasicText(label, style = TakuTheme.type.bodySm.copy(color = c.islandText),
    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(c.islandInk.copy(alpha = 0.08f))
      .padding(horizontal = 6.dp, vertical = 3.dp).semantics { contentDescription = "${fact.source}：${fact.text}。${fact.note}" })
}
