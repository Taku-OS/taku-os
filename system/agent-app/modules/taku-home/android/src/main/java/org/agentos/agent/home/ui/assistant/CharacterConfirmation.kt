package org.agentos.agent.home.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.agentos.agent.home.assistant.AssistantSession
import org.agentos.agent.home.assistant.AssistantException
import org.agentos.agent.home.assistant.CharacterTryOnPhase
import org.agentos.agent.home.assistant.CharacterTryOnState
import org.agentos.agent.home.design.TakuTheme

/** Compact confirmation over the live app. No screenshot backdrop, thumbnail or full-screen tint. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoxScope.CharacterConfirmation(state: CharacterTryOnState, session: AssistantSession) {
  if (state.phase == CharacterTryOnPhase.Starting) return
  Box(Modifier.fillMaxSize().clickable { ignoreStaleCard { session.cancelCharacter(state.requestId) } })
  val colors = TakuTheme.colors
  BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
    Column(
      Modifier.align(Alignment.TopCenter).widthIn(max = 380.dp).fillMaxWidth().heightIn(max = maxHeight)
        .clip(RoundedCornerShape(24.dp)).background(colors.surfaceInverse)
        .pointerInput(Unit) { detectTapGestures {} }.verticalScroll(rememberScrollState()).padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      val title = when (state.phase) {
        CharacterTryOnPhase.Checking -> "Checking character preview…"
        CharacterTryOnPhase.Confirming -> "Try this character?"
        CharacterTryOnPhase.Starting -> ""
        CharacterTryOnPhase.Failed -> state.message ?: "Character preview could not open."
      }
      BasicText(title, Modifier.fillMaxWidth(), style = TakuTheme.type.bodyXl.copy(color = colors.textOnInverse))
      // Natural-width pills stay left aligned; large font sizes may wrap rather than clip.
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.clip(RoundedCornerShape(24.dp)).background(colors.surfaceFrosted)
          .clickable { ignoreStaleCard { session.cancelCharacter(state.requestId) } }.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
          contentAlignment = Alignment.Center) {
          BasicText(if (state.phase == CharacterTryOnPhase.Failed) "Close" else "Not now",
            style = TakuTheme.type.bodyMdMedium.copy(color = colors.textOnInverse))
        }
        if (state.phase == CharacterTryOnPhase.Confirming) {
          Box(Modifier.clip(RoundedCornerShape(24.dp)).background(colors.surfaceCard)
            .clickable { ignoreStaleCard { session.confirmCharacter(state.requestId) } }.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center) {
            BasicText("Try on", style = TakuTheme.type.bodyMdMedium)
          }
        }
      }
    }
  }
}

/** A second tap can arrive before recomposition removes an expired or cancelled card. */
private inline fun ignoreStaleCard(action: () -> Unit) {
  try { action() } catch (failure: AssistantException) { if (failure.code != "STALE_REQUEST") throw failure }
}
