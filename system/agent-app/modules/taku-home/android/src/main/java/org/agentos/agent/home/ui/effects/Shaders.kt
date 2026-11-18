package org.agentos.agent.home.ui.effects

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * AGSL ports of the Figma effects. All coordinates are layer-local pixels; every shader
 * samples its `content` input (the layer the effect is applied to).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
object Shaders {
  /** The assistant glass's blurred scene, refracted edge and bright rim, on a rounded menu. */
  fun menuGlass(width: Float, height: Float, density: Float): RenderEffect {
    val shader = RuntimeShader("""
      uniform shader content;
      uniform float2 size;
      uniform float density;
      half4 main(float2 p) {
        float radius = 26.0 * density;
        float2 q = abs(p - size * 0.5) - (size * 0.5 - radius);
        float sd = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
        float2 normal = normalize(max(q, 0.001)) * sign(p - size * 0.5);
        float edge = clamp(1.0 + sd / (12.0 * density), 0.0, 1.0);
        float2 at = clamp(p - normal * edge * edge * 5.0 * density, float2(0.5), size - 0.5);
        half3 col = content.eval(at).rgb;
        col = mix(col, half3(0.97, 0.98, 1.0), 0.24);
        float rim = exp(-abs(sd + 0.8 * density) / (0.65 * density));
        col = mix(col, half3(1.0), rim * 0.4);
        return half4(col, 1.0);
      }
    """).apply { setFloatUniform("size", width, height); setFloatUniform("density", density) }
    return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"), gaussian(8f * density))
  }
  /**
   * One direction of a Gaussian blur whose sigma varies with y between [yA, yB].
   * Two passes (horizontal then vertical) give a progressive blur: the Figma
   * "BACKGROUND_BLUR PROGRESSIVE" used by the bottom edge, and the status-bar edge.
   */
  private const val PROGRESSIVE_BLUR = """
    uniform shader content;
    uniform float2 size;
    uniform float2 dir;
    uniform float sigmaA;
    uniform float sigmaB;
    uniform float yA;
    uniform float yB;
    half4 main(float2 p) {
      float t = clamp((p.y - yA) / (yB - yA), 0.0, 1.0);
      float sigma = mix(sigmaA, sigmaB, t * t * (3.0 - 2.0 * t));
      if (sigma < 0.35) return content.eval(p);
      float stepLen = sigma * 0.25;
      half4 acc = half4(0.0);
      float wsum = 0.0;
      for (int i = -12; i <= 12; i++) {
        float x = float(i) * stepLen;
        float w = exp(-0.5 * x * x / (sigma * sigma));
        acc += content.eval(clamp(p + dir * x, float2(0.5), size - 0.5)) * w;
        wsum += w;
      }
      return acc / wsum;
    }
  """

  /**
   * "Taku · Edge Refraction" (right) and "Taku · Left Edge Refraction", applied at both
   * screen edges of the pinned row, plus a progressive horizontal blur toward each edge.
   * Within [edge] px of an edge the row is vertically magnified about its centre by up to
   * (1 + flare) with an R/B split of `dispersion · t²` dp, exactly as the WGSL source.
   */
  private const val EDGE_REFRACTION = """
    uniform shader content;
    uniform float2 size;
    uniform float edge;
    uniform float blurZone;
    uniform float maxSigma;
    uniform float flare;
    uniform float dispersion;
    uniform float density;
    uniform float2 sides;
    half4 tap(float2 p) { return content.eval(clamp(p, float2(0.5), size - 0.5)); }
    half4 main(float2 p) {
      float d = min(sides.x > 0.5 ? p.x : 1e6, sides.y > 0.5 ? size.x - p.x : 1e6);
      if (d >= max(edge, blurZone)) return content.eval(p);
      float t = smoothstep(0.0, 1.0, clamp(1.0 - d / edge, 0.0, 1.0));
      float stretch = 1.0 + flare * t * t;
      float cy = size.y * 0.5;
      float qy = cy + (p.y - cy) / stretch;
      float shift = dispersion * t * t * density;
      float b = clamp(1.0 - d / blurZone, 0.0, 1.0);
      float sigma = maxSigma * b * b;
      if (sigma < 0.35) {
        half4 cr = tap(float2(p.x, qy + shift));
        half4 cg = tap(float2(p.x, qy));
        half4 cb = tap(float2(p.x, qy - shift));
        return half4(cr.r, cg.g, cb.b, max(cr.a, max(cg.a, cb.a)));
      }
      float stepLen = sigma * 0.4;
      half4 r = half4(0.0); half4 g = half4(0.0); half4 bl = half4(0.0);
      float wsum = 0.0;
      for (int i = -6; i <= 6; i++) {
        float x = float(i) * stepLen;
        float w = exp(-0.5 * x * x / (sigma * sigma));
        r += tap(float2(p.x + x, qy + shift)) * w;
        g += tap(float2(p.x + x, qy)) * w;
        bl += tap(float2(p.x + x, qy - shift)) * w;
        wsum += w;
      }
      r /= wsum; g /= wsum; bl /= wsum;
      return half4(r.r, g.g, bl.b, max(r.a, max(g.a, bl.a)));
    }
  """

  /**
   * The bottom edge: Figma "Bottom" fade (#E3E3E3 0→1 at 90 %) over the progressively
   * blurred feed, the "Live refractive glass" ellipse (GLASS refraction 1, depth 24,
   * light from the top, dispersion 1, 4 % milky fill, 8 % outer halo) and, while the entry
   * is expanded, the four additive prismatic glow blobs masked to the ellipse.
   * `content` is already progressively blurred.
   */
  private const val BOTTOM_GLASS = """
    uniform shader content;
    uniform float2 size;
    uniform float fadeHeight;
    uniform float4 ellipse;
    uniform float expand;
    uniform float depth;
    uniform float density;
    uniform float4 glowY;
    uniform float4 glowB;
    uniform float4 glowR;
    uniform float4 glowG;
    uniform half3 canvas;

    half3 faded(float2 p) {
      half4 c = content.eval(clamp(p, float2(0.5), size - 0.5));
      float g = clamp(p.y / fadeHeight, 0.0, 1.0) * 0.9;
      return mix(c.rgb, canvas, g);
    }
    float blob(float2 p, float4 b) {
      float2 d = (p - b.xy) / b.zw;
      return exp(-0.5 * dot(d, d));
    }
    half4 main(float2 p) {
      half3 base = faded(p);
      float2 q = (p - ellipse.xy) / ellipse.zw;
      float len = length(q);
      float sd = (len - 1.0) * min(ellipse.z, ellipse.w);
      // Outer drop shadow: 0/0 blur 32 (σ16) black 8 %, outside the shape only.
      if (sd > 0.0) {
        float sigma = 16.0 * density;
        float halo = 0.08 * exp(-0.5 * sd * sd / (sigma * sigma));
        base *= 1.0 - halo;
        if (sd > 1.0) return half4(base, 1.0);
      }
      float inside = max(-sd, 0.0);
      float2 n = normalize(q / ellipse.zw + 1e-5);
      float t = clamp(1.0 - inside / depth, 0.0, 1.0);
      float bend = t * t * depth * 0.45;
      float disp = t * t * 1.6 * density;
      half3 col;
      col.r = faded(p - n * (bend + disp)).r;
      col.g = faded(p - n * bend).g;
      col.b = faded(p - n * (bend - disp)).b;
      // 4 % milky fill: #E3E3E3@64 % → #FFF@80 % → #FFF top→bottom of the ellipse.
      float gy = clamp((p.y - (ellipse.y - ellipse.w)) / (2.0 * ellipse.w), 0.0, 1.0);
      half3 tint = mix(canvas, half3(1.0), smoothstep(0.0, 0.5, gy));
      float tintA = 0.04 * mix(0.64, 1.0, smoothstep(0.0, 1.0, gy));
      col = mix(col, tint, tintA);
      // Specular rim lit from the top (light angle 0°, intensity 1).
      float light = clamp(-n.y, 0.0, 1.0);
      float rim = 1.0 - smoothstep(0.0, 1.2 * density, inside);
      col += half3(rim * (0.12 + 0.5 * light));
      col += half3((1.0 - smoothstep(0.0, depth, inside)) * light * 0.05);
      // Prismatic glow (expanded only): yellow, blue, red additive; green normal.
      if (expand > 0.001) {
        half3 glow = half3(0.984, 1.0, 0.0) * blob(p, glowY) * 0.30
                   + half3(0.0, 0.184, 1.0) * blob(p, glowB) * 0.34
                   + half3(1.0, 0.0, 0.0) * blob(p, glowR) * 0.30;
        col = mix(col, half3(0.369, 0.82, 0.0), blob(p, glowG) * 0.30 * expand);
        col += glow * expand;
      }
      float aa = clamp(0.5 - sd, 0.0, 1.0);
      return half4(mix(base, min(col, half3(1.0)), aa), 1.0);
    }
  """

  fun progressiveBlur(width: Float, height: Float, yA: Float, sigmaA: Float, yB: Float, sigmaB: Float): RenderEffect {
    fun pass(dx: Float, dy: Float) = RuntimeShader(PROGRESSIVE_BLUR).apply {
      setFloatUniform("size", width, height)
      setFloatUniform("dir", dx, dy)
      setFloatUniform("sigmaA", sigmaA); setFloatUniform("sigmaB", sigmaB)
      setFloatUniform("yA", yA); setFloatUniform("yB", yB)
    }
    val horizontal = RenderEffect.createRuntimeShaderEffect(pass(1f, 0f), "content")
    val vertical = RenderEffect.createRuntimeShaderEffect(pass(0f, 1f), "content")
    return RenderEffect.createChainEffect(vertical, horizontal)
  }

  fun edgeRefraction(width: Float, height: Float, density: Float, left: Boolean = true, right: Boolean = true): RenderEffect {
    val shader = RuntimeShader(EDGE_REFRACTION).apply {
      setFloatUniform("sides", if (left) 1f else 0f, if (right) 1f else 0f)
      setFloatUniform("size", width, height)
      setFloatUniform("edge", 12f * density)
      setFloatUniform("blurZone", 28f * density)
      setFloatUniform("maxSigma", 6f * density)
      setFloatUniform("flare", 0.25f)
      setFloatUniform("dispersion", 0.4f)
      setFloatUniform("density", density)
    }
    return RenderEffect.createRuntimeShaderEffect(shader, "content")
  }

  /** [ellipse] = cx, cy, rx, ry in region pixels. Glow blobs: cx, cy, σx, σy. */
  fun bottomGlass(
    width: Float, height: Float, fadeHeight: Float, density: Float,
    ellipse: FloatArray, expand: Float, glows: Array<FloatArray>, blurSigmaBottom: Float, fade: androidx.compose.ui.graphics.Color,
  ): RenderEffect {
    val glass = RuntimeShader(BOTTOM_GLASS).apply {
      setFloatUniform("size", width, height)
      setFloatUniform("fadeHeight", fadeHeight)
      setFloatUniform("ellipse", ellipse)
      setFloatUniform("expand", expand)
      setFloatUniform("depth", 24f * density)
      setFloatUniform("density", density)
      setFloatUniform("glowY", glows[0]); setFloatUniform("glowB", glows[1])
      setFloatUniform("glowR", glows[2]); setFloatUniform("glowG", glows[3])
      setFloatUniform("canvas", fade.red, fade.green, fade.blue)
    }
    val blur = progressiveBlur(width, height, 0f, 0f, fadeHeight, blurSigmaBottom)
    return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(glass, "content"), blur)
  }

  /**
   * 1-1 press-and-hold: the whole home blurred (σ12, applied before this shader), the
   * "Bottom" veil (#E3E3E3 32 % → 100 % top→bottom), the four additive aurora blobs
   * (yellow, blue, red plus-lighter; green normal) clipped below [auroraTop], and the
   * "Live refractive glass" ellipse refracting all of it. [appear] fades the veil in.
   */
  private const val VOICE_BACKDROP = """
    uniform shader content;
    uniform float2 size;
    uniform float density;
    uniform float appear;
    uniform float auroraTop;
    uniform float4 ellipse;
    uniform float4 blobY; uniform float4 blobB; uniform float4 blobR; uniform float4 blobG;
    uniform half3 colY; uniform half3 colB; uniform half3 colR; uniform half3 colG;
    uniform float glow;
    uniform half3 canvas;

    float blob(float2 p, float4 b) {
      float2 d = (p - b.xy) / b.zw;
      return exp(-0.5 * dot(d, d));
    }
    half3 scene(float2 p) {
      half4 c = content.eval(clamp(p, float2(0.5), size - 0.5));
      float veil = mix(0.32, 1.0, clamp(p.y / size.y, 0.0, 1.0)) * appear;
      half3 col = mix(c.rgb, canvas, veil);
      if (p.y > auroraTop) {
        float k = glow * appear * smoothstep(auroraTop, auroraTop + 24.0 * density, p.y);
        col += (colY * blob(p, blobY) + colB * blob(p, blobB) + colR * blob(p, blobR)) * k;
        col = mix(col, colG, blob(p, blobG) * 0.85 * k);
      }
      return min(col, half3(1.0));
    }
    half4 main(float2 p) {
      half3 base = scene(p);
      float2 q = (p - ellipse.xy) / ellipse.zw;
      float sd = (length(q) - 1.0) * min(ellipse.z, ellipse.w);
      if (sd > 0.0) {
        float sigma = 16.0 * density;
        base *= 1.0 - 0.08 * appear * exp(-0.5 * sd * sd / (sigma * sigma));
        if (sd > 1.0) return half4(base, 1.0);
      }
      float depth = 24.0 * density;
      float inside = max(-sd, 0.0);
      float2 n = normalize(q / ellipse.zw + 1e-5);
      float t = clamp(1.0 - inside / depth, 0.0, 1.0);
      float bend = t * t * depth * 0.45;
      float disp = t * t * 1.6 * density;
      half3 col;
      col.r = scene(p - n * (bend + disp)).r;
      col.g = scene(p - n * bend).g;
      col.b = scene(p - n * (bend - disp)).b;
      float gy = clamp((p.y - (ellipse.y - ellipse.w)) / (2.0 * ellipse.w), 0.0, 1.0);
      half3 tint = mix(canvas, half3(1.0), smoothstep(0.0, 0.5, gy));
      col = mix(col, tint, 0.04 * mix(0.64, 1.0, gy));
      float light = clamp(-n.y, 0.0, 1.0);
      col += half3((1.0 - smoothstep(0.0, 1.2 * density, inside)) * (0.12 + 0.5 * light));
      float aa = clamp(0.5 - sd, 0.0, 1.0);
      return half4(mix(base, min(col, half3(1.0)), aa * appear), 1.0);
    }
  """

  /**
   * One "Taku is working" layer (1-4/1-5 "working gradient"): a CSS/Figma inset shadow of the
   * frame's rounded rect — blur 32 (σ16), its own 4 dp offset, one colour. The inset shadow is
   * the blurred outside of the frame shifted by [offset]: ~½ at the edge, falling off as erfc,
   * slightly thicker on the side the offset points away from. 1 − Φ(z) uses the logistic
   * approximation Φ(z) ≈ 1 / (1 + e^(−1.702 z)). Output is premultiplied; the caller picks the
   * Figma blend mode (plus-lighter or normal).
   */
  private const val EDGE_GLOW = """
    uniform float2 size;
    uniform float radius;
    uniform float sigma;
    uniform float2 offset;
    uniform float strength;
    uniform half3 color;

    float sdFrame(float2 p) {
      float2 c = size * 0.5;
      float2 d = abs(p - c) - (c - radius);
      return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - radius;
    }

    half4 main(float2 p) {
      float frame = sdFrame(p);
      if (frame > 1.5) return half4(0.0);
      // Distance inside the shifted hole; the shadow is what the hole does not cover.
      float inside = -sdFrame(p - offset);
      float g = 1.0 / (1.0 + exp(1.702 * inside / sigma)) * smoothstep(0.0, 1.5, 1.5 - frame);
      float alpha = clamp(g * strength, 0.0, 1.0);
      if (alpha < 0.003) return half4(0.0);
      return half4(color * alpha, alpha);
    }
  """

  /**
   * Bottom edge of the island's text (1-4 "Live edge refraction · Bottom"): within [edge]
   * px of the bottom the content is magnified horizontally by up to (1 + flare), split
   * R/B by `dispersion · t²`, blurred progressively up to [maxSigma] and faded out, so
   * truncated text visibly continues below.
   */
  /** Holds one [VOICE_BACKDROP] shader so per-frame aurora motion only updates uniforms. */
  class VoiceBackdrop {
    private val shader = RuntimeShader(VOICE_BACKDROP)
    fun effect(
      width: Float, height: Float, density: Float, appear: Float, auroraTop: Float, ellipse: FloatArray,
      blobs: Array<FloatArray>, colors: List<androidx.compose.ui.graphics.Color>, glow: Float, canvas: androidx.compose.ui.graphics.Color, blurSigma: Float,
    ): RenderEffect {
      shader.setFloatUniform("size", width, height)
      shader.setFloatUniform("density", density)
      shader.setFloatUniform("appear", appear)
      shader.setFloatUniform("auroraTop", auroraTop)
      shader.setFloatUniform("ellipse", ellipse)
      shader.setFloatUniform("blobY", blobs[0]); shader.setFloatUniform("blobB", blobs[1])
      shader.setFloatUniform("blobR", blobs[2]); shader.setFloatUniform("blobG", blobs[3])
      listOf("colY", "colB", "colR", "colG").forEachIndexed { i, name -> colors[i].let { shader.setFloatUniform(name, it.red, it.green, it.blue) } }
      shader.setFloatUniform("glow", glow)
      shader.setFloatUniform("canvas", canvas.red, canvas.green, canvas.blue)
      return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"), gaussian(blurSigma * appear))
    }
  }

  /** One [EDGE_GLOW] layer; update per frame and draw with a ShaderBrush and the layer's blend mode. */
  class EdgeGlow {
    val shader = RuntimeShader(EDGE_GLOW)
    /** [offsetX]/[offsetY] are the Figma shadow offset in px (e.g. green 0, −4 dp). */
    fun update(width: Float, height: Float, radius: Float, density: Float, offsetX: Float, offsetY: Float, strength: Float, color: androidx.compose.ui.graphics.Color) {
      shader.setFloatUniform("size", width, height)
      shader.setFloatUniform("radius", radius)
      shader.setFloatUniform("sigma", 16f * density)
      shader.setFloatUniform("offset", offsetX, offsetY)
      shader.setFloatUniform("strength", strength)
      shader.setFloatUniform("color", color.red, color.green, color.blue)
    }
  }

  /** Uniform Gaussian blur from a Figma/CSS sigma in px (Android's blur takes a Skia radius). */
  fun gaussian(sigmaPx: Float): RenderEffect {
    val radius = ((sigmaPx - 0.5f) / 0.57735f).coerceAtLeast(0.01f)
    return RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
  }
}
