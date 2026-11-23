package org.agentos.agent.home

import com.facebook.react.bridge.ReactContext
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.agentos.agent.home.feed.FeedActions
import org.agentos.agent.home.feed.FeedException
import org.agentos.agent.home.feed.FeedGeneration
import org.agentos.agent.home.feed.FeedOrigin
import org.agentos.agent.home.feed.FeedRepository
import org.agentos.agent.home.panels.PanelDirectory
import org.agentos.agent.home.panels.PanelNavigation
import org.json.JSONObject

/**
 * JavaScript bridge for `TakuFeedCard` and `TakuPanel` surfaces (see CONTRACT.md "Native module
 * TakuHome"). Panel content is not bridged here: pages read the native matter through the
 * Assistant Interface. This module only projects the desktop's directory and navigation state so
 * a root knows which Panel it shows and when to reread the list.
 */
class TakuHomeModule : Module() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var watcher: Job? = null
  private var panelWatcher: Job? = null
  private var navigationWatcher: Job? = null

  private val context: ReactContext
    get() = appContext.reactContext as? ReactContext ?: throw Exceptions.ReactContextLost()

  override fun definition() = ModuleDefinition {
    Name("TakuHome")
    Events("onCardChanged", "onPanelsChanged", "onPanelNavigation")

    View(VoiceEffectExpoView::class) {
      Prop("kind") { view: VoiceEffectExpoView, value: String -> view.effect.kind = value }
      Prop("target") { view: VoiceEffectExpoView, value: String -> view.effect.target = value }
      Prop("level") { view: VoiceEffectExpoView, value: Float -> view.effect.level = value }
      Prop("flat") { view: VoiceEffectExpoView, value: Boolean -> view.effect.flat = value }
      OnViewDidUpdateProps { view: VoiceEffectExpoView -> view.effect.update() }
    }


    Function("beginFeedGeneration") { attemptId: String ->
      FeedGeneration.begin(attemptId, context)
    }

    Function("completeFeedGeneration") { attemptId: String, receiptJson: String ->
      FeedGeneration.complete(attemptId, receiptJson, context)
    }

    Function("getCard") { cardId: String ->
      FeedRepository.get(context).card(cardId)?.presentationJson()?.toString()
    }

    Function("readAssetText") { path: String ->
      if (!path.startsWith("feed/") || path.contains("..") || !path.endsWith(".svg")) return@Function null
      try {
        context.assets.open(path).bufferedReader().use { it.readText() }
      } catch (_: java.io.IOException) {
        null
      }
    }

    Function("reportCardHeight") { cardId: String, heightDp: Double ->
      if (heightDp > 0 && heightDp < 4000) FeedRepository.get(context).setHeight(cardId, heightDp.toFloat())
    }

    /**
     * Reopens the Jev or Fal setup page (Figma 2-3 / 2-5) from the chat: a GUI card Jev could not
     * compose, or dictation that needs a fal key. Answers `{ok}` or `{ok:false, code}`.
     */
    Function("openAiSetup") { service: String ->
      try {
        val target = org.agentos.agent.home.onboarding.OnboardingService.parse(service)
          ?: throw org.agentos.agent.home.onboarding.OnboardingException("INVALID_SERVICE", "service must be jev or fal")
        org.agentos.agent.home.onboarding.ServiceSetup.open(context, target)
        JSONObject().put("ok", true).toString()
      } catch (e: org.agentos.agent.home.onboarding.OnboardingException) {
        JSONObject().put("ok", false).put("code", e.code).put("message", e.message).toString()
      }
    }

    /** The desktop's actual navigation state; the same projection the native provider serves. */
    Function("panelNavigation") { PanelNavigation.state.value.toJson().toString() }

    AsyncFunction("dispatchAction") Coroutine { cardId: String, actionId: String, valuesJson: String ->
      try {
        val values = if (valuesJson.isBlank()) JSONObject() else JSONObject(valuesJson)
        JSONObject().put("ok", true).put("result", FeedActions.dispatch(context, cardId, actionId, values, FeedOrigin.GUI)).toString()
      } catch (e: FeedException) {
        JSONObject().put("ok", false).put("code", e.code).put("message", e.message).toString()
      }
    }

    OnStartObserving {
      watcher?.cancel()
      watcher = scope.launch {
        FeedRepository.get(context).changes.collect { id -> sendEvent("onCardChanged", mapOf("cardId" to id)) }
      }
    }

    OnStopObserving {
      watcher?.cancel()
      watcher = null
    }

    // A committed directory change is only an invalidation token; the list rereads its pages.
    OnStartObserving("onPanelsChanged") {
      val directory = PanelDirectory.get(context)
      directory.start()
      panelWatcher?.cancel()
      panelWatcher = scope.launch {
        directory.state.collect { state ->
          if (state.listRevision >= 0) sendEvent("onPanelsChanged", mapOf("listRevision" to state.listRevision))
        }
      }
    }

    OnStopObserving("onPanelsChanged") {
      panelWatcher?.cancel()
      panelWatcher = null
    }

    OnStartObserving("onPanelNavigation") {
      navigationWatcher?.cancel()
      navigationWatcher = scope.launch {
        PanelNavigation.state.collect { snapshot ->
          sendEvent(
            "onPanelNavigation",
            mapOf("available" to snapshot.available, "visible" to snapshot.visible, "selectedPanelId" to snapshot.selectedPanelId),
          )
        }
      }
    }

    OnStopObserving("onPanelNavigation") {
      navigationWatcher?.cancel()
      navigationWatcher = null
    }

    OnDestroy {
      watcher?.cancel()
      panelWatcher?.cancel()
      navigationWatcher?.cancel()
    }
  }
}
