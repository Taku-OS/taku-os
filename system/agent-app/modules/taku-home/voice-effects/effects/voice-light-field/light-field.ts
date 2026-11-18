/**
 * The voice light: Figma's blurred light layers, re-rendered as one flowing
 * field in a small WebGL shader.
 *
 * Material (exact, from the Figma nodes — not sampled from screenshots):
 *
 *   slot  node        listening 462:13264/13395      Live Talk 462:13323/15118/15374/15552
 *   0     Vector 4    #FBFF00  linear dodge          #FBFF2A  linear dodge
 *   1     Vector 5    #FF0000  linear dodge (180°)   #E94E17  lighten (180°)
 *   2     Vector 3    #5ED100  normal                #A8FF61  lighten
 *   3     Vector 2    #002FFF  linear dodge (180°)   #3300FF  linear dodge (180°)
 *   4     Vector 6    —                              #619DFF  lighten
 *   5     Vector 7    —                              #FF6163  lighten (180°)
 *
 * Every layer is a solid fill at 100% with a 58px layer blur, composited in
 * Figma's bottom-to-top order over the sheet behind it (#E3E3E3 under the
 * listening glass, the 92% black backdrop in Live Talk). Each layer's shape
 * is Figma's own blurred export (the `listen-glow-*` / `live-glow-*` SVGs),
 * used as a soft alpha mask.
 *
 * Motion: the masks are never just moved or faded. Every pixel samples them
 * through
 *   1. a shared, slowly evolving curl flow (low-frequency, divergence-free,
 *      so contours bend and curl without tearing),
 *   2. a per-layer warp with its own seed (neighbouring colours deform
 *      differently, so they merge and separate),
 *   3. a per-layer travel along the bottom (1D gradient noise, never a
 *      repeating sine), and
 *   4. a vertical reach anchored below the screen, raised locally by
 *      travelling crests that rise, widen and dissolve.
 * All of it is driven by `flow` (always advancing) and `level` (speech,
 * smoothed): level adds reach, deformation and a little luminosity; flow
 * speed comes from the timeline. Nothing is a function of wall time, so a
 * scrubbed frame or an exported video reproduces the same light.
 *
 * One WebGL context renders into an offscreen canvas and is copied into the
 * 2D canvas of each target (listening sheet, Live Talk) that is visible.
 */
import { LIGHT_MASKS as A } from './masks';

type Blend = 0 | 1 | 2; // normal, linear dodge (plus-lighter), lighten
const NORMAL: Blend = 0;
const ADD: Blend = 1;
const LIGHTEN: Blend = 2;

interface LayerDef {
  src: string;
  /** Figma node box in phone px (the export adds 58px bleed on each side). */
  x: number;
  y: number;
  w: number;
  h: number;
  color: string;
  blend: Blend;
  /** Rotated 180° in Figma: the export is drawn mirrored. */
  flip?: boolean;
}

const BLEED = 58;

const LISTEN: readonly (LayerDef | null)[] = [
  {
    src: A.listenGlow4,
    x: 147.54,
    y: 816.11,
    w: 130.783,
    h: 188.424,
    color: '#FBFF00',
    blend: ADD,
  },
  {
    src: A.listenGlow5,
    x: 6.77,
    y: 889.52,
    w: 400.224,
    h: 74.823,
    color: '#FF0000',
    blend: ADD,
    flip: true,
  },
  { src: A.listenGlow3, x: 128, y: 869, w: 201, h: 97, color: '#5ED100', blend: NORMAL },
  {
    src: A.listenGlow2,
    x: 42.12,
    y: 821.25,
    w: 326.877,
    h: 154.753,
    color: '#002FFF',
    blend: ADD,
    flip: true,
  },
  null,
  null,
];

const LIVE: readonly LayerDef[] = [
  { src: A.liveGlow4, x: 168, y: 882, w: 130, h: 63, color: '#FBFF2A', blend: ADD },
  {
    src: A.liveGlow5,
    x: -21.33,
    y: 877.5,
    w: 428.272,
    h: 74.842,
    color: '#E94E17',
    blend: LIGHTEN,
    flip: true,
  },
  { src: A.liveGlow3, x: 128, y: 865, w: 201, h: 97, color: '#A8FF61', blend: LIGHTEN },
  {
    src: A.liveGlow2,
    x: 42.12,
    y: 817.247,
    w: 326.877,
    h: 154.753,
    color: '#3300FF',
    blend: ADD,
    flip: true,
  },
  { src: A.liveGlow6, x: -54.79, y: 781, w: 187.294, h: 184.288, color: '#619DFF', blend: LIGHTEN },
  {
    src: A.liveGlow7,
    x: 284.703,
    y: 749,
    w: 178.407,
    h: 216.288,
    color: '#FF6163',
    blend: LIGHTEN,
    flip: true,
  },
];

const SLOTS = 6;

/** The field's region in phone px: full width, from y 560 to the bottom. */
export const FIELD = { x: 0, y: 560, w: 412, h: 357 } as const;
/** Field pixels per phone px. The light is a 58px blur, so half
    resolution is visually lossless; the browser's bilinear upscale is smooth. */
const RES = 0.5;
/** Vertical reach is a stretch about this line (below the screen edge). */
const ANCHOR_Y = 960;
const LISTEN_CLIP_Y = 705;

/** The measured sheet colour behind each target (sRGB 0–1). */
export const BACKDROP = {
  light: [227 / 255, 227 / 255, 228 / 255],
  dark: [18 / 255, 19 / 255, 19 / 255],
} as const;

export type LightTarget = 'listen' | 'live';

export interface LightFrame {
  /** Flow clock (flow seconds). */
  flow: number;
  /** Smoothed speech energy, 0…1. */
  level: number;
  /** 0 = listening geometry, 1 = Live Talk geometry. */
  mix: number;
}

/* ---------------------------------------------------- deterministic noise */

function hash(n: number): number {
  const s = Math.sin(n * 157.31 + 47.13) * 43758.5453;
  return s - Math.floor(s);
}

/** 1D gradient noise, ≈ −1…1, C2-smooth. */
function noise1(x: number): number {
  const i = Math.floor(x);
  const f = x - i;
  const g0 = (hash(i) * 2 - 1) * f;
  const g1 = (hash(i + 1) * 2 - 1) * (f - 1);
  const u = f * f * f * (f * (f * 6 - 15) + 10);
  return 2 * (g0 + (g1 - g0) * u);
}

const smooth = (a: number, b: number, x: number) => {
  const t = Math.min(1, Math.max(0, (x - a) / (b - a)));
  return t * t * (3 - 2 * t);
};

/** Per-frame motion parameters that depend only on time: travel offsets per
    layer and the three travelling crests. */
export function motionAt(frame: LightFrame) {
  const { flow: f, level: L } = frame;
  const travel: number[] = [];
  for (let i = 0; i < SLOTS; i++) {
    /* Horizontal travel ±~70px over ~1.5 flow-seconds; small vertical bob. */
    travel.push(68 * noise1(f * 0.62 + 13.7 * i + 0.3), 9 * noise1(f * 0.8 + 5.3 * i + 41));
  }
  const crests: number[] = [];
  for (let j = 0; j < 3; j++) {
    const x = 206 + 210 * noise1(f * 0.34 + 9.1 * j + 3.3);
    const life = smooth(0.05, 0.75, noise1(f * 0.58 + 6.7 * j + 21.4));
    const h = life * (0.1 + 0.3 * L);
    const w = 52 + 70 * life;
    crests.push(x, h, w, 0);
  }
  return { travel, crests };
}

/* ------------------------------------------------------------- shaders */

const VERT = `
attribute vec2 aPos;
varying vec2 vUv;
void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }
`;

const FRAG = `
precision highp float;
varying vec2 vUv;
uniform sampler2D uAtlas;
uniform vec4 uField;          // x, y, w, h in phone px
uniform float uFlow, uLevel, uMix;
uniform vec3 uBack;
uniform vec4 uRectA[6], uRectB[6];  // listening / Live geometry (bleed box)
uniform vec4 uUvA[6], uUvB[6];      // atlas cells
uniform vec3 uCol[6];
uniform vec3 uLayer[6];             // blend, presence, maskMix
uniform vec2 uTravel[6];
uniform vec4 uCrest[3];             // x, height, width, -
uniform float uAnchor;
uniform vec2 uFadeTop;              // light fades in over [x, y] (phone px)

float h3(vec3 p) { return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453); }
float vnoise(vec3 p) {
  vec3 i = floor(p); vec3 f = fract(p);
  vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
  float a = mix(mix(h3(i), h3(i + vec3(1,0,0)), u.x), mix(h3(i + vec3(0,1,0)), h3(i + vec3(1,1,0)), u.x), u.y);
  float b = mix(mix(h3(i + vec3(0,0,1)), h3(i + vec3(1,0,1)), u.x), mix(h3(i + vec3(0,1,1)), h3(i + vec3(1,1,1)), u.x), u.y);
  return mix(a, b, u.z);
}
// Divergence-free flow from a noise potential: contours curl, never tear.
vec2 curl(vec2 p, float z) {
  const float e = 0.2;
  float a = vnoise(vec3(p + vec2(0.0, e), z)), b = vnoise(vec3(p - vec2(0.0, e), z));
  float c = vnoise(vec3(p + vec2(e, 0.0), z)), d = vnoise(vec3(p - vec2(e, 0.0), z));
  return vec2(a - b, d - c) / (2.0 * e);
}
vec2 warp2(vec2 p, float z) {
  return vec2(vnoise(vec3(p, z)), vnoise(vec3(p + 17.3, z + 3.1))) * 2.0 - 1.0;
}
float cell(vec4 uv, vec2 l) {
  if (l.x <= 0.0 || l.y <= 0.0 || l.x >= 1.0 || l.y >= 1.0) return 0.0;
  return texture2D(uAtlas, uv.xy + l * uv.zw).a;
}

void main() {
  vec2 p = uField.xy + vec2(vUv.x, 1.0 - vUv.y) * uField.zw;
  float L = uLevel;

  // Reach: a stretch about the anchor line, raised locally by the crests.
  float reach = 0.95 + 0.2 * L;
  float crestGlow = 0.0;
  for (int j = 0; j < 3; j++) {
    float g = exp(-pow((p.x - uCrest[j].x) / uCrest[j].z, 2.0));
    reach += uCrest[j].y * g;
    crestGlow += uCrest[j].y * g;
  }

  // Shared slow curl flow (wavelength ~170px).
  vec2 flowW = curl(p / 170.0, uFlow * 0.24) * (11.0 + 17.0 * L);
  float lum = 0.86 + 0.14 * L + 0.22 * crestGlow;

  vec3 R = uBack;
  for (int i = 0; i < 6; i++) {
    vec3 lay = uLayer[i];
    if (lay.y <= 0.0) continue;
    vec2 q = p + flowW + warp2(p / 125.0 + float(i) * 4.7, uFlow * 0.3 + float(i) * 1.9) * (12.0 + 14.0 * L);
    q -= uTravel[i];
    q.y = uAnchor + (q.y - uAnchor) / reach;
    vec4 rA = uRectA[i]; vec4 rB = uRectB[i];
    vec4 r = mix(rA, rB, uMix);
    vec2 l = (q - r.xy) / r.zw;
    float m = mix(cell(uUvA[i], l), cell(uUvB[i], l), lay.z);
    float a = clamp(m * lay.y * lum, 0.0, 1.0);
    vec3 c = uCol[i];
    if (lay.x < 0.5) R = mix(R, c, a);
    else if (lay.x < 1.5) R = min(R + c * a, 1.0);
    else R = mix(R, max(R, c), a);
  }

  // Express the composite as the least-opaque layer that reproduces it over
  // the measured sheet colour, so the canvas composites normally on the page.
  R = mix(uBack, R, smoothstep(uFadeTop.x, uFadeTop.y, p.y));
  vec3 up = max(R - uBack, 0.0) / max(1.0 - uBack, 1e-3);
  vec3 dn = max(uBack - R, 0.0) / max(uBack, 1e-3);
  float alpha = clamp(max(max(max(up.r, up.g), up.b), max(max(dn.r, dn.g), dn.b)), 0.0, 1.0);
  vec3 col = clamp(R - uBack * (1.0 - alpha), 0.0, alpha);
  // Sub-LSB dither: keeps the long soft gradients free of 8-bit banding.
  float d = (h3(vec3(gl_FragCoord.xy, 0.0)) - 0.5) / 255.0;
  gl_FragColor = vec4(clamp(col + d * alpha, 0.0, 1.0), alpha);
}
`;

/* ------------------------------------------------------------ renderer */

function hexRgb(hex: string): [number, number, number] {
  const n = parseInt(hex.slice(1), 16);
  return [((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255];
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = reject;
    img.src = src;
  });
}

interface Cell {
  u: number;
  v: number;
  du: number;
  dv: number;
}

const ATLAS_SCALE = 0.5;

/** Packs the blurred Figma exports (mirrored where Figma rotates 180°) into
    one alpha atlas. */
async function buildAtlas(defs: readonly LayerDef[]) {
  const images = await Promise.all(defs.map((d) => loadImage(d.src)));
  const W = 1024;
  const cells: Cell[] = [];
  const place: { x: number; y: number; w: number; h: number }[] = [];
  let x = 1;
  let y = 1;
  let rowH = 0;
  for (const d of defs) {
    const w = Math.ceil((d.w + BLEED * 2) * ATLAS_SCALE);
    const h = Math.ceil((d.h + BLEED * 2) * ATLAS_SCALE);
    if (x + w + 1 > W) {
      x = 1;
      y += rowH + 2;
      rowH = 0;
    }
    place.push({ x, y, w, h });
    x += w + 2;
    rowH = Math.max(rowH, h);
  }
  const H = y + rowH + 1;
  const canvas = document.createElement('canvas');
  canvas.width = W;
  canvas.height = H;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('2d context unavailable');
  defs.forEach((d, i) => {
    const pl = place[i]!;
    ctx.save();
    if (d.flip) {
      ctx.translate(pl.x + pl.w, pl.y);
      ctx.scale(-1, 1);
      ctx.drawImage(images[i]!, 0, 0, pl.w, pl.h);
    } else {
      ctx.drawImage(images[i]!, pl.x, pl.y, pl.w, pl.h);
    }
    ctx.restore();
    cells.push({ u: pl.x / W, v: pl.y / H, du: pl.w / W, dv: pl.h / H });
  });
  return { canvas, cells };
}

interface Uniforms {
  [name: string]: WebGLUniformLocation | null;
}

export class LightField {
  private gl: WebGLRenderingContext;
  private canvas: HTMLCanvasElement;
  private program: WebGLProgram;
  private u: Uniforms = {};
  private ready = false;
  private disposed = false;
  /** Lost without a dispose (GPU reset, or the browser over its context
      limit): stops drawing, and `acquireLightField` makes a new one. */
  private lost = false;
  private cellsA: Cell[] = [];
  private cellsB: Cell[] = [];

  /** Throws when WebGL is unavailable; callers fall back to the static layers. */
  constructor(onReady?: () => void, onLost?: () => void) {
    this.canvas = document.createElement('canvas');
    this.canvas.width = Math.round(FIELD.w * RES);
    this.canvas.height = Math.round(FIELD.h * RES);
    const gl = this.canvas.getContext('webgl', {
      premultipliedAlpha: true,
      alpha: true,
      antialias: false,
      preserveDrawingBuffer: true,
    });
    if (!gl) throw new Error('WebGL unavailable');
    this.gl = gl;
    this.canvas.addEventListener('webglcontextlost', () => {
      this.lost = true;
      /* Not for our own dispose: nobody is listening by then. */
      if (!this.disposed) onLost?.();
    });
    this.program = this.link();
    const buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    const loc = gl.getAttribLocation(this.program, 'aPos');
    gl.enableVertexAttribArray(loc);
    gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);

    const defs: LayerDef[] = [...LIVE];
    LISTEN.forEach((d) => d && defs.push(d));
    void buildAtlas(defs)
      .then(({ canvas, cells }) => {
        if (this.disposed) return;
        const tex = gl.createTexture();
        gl.bindTexture(gl.TEXTURE_2D, tex);
        gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, canvas);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        this.cellsB = cells.slice(0, LIVE.length);
        let k = LIVE.length;
        this.cellsA = LISTEN.map((d, i) => (d ? cells[k++]! : this.cellsB[i]!));
        this.ready = true;
        onReady?.();
      })
      .catch(() => undefined);
  }

  get isReady() {
    return this.ready && !this.lost && !this.disposed;
  }

  get isUsable() {
    return !this.lost && !this.disposed;
  }

  private link(): WebGLProgram {
    const gl = this.gl;
    const compile = (type: number, src: string) => {
      const sh = gl.createShader(type)!;
      gl.shaderSource(sh, src);
      gl.compileShader(sh);
      if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
        throw new Error(gl.getShaderInfoLog(sh) ?? 'shader compile failed');
      }
      return sh;
    };
    const program = gl.createProgram()!;
    gl.attachShader(program, compile(gl.VERTEX_SHADER, VERT));
    gl.attachShader(program, compile(gl.FRAGMENT_SHADER, FRAG));
    gl.linkProgram(program);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
      throw new Error(gl.getProgramInfoLog(program) ?? 'link failed');
    }
    gl.useProgram(program);
    for (const name of [
      'uAtlas',
      'uField',
      'uFlow',
      'uLevel',
      'uMix',
      'uBack',
      'uRectA',
      'uRectB',
      'uUvA',
      'uUvB',
      'uCol',
      'uLayer',
      'uTravel',
      'uCrest',
      'uAnchor',
      'uFadeTop',
    ]) {
      this.u[name] = gl.getUniformLocation(
        program,
        name.startsWith('uRect') ||
          name.startsWith('uUv') ||
          ['uCol', 'uLayer', 'uTravel', 'uCrest'].includes(name)
          ? `${name}[0]`
          : name,
      );
    }
    return program;
  }

  /**
   * Renders the field for one target and copies it into `out` (a 2D canvas
   * whose buffer this sizes). Returns false until the masks are loaded.
   */
  draw(out: HTMLCanvasElement, target: LightTarget, frame: LightFrame): boolean {
    if (!this.isReady) return false;
    const gl = this.gl;
    const dark = target === 'live';
    const mix = Math.min(1, Math.max(0, frame.mix));
    const { travel, crests } = motionAt(frame);

    const rect = (d: LayerDef) => [d.x - BLEED, d.y - BLEED, d.w + BLEED * 2, d.h + BLEED * 2];
    const rectA: number[] = [];
    const rectB: number[] = [];
    const uvA: number[] = [];
    const uvB: number[] = [];
    const col: number[] = [];
    const layer: number[] = [];
    for (let i = 0; i < SLOTS; i++) {
      const b = LIVE[i]!;
      const a = LISTEN[i] ?? b;
      rectA.push(...rect(a));
      rectB.push(...rect(b));
      const ca = this.cellsA[i]!;
      const cb = this.cellsB[i]!;
      uvA.push(ca.u, ca.v, ca.du, ca.dv);
      uvB.push(cb.u, cb.v, cb.du, cb.dv);
      /* Material: the target's own palette and blend modes; geometry and
         mask shape morph by `mix`. Live-only layers belong to the dark
         material only. */
      const m = dark ? b : LISTEN[i];
      col.push(...hexRgb(m ? m.color : '#000000'));
      layer.push(m ? m.blend : 0, m ? 1 : 0, LISTEN[i] ? mix : 1);
    }

    gl.viewport(0, 0, this.canvas.width, this.canvas.height);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
    const u = this.u;
    gl.uniform1i(u.uAtlas!, 0);
    gl.uniform4f(u.uField!, FIELD.x, FIELD.y, FIELD.w, FIELD.h);
    gl.uniform1f(u.uFlow!, frame.flow);
    gl.uniform1f(u.uLevel!, Math.min(1, Math.max(0, frame.level)));
    gl.uniform1f(u.uMix!, mix);
    gl.uniform1f(u.uAnchor!, ANCHOR_Y);
    /* The listening light lives in Figma's clipped group (top at phone y
       705): it fades out before that edge so the clip never shows. */
    if (dark) gl.uniform2f(u.uFadeTop!, -1e4, -9e3);
    else gl.uniform2f(u.uFadeTop!, LISTEN_CLIP_Y, LISTEN_CLIP_Y + 80);
    const back = dark ? BACKDROP.dark : BACKDROP.light;
    gl.uniform3f(u.uBack!, back[0], back[1], back[2]);
    gl.uniform4fv(u.uRectA!, rectA);
    gl.uniform4fv(u.uRectB!, rectB);
    gl.uniform4fv(u.uUvA!, uvA);
    gl.uniform4fv(u.uUvB!, uvB);
    gl.uniform3fv(u.uCol!, col);
    gl.uniform3fv(u.uLayer!, layer);
    gl.uniform2fv(u.uTravel!, travel);
    gl.uniform4fv(u.uCrest!, crests);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);

    if (out.width !== this.canvas.width || out.height !== this.canvas.height) {
      out.width = this.canvas.width;
      out.height = this.canvas.height;
    }
    const ctx = out.getContext('2d');
    if (!ctx) return false;
    ctx.clearRect(0, 0, out.width, out.height);
    ctx.drawImage(this.canvas, 0, 0);
    return true;
  }

  dispose() {
    this.disposed = true;
    this.gl.getExtension('WEBGL_lose_context')?.loseContext();
  }
}

/**
 * The page's one light renderer. Every phone (and the Voice Light preview)
 * draws through the same field: `draw` sets every uniform it uses and copies
 * its result into the caller's own 2D canvas before returning, so nothing of
 * one caller's frame survives into the next. One WebGL context per page, then,
 * however many phones a story or docs page mounts — Chrome keeps only 16 and
 * drops the oldest beyond that.
 *
 * `onReady` runs once the masks are loaded (on a later task if they already
 * are). `release` is idempotent; the context is disposed one task after the
 * last release, unless it was acquired again. If the context is lost (a GPU
 * reset), `onLost` tells every holder to fall back to its static layers, and
 * the next acquire builds a new field. Throws when WebGL is
 * unavailable; callers fall back to the static layers.
 */
let shared: {
  field: LightField;
  waiting: Set<() => void>;
  losing: Set<() => void>;
  users: number;
} | null = null;

export function acquireLightField(
  onReady?: () => void,
  onLost?: () => void,
): {
  field: LightField;
  release: () => void;
} {
  if (!shared || !shared.field.isUsable) {
    const waiting = new Set<() => void>();
    const losing = new Set<() => void>();
    const field = new LightField(
      () => {
        for (const ready of [...waiting]) ready();
        waiting.clear();
      },
      () => {
        for (const lost of [...losing]) lost();
      },
    );
    shared = { field, waiting, losing, users: 0 };
  }
  const entry = shared;
  entry.users++;
  /* A distinct function per acquire, so two callers passing the same
     callback are still notified and released separately. */
  const ready = () => onReady?.();
  const lost = () => onLost?.();
  let released = false;
  if (entry.field.isReady) setTimeout(() => !released && ready(), 0);
  else entry.waiting.add(ready);
  entry.losing.add(lost);
  return {
    field: entry.field,
    release: () => {
      if (released) return;
      released = true;
      entry.waiting.delete(ready);
      entry.losing.delete(lost);
      entry.users--;
      /* One task later: React runs every cleanup of a commit before any new
         effect, so a phone replacing another (scrolling a review sheet,
         switching stories) would otherwise tear the context down and build
         it again in the same frame. */
      if (entry.users === 0)
        setTimeout(() => {
          if (entry.users > 0) return;
          entry.field.dispose();
          if (shared === entry) shared = null;
        }, 0);
    },
  };
}
