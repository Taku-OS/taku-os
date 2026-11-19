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

/**
 * First-run onboarding over the Home wallpaper (Figma 1155:527 "0 empty", 1155:619 "1-name",
 * 1155:846 "1-name_input"). It is not a Feed item and not the SystemUI island: a Compose card
 * with the expanded island's look that can take keyboard input. It cannot be dismissed; the
 * Feed, home pages and AI entry stay behind it until [OnboardingRepository] says it is done.
 */
@Composable
fun BoxScope.OnboardingLayer(state: OnboardingRepository.State, repository: OnboardingRepository) {
  val step = state.step ?: return
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val actions = remember(repository) { OnboardingActions(context.applicationContext, repository, scope) }

  HiddenStatusBar()
  // Back does nothing: the only ways forward are the card's own actions. Nothing else of Home
  // is composed underneath, so there is no Feed, page or AI entry to reach around the card.
  BackHandler(enabled = true) {}

  EmptyHome(Modifier.align(Alignment.Center))
  OnboardingCard(step, state.userName, actions, Modifier.align(Alignment.TopCenter))
}

/** Runs card actions off the main thread, one at a time, through the shared repository. */
@Stable
private class OnboardingActions(val context: Context, val repository: OnboardingRepository, val scope: CoroutineScope) {
  var busy by mutableStateOf(false)
    private set

  fun perform(action: OnboardingAction, name: String? = null) = launchAction {
    withContext(Dispatchers.IO) { repository.perform(action, name) }
  }

  /** The existing ChatGPT subscription page; onboarding completes once the broker confirms sign-in. */
  fun connectChatGpt() = launchAction {
    withContext(Dispatchers.IO) { AgentGateway(context.contentResolver).call("openSettings", JSONObject().put("page", "account")) }
  }

  private fun launchAction(block: suspend () -> Unit) {
    if (busy) return
    busy = true
    scope.launch {
      try {
        block()
      } catch (e: OnboardingException) {
        Log.w(TAG, "${e.code}: ${e.message}")
        toast(if (e.code == "NAME_NOT_SAVED") "Couldn’t save your name. Try again, or tap Later." else "Something went wrong. Try again.")
      } catch (e: GatewayException) {
        Log.w(TAG, "${e.code}: ${e.message}")
        toast("ChatGPT sign-in couldn’t be opened. Try again.")
      } finally {
        busy = false
      }
    }
  }

  private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

  private companion object { const val TAG = "TakuOnboarding" }
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
private fun OnboardingCard(step: OnboardingStep, userName: String?, actions: OnboardingActions, modifier: Modifier) {
  val c = TakuTheme.colors
  var expanded by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { expanded = true }
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
          OnboardingStep.CONNECT_AI -> ConnectAiStep(userName, actions)
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
 * The AI connection step (Figma 1155:1073) is the next implementation pass. Until its chips land,
 * this keeps onboarding completable through the existing ChatGPT sign-in, which is the one
 * required connection; Jev and Fal stay optional and are offered where they are first needed.
 */
@Composable
private fun ConnectAiStep(userName: String?, actions: OnboardingActions) {
  val c = TakuTheme.colors
  val type = TakuTheme.type
  val greeting = buildAnnotatedString {
    if (userName == null) append("Nice to meet you.")
    else {
      append("Nice to meet you, ")
      withStyle(SpanStyle(fontFamily = type.bodyXlMedium.fontFamily, fontWeight = type.bodyXlMedium.fontWeight, color = c.islandTextStrong)) { append(userName) }
      append(".")
    }
    append("\nConnect these AI accounts so I can answer and help with tasks.")
  }
  Header { BasicText(greeting, style = type.bodyXl.copy(color = c.islandText)) }
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    IslandButton("Connect ChatGPT", IslandButtonStyle.Brand, enabled = true) { actions.connectChatGpt() }
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

/** Rasterizes a bundled SVG at display density without changing its geometry. */
@Composable
private fun SvgAsset(path: String, width: Dp, height: Dp) {
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
  if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(width, height))
  else Spacer(Modifier.size(width, height))
}

private val CARD_RADIUS = 40.dp
/** Collapsed island pill the card grows from (Figma 4-connecting compact pill height). */
private val PILL_WIDTH = 128.dp
private val PILL_HEIGHT = 32.dp
/** Row SVG and its width, top to bottom (Figma rows 1–13). */
private val STAR_ROWS = listOf(1 to 4, 1 to 4, 3 to 14, 3 to 14, 5 to 24, 6 to 44, 7 to 64, 6 to 44, 5 to 24, 3 to 14, 3 to 14, 1 to 4, 1 to 4)
