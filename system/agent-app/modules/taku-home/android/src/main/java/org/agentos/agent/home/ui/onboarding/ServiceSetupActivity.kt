package org.agentos.agent.home.ui.onboarding

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.agentos.agent.home.assistant.AssistantSession
import org.agentos.agent.home.design.TakuMotion
import org.agentos.agent.home.design.TakuTheme
import org.agentos.agent.home.onboarding.Connection
import org.agentos.agent.home.onboarding.OnboardingException
import org.agentos.agent.home.onboarding.OnboardingRepository
import org.agentos.agent.home.onboarding.OnboardingService
import org.agentos.agent.home.onboarding.ServiceSetup

/**
 * The Jev (Figma 1379:2556 "2-3") or Fal (1379:2782 "2-5") setup page on its own, for a user who
 * skipped it during onboarding: the same island card and key page over whatever was on screen.
 * Back, the chevron, "Skip for now" or a tap outside the card closes it; once connected the card
 * shows the connected state (1379:2680 / 1379:2907) with Done. Opened by [ServiceSetup].
 */
class ServiceSetupActivity : AppCompatActivity() {
  override fun onCreate(state: Bundle?) {
    enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
    super.onCreate(state)
    val service = OnboardingService.parse(intent.getStringExtra(ServiceSetup.EXTRA_SERVICE))
      ?.takeIf { it != OnboardingService.CHATGPT }
    if (service == null) { finish(); return }
    window.isNavigationBarContrastEnforced = false
    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
    window.attributes = window.attributes.apply { blurBehindRadius = (24 * resources.displayMetrics.density).toInt() }
    // The island's "fal API key missing" prompt brought the user here; it has done its job.
    if (service == OnboardingService.FAL) AssistantSession.get(this).clearVoiceSetupError()
    setContent { TakuTheme { ServiceSetupScreen(service, ::finish) } }
  }

  /** A request for the other service while this page is up replaces it; the same one is a no-op. */
  override fun onNewIntent(next: android.content.Intent) {
    super.onNewIntent(next)
    if (next.getStringExtra(ServiceSetup.EXTRA_SERVICE) == intent.getStringExtra(ServiceSetup.EXTRA_SERVICE)) return
    setIntent(next)
    recreate()
  }
}

/** Reads, connects and follows one service through the shared repository, one action at a time. */
@Stable
private class ServiceSetupModel(
  val context: Context,
  val repository: OnboardingRepository,
  val service: OnboardingService,
  val scope: CoroutineScope,
) : ServiceKeyActions {
  var connection by mutableStateOf(Connection.UNKNOWN)
    private set
  override var busy by mutableStateOf(false)
    private set

  suspend fun read() {
    connection = withContext(Dispatchers.IO) {
      try { repository.serviceStatus(service) } catch (e: OnboardingException) { Log.w(TAG, "${e.code}: ${e.message}"); Connection.UNKNOWN }
    }
  }

  /**
   * Jev never set up: first switch on a Jev model Magpie already serves, as the onboarding Jev page
   * does. A Jev the user turned off (a model still bound) is left off: only Connect with a key, an
   * explicit choice, turns it back on. When nothing is served the key steps stay; that is the
   * normal path, so it is not reported.
   */
  suspend fun tryServedJev() {
    if (service != OnboardingService.JEV || connection != Connection.NOT_CONNECTED || busy) return
    busy = true
    try {
      connection = withContext(Dispatchers.IO) {
        if (repository.jevBound()) connection else repository.setUp(OnboardingService.JEV, null)
      }
    } catch (e: OnboardingException) {
      Log.i(TAG, "no served Jev model: ${e.code}")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "served Jev check failed", e)
    } finally {
      busy = false
    }
  }

  override fun copy(label: String, text: String): Boolean = copyText(context, label, text)

  override fun saveKey(service: OnboardingService, key: String, onSaved: () -> Unit, onError: (String) -> Unit) {
    if (busy) return
    busy = true
    scope.launch {
      try {
        connection = withContext(Dispatchers.IO) { repository.setUp(service, key) }
        onSaved()
      } catch (e: OnboardingException) {
        Log.w(TAG, "${e.code}: ${e.message}")
        onError(onboardingMessage(e.code))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Anything unexpected is a failed save, never a crashed page.
        Log.w(TAG, "service setup failed", e)
        onError(onboardingMessage(""))
      } finally {
        busy = false
      }
    }
  }

  private companion object { const val TAG = "TakuServiceSetup" }
}

@Composable
private fun ServiceSetupScreen(service: OnboardingService, onClose: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val repository = remember { OnboardingRepository.get(context.applicationContext) }
  val model = remember(service) { ServiceSetupModel(context.applicationContext, repository, service, scope) }
  val c = TakuTheme.colors
  LaunchedEffect(model) { model.read(); model.tryServedJev() }
  // A Jev key saved before Magpie listed its model keeps connecting in the background; follow it.
  LaunchedEffect(model.connection) {
    while (model.connection == Connection.CONNECTING) { delay(POLL_MS); model.read() }
  }
  // Why this service did not connect after all, in the repository's words, once; never another service's.
  LaunchedEffect(repository, service) {
    repository.notices.collect { if (it.service == service) Toast.makeText(context, it.text, Toast.LENGTH_SHORT).show() }
  }

  HiddenStatusBar()
  Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose))
    Box(
      Modifier
        .align(Alignment.TopCenter)
        .padding(horizontal = 8.dp)
        .padding(top = 8.dp)
        .animateContentSize(TakuMotion.morph())
        .fillMaxWidth()
        .clip(RoundedCornerShape(CARD_RADIUS))
        .background(c.islandSurface)
        // Taps inside the card stay in the card.
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
      Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BackChevron(onClose)
        if (model.connection == Connection.CONNECTED) {
          ServiceTitle(service, connected = true)
          Row(Modifier.fillMaxWidth()) { IslandButton("Done", IslandButtonStyle.Primary, enabled = true, onClick = onClose) }
        } else {
          KeyPage(service, model.connection, model, onSkip = onClose)
        }
      }
    }
  }
}

/** How often a background Jev connection is re-read while the page waits for it. */
private const val POLL_MS = 3_000L
