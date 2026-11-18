/** Network SVGs are static drawings, never containers for another network request. */
export const MAX_REMOTE_SVG = 256 * 1024;
const tags = new Set(['svg', 'g', 'defs', 'path', 'rect', 'circle', 'ellipse', 'line', 'polyline',
  'polygon', 'linearGradient', 'radialGradient', 'stop', 'clipPath', 'mask', 'use', 'text', 'tspan', 'title', 'desc']);

export function safeRemoteSvg(text: string): boolean {
  if (!text || text.length > MAX_REMOTE_SVG) return false;
  // No entities/DTD, CSS, namespace aliases or escaped URL spellings. This deliberately
  // accepts a conservative SVG subset; packaged design assets use their existing path.
  if (/[&\\]|<!|<\?/.test(text) || /\b(?:style|on[a-z]+)\s*=/i.test(text)) return false;
  const elements = [...text.matchAll(/<\/?([\w:-]+)\b[^>]*>/g)];
  if (!elements.length || elements.length > 2048 || elements.some(m => !tags.has(m[1]))) return false;
  // Reject malformed/unquoted attributes instead of relying on parser recovery.
  for (const element of elements) {
    const tail = element[0].slice(element[0].indexOf(element[1]) + element[1].length, -1).replace(/\/$/, '');
    if (element[0].startsWith('</')) { if (tail.trim()) return false; continue; }
    if (!/^(?:\s+[\w:.-]+\s*=\s*(?:"[^"<>]*"|'[^'<>]*'))*\s*$/.test(tail)) return false;
  }
  for (const match of text.matchAll(/\b(?:[\w-]+:)?href\s*=\s*(["'])([\s\S]*?)\1/gi)) {
    if (!/^#[A-Za-z_][\w.-]*$/.test(match[2])) return false;
  }
  for (const match of text.matchAll(/url\s*\(([\s\S]*?)\)/gi)) {
    if (!/^\s*#[A-Za-z_][\w.-]*\s*$/.test(match[1])) return false;
  }
  return true;
}

export async function fetchRemoteSvg(uri: string): Promise<string | null> {
  if (!uri.startsWith('https://')) return null;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10_000);
  try {
    const response = await fetch(uri, {credentials: 'omit', redirect: 'error', signal: controller.signal});
    if (!response.ok || response.redirected || (response.url && !response.url.startsWith('https://')) ||
        response.headers.get('content-type')?.split(';')[0].trim().toLowerCase() !== 'image/svg+xml' ||
        Number(response.headers.get('content-length')) > MAX_REMOTE_SVG) return null;
    const text = await response.text();
    return safeRemoteSvg(text) ? text : null;
  } finally { clearTimeout(timer); }
}
