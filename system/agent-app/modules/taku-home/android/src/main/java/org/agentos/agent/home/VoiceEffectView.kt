package org.agentos.agent.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView
import org.json.JSONObject

/** Local, non-interactive designer animation. No microphone, network or JS/native interfaces. */
class VoiceEffectView(context: Context) : android.widget.FrameLayout(context) {
  var kind = "light"
  var target = "listen"
  var level = 0f
  var flat = false
  private var browser: WebView? = null
  private var loaded = false

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (browser != null) return
    val view = WebView(context)
    browser = view
    view.setBackgroundColor(Color.TRANSPARENT)
    view.isFocusable = false
    view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    view.setOnTouchListener { _, _ -> true }
    view.settings.apply {
      javaScriptEnabled = true
      allowFileAccess = false
      allowContentAccess = false
      blockNetworkLoads = true
      domStorageEnabled = false
    }
    view.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
      override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        if (request.url.scheme == "data") null else
          WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf()))
      override fun onPageFinished(view: WebView, url: String?) { loaded = true; update() }
    }
    addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    view.loadDataWithBaseURL("https://taku-effects.invalid/", context.assets.open("voice-effects.html").bufferedReader().use { it.readText() }, "text/html", "UTF-8", null)
  }

  fun update() {
    if (!loaded) return
    val props = JSONObject().put("kind", if (kind == "wave") "wave" else "light")
      .put("target", if (target == "live") "live" else "listen")
      .put("level", if (level.isFinite()) level.coerceIn(0f, 1f) else 0f).put("flat", flat)
      .put("active", windowVisibility == VISIBLE && isAttachedToWindow)
      .put("still", !ValueAnimator.areAnimatorsEnabled())
    browser?.evaluateJavascript("window.setVoiceEffect && window.setVoiceEffect($props)", null)
  }

  override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); update() }
  override fun onDetachedFromWindow() {
    loaded = false
    browser?.let { removeView(it); it.stopLoading(); it.destroy() }
    browser = null
    super.onDetachedFromWindow()
  }
}

class VoiceEffectExpoView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  val effect = VoiceEffectView(context)
  override val shouldUseAndroidLayout = true
  init { addView(effect, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
}
