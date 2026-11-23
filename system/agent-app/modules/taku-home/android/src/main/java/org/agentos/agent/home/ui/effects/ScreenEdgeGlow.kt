package org.agentos.agent.home.ui.effects

import android.graphics.RuntimeShader
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Native AGSL port of the designer's screen-edge-glow; see working-glow.source.json. */
internal class ScreenEdgeGlow {
  val shader = RuntimeShader(SOURCE)
  private var geometry: List<Float>? = null

  init {
    shader.setFloatUniform("uCol", floatArrayOf(.2333f, 1f, 0f, .5098f, .8366f, 1f, 1f, .3991f, .2329f, .28f, .4f, 1f))
    shader.setFloatUniform("uOff", floatArrayOf(0f, -4f, 4f, 0f, -4f, 0f, 0f, 4f))
    shader.setFloatUniform("uDodge", floatArrayOf(1f, 0f, 0f, 1f))
    shader.setFloatUniform("uSigma", 13.5f)
    shader.setFloatUniform("uFloor", .34f)
    shader.setFloatUniform("uWidth", 20f, 150f)
    shader.setFloatUniform("uReach", .5f, 1.45f)
    shader.setFloatUniform("uDrift", 90f)
    shader.setFloatUniform("uPk", PACKETS.flatMap { it.take(4) }.map { it.toFloat() }.toFloatArray())
    shader.setFloatUniform("uPkA", PACKETS.map { it[4].toFloat() }.toFloatArray())
  }

  /** Geometry is physical pixels; the handoff's CSS px become Android dp. */
  fun update(width: Float, height: Float, radius: Float, density: Float, time: Float, level: Float,
             still: Boolean = false, windowAlpha: Float = 1f) {
    val w = width / density; val h = height / density
    val r = (radius / density).coerceIn(0f, minOf(w, h) / 2f)
    val next = listOf(w, h, r)
    if (geometry != next) {
      geometry = next
      shader.setFloatUniform("uSize", w, h)
      shader.setFloatUniform("uRadius", r)
      shader.setFloatUniform("uNorm", normaliser(w.toDouble(), h.toDouble(), r.toDouble()))
    }
    shader.setFloatUniform("uDensity", density)
    shader.setFloatUniform("uPx", density)
    shader.setFloatUniform("uWindowAlpha", windowAlpha)
    shader.setFloatUniform("uTime", time)
    shader.setFloatUniform("uLevel", level)
    shader.setFloatUniform("uFlow", if (still) 0f else 1f)
  }

  companion object {
    private val PACKETS = arrayOf(
      doubleArrayOf(.05, 118.0, .47, 0.0, 1.0), doubleArrayOf(.21, -86.0, .61, 1.7, .9),
      doubleArrayOf(.37, 64.0, .39, 3.1, 1.1), doubleArrayOf(.52, 152.0, .83, .6, .8),
      doubleArrayOf(.66, -131.0, .52, 2.4, .95), doubleArrayOf(.81, 92.0, .71, 4.2, 1.0),
      doubleArrayOf(.93, -58.0, .33, 5.0, .85),
    )

    private fun normaliser(w: Double, h: Double, r: Double): Float {
      val per = 2 * (w - 2 * r) + 2 * (h - 2 * r) + 2 * PI * r
      var sum = 0.0
      for (i in 0 until 64) for (j in 0 until 200) {
        val s = i / 64.0 * per; val t = j * .37
        var b = .34
        for (k in PACKETS) {
          val c = k[0] * per + k[1] * t + 46 * sin(.29 * t + k[3])
          val width = 20 + 130 * (.5 + .5 * sin(k[2] * t + k[3]))
          val delta = s - c
          val d = delta - per * floor(delta / per + .5)
          b += k[4] * minOf(1.6, sqrt(44 / width)) * exp(-.5 * (d / width) * (d / width))
        }
        sum += b
      }
      return (12800 / sum).toFloat()
    }

    private const val SOURCE = """
uniform float2 uSize;      // screen, CSS px
uniform float uRadius;
uniform float uDensity;
uniform float uWindowAlpha;
uniform float uPx;       // device px per CSS px
uniform float uTime;     // s, continuous
uniform float uLevel;    // 0–1
uniform float uFlow;     // 1 = flowing, 0 = Figma's static light
uniform float uNorm;     // 1 / time-mean of the packet field
uniform float3 uCol[4];
uniform float2 uOff[4];
uniform float uDodge[4];
uniform float uSigma;
uniform float uFloor;
uniform float2 uWidth;     // packet width range, px
uniform float2 uReach;     // depth scale filament / bloom
uniform float uDrift;    // colour drift, px
uniform float4 uPk[7];   // s0, speed, breathe, phase
uniform float uPkA[7]; // amplitude
const float PI = 3.14159265;
float2 H; float2 IN; float R, LX, LY, ARC, PER;

float sdRB(float2 p) {
  float2 q = abs(p) - H + R;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - R;
}
float erf_(float x) {
  float t = 1.0 / (1.0 + 0.3275911 * abs(x));
  float y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x);
  return sign(x) * y;
}
float Phi(float x) { return 0.5 * (1.0 + erf_(x * 0.70710678)); }

/* Arc length (clockwise from the top edge's left end) of the boundary point
   nearest p. Continuous through every corner. */
float arcOf(float2 p) {
  bool cx = abs(p.x) > IN.x, cy = abs(p.y) > IN.y;
  if (cx && cy) {
    float2 c = float2(sign(p.x) * IN.x, sign(p.y) * IN.y);
    float a = atan(p.y - c.y, p.x - c.x);                 // y down
    if (p.x > 0.0 && p.y < 0.0) return LX + (a + 0.5 * PI) * R;               // TR
    if (p.x > 0.0) return LX + ARC + LY + a * R;                               // BR
    if (p.y > 0.0) return 2.0 * LX + 2.0 * ARC + LY + (a - 0.5 * PI) * R;      // BL
    return 2.0 * LX + 3.0 * ARC + 2.0 * LY + (a < 0.0 ? a + 2.0 * PI : a) * R - PI * R; // TL
  }
  float dx = H.x - abs(p.x), dy = H.y - abs(p.y);
  if (dy < dx || cy) {
    return p.y < 0.0 ? p.x + IN.x : LX + 2.0 * ARC + LY + (IN.x - p.x);
  }
  return p.x > 0.0 ? LX + ARC + (p.y + IN.y) : 2.0 * LX + 3.0 * ARC + LY + (IN.y - p.y);
}
/* Outward normal of the boundary at arc s. */
float2 normalAt(float s) {
  s = mod(s, PER);
  if (s < LX) return float2(0.0, -1.0);
  s -= LX;
  if (s < ARC) { float a = -0.5 * PI + s / R; return float2(cos(a), sin(a)); }
  s -= ARC;
  if (s < LY) return float2(1.0, 0.0);
  s -= LY;
  if (s < ARC) { float a = s / R; return float2(cos(a), sin(a)); }
  s -= ARC;
  if (s < LX) return float2(0.0, 1.0);
  s -= LX;
  if (s < ARC) { float a = 0.5 * PI + s / R; return float2(cos(a), sin(a)); }
  s -= ARC;
  if (s < LY) return float2(-1.0, 0.0);
  s -= LY;
  float a = PI + s / R;
  return float2(cos(a), sin(a));
}

half4 main(float2 position) {
  H = uSize * 0.5; R = uRadius; IN = H - R;
  LX = 2.0 * IN.x; LY = 2.0 * IN.y; ARC = 0.5 * PI * R; PER = 2.0 * LX + 2.0 * LY + 4.0 * ARC;
  float2 p = position / uDensity - H;
  float sd = sdRB(p);
  float inside = clamp(0.5 - sd * uPx, 0.0, 1.0);
  if (inside <= 0.0 || sd < -110.0 || uLevel <= 0.0005) return half4(0.0);

  float t = uTime;
  float s = arcOf(p);

  /* Packet field: brightness B and the local packet width. */
  float B = 1.0, reachK = 1.0, drift = 0.0;
  if (uFlow > 0.0) {
    float b = uFloor, gw = 0.0, gs = 0.0;
    for (int i = 0; i < 7; i++) {
      float4 k = uPk[i];
      float c = k.x * PER + k.y * t + 46.0 * sin(0.29 * t + k.w);
      float w = mix(uWidth.x, uWidth.y, 0.5 + 0.5 * sin(k.z * t + k.w));
      float d = s - c;
      d -= PER * floor(d / PER + 0.5);
      float g = exp(-0.5 * (d / w) * (d / w));
      b += uPkA[i] * min(1.6, sqrt(uWidth.x * 2.2 / w)) * g;
      gw += g * w; gs += g;
    }
    float bloom = smoothstep(uWidth.x, uWidth.y, gw / max(gs, 1e-4));
    B = mix(1.0, b * uNorm, uFlow);
    reachK = mix(1.0, mix(uReach.x, uReach.y, bloom), clamp(gs, 0.0, 1.0) * uFlow);
    drift = uFlow * uDrift * (0.62 * sin(2.0 * PI * 2.0 * s / PER + 0.23 * t) + 0.38 * sin(2.0 * PI * 3.0 * s / PER - 0.31 * t + 1.1));
  }
  float2 n = normalAt(s + drift);
  /* The inner contour flows a little (never at the very edge). */
  float warp = uFlow * 2.6 * sin(-sd * 0.16 + s * 0.027 - 1.4 * t) * (1.0 - smoothstep(-18.0, -1.0, sd));
  float sigma = uSigma * reachK * (0.7 + 0.3 * uLevel);

  /* Figma's stack, folded into  out = background · A + K. */
  float3 K = float3(0.0);
  float A = 1.0;
  for (int i = 0; i < 4; i++) {
    float sdk = sd - dot(uOff[i], n) + warp;
    float a = clamp(Phi(sdk / sigma) * B * uLevel, 0.0, 1.0) * inside;
    if (uDodge[i] > 0.5) {
      K += uCol[i] * a;
    } else {
      K = K * (1.0 - a) + uCol[i] * a;
      A *= 1.0 - a;
    }
  }
  // Same folded premultiplied light as the designer's WebGL canvas. Additive
  // layers contribute RGB without adding normal-layer coverage.
  return half4(min(K, float3(1.0)), 1.0 - A) / uWindowAlpha;
}
    """
  }
}
