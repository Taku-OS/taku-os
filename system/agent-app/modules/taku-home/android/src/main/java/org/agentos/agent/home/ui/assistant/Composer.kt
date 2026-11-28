package org.agentos.agent.home.ui.assistant

import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.agentos.agent.home.assistant.AssistantSession
import org.agentos.agent.home.assistant.AssistantState
import org.agentos.agent.home.assistant.RetainedVoiceResult
import org.agentos.agent.home.assistant.VoiceOutcome
import org.agentos.agent.home.assistant.VoicePhase
import org.agentos.agent.home.assistant.VoiceState
import org.agentos.agent.home.assistant.VoiceTarget
import org.agentos.agent.home.design.TakuIcon
import org.agentos.agent.home.design.TakuIcons
import org.agentos.agent.home.design.TakuRadius
import org.agentos.agent.home.design.TakuSize
import org.agentos.agent.home.design.TakuSpacing
import org.agentos.agent.home.design.TakuTheme
import org.agentos.agent.home.ui.effects.Shaders
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode

/**
 * The text input (1-2 → 1-3, dictation 1-5). Opening pops a glass capsule off the top of the
 * bottom sphere (Figma "Glass morph · capsule to ellipse"), which flies up and widens into the
 * 380 × 64 field 16 dp above the keyboard; behind it the lower screen frosts (σ6, #E3E3E3 → 90 %).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScope.TextComposer(scene: GraphicsLayer, open: Boolean, state: AssistantState, voice: VoiceState, session: AssistantSession, screenWidth: Float, screenHeight: Float) {
  val density = LocalDensity.current
  val colors = TakuTheme.colors
  val morph = remember { Animatable(0f) }
  LaunchedEffect(open) { morph.animateTo(if (open) 1f else 0f, if (open) tween(540) else tween(300)) }
  val p = morph.value
  if (p <= 0.001f && !open) return

  val imeBottom = WindowInsets.ime.getBottom(density)
  val navBottom = WindowInsets.navigationBars.getBottom(density)
  val gap = with(density) { 16.dp.toPx() }
  val fieldHeight = with(density) { 64.dp.toPx() }
  var measuredField by remember { mutableFloatStateOf(fieldHeight) }
  val fieldBottom = screenHeight - max(imeBottom.toFloat(), navBottom + gap) - gap
  val fieldTop = fieldBottom - measuredField
  val fieldWidth = screenWidth - 2 * gap
  // The capsule may grow upward only into the space above the keyboard; a long retained result
  // scrolls inside the remainder instead of pushing the field and its own start off-screen.
  val maxFieldHeight = with(density) { (fieldBottom - gap).coerceAtLeast(fieldHeight).toDp() }.coerceAtLeast(64.dp)

  // Frost behind the field and keyboard (1-3 "bottom": 128 dp above the keyboard to the screen edge).
  BottomFrost(scene, top = fieldTop - with(density) { 48.dp.toPx() }, strength = p)

  // The flying capsule (until the field takes over).
  if (p < 0.98f) PopCapsule(scene, p, Rect(gap, fieldTop, gap + fieldWidth, fieldBottom), screenWidth, screenHeight)

  // The field itself.
  val fieldAlpha = ((p - 0.82f) / 0.18f).coerceIn(0f, 1f)
  val focus = remember { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current
  LaunchedEffect(open) { if (open) { runCatching { focus.requestFocus() }; keyboard?.show() } else keyboard?.hide() }
  // Closing the keyboard (back / swipe) closes the input. Only in the focused window: a window
  // that loses focus loses the keyboard with it, which is not the user closing it (BUG-0047).
  var imeSeen by remember { mutableStateOf(false) }
  val imeVisible = WindowInsets.isImeVisible
  val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
  LaunchedEffect(imeVisible, open, focused) {
    if (!open) { imeSeen = false; return@LaunchedEffect }
    if (!focused) { imeSeen = false; return@LaunchedEffect }
    if (imeVisible) imeSeen = true
    else if (imeSeen && !voice.listening) { imeSeen = false; session.closeEntry() }
  }

  var value by remember { mutableStateOf(TextFieldValue(state.draft, TextRange(state.draft.length))) }
  LaunchedEffect(state.draft) { if (state.draft != value.text) value = TextFieldValue(state.draft, TextRange(state.draft.length)) }
  val dictation = voice.target == VoiceTarget.Dictation
  val dictating = dictation && voice.phase in VoiceState.ACTIVE
  // Only actual capture hides the draft behind the live preview (1-5); preparing and finalizing
  // keep the field editable, so words typed while the tail is recognised survive the merge.
  val capturing = dictation && voice.phase == VoicePhase.Capturing
  // A Text release shows the tail being recognised next to the editable draft, never instead of it.
  val tail = voice.preview.takeIf {
    dictating ||
      (voice.phase == VoicePhase.Closed && voice.outcome is VoiceOutcome.Failed) ||
      (voice.target == VoiceTarget.Hold && voice.phase == VoicePhase.Finalizing)
  }
  val incomplete = voice.outcome is VoiceOutcome.Failed
  val tailStatus = when {
    tail == null -> null
    incomplete -> if (tail.isBlank()) "Not recognised" else "Not recognised: $tail"
    voice.phase == VoicePhase.Closed -> null
    tail.isNotBlank() -> tail
    voice.phase == VoicePhase.Capturing || voice.phase == VoicePhase.Preparing -> "Listening…"
    else -> "Recognising…"
  }
  // Keep (or bring back) the keyboard across dictation start/stop, e.g. after the stop button.
  LaunchedEffect(dictating) { if (open) { runCatching { focus.requestFocus() }; keyboard?.show() } }
  val canSend = value.text.isNotBlank() || state.draftAttachmentNames.isNotEmpty()
  val send: () -> Unit = { if (canSend) { runCatching { session.send(value.text) } } }
  val textScroll = rememberScrollState()
  LaunchedEffect(capturing, voice.preview, textScroll.maxValue) {
    if (capturing) textScroll.animateScrollTo(textScroll.maxValue) else textScroll.scrollTo(0)
  }

  Box(
    Modifier
      .offset { IntOffset(gap.roundToInt(), fieldTop.roundToInt()) }
      .width(with(density) { fieldWidth.toDp() })
      .heightIn(min = 64.dp, max = maxFieldHeight)
      .onSizeChanged { measuredField = it.height.toFloat() }
      .graphicsLayer { alpha = fieldAlpha; scaleX = 0.96f + 0.04f * fieldAlpha; scaleY = 0.96f + 0.04f * fieldAlpha }
      .shadowGlow(48.dp, colors.shadowMenu)
      .clip(RoundedCornerShape(32.dp))
      .background(colors.surfaceCard)
      .padding(TakuSpacing.md),
  ) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TakuSpacing.sm)) {
      Row(Modifier.fillMaxWidth().weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TakuSpacing.sm)) {
        // 1-5 (node 1:4164) while capturing: one 40 dp line beside the meter and the stop button.
        Column(Modifier.weight(1f).padding(start = 4.dp).then(if (capturing) Modifier.height(TakuSize.control) else Modifier.verticalScroll(textScroll))) {
          Box(Modifier.then(if (capturing) Modifier.fillMaxHeight().clipToBounds() else Modifier), contentAlignment = Alignment.CenterStart) {
            // The field stays composed and focused while recording (1-5): swapping it out would
            // drop focus and the IME connection. It is only hidden behind the live preview, and
            // keystrokes are held off only while capture is actually running.
            BasicTextField(
              value = value,
              onValueChange = { if (!capturing) { value = it; session.setDraft(it.text) } },
              textStyle = TakuTheme.type.bodyLg.copy(color = colors.textSecondary),
              cursorBrush = SolidColor(colors.textPrimary),
              maxLines = 5,
              keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
              keyboardActions = KeyboardActions(onSend = { if (!capturing) send() }),
              modifier = Modifier.fillMaxWidth().focusRequester(focus).graphicsLayer { alpha = if (capturing) 0f else 1f }
                .semantics { contentDescription = "Ask Taku" },
              decorationBox = { inner ->
                if (value.text.isEmpty()) BasicText("Ask Taku to do anything...", style = TakuTheme.type.bodyLg.copy(color = colors.textPlaceholder), maxLines = 1)
                inner()
              },
            )
            if (capturing) DictationLine(tailStatus ?: "Listening…")
          }
          // Preparing, or recognising the tail: the draft stays visible, the state is separate.
          if (!capturing && tailStatus != null) DictationText(tailStatus)
          if (!capturing && state.draftAttachmentNames.isNotEmpty()) {
            BasicText(state.draftAttachmentNames.joinToString(" · "), style = TakuTheme.type.bodyLg.copy(color = colors.textSecondary))
          }
        }
        if (capturing) DictationMeter(voice.levels)
        when {
          dictating && voice.phase != VoicePhase.Preparing && voice.phase != VoicePhase.Capturing ->
            Box(Modifier.size(TakuSize.control).semantics { contentDescription = "Recognising" }, contentAlignment = Alignment.Center) { WorkingDots(ink = TakuTheme.colors.textPrimary) }
          dictating -> RoundButton(colors.surfaceInverse, "Stop dictation", onClick = { session.stopDictation() }) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(TakuRadius.sm)).background(colors.iconOnInverse))
          }
          // Figma 1-3 send (node 1:3663): #1B1B1B arrow on Main/Brand (issue #32 C3).
          canSend -> RoundButton(colors.accentSend, "Send", onClick = { send() }) { TakuIcon(TakuIcons.ArrowUp, 18.dp, tint = colors.icon) }
          else -> RoundButton(null, "Dictate", onClick = { session.startDictation() }) { TakuIcon(TakuIcons.Mic, 18.dp, tint = colors.icon) }
        }
      }
      state.retainedVoice?.let { RetainedVoice(it, Modifier.weight(1f, fill = false)) }
    }
  }
}

/** A successful dictation too long for the draft: the whole result, selectable, copyable and scrollable. */
@Composable
private fun RetainedVoice(result: RetainedVoiceResult, modifier: Modifier = Modifier) {
  val colors = TakuTheme.colors
  Column(
    modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
    verticalArrangement = Arrangement.spacedBy(TakuSpacing.xs),
  ) {
    BasicText("The draft is full. Your words are kept:", style = TakuTheme.type.bodySm.copy(color = colors.textPlaceholder), maxLines = 2)
    SelectionContainer {
      BasicText(
        result.text,
        style = TakuTheme.type.bodyLg.copy(color = colors.textSecondary),
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
      )
    }
  }
}

@Composable
private fun RoundButton(fill: androidx.compose.ui.graphics.Color?, description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
  Box(
    Modifier.size(TakuSize.control).clip(CircleShape).then(if (fill != null) Modifier.background(fill) else Modifier)
      .clickable(onClick = onClick).semantics { contentDescription = description },
    contentAlignment = Alignment.Center,
  ) { content() }
}

@Composable
private fun DictationText(text: String) {
  val colors = TakuTheme.colors
  BasicText(
    text.ifEmpty { " " },
    style = TakuTheme.type.bodyLg.copy(color = colors.textSecondary),
    softWrap = true,
    modifier = Modifier.fillMaxWidth(),
  )
}

/**
 * The live transcript on one line (1-5, node 1:4167): Body/Regular/lg in Content/800. It keeps
 * the newest words in view; once the line overflows, the older words fade out over 90 dp at the
 * start (to 24 %, the frame's fade), so nothing just said disappears under the meter.
 */
@Composable
private fun DictationLine(text: String) {
  val colors = TakuTheme.colors
  val scroll = rememberScrollState()
  LaunchedEffect(text, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
  val overflowing = scroll.maxValue > 0
  BasicText(
    text.ifEmpty { " " },
    style = TakuTheme.type.bodyLg.copy(color = colors.textSecondary),
    maxLines = 1,
    softWrap = false,
    modifier = Modifier
      .fillMaxWidth()
      .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
      .drawWithContent {
        drawContent()
        if (overflowing) {
          val fade = minOf(90.dp.toPx(), size.width)
          val mask = colors.textSecondary
          drawRect(Brush.horizontalGradient(0f to mask.copy(alpha = 0.24f), fade / size.width to mask.copy(alpha = 1f)),
            blendMode = BlendMode.DstIn)
        }
      }
      .horizontalScroll(scroll, enabled = false),
  )
}

/**
 * "Voice waveform · Gap 2" (1-5, node 1:4169): 29 resting 3 dp dots (Main/Foreground at 20 %),
 * then six bars (55 %, the last 38 %) whose Figma heights 8/13/6/8/11/4 dp scale with the latest
 * measured levels, as in the chat's dictation bar; 2 dp apart, 32 dp tall.
 */
@Composable
private fun DictationMeter(levels: List<Float>) {
  val ink = TakuTheme.colors.surfaceInverse
  val shape = remember { floatArrayOf(8f, 13f, 6f, 8f, 11f, 4f) }
  val dots = 29
  Canvas(Modifier.size(width = ((dots + shape.size) * 5 - 2).dp, height = 32.dp).semantics { contentDescription = "Listening" }) {
    val unit = 3.dp.toPx(); val pitch = 5.dp.toPx(); val cy = size.height / 2f
    for (i in 0 until dots) {
      drawRoundRect(ink, Offset(i * pitch, cy - unit / 2), androidx.compose.ui.geometry.Size(unit, unit), CornerRadius(unit / 2), alpha = 0.2f)
    }
    for (i in shape.indices) {
      val level = levels.getOrNull(levels.size - shape.size + i)
      val scale = if (level == null) 0.4f else 0.4f + level.coerceIn(0f, 1f) * 1.2f
      val h = (3f + (shape[i] - 3f) * scale).coerceIn(3f, 16f).dp.toPx()
      drawRoundRect(ink, Offset((dots + i) * pitch, cy - h / 2), androidx.compose.ui.geometry.Size(unit, h), CornerRadius(unit / 2),
        alpha = if (i == shape.lastIndex) 0.38f else 0.55f)
    }
  }
}

/** Glass capsule 128 × 64 rising from the sphere with a metaball neck, then morphing into the field. */
@Composable
private fun PopCapsule(scene: GraphicsLayer, p: Float, field: Rect, screenWidth: Float, screenHeight: Float) {
  val density = LocalDensity.current
  val colors = TakuTheme.colors
  val glass = rememberGraphicsLayer()
  Canvas(Modifier.fillMaxSize()) {
    val d = density.density
    val cx = screenWidth / 2f
    val sphereTop = screenHeight - 80f * d
    val rise = (p / 0.4f).coerceIn(0f, 1f)
    val fly = ((p - 0.4f) / 0.6f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) * (1f - it) }
    val capW = 128f * d; val capH = 64f * d
    val startTop = sphereTop + 24f * d - (88f + 24f) * d * rise
    val rect = Rect(
      left = lerp(cx - capW / 2, field.left, fly), top = lerp(startTop, field.top, fly),
      right = lerp(cx + capW / 2, field.right, fly), bottom = lerp(startTop + capH, field.bottom, fly),
    )
    val r = rect.height / 2f
    val path = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(r))) }
    // Neck to the sphere while still attached (1-2).
    if (p < 0.4f) {
      val neckTop = rect.bottom - 2f * d; val mid = (neckTop + sphereTop) / 2f
      val w = (6.25f + 8f * (1f - rise)) * d
      path.addPath(Path().apply {
        moveTo(cx - w - 8f * d, neckTop); quadraticTo(cx - w * 0.6f, mid, cx - w - 10f * d, sphereTop + 4f * d)
        lineTo(cx + w + 10f * d, sphereTop + 4f * d); quadraticTo(cx + w * 0.6f, mid, cx + w + 8f * d, neckTop); close()
      })
    }
    // Outer halo: 0/0 blur 32, black 8 %.
    drawIntoCanvas { c ->
      val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.TRANSPARENT; setShadowLayer(16f * d, 0f, 0f, colors.shadowBadge.toArgb()) }
      c.nativeCanvas.drawPath(path.asAndroidPath(), paint)
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      glass.renderEffect = Shaders.gaussian(8f * d).asComposeRenderEffect()
      glass.record(IntSize(size.width.toInt(), size.height.toInt())) { drawLayer(scene) }
      clipPath(path) { drawLayer(glass) }
    }
    // Milky glass fill (#E3E3E3 64 % → white) thickening into Surface/900 as it becomes the field.
    drawPath(path, Brush.verticalGradient(0f to colors.edgeFade.copy(alpha = 0.64f), 0.5f to colors.surfaceCard.copy(alpha = 0.8f), 1f to colors.surfaceCard, startY = rect.top, endY = rect.bottom), alpha = 0.18f + 0.74f * fly)
    drawPath(path, colors.glassRim, style = Stroke(1.2f * d), alpha = 1f - fly * 0.6f)
  }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Soft 0/0 glow shadow (Figma "0 0 48 rgba(0,0,0,.16)") drawn behind a rounded element. */
private fun Modifier.shadowGlow(blur: androidx.compose.ui.unit.Dp, color: androidx.compose.ui.graphics.Color) = drawWithContent {
  drawIntoCanvas { c ->
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.TRANSPARENT; setShadowLayer(blur.toPx() / 2f, 0f, 0f, color.toArgb()) }
    val r = minOf(size.height / 2f, 32.dp.toPx())
    c.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
  }
  drawContent()
}

/** Progressive frost of the scene from [top] to the bottom (σ 0 → 6 dp over 48 dp), fading to #E3E3E3 90 %. */
@Composable
fun BottomFrost(scene: GraphicsLayer, top: Float, strength: Float) {
  val density = LocalDensity.current
  val colors = TakuTheme.colors
  val region = rememberGraphicsLayer()
  val key = remember { arrayOfNulls<Any>(1) }
  Canvas(Modifier.fillMaxSize()) {
    val t = top.coerceIn(0f, size.height)
    val h = size.height - t
    if (h < 1f) return@Canvas
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      val k = listOf(size.width, h, strength)
      if (key[0] != k) {
        region.renderEffect = Shaders.progressiveBlur(size.width, h, 0f, 0f, 48f * density.density, 6f * density.density * strength).asComposeRenderEffect()
        key[0] = k
      }
      region.clip = true
      region.topLeft = IntOffset(0, t.roundToInt())
      region.record(IntSize(size.width.toInt(), h.toInt())) { translate(0f, -t) { drawLayer(scene) } }
      drawLayer(region)
    }
    drawRect(Brush.verticalGradient(0f to colors.edgeFade.copy(alpha = 0f), 1f to colors.edgeFade.copy(alpha = 0.9f * strength), startY = t, endY = size.height), topLeft = Offset(0f, t))
  }
}
