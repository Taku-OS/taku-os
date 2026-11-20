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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.agentos.agent.home.design.TakuMotion
import org.agentos.agent.home.design.TakuTheme
import org.agentos.agent.home.onboarding.OnboardingAction
import org.agentos.agent.home.onboarding.OnboardingException
import org.agentos.agent.home.onboarding.OnboardingRepository
import org.agentos.agent.home.onboarding.OnboardingRules
import org.agentos.agent.home.onboarding.OnboardingStep
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.delay
import org.agentos.agent.home.design.TakuIcon
import org.agentos.agent.home.design.TakuIcons
import org.agentos.agent.home.onboarding.Connection
import org.agentos.agent.home.onboarding.AiAccess
import org.agentos.agent.home.onboarding.OnboardingService
import org.agentos.agent.home.onboarding.Services
import org.agentos.agent.home.ui.assistant.WorkingDots

/**
 * First-run onboarding over the Home wallpaper (Figma 1155:527 "0 empty", 1379:1785 "1-name",
 * 1379:2012 "1-name_input", 1379:2239–1379:2907 "2-x connect AI" pages, 1341:782 "6-proactive"). It is not a Feed item and not the SystemUI island: a Compose card
 * with the expanded island's look that can take keyboard input. It cannot be dismissed; the
 * Feed, home pages and AI entry stay behind it until [OnboardingRepository] says it is done.
 */
@Composable
fun BoxScope.OnboardingLayer(state: OnboardingRepository.State, repository: OnboardingRepository) {
  val step = state.step ?: return
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val actions = remember(repository) { OnboardingActions(context.applicationContext, repository, scope) }
  // Why a ChatGPT sign-in or a saved Jev key did not connect, once per failure. Only while the AI
  // step shows them: a reopened setup page (ServiceSetupActivity) speaks for itself.
  LaunchedEffect(repository, step) { if (step == OnboardingStep.CONNECT_AI) repository.notices.collect { actions.toast(it.text) } }

  HiddenStatusBar()
  if (OnboardingRules.takesOverHome(step)) {
    // Back does nothing: the only ways forward are the card's own actions. Nothing else of Home
    // is composed underneath, so there is no Feed, page or AI entry to reach around the card.
    BackHandler(enabled = true) {}
    EmptyHome(Modifier.align(Alignment.Center))
  }
  OnboardingCard(step, state, actions, Modifier.align(Alignment.TopCenter))
}

/**
 * What a Jev or Fal key page needs from its host: the onboarding card ([OnboardingActions]) or the
 * same page reopened later ([ServiceSetupActivity]).
 */
internal interface ServiceKeyActions {
  val busy: Boolean
  fun copy(label: String, text: String): Boolean
  fun saveKey(service: OnboardingService, key: String, onSaved: () -> Unit, onError: (String) -> Unit)
}

/** Runs card actions off the main thread, one at a time, through the shared repository. */
@Stable
private class OnboardingActions(val context: Context, val repository: OnboardingRepository, val scope: CoroutineScope) : ServiceKeyActions {
  override var busy by mutableStateOf(false)
    private set

  fun perform(action: OnboardingAction, name: String? = null) = launchAction {
    withContext(Dispatchers.IO) { repository.perform(action, name) }
  }

  /**
   * Starts device-code sign-in; Home stays in front and the ChatGPT page shows the official link
   * and the code ([ChatGptPage]) until the account lands on the user's other device.
   */
  fun connectChatGpt() = launchAction { withContext(Dispatchers.IO) { repository.connect(OnboardingService.CHATGPT) } }

  fun cancelSignIn() = launchAction { withContext(Dispatchers.IO) { repository.cancelSignIn() } }

  /**
   * Saves a pasted Jev (TypeSafe or OpenRouter) or Fal key through the same store as its settings
   * page. Failures go to [onError] so the key field can say why in place, not in a toast the
   * keyboard covers.
   */
  override fun saveKey(service: OnboardingService, key: String, onSaved: () -> Unit, onError: (String) -> Unit) =
    launchAction(onError) {
      withContext(Dispatchers.IO) { repository.connect(service, key) }
      onSaved()
    }

  /**
   * Jev without a key, on arriving at the Jev page: switches on a Jev model Magpie already serves,
   * and the page then shows Jev connected. When there is none the page keeps its key steps; that
   * is the normal first-time path, so it is not reported as an error.
   */
  fun enableJev() {
    if (busy) return
    busy = true
    scope.launch {
      try {
        withContext(Dispatchers.IO) { repository.connect(OnboardingService.JEV) }
      } catch (e: OnboardingException) {
        Log.w(TAG, "${e.code}: ${e.message}")
        if (e.code != "NO_DECISION_MODEL" && e.code != "MAGPIE_UNAVAILABLE") toast(message(e.code))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Anything unexpected is a failed action, never a crashed Home.
        Log.w(TAG, "Jev enable failed", e)
        toast(message(""))
      } finally {
        busy = false
      }
    }
  }

  override fun copy(label: String, text: String): Boolean = copyText(context, label, text)

  /** Runs [block] as the one card action; a failure is shown through [onError], else a toast. */
  private fun launchAction(onError: ((String) -> Unit)? = null, block: suspend () -> Unit) {
    if (busy) return
    busy = true
    val report: (String) -> Unit = onError ?: { toast(it) }
    scope.launch {
      try {
        block()
      } catch (e: OnboardingException) {
        Log.w(TAG, "${e.code}: ${e.message}")
        report(message(e.code))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Anything unexpected is a failed action, never a crashed Home.
        Log.w(TAG, "onboarding action failed", e)
        report(message(""))
      } finally {
        busy = false
      }
    }
  }

  fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

  private fun message(code: String) = onboardingMessage(code)

  private companion object {
    const val TAG = "TakuOnboarding"
  }
}

/** The card's words for a repository failure code. */
internal fun onboardingMessage(code: String) = when (code) {
  "NAME_NOT_SAVED" -> "Couldn’t save your name. Try again, or tap Skip for now."
  "INVALID_KEY" -> "That key doesn’t look right. Jev takes a TypeSafe (apikey_…) or OpenRouter (sk-or-…) key."
  "KEY_NOT_SAVED", "SETTINGS_WRITE_FAILED" -> "Couldn’t save the key. Try again."
  "NO_DECISION_MODEL" -> "No Jev model is available for this key yet. Try again in a moment."
  "MAGPIE_UNAVAILABLE" -> "The model service isn’t ready yet. Try again in a moment."
  "AUDIO_UNAVAILABLE" -> "Taku Audio isn’t available right now."
  "TASK_RUNNING" -> "Taku is still finishing a task. Try again in a moment."
  "AI_NOT_CONNECTED" -> "Connect ChatGPT first."
  "LOGIN_UNAVAILABLE", "ACCOUNT_UNAVAILABLE", "GATEWAY_UNAVAILABLE" -> "ChatGPT sign-in isn’t available right now. Try again."
  else -> "Something went wrong. Try again."
}

/**
 * Puts [text] on the clipboard. Android 13+ confirms with its own clipboard preview, so the card
 * only marks the copied item; older releases get a short toast.
 */
internal fun copyText(context: Context, label: String, text: String): Boolean {
  val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
  return try {
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    true
  } catch (e: RuntimeException) {
    Log.w("TakuOnboarding", "could not copy $label", e)
    Toast.makeText(context, "Couldn’t copy. Try again.", Toast.LENGTH_SHORT).show()
    false
  }
}

/** The design shows no status bar while the card covers the top; a swipe still reveals it. */
@Composable
internal fun HiddenStatusBar() {
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
  val expanded = appeared
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

/** 1379:1785 (empty: Continue disabled) and 1379:2012 (a name entered: Continue enabled). */
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

  Message("What should I call you?")
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
    // "Skip for now" skips only the name; onboarding continues with the AI connection (2-0).
    IslandButton("Skip for now", IslandButtonStyle.Secondary, enabled = true) { actions.perform(OnboardingAction.SKIP_NAME) }
    IslandButton("Continue", IslandButtonStyle.Primary, enabled = ready) { submit() }
  }
}

/**
 * The AI step's pages (Figma 1379:2239 "2-0 connect AI", then one page per service: ChatGPT
 * 1379:2331/2454, Jev 1379:2556/2680, fal 1379:2782/2907). They are the card's own navigation:
 * the stored step stays `connect_ai` throughout, every connection goes through the same
 * [OnboardingRepository] calls as before, and only Continue on the last page moves on
 * (FINISH, "You’re all set"). A service that is already connected shows its connected state
 * with the way forward; it is not managed here.
 */
private enum class AiPage { INTRO, CHATGPT, JEV, FAL }

@Composable
private fun ConnectAiStep(state: OnboardingRepository.State, actions: OnboardingActions) {
  val services = state.services
  var pageId by rememberSaveable { mutableStateOf(AiPage.INTRO.name) }
  // Fal is null when Taku Audio is not installed: the flow then ends at Jev.
  val pages = listOfNotNull(AiPage.CHATGPT, AiPage.JEV, AiPage.FAL.takeIf { services.fal != null })
  val page = AiPage.valueOf(pageId).let { if (it == AiPage.FAL && services.fal == null) AiPage.JEV else it }

  fun open(next: AiPage) {
    when (next) {
      // Arriving at ChatGPT asks for a device code right away, as the frame shows it, but only when
      // there is no AI yet: starting a sign-in switches a working Magpie away (and is refused while
      // a task runs), so with Magpie serving, Connect waits to be tapped.
      AiPage.CHATGPT -> if (services.chatgpt == Connection.NOT_CONNECTED && state.signIn == null &&
        state.facts.ai == AiAccess.UNAVAILABLE) actions.connectChatGpt()
      // Arriving at Jev first tries a Jev model Magpie already serves (no key needed).
      AiPage.JEV -> if (services.jev == Connection.NOT_CONNECTED) actions.enableJev()
      else -> Unit
    }
    pageId = next.name
  }
  fun forward(from: AiPage) {
    val next = pages.getOrNull(pages.indexOf(from) + 1)
    if (next == null) actions.perform(OnboardingAction.FINISH) else open(next)
  }
  fun back() {
    when (page) {
      AiPage.CHATGPT -> {
        // Leaving a sign-in in progress cancels it, as Cancel did; coming back starts a new one.
        if (services.chatgpt == Connection.CONNECTING) actions.cancelSignIn()
        pageId = AiPage.INTRO.name
      }
      AiPage.JEV -> pageId = AiPage.CHATGPT.name
      AiPage.FAL -> pageId = AiPage.JEV.name
      AiPage.INTRO -> Unit
    }
  }
  // System back follows the card's chevron; on the first page it stays a no-op (OnboardingLayer).
  BackHandler(enabled = page != AiPage.INTRO) { back() }

  AnimatedContent(
    targetState = page,
    transitionSpec = {
      fadeIn(tween(220, delayMillis = 90)) togetherWith fadeOut(tween(90)) using SizeTransform(clip = false) { _, _ -> TakuMotion.morph() }
    },
    label = "onboardingAiPage",
  ) { shown ->
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      if (shown == AiPage.INTRO) {
        AiIntro(state.userName) { open(AiPage.CHATGPT) }
        return@Column
      }
      BackChevron { back() }
      ServiceProgress(pages, shown, services)
      val service = shown.service
      val next = pages.getOrNull(pages.indexOf(shown) + 1)
      val nextLabel = next?.let { "Next: ${it.service.label}" } ?: "Continue"
      val forwardEnabled = !actions.busy
      if (connectionOf(services, service) == Connection.CONNECTED) {
        ServiceTitle(service, connected = true)
        Row(Modifier.fillMaxWidth()) {
          IslandButton(nextLabel, IslandButtonStyle.Primary, enabled = forwardEnabled) { forward(shown) }
        }
      } else when (service) {
        OnboardingService.CHATGPT -> ChatGptPage(state, actions)
        else -> KeyPage(service, connectionOf(services, service), actions) { forward(shown) }
      }
    }
  }
}

private val AiPage.service: OnboardingService get() = when (this) {
  AiPage.JEV -> OnboardingService.JEV
  AiPage.FAL -> OnboardingService.FAL
  else -> OnboardingService.CHATGPT
}

private val OnboardingService.label: String get() = when (this) {
  OnboardingService.CHATGPT -> "ChatGPT"
  OnboardingService.JEV -> "Jev"
  OnboardingService.FAL -> "Fal"
}

private fun connectionOf(services: Services, service: OnboardingService): Connection = when (service) {
  OnboardingService.CHATGPT -> services.chatgpt
  OnboardingService.JEV -> services.jev
  OnboardingService.FAL -> services.fal ?: Connection.NOT_CONNECTED
}

/** 1379:2239: the greeting and "Connect AI Agent", which opens the ChatGPT page. */
@Composable
private fun AiIntro(userName: String?, onStart: () -> Unit) {
  val c = TakuTheme.colors
  val type = TakuTheme.type
  val greeting = buildAnnotatedString {
    if (userName == null) append("Nice to meet you.")
    else {
      append("Nice to meet you, ")
      withStyle(SpanStyle(fontFamily = type.bodyXlMedium.fontFamily, fontWeight = type.bodyXlMedium.fontWeight, color = c.islandTextStrong)) { append(userName) }
      append(".")
    }
    append("\nSet up AI connections so I can answer and help with tasks.")
  }
  // The frame draws a back chevron here; there is no step to return to from the AI step (the name
  // is saved or skipped for good), so this page keeps the eyes like the other first pages.
  Header { BasicText(greeting, style = type.bodyXl.copy(color = c.islandText)) }
  Row(Modifier.fillMaxWidth()) {
    IslandButton("Connect AI Agent", IslandButtonStyle.Primary, enabled = true, onClick = onStart)
  }
}

/** The 32 dp back chevron at the top of each service page. */
@Composable
internal fun BackChevron(onBack: () -> Unit) {
  Box(
    Modifier
      .size(32.dp)
      .clip(CircleShape)
      .clickable(onClick = onBack)
      .semantics { role = Role.Button; contentDescription = "Back" },
    contentAlignment = Alignment.Center,
  ) {
    SvgAsset("onboarding/chevron-back.svg", 32.dp, 32.dp)
  }
}

/**
 * One 2 dp segment per service, 4 dp apart: Green/300 once that service is connected, Main/Surface/
 * Secondary for the page being shown, Surface/200 otherwise.
 */
@Composable
private fun ServiceProgress(pages: List<AiPage>, current: AiPage, services: Services) {
  val c = TakuTheme.colors
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    for (page in pages) {
      val color = when {
        connectionOf(services, page.service) == Connection.CONNECTED -> c.accentPositive
        page == current -> c.islandProgress
        else -> c.islandChip
      }
      Box(Modifier.weight(1f).height(2.dp).clip(CircleShape).background(color))
    }
  }
}

/**
 * The service mark (or, once connected, the check in a solid white disc) and Heading/lg title,
 * over its Body/md line.
 */
@Composable
internal fun ServiceTitle(service: OnboardingService, connected: Boolean) {
  val c = TakuTheme.colors
  val type = TakuTheme.type
  val subtitle = when (service) {
    OnboardingService.CHATGPT -> "Answer questions, write and plan"
    OnboardingService.JEV -> "Help you make decisions"
    OnboardingService.FAL -> "Create images, video and audio"
  }
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
      if (connected) ConnectedDisc() else ServiceMark(service)
      BasicText(
        if (connected) "${service.label} Connected" else "Connect ${service.label}",
        style = type.headingLg.copy(color = c.islandTextStrong),
        maxLines = 1,
      )
    }
    BasicText(subtitle, style = type.bodyMd.copy(color = c.islandText))
  }
}

/** Connected: the existing check in a solid white 20 dp disc (the primary button's colours). */
@Composable
private fun ConnectedDisc() {
  val c = TakuTheme.colors
  Box(Modifier.size(20.dp).background(c.islandAction, CircleShape), contentAlignment = Alignment.Center) {
    TakuIcon(TakuIcons.Check, 12.dp, tint = c.islandActionText)
  }
}

/** The Figma title marks, 20 dp: OpenAI in white, Jev centre-cropped into a circle, fal. */
@Composable
private fun ServiceMark(service: OnboardingService) {
  when (service) {
    OnboardingService.CHATGPT -> SvgAsset("onboarding/chatgpt-openai.svg", 20.dp, 20.dp, tint = TakuTheme.colors.islandInk)
    OnboardingService.JEV -> AssetBitmap("onboarding/jev-mark.png", 20.dp, Modifier.size(20.dp).clip(CircleShape), ContentScale.Crop)
    OnboardingService.FAL -> SvgAsset("onboarding/fal-mark.svg", 20.dp, 20.dp)
  }
}

/**
 * 1379:2331: ChatGPT device-code sign-in, with the broker's real link and one-time code. Arriving
 * here starts the sign-in; Connect stays disabled while it waits for approval on the user's other
 * device, and asks again if a sign-in failed or was never started. The page turns into the
 * connected state (1379:2454) when the account lands.
 */
@Composable
private fun ChatGptPage(state: OnboardingRepository.State, actions: OnboardingActions) {
  val chatgpt = state.services.chatgpt
  val waiting = chatgpt == Connection.CONNECTING || actions.busy
  // After approval the broker verifies for a moment and no longer reports the code; keep the last
  // one on screen until the account lands instead of flashing back to empty wells.
  var lastSignIn by remember { mutableStateOf(state.signIn) }
  LaunchedEffect(state.signIn) { state.signIn?.let { lastSignIn = it } }
  val signIn = state.signIn ?: lastSignIn.takeIf { chatgpt == Connection.CONNECTING }
  var copied by remember(signIn) { mutableStateOf<String?>(null) }
  LaunchedEffect(copied) { if (copied != null) { delay(COPIED_MS); copied = null } }
  val type = TakuTheme.type
  val strong = TakuTheme.colors.islandTextStrong

  ServiceTitle(OnboardingService.CHATGPT, connected = false)
  Step(1, "Open this link below") {
    CopyWell("link", signIn?.displayUrl, type.bodyLg.copy(color = strong), copied == "link", waiting) {
      if (signIn != null && actions.copy("Link", signIn.url)) copied = "link"
    }
  }
  Step(2, "Sign in to ChatGPT")
  Step(3, "Enter the code and approve") {
    CopyWell("code", signIn?.code, type.subHeadingLg.copy(color = strong), copied == "code", waiting) {
      if (signIn != null && actions.copy("Code", signIn.code)) copied = "code"
    }
  }
  Row(Modifier.fillMaxWidth()) {
    IslandButton("Connect", IslandButtonStyle.Primary, enabled = !waiting) { actions.connectChatGpt() }
  }
}

/**
 * 1379:2556 (Jev) and 1379:2782 (fal): where to get a key, then the key itself. The key is shown
 * by default and the eye masks it. While it saves (Jev can take up to ~30 s while Magpie lists the
 * model, and may keep connecting in the background after that) the field locks and Connect reads
 * "Connecting…"; a failure is said right under the field. "Skip for now" moves on unconnected.
 */
@Composable
internal fun KeyPage(service: OnboardingService, connection: Connection, actions: ServiceKeyActions, onSkip: () -> Unit) {
  val c = TakuTheme.colors
  val type = TakuTheme.type
  val (who, keysUrl) = when (service) {
    OnboardingService.JEV -> "Jev" to JEV_KEYS_URL
    else -> "fal" to FAL_KEYS_URL
  }
  // The key itself is never put in saved instance state.
  var draft by remember(service) { mutableStateOf("") }
  var shown by rememberSaveable(service) { mutableStateOf(true) }
  var error by remember(service) { mutableStateOf<String?>(null) }
  var copied by remember(service) { mutableStateOf(false) }
  LaunchedEffect(copied) { if (copied) { delay(COPIED_MS); copied = false } }
  val keyboard = LocalSoftwareKeyboardController.current
  val connecting = actions.busy || connection == Connection.CONNECTING
  val ready = OnboardingRules.canSaveKey(service, draft) && !connecting
  fun save() {
    if (!ready) return
    error = null
    keyboard?.hide()
    actions.saveKey(service, draft, onSaved = {}, onError = { error = it })
  }

  ServiceTitle(service, connected = false)
  Step(1, "Sign up or sign in to $who") {
    CopyWell("link", keysUrl, type.bodyLg.copy(color = c.islandTextStrong), copied, waiting = false) {
      if (actions.copy("Link", keysUrl)) copied = true
    }
  }
  Step(2, "Create and copy an API key")
  Step(3, "Paste the key below") {
    BasicTextField(
      value = draft,
      onValueChange = { if (it.length <= 4096) { draft = it; error = null } },
      enabled = !connecting,
      singleLine = true,
      textStyle = type.bodyLg.copy(color = c.islandText),
      cursorBrush = SolidColor(c.islandInk),
      visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { save() }),
      modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$who API key" },
      decorationBox = { field ->
        Well(Modifier.height(48.dp)) {
          Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (draft.isEmpty()) BasicText("API key", style = type.bodyLg.copy(color = c.islandPlaceholder), maxLines = 1)
            field()
          }
          // The eye shows the key's state: open while shown (the default), slashed while masked.
          Box(
            Modifier
              .size(32.dp)
              .clip(CircleShape)
              .clickable { shown = !shown }
              .semantics { role = Role.Button; contentDescription = if (shown) "Hide key" else "Show key" },
            contentAlignment = Alignment.Center,
          ) {
            if (shown) SvgAsset("onboarding/eye.svg", 18.1495.dp, 18.dp) else SvgAsset("onboarding/eye-off.svg", 18.dp, 18.dp)
          }
        }
      },
    )
    error?.let {
      BasicText(it, style = type.bodyMd.copy(color = c.islandTextStrong), modifier = Modifier.padding(horizontal = 12.dp))
    }
  }
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(Modifier.fillMaxWidth()) {
      IslandButton(if (connecting) "Connecting…" else "Connect", IslandButtonStyle.Primary, enabled = ready) { save() }
    }
    // A text action, 40 dp tall: Body/Medium/lg in Surface/800, no fill.
    Box(
      Modifier
        .fillMaxWidth()
        .height(40.dp)
        .clip(CircleShape)
        .clickable(enabled = !actions.busy, onClick = onSkip)
        .semantics { role = Role.Button; if (actions.busy) disabled() },
      contentAlignment = Alignment.Center,
    ) {
      BasicText("Skip for now", style = type.bodyLgMedium.copy(color = c.islandText), maxLines = 1)
    }
  }
}

/** A numbered step: the number in a 28 dp Surface/200 ring, 8 dp, the instruction; then its well. */
@Composable
private fun Step(number: Int, text: String, content: (@Composable () -> Unit)? = null) {
  val c = TakuTheme.colors
  val type = TakuTheme.type
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(28.dp).border(1.dp, c.islandChip, CircleShape), contentAlignment = Alignment.Center) {
        BasicText(number.toString(), style = type.bodyLgMedium.copy(color = c.islandTextStrong, textAlign = TextAlign.Center))
      }
      BasicText(text, style = type.bodyLg.copy(color = c.islandText))
    }
    content?.invoke()
  }
}

/** Surface/50 row, radius 24, 12 dp in at the start and 8 dp elsewhere, at least 48 dp tall. */
@Composable
private fun Well(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
  Row(
    modifier
      .fillMaxWidth()
      .heightIn(min = 48.dp)
      .clip(RoundedCornerShape(24.dp))
      .background(TakuTheme.colors.islandWell)
      .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    content = content,
  )
}

/**
 * A value to use on another device with its copy button (32 dp, Surface/200). Working dots stand
 * in until the value arrives; the button shows the white check disc for a moment once copied.
 */
@Composable
private fun CopyWell(what: String, value: String?, style: TextStyle, copied: Boolean, waiting: Boolean, onCopy: () -> Unit) {
  val c = TakuTheme.colors
  Well {
    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
      when {
        value != null -> BasicText(value, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        waiting -> WorkingDots(ink = c.islandInk)
        else -> BasicText("—", style = style.copy(color = c.islandPlaceholder), maxLines = 1)
      }
    }
    Box(
      Modifier
        .size(32.dp)
        .clip(CircleShape)
        .background(if (copied) c.islandAction else c.islandChip)
        .clickable(enabled = value != null, onClick = onCopy)
        .semantics { role = Role.Button; contentDescription = "Copy $what"; if (value == null) disabled() },
      contentAlignment = Alignment.Center,
    ) {
      if (copied) TakuIcon(TakuIcons.Check, 16.dp, tint = c.islandActionText)
      else SvgAsset("onboarding/copy.svg", 18.dp, 18.dp)
    }
  }
}

/**
 * 1341:782 "You’re all set": over the real Feed. "I’ll explore", or a tap on any Feed card
 * ([org.agentos.agent.home.ui.HomeController.feedCardTapped]), completes onboarding and the card leaves.
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

internal enum class IslandButtonStyle { Brand, Primary, Secondary }

/** Full-width pill action: 12/8 dp padding, Body/Medium/lg (Figma 1155:539, 1155:861, 1155:863). */
@Composable
internal fun RowScope.IslandButton(label: String, style: IslandButtonStyle, enabled: Boolean, onClick: () -> Unit) {
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

/**
 * A bundled raster asset, decoded once and downsampled (by powers of two) to no less than [drawn]
 * on its short side, so a large source costs only what it shows. The file itself is unchanged.
 */
@Composable
private fun AssetBitmap(path: String, drawn: Dp, modifier: Modifier, contentScale: ContentScale = ContentScale.FillBounds) {
  val context = LocalContext.current
  val target = with(LocalDensity.current) { drawn.roundToPx() }.coerceAtLeast(1)
  val bitmap = remember(path, target) {
    runCatching {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
      var sample = 1
      while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
      val options = BitmapFactory.Options().apply { inSampleSize = sample }
      context.assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }?.asImageBitmap()
    }.onFailure { Log.w("TakuOnboarding", "could not draw $path", it) }.getOrNull()
  }
  if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = contentScale, modifier = modifier)
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

internal val CARD_RADIUS = 40.dp
/** How long a copied link or code keeps its check mark. */
private const val COPIED_MS = 2_000L
/** Where each service's API keys are made (Figma 1379:2659, 1382:3245). */
private const val JEV_KEYS_URL = "https://console.typesafe.ai/keys"
private const val FAL_KEYS_URL = "https://fal.ai/dashboard/keys"
/** Collapsed island pill the card grows from (Figma 4-connecting compact pill height). */
private val PILL_WIDTH = 128.dp
private val PILL_HEIGHT = 32.dp
/** Row SVG and its width, top to bottom (Figma rows 1–13). */
private val STAR_ROWS = listOf(1 to 4, 1 to 4, 3 to 14, 3 to 14, 5 to 24, 6 to 44, 7 to 64, 6 to 44, 5 to 24, 3 to 14, 3 to 14, 1 to 4, 1 to 4)
