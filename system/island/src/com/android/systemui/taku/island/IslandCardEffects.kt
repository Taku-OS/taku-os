/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.graphics.RenderEffect
import android.graphics.RuntimeShader

/** Native AGSL equivalents of the authored edge-only Figma shaders. */
internal object IslandCardEffects {
  /** Figma 297:432: the authored 16 dp right-edge region, flare .25 / dispersion .4.
   * Equivalent to the WGSL's three channel samples; no blur across the title or card.
   * Explicit bounds are essential: clamping outside the layer extrudes a vertical strip.
   */
  fun chipEdge(width: Float, height: Float, density: Float): RenderEffect {
    val shader = RuntimeShader("""
      uniform shader content;
      uniform float2 size;
      uniform float edge;
      uniform float dispersion;
      half4 sampleAt(float2 p) { return content.eval(clamp(p, float2(0.5), size - 0.5)); }
      half4 main(float2 p) {
        if (p.x < 0.0 || p.y < 0.0 || p.x >= size.x || p.y >= size.y) return half4(0.0);
        if (p.x < size.x - edge) return content.eval(p);
        float t = smoothstep(0.0, 1.0, (p.x - (size.x - edge)) / edge);
        float stretch = 1.0 + 0.25 * t * t;
        float2 q = float2(p.x, size.y * 0.5 + (p.y - size.y * 0.5) / stretch);
        float shift = dispersion * t * t;
        half4 r = sampleAt(q + float2(0.0, shift));
        half4 g = sampleAt(q);
        half4 b = sampleAt(q - float2(0.0, shift));
        return half4(r.r, g.g, b.b, max(r.a, max(g.a, b.a)));
      }
    """).apply {
      setFloatUniform("size", width, height)
      setFloatUniform("edge", 16f * density)
      setFloatUniform("dispersion", .4f * density)
    }
    return RenderEffect.createRuntimeShaderEffect(shader, "content")
  }
  private const val BOTTOM_EDGE = """
    uniform shader content;
    uniform float2 size;
    uniform float edge;
    uniform float flare;
    uniform float dispersion;
    uniform float maxSigma;
    uniform float fade;
    half4 tap(float2 p) { return content.eval(clamp(p, float2(0.5), size - 0.5)); }
    half4 main(float2 p) {
      float d = size.y - p.y;
      if (d >= edge) return content.eval(p);
      float t = smoothstep(0.0, 1.0, 1.0 - d / edge);
      float stretch = 1.0 + flare * t * t;
      float cx = size.x * 0.5;
      float qx = cx + (p.x - cx) / stretch;
      float shift = dispersion * t * t;
      float sigma = maxSigma * t;
      half4 acc = half4(0.0);
      float wsum = 0.0;
      for (int i = -4; i <= 4; i++) {
        float y = float(i) * max(sigma, 0.01) * 0.5;
        float w = exp(-0.125 * float(i * i));
        half4 r = tap(float2(qx + shift, p.y + y));
        half4 g = tap(float2(qx, p.y + y));
        half4 b = tap(float2(qx - shift, p.y + y));
        acc += half4(r.r, g.g, b.b, max(r.a, max(g.a, b.a))) * w;
        wsum += w;
      }
      return acc / wsum * (1.0 - fade * t);
    }
  """

  fun bottomEdge(width: Float, height: Float, density: Float, edge: Float = 32f * density, fade: Float = 1f): RenderEffect {
    val shader = RuntimeShader(BOTTOM_EDGE).apply {
      setFloatUniform("size", width, height)
      setFloatUniform("edge", edge)
      setFloatUniform("flare", 0.12f)
      setFloatUniform("dispersion", 0.4f * density)
      setFloatUniform("maxSigma", 2f * density)
      setFloatUniform("fade", fade)
    }
    return RenderEffect.createRuntimeShaderEffect(shader, "content")
  }

}
