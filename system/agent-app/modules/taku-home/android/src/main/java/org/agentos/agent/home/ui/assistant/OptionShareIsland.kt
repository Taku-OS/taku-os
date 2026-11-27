package org.agentos.agent.home.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.agentos.agent.home.assistant.AssistantSession
import org.agentos.agent.home.assistant.OptionShare
import org.agentos.agent.home.design.TakuTheme

@Composable
internal fun ColumnScope.OptionShareIslandBody(share: OptionShare, session: AssistantSession) {
  val c = TakuTheme.colors; val type = TakuTheme.type; val reply = share.options.replyId
  Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    BasicText("Send group poll", style = type.bodyXl.copy(color = c.islandText))
    ShareChoice(share.pollTarget.ifBlank { "Choose WhatsApp group" },
      if (share.busy) "Opening WhatsApp…" else if (share.pollTarget.isBlank()) "Choose in WhatsApp; no group name needed" else "Tap to change group",
      !share.busy) { session.pickOptionPollRecipient(reply) }
    ShareField("Poll question", share.pollQuestion, "Where should we eat?") { session.setOptionPoll(reply, it) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surfaceCard)
      .background(c.accentPositive.copy(alpha = .18f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      BasicText(if (share.pollQuestion.isBlank()) "Poll question" else share.wireQuestion, style = type.bodyXlMedium.copy(color = c.textPrimary))
      BasicText("Select one", style = type.bodySm.copy(color = c.textSecondary))
      share.options.items.forEach { item ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Box(Modifier.size(20.dp).border(1.dp, c.accentPositive, CircleShape))
          BasicText(item.label, style = type.bodyLg.copy(color = c.textPrimary), modifier = Modifier.weight(1f))
        }
      }
    }
    BasicText("Taku will verify the group and send this single-choice poll.", style = type.bodySm.copy(color = c.islandTextMuted))
    val valid = !share.busy && runCatching(share::validatePoll).isSuccess
    ShareButton("Send to group", true, valid) { runCatching { session.prepareOptionPoll(reply) } }
    ShareButton("Back to options", false, !share.busy) { session.cancelOptionShare(reply) }
    share.error?.let { BasicText(it, style = type.bodySm.copy(color = c.islandTextMuted)) }
  }
}

@Composable
private fun ShareChoice(title: String, subtitle: String, enabled: Boolean, action: () -> Unit) {
  val c = TakuTheme.colors; val type = TakuTheme.type
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.islandChip)
    .clickable(enabled = enabled, role = Role.Button) { runCatching { action() } }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    BasicText(title, style = type.bodyXl.copy(color = if (enabled) c.islandText else c.islandTextMuted))
    BasicText(subtitle, style = type.bodySm.copy(color = c.islandTextMuted))
  }
}

@Composable
private fun ShareField(label: String, value: String, placeholder: String, change: (String) -> Unit) {
  val c = TakuTheme.colors; val type = TakuTheme.type
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    BasicText(label, style = type.bodySm.copy(color = c.islandTextMuted))
    BasicTextField(value, { runCatching { change(it) } }, singleLine = true, textStyle = type.bodyLg.copy(color = c.islandInk), cursorBrush = SolidColor(c.islandInk),
      modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.islandChip).padding(12.dp),
      decorationBox = { field -> Box { if (value.isEmpty()) BasicText(placeholder, style = type.bodyLg.copy(color = c.islandTextMuted)); field() } })
  }
}

@Composable
private fun ShareButton(text: String, primary: Boolean, enabled: Boolean = true, action: () -> Unit) {
  val c = TakuTheme.colors
  Box(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(50))
    .background(if (primary && enabled) c.islandInk else c.islandChip)
    .clickable(enabled = enabled, role = Role.Button) { runCatching { action() } }.padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
    BasicText(text, style = TakuTheme.type.bodyLg.copy(color = if (primary && enabled) c.islandSurface else c.islandTextMuted))
  }
}
