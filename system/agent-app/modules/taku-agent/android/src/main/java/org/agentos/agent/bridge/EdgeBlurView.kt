package org.agentos.agent.bridge

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView

/**
 * Progressive edge blur over its own children (the chat list, Figma 2-1): content scrolling up
 * under the title row blurs and fades into the status bar, and content passing under the
 * composer does the same toward the bottom. A separable Gaussian with a per-row σ, plus the fade.
 *
 * One pass of 25 taps over ±3σ samples every σ/4: at σ 12 dp (≈ 31 px) the taps sit ≈ 8 px apart,
 * so black text turns into a lattice of offset copies. The blur therefore runs in [ROUNDS] of
 * growing σ whose squares sum to the target: the first round samples densely (≈ 1.5 px) and
 * each later round samples content already smooth at its own step, so the result stays smooth.
 *
 * Zones are in dp from the view's top: the top zone is sharp at [topSharp] and fully blurred
 * at [topFull] (above it); the bottom zone is sharp at [bottomSharp] and full at [bottomFull]
 * (below it). A negative sharp edge turns that zone off. Android 13+ (RuntimeShader); older
 * versions draw the children unchanged.
 */
class EdgeBlurView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  var topSharp = -1f; var topFull = 0f
  var bottomSharp = -1f; var bottomFull = 0f
  /** Largest σ (dp), reached at the full edges. */
  var sigma = 12f
  /** Also fade the content out toward the full edges. */
  var fade = true
  /** A uniform blur (σ dp) everywhere on top of the edge zones, e.g. under a long-press menu. */
  var uniformSigma = 0f

  private var applied: List<Float>? = null

  init { setWillNotDraw(false) }

  fun update() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || width <= 0 || height <= 0) return
    val d = resources.displayMetrics.density
    val key = listOf(width.toFloat(), height.toFloat(), topSharp, topFull, bottomSharp, bottomFull, sigma, if (fade) 1f else 0f, uniformSigma, d)
    if (key == applied) return
    applied = key
    setRenderEffect(effect(d))
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun effect(d: Float): RenderEffect? {
    if (topSharp < 0f && bottomSharp < 0f && uniformSigma <= 0f) return null
    fun pass(dx: Float, dy: Float, share: Float, withFade: Boolean) = RenderEffect.createRuntimeShaderEffect(RuntimeShader(SHADER).apply {
      setFloatUniform("size", width.toFloat(), height.toFloat())
      setFloatUniform("dir", dx, dy)
      setFloatUniform("zones", if (topSharp < 0f) -1f else topSharp * d, topFull * d, if (bottomSharp < 0f) -1f else bottomSharp * d, bottomFull * d)
      setFloatUniform("sigmaMax", sigma * d * share)
      setFloatUniform("sigmaMin", uniformSigma * d * share)
      setFloatUniform("fade", if (withFade && fade) 1f else 0f)
    }, "content")
    var chain: RenderEffect? = null
    ROUNDS.forEachIndexed { i, share ->
      val last = i == ROUNDS.lastIndex
      for (vertical in listOf(true, false)) {
        // The fade applies once, in the very last pass.
        val next = pass(if (vertical) 0f else 1f, if (vertical) 1f else 0f, share, last && !vertical)
        chain = chain?.let { RenderEffect.createChainEffect(next, it) } ?: next
      }
    }
    return chain
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    update()
  }

  private companion object {
    /** σ share per round (0.2² + 0.4² + 0.894² ≈ 1); each round's tap step ≤ the σ before it. */
    val ROUNDS = floatArrayOf(0.2f, 0.4f, 0.894f)
    /** σ follows a smoothstep from each sharp edge to its full edge; the fade (1 − t²) trails it. */
    const val SHADER = """
      uniform shader content;
      uniform float2 size;
      uniform float2 dir;
      uniform float4 zones;
      uniform float sigmaMax;
      uniform float sigmaMin;
      uniform float fade;
      float ramp(float y, float sharp, float full) {
        if (sharp < 0.0) return 0.0;
        float t = clamp((y - sharp) / (full - sharp), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
      }
      half4 main(float2 p) {
        float t = max(ramp(p.y, zones.x, zones.y), ramp(p.y, zones.z, zones.w));
        float sigma = max(sigmaMax * t, sigmaMin);
        half4 c;
        if (sigma < 0.35) {
          c = content.eval(p);
        } else {
          float stepLen = sigma * 0.25;
          half4 acc = half4(0.0);
          float wsum = 0.0;
          for (int i = -12; i <= 12; i++) {
            float x = float(i) * stepLen;
            float w = exp(-0.5 * x * x / (sigma * sigma));
            acc += content.eval(clamp(p + dir * x, float2(0.5), size - 0.5)) * w;
            wsum += w;
          }
          c = acc / wsum;
        }
        if (fade > 0.5) c *= half(1.0 - t * t);
        return c;
      }
    """
  }
}
