/** taku_phone tools by their bare name → the i18n key of their user-facing label. */
export const phoneToolKeys: Readonly<Record<string, string>> = {
  device_info: 'deviceInfo', observe_background: 'observeScreen', open_app: 'openApp', tap: 'tap', type_text: 'typeText', apps: 'apps',
  request_apps: 'requestApps', release_apps: 'releaseApps', screen_status: 'screenStatus', screenshot: 'screenshot',
  swipe: 'swipe', touch: 'touch',
};

/**
 * A taku_phone tool's text result as the activity list shows it. The phone tools answer the agent in JSON
 * (`{"released":["com.xingin.xhs"],"held":[]}`, frame identities, app catalogues): machine data, not
 * something to read in the list, so a JSON result keeps only its human `message`/`error` text and
 * is otherwise left out (the native thread keeps it whole). Plain text passes through.
 */
export function phoneToolResultText(text: string): string {
  const trimmed = text.trim();
  if (!/^[[{]/.test(trimmed)) return text;
  let value: unknown;
  try { value = JSON.parse(trimmed); } catch { return text; }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return '';
  const o = value as Record<string, unknown>;
  const error = o.error !== null && typeof o.error === 'object' ? (o.error as Record<string, unknown>).message : o.error;
  for (const v of [o.message, error]) if (typeof v === 'string' && v.trim()) return v.trim();
  return '';
}

/**
 * A tool's own words with taku_phone tool identifiers (`release_apps`, `taku_phone__open_app`,
 * `mcp_taku_phone_3013b55d_tap`, `taku_phone.screen_status()`) replaced by their labels, so an agent
 * hint like "release_apps it to close it" never shows an internal name.
 */
export function humanizeToolNames(text: string, label: (key: string) => string): string {
  return text.replace(/`?(?<![\w./-])(?:mcp_{0,2}taku_phone(?:__|_|\.|\/)(?:[0-9a-f]{8}_)?|taku_phone(?:__|_|\.|\/))?([a-z][a-z0-9]*(?:_[a-z0-9]+)*)(?![\w/-]|\.\w)(?:\(\))?`?/g,
    (found, tool: string) => {
      const key = phoneToolKeys[tool];
      const prefixed = /taku_phone/.test(found);
      // A bare word is a tool name only when it is a multi-word key (`open_app`), not plain prose (`tap`, `apps`).
      if (!key || (!prefixed && !tool.includes('_'))) return found;
      return label(key);
    });
}
