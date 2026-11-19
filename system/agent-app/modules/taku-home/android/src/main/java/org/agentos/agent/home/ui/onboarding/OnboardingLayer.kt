package org.agentos.agent.home.ui.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.agentos.agent.home.assistant.AgentGateway
import org.agentos.agent.home.assistant.GatewayException
import org.agentos.agent.home.design.TakuMotion
import org.agentos.agent.home.design.TakuTheme
import org.agentos.agent.home.onboarding.OnboardingAction
import org.agentos.agent.home.onboarding.OnboardingException
import org.agentos.agent.home.onboarding.OnboardingRepository
import org.agentos.agent.home.onboarding.OnboardingRules
import org.agentos.agent.home.onboarding.OnboardingStep
import org.json.JSONObject
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.agentos.agent.home.design.TakuIcon
import org.agentos.agent.home.design.TakuIcons
import org.agentos.agent.home.onboarding.Connection
import org.agentos.agent.home.onboarding.OnboardingService
import org.agentos.agent.home.onboarding.Services
import org.agentos.agent.home.ui.assistant.WorkingDots
import org.agentos.agent.home.ui.effects.Shaders

/**
 * First-run onboarding over the Home wallpaper (Figma 1155:527 "0 empty", 1155:619 "1-name",
 * 1155:846 "1-name_input", 1155:1073 "2-connect AI", 1341:782 "6-proactive"). It is not a Feed item and not the SystemUI island: a Compose card
 * with the expanded island's look that can take keyboard input. It cannot be dismissed; the
 * Feed, home pages and AI entry stay behind it until [OnboardingRepository] says it is done.
 */
@Composable
fun BoxScope.OnboardingLayer(state: OnboardingRepository.State, repository: OnboardingRepository) {
  val step = state.step ?: return
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val actions = remember(repository) { OnboardingActions(context.applicationContext, repository, scope) }
  // Why a ChatGPT sign-in failed, in the broker's own words, once per failure.
  LaunchedEffect(repository) { repository.notices.collect { actions.toast(it) } }
  // Back from the browser (or anywhere else): the card grows out of the pill again.
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) { actions.resumed = true; actions.away = false }
      if (event == Lifecycle.Event.ON_PAUSE) actions.resumed = false
    }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }

  HiddenStatusBar()
  if (OnboardingRules.takesOverHome(step)) {
    // Back does nothing: the only ways forward are the card's own actions. Nothing else of Home
    // is composed underneath, so there is no Feed, page or AI entry to reach around the card.
    BackHandler(enabled = true) {}
    EmptyHome(Modifier.align(Alignment.Center))
  }
  OnboardingCard(step, state, actions, Modifier.align(Alignment.TopCenter))
}

/** Runs card actions off the main thread, one at a time, through the shared repository. */
@Stable
private class OnboardingActions(val context: Context, val repository: OnboardingRepository, val scope: CoroutineScope) {
  var busy by mutableStateOf(false)
    private set
  /** The card has shrunk into the camera pill while a sign-in is in the browser. */
  var away by mutableStateOf(false)
  var resumed = true

  fun perform(action: OnboardingAction, name: String? = null) = launchAction {
    withContext(Dispatchers.IO) { repository.perform(action, name) }
  }

  /**
   * One tap: the card shrinks into the pill and the official ChatGPT page opens; the island
   * follows the sign-in and the assistant brings Home back when it lands.
   */
  fun connectChatGpt() {
    if (busy) return
    away = true
    launchAction(onFailure = { away = false }) {
      withContext(Dispatchers.IO) { repository.connect(OnboardingService.CHATGPT) }
      // Nothing opened (already signed in, or the page could not start): grow back.
      delay(LEAVE_GRACE_MS)
      if (resumed) away = false
    }
  }

  fun cancelSignIn() = launchAction { withContext(Dispatchers.IO) { repository.cancelSignIn() } }

  /** Saves a pasted Jev (TypeSafe or OpenRouter) or Fal key through the same store as its settings page. */
  fun saveKey(service: OnboardingService, key: String, onSaved: () -> Unit) = launchAction {
    withContext(Dispatchers.IO) { repository.connect(service, key) }
    onSaved()
  }

  /**
   * Jev without a key: switches on a Jev model Magpie already serves. When there is none, the key
   * field opens instead (no error: that is the normal first-time path).
   */
  fun enableJev(onNeedsKey: () -> Unit) {
    if (busy) return
    busy = true
    scope.launch {
      try {
        withContext(Dispatchers.IO) { repository.connect(OnboardingService.JEV) }
      } catch (e: OnboardingException) {
        Log.w(TAG, "${e.code}: ${e.message}")
        if (e.code == "NO_DECISION_MODEL" || e.code == "MAGPIE_UNAVAILABLE") onNeedsKey() else toast(message(e.code))
      } finally {
        busy = false
      }
    }
  }

  /** The ChatGPT subscription page, for a chip that is already connected. */
  fun manageChatGpt() = launchAction {
    try {
      withContext(Dispatchers.IO) { AgentGateway(context.contentResolver).call("openSettings", JSONObject().put("page", "account")) }
    } catch (e: GatewayException) {
      throw OnboardingException(e.code, e.message ?: e.code)
    }
  }

  private fun launchAction(onFailure: () -> Unit = {}, block: suspend () -> Unit) {
    if (busy) return
    busy = true
    scope.launch {
      try {
        block()
      } catch (e: OnboardingException) {
        Log.w(TAG, "${e.code}: ${e.message}")
        onFailure()
        toast(message(e.code))
      } finally {
        busy = false
      }
    }
  }

  fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

  private fun message(code: String) = when (code) {
    "NAME_NOT_SAVED" -> "Couldn’t save your name. Try again, or tap Later."
    "INVALID_KEY" -> "That key doesn’t look right. Jev takes a TypeSafe (apikey_…) or OpenRouter (sk-or-…) key."
    "KEY_NOT_SAVED", "SETTINGS_WRITE_FAILED" -> "Couldn’t save the key. Try again."
    "NO_DECISION_MODEL" -> "Key saved. Jev isn’t listed for it yet; tap Jev again in a moment."
    "MAGPIE_UNAVAILABLE" -> "The model service isn’t ready yet. Try again in a moment."
    "AUDIO_UNAVAILABLE" -> "Taku Audio isn’t available right now."
    "TASK_RUNNING" -> "Taku is still finishing a task. Try again in a moment."
    "AI_NOT_CONNECTED" -> "Connect ChatGPT first."
    "LOGIN_UNAVAILABLE", "ACCOUNT_UNAVAILABLE", "GATEWAY_UNAVAILABLE" -> "ChatGPT sign-in isn’t available right now. Try again."
    else -> "Something went wrong. Try again."
  }

  private companion object {
    const val TAG = "TakuOnboarding"
    /** How long the browser may take to cover Home before the card grows back. */
    const val LEAVE_GRACE_MS = 1_500L
  }
}

/** The design shows no status bar while the card covers the top; a swipe still reveals it. */
@Composable
private fun HiddenStatusBar() {
  val view = LocalView.current
  DisposableEffect(view) {
    val window = view.context.findActivity()?.window
    val controller = window?.let { WindowCompat.getInsetsController(it, view) }
    controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller?.hide(WindowInsetsCompat.Type.statusBars())
    onDispose { controller?.show(WindowInsetsCompat.Type.statusBars()) }
  }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

/**
 * The expanded-island card: Content/900, radius 40, 8 dp from the top and sides, padding 16.
 * It grows out of a camera-sized pill on first show (island morph spring), and its height follows
 * each step with the same spring while the copy cross-fades.
 */
@Composable
private fun OnboardingCard(step: OnboardingStep, state: OnboardingRepository.State, actions: OnboardingActions, modifier: Modifier) {
  val c = TakuTheme.colors
  var appeared by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { appeared = true }
  // Leaving for the browser shrinks the card back into the pill (the reverse of its entrance).
  val expanded = appeared && !actions.away
  val contentAlpha by animateFloatAsState(if (expanded) 1f else 0f, tween(durationMillis = 220, delayMillis = 120), label = "onboardingContent")
  Box(
    modifier
      .padding(horizontal = 8.dp)
      .padding(top = 8.dp)
      .animateContentSize(TakuMotion.morph())
      .then(if (expanded) Modifier.fillMaxWidth() else Modifier.width(PILL_WIDTH).height(PILL_HEIGHT))
      .clip(RoundedCornerShape(CARD_RADIUS))
      .background(if (expanded) c.islandSurface else c.islandSolid),
  ) {
    if (!expanded) return@Box
    AnimatedContent(
      targetState = step,
      transitionSpec = {
        fadeIn(tween(220, delayMillis = 90)) togetherWith fadeOut(tween(90)) using SizeTransform(clip = false) { _, _ -> TakuMotion.morph() }
      },
      label = "onboardingStep",
      modifier = Modifier.graphicsLayer { alpha = contentAlpha },
    ) { shown ->
      Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (shown) {
          OnboardingStep.INTRO -> IntroStep(actions)
          OnboardingStep.NAME -> NameStep(actions)
          OnboardingStep.CONNECT_AI -> ConnectAiStep(state, actions)
          OnboardingStep.ALL_SET -> AllSetStep(state.userName, actions)
          OnboardingStep.COMPLETED -> Unit
        }
      }
    }
  }
}

/** 1155:527 — the only way forward is "Set up Taku". */
@Composable
private fun IntroStep(actions: OnboardingActions) {
  Message("Hi. I’m Taku, your personal agent. I can answer questions, get things done, and keep you a step ahead.")
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    IslandButton("Set up Taku", IslandButtonStyle.Brand, enabled = true) { actions.perform(OnboardingAction.SET_UP) }
  }
}

/** 1155:619 (empty: Continue disabled) and 1155:846 (a name entered: Continue enabled). */
@Composable
private fun NameStep(actions: OnboardingActions) {
  val c = TakuTheme.colors
  val type = TakuTheme.type
  var draft by rememberSaveable { mutableStateOf("") }
  val focus = remember { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current
  val ready = OnboardingRules.canContinue(draft)
  fun submit() { if (ready) actions.perform(OnboardingAction.SUBMIT_NAME, draft) }
  // The frames show the keyboard already up for this step.
  LaunchedEffect(Unit) { withFrameNanos {}; focus.requestFocus(); keyboard?.show() }

  Message("Before we start,\nwhat should I call you?")
  BasicTextField(
    value = draft,
    onValueChange = { if (it.length <= OnboardingRules.NAME_MAX) draft = it },
    singleLine = true,
    textStyle = type.bodyLg.copy(color = c.islandTextStrong),
    cursorBrush = SolidColor(c.islandInk),
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(onDone = { submit() }),
    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Your name" },
    decorationBox = { field ->
      Box(Modifier.fillMaxWidth().clip(CircleShape).background(c.islandField).padding(12.dp), contentAlignment = Alignment.CenterStart) {
        if (draft.isEmpty()) BasicText("Your name...", style = type.bodyLg.copy(color = c.islandPlaceholder), maxLines = 1)
        field()
      }
    },
  )
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    // "Later" skips only the name; onboarding continues with the AI connection.
    IslandButton("Later", IslandButtonStyle.Secondary, enabled = true) { actions.perform(OnboardingAction.SKIP_NAME) }
    IslandButton("Continue", IslandButtonStyle.Primary, enabled = ready) { submit() }
  }
}

/**
 * 1155:1073: the three services as chips. ChatGPT signs in through the official page in one tap;
 * Jev (TypeSafe or OpenRouter, configured into Magpie in the background) and Fal keys are pasted
 * right here; a Jev Magpie already serves is switched on without a key. Connecting shows the island's working dots,
 * connected a checkmark (product states; the frame has only the idle chips). Once AI is available
 * (ChatGPT, or Magpie with a model) Continue finishes setup; Jev and Fal stay optional.
 */
@Composable
private fun ConnectAiStep(state: OnboardingRepository.State, actions: OnboardingActions) {
  val c = TakuTheme.colors
  val type = TakuTheme.type
  val services = state.services
  var editing by rememberSaveable { mutableStateOf<String?>(null) }
  val editingService = OnboardingService.parse(editing)
  val greeting = buildAnnotatedString {
    if (state.userName == null) append("Nice to meet you.")
    else {
      append("Nice to meet you, ")
      withStyle(SpanStyle(fontFamily = type.bodyXlMedium.fontFamily, fontWeight = type.bodyXlMedium.fontWeight, color = c.islandTextStrong)) { append(state.userName) }
      append(".")
    }
    append("\nConnect these AI accounts so I can answer and help with tasks.")
  }
  Header { BasicText(greeting, style = type.bodyXl.copy(color = c.islandText)) }
  ServiceChips(services) { service ->
    when (service) {
      OnboardingService.CHATGPT -> {
        editing = null
        if (services.chatgpt == Connection.CONNECTED) actions.manageChatGpt() else actions.connectChatGpt()
      }
      OnboardingService.JEV -> when {
        editing == service.id -> editing = null
        services.jev == Connection.CONNECTED -> editing = service.id
        else -> actions.enableJev { editing = service.id }
      }
      else -> editing = if (editing == service.id) null else service.id
    }
  }
  when {
    editingService != null -> KeyEditor(editingService, actions) { editing = null }
    services.chatgpt == Connection.CONNECTING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      IslandButton("Cancel sign-in", IslandButtonStyle.Secondary, enabled = !actions.busy) { actions.cancelSignIn() }
    }
    state.canFinish -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      IslandButton("Continue", IslandButtonStyle.Primary, enabled = !actions.busy) { actions.perform(OnboardingAction.FINISH) }
    }
  }
}

/**
 * The chip row (1155:1084): 8 dp apart, scrolling sideways when the chips do not fit. It runs
 * into the card's right padding, where the Figma "Taku · Edge Refraction" lens bends what
 * scrolls under the card edge (the same shader and values as the pinned row).
 */
@Composable
private fun ServiceChips(services: Services, onTap: (OnboardingService) -> Unit) {
  val scroll = rememberScrollState()
  val density = LocalDensity.current.density
  Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .layout { measurable, constraints ->
        val bleed = 16.dp.roundToPx()
        val width = constraints.maxWidth + bleed
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
      }
      .graphicsLayer {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && size.width > 0f)
          renderEffect = Shaders.edgeRefraction(size.width, size.height, density, left = scroll.value > 0, right = true).asComposeRenderEffect()
      }
      .horizontalScroll(scroll)
      .padding(end = 16.dp),
  ) {
    ServiceChip(OnboardingService.CHATGPT, "ChatGPT", services.chatgpt, 124.dp, onTap)
    ServiceChip(OnboardingService.JEV, "Jev", services.jev, 86.dp, onTap)
    services.fal?.let { ServiceChip(OnboardingService.FAL, "Fal", it, 82.dp, onTap) }
  }
}

/** A Surface/200 pill: 16 dp mark, 4 dp, label; dots while connecting, a check once connected. */
@Composable
private fun ServiceChip(service: OnboardingService, label: String, connection: Connection, minWidth: Dp, onTap: (OnboardingService) -> Unit) {
  val c = TakuTheme.colors
  val status = when (connection) {
    Connection.CONNECTED -> "connected"
    Connection.CONNECTING -> "connecting"
    else -> "not connected"
  }
  Row(
    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .widthIn(min = minWidth)
      .clip(CircleShape)
      .background(c.islandChip)
      .clickable { onTap(service) }
      .semantics { role = Role.Button; contentDescription = "$label, $status" }
      .padding(horizontal = 12.dp, vertical = 8.dp),
  ) {
    ServiceMark(service)
    BasicText(label, style = TakuTheme.type.bodyLgMedium.copy(color = c.islandChipText), maxLines = 1)
    when (connection) {
      Connection.CONNECTING -> WorkingDots(ink = c.islandChipText)
      Connection.CONNECTED -> TakuIcon(TakuIcons.Check, 16.dp, tint = c.islandChipText)
      else -> Unit
    }
  }
}

/** The Figma marks: OpenAI (tinted like the label), Jev in a 16 dp circle, Fal with its crop. */
@Composable
private fun ServiceMark(service: OnboardingService) {
  when (service) {
    OnboardingService.CHATGPT -> SvgAsset("onboarding/chatgpt-openai.svg", 16.dp, 16.dp, tint = TakuTheme.colors.islandChipText)
    OnboardingService.JEV -> AssetBitmap("onboarding/jev.png", Modifier.size(16.dp).clip(CircleShape))
    OnboardingService.FAL -> Box(Modifier.size(16.dp).clipToBounds()) {
      // Figma 1326:772: the image is 147.53% × 148.58% of the box, offset −23.88% / −24.41%.
      AssetBitmap("onboarding/fal.png", Modifier
        .wrapContentSize(Alignment.TopStart, unbounded = true)
        .offset(x = (-3.8208).dp, y = (-3.9056).dp)
        .size(width = 23.6048.dp, height = 23.7728.dp))
    }
  }
}

/** The Jev or Fal key, pasted in place (the name field's style); masked like any secret. */
@Composable
private fun KeyEditor(service: OnboardingService, actions: OnboardingActions, onClose: () -> Unit) {
  val c = TakuTheme.colors
  val type = TakuTheme.type
  var draft by remember(service) { mutableStateOf("") }
  val focus = remember { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current
  val ready = OnboardingRules.canSaveKey(service, draft) && !actions.busy
  val placeholder = if (service == OnboardingService.JEV) "Paste a TypeSafe or OpenRouter key" else "Paste your fal key"
  fun save() { if (ready) actions.saveKey(service, draft) { onClose() } }
  LaunchedEffect(service) { withFrameNanos {}; focus.requestFocus(); keyboard?.show() }

  BasicTextField(
    value = draft,
    onValueChange = { if (it.length <= 4096) draft = it },
    singleLine = true,
    textStyle = type.bodyLg.copy(color = c.islandTextStrong),
    cursorBrush = SolidColor(c.islandInk),
    visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(onDone = { save() }),
    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = placeholder },
    decorationBox = { field ->
      Box(Modifier.fillMaxWidth().clip(CircleShape).background(c.islandField).padding(12.dp), contentAlignment = Alignment.CenterStart) {
        if (draft.isEmpty()) BasicText(placeholder, style = type.bodyLg.copy(color = c.islandPlaceholder), maxLines = 1)
        field()
      }
    },
  )
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    IslandButton("Cancel", IslandButtonStyle.Secondary, enabled = true) { onClose() }
    IslandButton("Save", IslandButtonStyle.Primary, enabled = ready) { save() }
  }
}

/**
 * 1341:782 "You’re all set": over the real Feed, with the AI entry held open below. "I’ll explore"
 * completes onboarding and the card leaves.
 */
@Composable
private fun AllSetStep(userName: String?, actions: OnboardingActions) {
  val c = TakuTheme.colors
  val type = TakuTheme.type
  val text = buildAnnotatedString {
    if (userName == null) append("You’re all set.")
    else {
      append("You’re all set, ")
      withStyle(SpanStyle(fontFamily = type.bodyXlMedium.fontFamily, fontWeight = type.bodyXlMedium.fontWeight, color = c.islandTextStrong)) { append(userName) }
      append(".")
    }
    append("\nHold any card to pin it, remove it, or ask Taku.")
  }
  Header { BasicText(text, style = type.bodyXl.copy(color = c.islandText)) }
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    IslandButton("I’ll explore", IslandButtonStyle.Primary, enabled = !actions.busy) { actions.perform(OnboardingAction.EXPLORE) }
  }
}

@Composable
private fun Message(text: String) {
  val c = TakuTheme.colors
  Header { BasicText(text, style = TakuTheme.type.bodyXl.copy(color = c.islandText)) }
}

/** Eyes over the message, 4 dp apart (Figma 1155:531). */
@Composable
private fun Header(message: @Composable () -> Unit) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Eyes()
    message()
  }
}

/** Two 6×12 Main/Surface/Default pills, 4 dp apart, centred in a 32 dp box (Figma "Eyes"). */
@Composable
private fun Eyes() {
  val ink = TakuTheme.colors.islandInk
  Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      repeat(2) { Box(Modifier.width(6.dp).height(12.dp).clip(CircleShape).background(ink)) }
    }
  }
}

private enum class IslandButtonStyle { Brand, Primary, Secondary }

/** Full-width pill action: 12/8 dp padding, Body/Medium/lg (Figma 1155:539, 1155:861, 1155:863). */
@Composable
private fun RowScope.IslandButton(label: String, style: IslandButtonStyle, enabled: Boolean, onClick: () -> Unit) {
  val c = TakuTheme.colors
  val (fill, ink) = when {
    !enabled -> c.islandActionDisabled to c.islandActionDisabledText
    style == IslandButtonStyle.Brand -> c.islandBrand to c.islandActionText
    style == IslandButtonStyle.Primary -> c.islandAction to c.islandActionText
    else -> c.islandChip to c.islandChipText
  }
  Box(
    Modifier
      .weight(1f)
      .clip(CircleShape)
      .background(fill)
      .clickable(enabled = enabled, onClick = onClick)
      .semantics { role = Role.Button; if (!enabled) disabled() }
      .padding(horizontal = 12.dp, vertical = 8.dp),
    contentAlignment = Alignment.Center,
  ) {
    BasicText(label, style = TakuTheme.type.bodyLgMedium.copy(color = ink), maxLines = 1)
  }
}

/** The empty Home under the card (Figma 1155:542): dot-pixel star, title and hint, centred. */
@Composable
private fun EmptyHome(modifier: Modifier) {
  val c = TakuTheme.colors
  val type = TakuTheme.type
  Column(
    modifier.fillMaxWidth().padding(horizontal = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    DotStar()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      BasicText("Your Day, In One Place", style = type.headingXl.copy(color = c.textPrimary, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
      BasicText(
        "Important updates and things to do appear here.\nConnect your apps to make this Feed yours.",
        style = type.bodyLg.copy(color = c.textTertiary, textAlign = TextAlign.Center),
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

/** "Star · dot pixel · 4px / gap 1px" (1155:545): 13 rows of the original Figma row SVGs. */
@Composable
private fun DotStar() {
  Column(Modifier.size(64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
    for ((row, width) in STAR_ROWS) SvgAsset("onboarding/star-row-$row.svg", width.dp, 4.dp)
  }
}

/** A bundled raster asset, decoded once. */
@Composable
private fun AssetBitmap(path: String, modifier: Modifier) {
  val context = LocalContext.current
  val bitmap = remember(path) {
    runCatching { context.assets.open(path).use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }
      .onFailure { Log.w("TakuOnboarding", "could not draw $path", it) }.getOrNull()
  }
  if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = modifier)
  else Spacer(modifier)
}

/** Rasterizes a bundled SVG at display density without changing its geometry; [tint] recolours it. */
@Composable
private fun SvgAsset(path: String, width: Dp, height: Dp, tint: Color? = null) {
  val context = LocalContext.current
  val density = LocalDensity.current
  val w = with(density) { width.roundToPx() }
  val h = with(density) { height.roundToPx() }
  val bitmap = remember(path, w, h) {
    runCatching {
      val svg = context.assets.open(path).use { SVG.getFromInputStream(it) }
      Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.scale(w / svg.documentWidth, h / svg.documentHeight)
        svg.renderToCanvas(canvas)
      }.asImageBitmap()
    }.onFailure { Log.w("TakuOnboarding", "could not draw $path", it) }.getOrNull()
  }
  if (bitmap != null) Image(bitmap, contentDescription = null, colorFilter = tint?.let { ColorFilter.tint(it) }, modifier = Modifier.size(width, height))
  else Spacer(Modifier.size(width, height))
}

private val CARD_RADIUS = 40.dp
/** Collapsed island pill the card grows from (Figma 4-connecting compact pill height). */
private val PILL_WIDTH = 128.dp
private val PILL_HEIGHT = 32.dp
/** Row SVG and its width, top to bottom (Figma rows 1–13). */
private val STAR_ROWS = listOf(1 to 4, 1 to 4, 3 to 14, 3 to 14, 5 to 24, 6 to 44, 7 to 64, 6 to 44, 5 to 24, 3 to 14, 3 to 14, 1 to 4, 1 to 4)
