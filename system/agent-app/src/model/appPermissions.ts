/**
 * App Permission Center contract shared by the React Native screen and the native gateway.
 *
 * The native gateway (`system/assistant`) is the only authority: every call returns a complete
 * `AppPermissionsSnapshot`, and the screen renders that snapshot as-is. Nothing here grants access;
 * these helpers only parse, sort and explain the state that the gateway already decided.
 */

// ---- Gateway contract (must match the native AgentGatewayProvider byte-for-byte) ----

export type PermissionMode = 'deny' | 'manual' | 'auto' | 'yolo';
export type PermissionScope = 'memory.read' | 'memory.write' | 'taku.control' | 'taku.root';

export interface ScopeGrantMap {
  'memory.read': boolean;
  'memory.write': boolean;
  'taku.control': boolean;
  'taku.root': boolean;
}

export interface ManagedAppEntry {
  packageName: string;
  label: string;
  versionName: string | null;
  versionCode: number;
  enabled: boolean;
  certSha256: string;
  grantSource: 'manual' | 'auto' | 'default';
  scopes: ScopeGrantMap;
  effectiveScopes: ScopeGrantMap;
  autoReason?: string | null;
  updatedAt?: number | null;
}

export interface PermissionAuditEvent {
  at: number;
  packageName: string;
  label: string;
  scope: PermissionScope;
  decision: 'allowed' | 'denied';
  by: string;
}

export interface AppPermissionsSnapshot {
  revision: number;
  mode: PermissionMode;
  allowRootInYolo: boolean;
  apps: ManagedAppEntry[];
  recentEvents: PermissionAuditEvent[];
}

/** Gateway actions. `src/native/agent.ts` lists the same literals in `AgentAction`. */
export type AppPermissionAction = 'readAppPermissions' | 'setAppPermissionMode' | 'setAppPermissionScope' | 'resetAppPermission';

/** Exact request bodies. The gateway rejects unknown keys (`UNSUPPORTED_PARAMETER`), so optional keys are omitted, never null. */
export interface AppPermissionParams {
  readAppPermissions: {query?: string};
  setAppPermissionMode: {mode: PermissionMode; allowRootInYolo?: boolean};
  setAppPermissionScope: {packageName: string; scope: PermissionScope; allowed: boolean};
  resetAppPermission: {packageName: string};
}

export const PERMISSION_MODES: readonly PermissionMode[] = ['deny', 'manual', 'auto', 'yolo'];
export const PERMISSION_SCOPES: readonly PermissionScope[] = ['memory.read', 'memory.write', 'taku.control', 'taku.root'];
/** Scopes that Auto mode may never grant by itself. */
export const MANUAL_ONLY_SCOPES: readonly PermissionScope[] = ['memory.write', 'taku.root'];

// ---- Repository ----

export type PermissionErrorCode =
  | 'UNAVAILABLE' | 'UNSUPPORTED' | 'INVALID_RESPONSE' | 'INVALID_ARGUMENT'
  | 'NOT_FOUND' | 'CONFLICT' | 'PERMISSION_DENIED' | 'OPERATION_FAILED';
export type PermissionResult<T> = {ok: true; value: T} | {ok: false; code: PermissionErrorCode; message: string};

export interface AppPermissionsRepository {
  read(query?: string): Promise<PermissionResult<AppPermissionsSnapshot>>;
  setMode(mode: PermissionMode, allowRootInYolo?: boolean): Promise<PermissionResult<AppPermissionsSnapshot>>;
  setScope(packageName: string, scope: PermissionScope, allowed: boolean): Promise<PermissionResult<AppPermissionsSnapshot>>;
  reset(packageName: string): Promise<PermissionResult<AppPermissionsSnapshot>>;
}

export const permissionFailure = (code: PermissionErrorCode, message: string = code): {ok: false; code: PermissionErrorCode; message: string} =>
  ({ok: false, code, message});

async function unavailable(): Promise<PermissionResult<AppPermissionsSnapshot>> {
  return permissionFailure('UNAVAILABLE', 'App permission gateway is not connected');
}
export const unavailablePermissionsRepository: AppPermissionsRepository = {read: unavailable, setMode: unavailable, setScope: unavailable, reset: unavailable};

/** Maps gateway error codes (`AgentGatewayError.code`) onto the small set the screen explains. */
export function normalizePermissionError(code: unknown): PermissionErrorCode {
  if (typeof code !== 'string' || !code) return 'UNAVAILABLE';
  if (code === 'UNSUPPORTED_ACTION' || code === 'UNSUPPORTED_PARAMETER' || code === 'UNSUPPORTED') return 'UNSUPPORTED';
  if (code === 'NATIVE_UNAVAILABLE' || code === 'GATEWAY_UNAVAILABLE' || code === 'RUNTIME_UNAVAILABLE' || code === 'UNAVAILABLE') return 'UNAVAILABLE';
  if (code === 'PERMISSION_DENIED' || code.endsWith('_LOCKED')) return 'PERMISSION_DENIED';
  if (code === 'NOT_FOUND' || code.endsWith('_NOT_FOUND')) return 'NOT_FOUND';
  if (code === 'CONFLICT' || code.endsWith('_CONFLICT')) return 'CONFLICT';
  if (code === 'INVALID_RESPONSE') return 'INVALID_RESPONSE';
  if (code === 'INVALID_ARGUMENT' || code.startsWith('INVALID_')) return 'INVALID_ARGUMENT';
  return 'OPERATION_FAILED';
}

export const isPermissionMode = (value: unknown): value is PermissionMode => PERMISSION_MODES.includes(value as PermissionMode);
export const isPermissionScope = (value: unknown): value is PermissionScope => PERMISSION_SCOPES.includes(value as PermissionScope);
/** Android package names: dot-separated Java identifiers. Kept loose enough for test packages. */
export const isPackageName = (value: unknown): value is string =>
  typeof value === 'string' && value.length <= 255 && /^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*$/.test(value);

// ---- Parsing (fail closed: never render a guessed security state) ----

const record = (value: unknown): Record<string, unknown> | null =>
  value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;

function scopeMap(value: unknown): ScopeGrantMap | null {
  const map = record(value);
  if (!map || PERMISSION_SCOPES.some(scope => typeof map[scope] !== 'boolean')) return null;
  return {
    'memory.read': map['memory.read'] as boolean, 'memory.write': map['memory.write'] as boolean,
    'taku.control': map['taku.control'] as boolean, 'taku.root': map['taku.root'] as boolean,
  };
}

function appEntry(value: unknown): ManagedAppEntry | null {
  const entry = record(value);
  if (!entry || !isPackageName(entry.packageName)) return null;
  const scopes = scopeMap(entry.scopes), effectiveScopes = scopeMap(entry.effectiveScopes);
  const grantSource = entry.grantSource;
  if (!scopes || !effectiveScopes || (grantSource !== 'manual' && grantSource !== 'auto' && grantSource !== 'default')) return null;
  const label = typeof entry.label === 'string' && entry.label.trim() ? entry.label.trim() : entry.packageName;
  const reason = typeof entry.autoReason === 'string' && entry.autoReason.trim() ? entry.autoReason.trim() : null;
  return {
    packageName: entry.packageName, label,
    versionName: typeof entry.versionName === 'string' && entry.versionName ? entry.versionName : null,
    versionCode: typeof entry.versionCode === 'number' && Number.isFinite(entry.versionCode) ? entry.versionCode : 0,
    enabled: typeof entry.enabled === 'boolean' ? entry.enabled : true,
    certSha256: typeof entry.certSha256 === 'string' ? entry.certSha256 : '',
    grantSource, scopes, effectiveScopes, autoReason: reason,
    updatedAt: typeof entry.updatedAt === 'number' && Number.isFinite(entry.updatedAt) ? entry.updatedAt : null,
  };
}

function auditEvent(value: unknown): PermissionAuditEvent | null {
  const event = record(value);
  if (!event || typeof event.at !== 'number' || !Number.isFinite(event.at) || !isPackageName(event.packageName) ||
    !isPermissionScope(event.scope) || (event.decision !== 'allowed' && event.decision !== 'denied')) return null;
  return {
    at: event.at, packageName: event.packageName,
    label: typeof event.label === 'string' && event.label.trim() ? event.label.trim() : event.packageName,
    scope: event.scope, decision: event.decision, by: typeof event.by === 'string' ? event.by : '',
  };
}

/**
 * Validates a gateway response. Mode, the root flag and every app's scope maps are security state:
 * any malformed or duplicated value rejects the whole snapshot. Audit events are informational, so a
 * malformed event is dropped instead.
 */
export function parseAppPermissionsSnapshot(raw: unknown): PermissionResult<AppPermissionsSnapshot> {
  const invalid = (detail: string) => permissionFailure('INVALID_RESPONSE', `Invalid app permission snapshot: ${detail}`);
  const value = record(raw);
  if (!value) return invalid('not an object');
  if (typeof value.revision !== 'number' || !Number.isFinite(value.revision) || value.revision < 0) return invalid('revision');
  if (!isPermissionMode(value.mode)) return invalid('mode');
  if (typeof value.allowRootInYolo !== 'boolean') return invalid('allowRootInYolo');
  if (!Array.isArray(value.apps)) return invalid('apps');
  const apps: ManagedAppEntry[] = [], seen = new Set<string>();
  for (const item of value.apps) {
    const entry = appEntry(item);
    if (!entry) return invalid('app entry');
    if (seen.has(entry.packageName)) return invalid(`duplicate ${entry.packageName}`);
    seen.add(entry.packageName);
    apps.push(entry);
  }
  const events = Array.isArray(value.recentEvents) ? value.recentEvents : [];
  const recentEvents = events.map(auditEvent).filter((event): event is PermissionAuditEvent => event !== null);
  return {ok: true, value: {revision: value.revision, mode: value.mode, allowRootInYolo: value.allowRootInYolo, apps, recentEvents}};
}

// ---- Presentation helpers ----

export const emptyScopes = (): ScopeGrantMap => ({'memory.read': false, 'memory.write': false, 'taku.control': false, 'taku.root': false});
export const grantedScopes = (map: ScopeGrantMap): PermissionScope[] => PERMISSION_SCOPES.filter(scope => map[scope]);
export const hasAnyGrant = (map: ScopeGrantMap): boolean => PERMISSION_SCOPES.some(scope => map[scope]);
export const hasCustomGrant = (entry: Pick<ManagedAppEntry, 'grantSource'>): boolean => entry.grantSource !== 'default';

export type AccessSummary = {memory: 'none' | 'read' | 'write' | 'readWrite'; control: boolean; root: boolean};
export function accessSummary(map: ScopeGrantMap): AccessSummary {
  const read = map['memory.read'], write = map['memory.write'];
  return {memory: read && write ? 'readWrite' : read ? 'read' : write ? 'write' : 'none', control: map['taku.control'], root: map['taku.root']};
}

export type AppFilter = 'all' | 'allowed' | 'custom' | 'root';
export const APP_FILTERS: readonly AppFilter[] = ['all', 'allowed', 'custom', 'root'];

export function matchesFilter(entry: ManagedAppEntry, filter: AppFilter): boolean {
  if (filter === 'allowed') return hasAnyGrant(entry.effectiveScopes);
  if (filter === 'custom') return hasCustomGrant(entry);
  if (filter === 'root') return entry.effectiveScopes['taku.root'];
  return true;
}

/** Riskiest first: effective Root, then any effective access, then customized, then the rest by name. */
function rank(entry: ManagedAppEntry): number {
  if (entry.effectiveScopes['taku.root']) return 0;
  if (hasAnyGrant(entry.effectiveScopes)) return 1;
  return hasCustomGrant(entry) ? 2 : 3;
}
export function compareApps(left: ManagedAppEntry, right: ManagedAppEntry): number {
  return rank(left) - rank(right) || left.label.localeCompare(right.label) || left.packageName.localeCompare(right.packageName);
}

/** Client-side search over label and package name; returns a new sorted array. */
export function filterApps(apps: readonly ManagedAppEntry[], query: string, filter: AppFilter): ManagedAppEntry[] {
  const needle = query.trim().toLocaleLowerCase();
  return apps.filter(entry => matchesFilter(entry, filter) &&
    (!needle || entry.label.toLocaleLowerCase().includes(needle) || entry.packageName.toLocaleLowerCase().includes(needle)))
    .sort(compareApps);
}

export const filterCount = (apps: readonly ManagedAppEntry[], filter: AppFilter): number => apps.filter(entry => matchesFilter(entry, filter)).length;

type ModeState = Pick<AppPermissionsSnapshot, 'mode' | 'allowRootInYolo'>;

/** Whether the screen may offer this per-app switch. Deny blocks all third-party access; global Root in YOLO owns Root. */
export function scopeEditable(state: ModeState, scope: PermissionScope): boolean {
  if (state.mode === 'deny') return false;
  return !(scope === 'taku.root' && state.mode === 'yolo' && state.allowRootInYolo);
}

/**
 * Why a switch shows what it shows, when the explicit choice and the enforced state differ.
 * Switches always render `effectiveScopes` (what is enforced now); this note explains the gap.
 */
export type ScopeNote = 'rootForAll' | 'savedPaused' | 'byAgent' | 'byOpenAccess' | 'needsYou' | null;
export function scopeNote(state: ModeState, entry: ManagedAppEntry, scope: PermissionScope): ScopeNote {
  const explicit = entry.scopes[scope], effective = entry.effectiveScopes[scope], source = entry.grantSource;
  if (scope === 'taku.root' && state.mode === 'yolo' && state.allowRootInYolo) return 'rootForAll';
  if (source !== 'default' && explicit && !effective) return 'savedPaused';
  if (effective && source === 'auto' && explicit) return 'byAgent';
  if (effective && !(source === 'manual' && explicit) && state.mode === 'yolo') return 'byOpenAccess';
  if (!effective && state.mode === 'auto' && MANUAL_ONLY_SCOPES.includes(scope)) return 'needsYou';
  return null;
}

/** How exposed Root is. `all` means every third-party app may run Root commands. */
export function rootExposure(snapshot: AppPermissionsSnapshot): 'none' | 'some' | 'all' {
  if (snapshot.mode === 'yolo' && snapshot.allowRootInYolo) return 'all';
  return snapshot.apps.some(entry => entry.effectiveScopes['taku.root']) ? 'some' : 'none';
}

/**
 * Body for a mode change. Root-for-all is cleared on every mode change, so re-entering YOLO never
 * silently restores Root: it must be confirmed again through the dedicated switch.
 */
export function modeChange(mode: PermissionMode): {mode: PermissionMode; allowRootInYolo: false} {
  return {mode, allowRootInYolo: false};
}

export type EventActor = 'agent' | 'you' | 'mode' | 'system';
/** Who decided an audit event, from the gateway's free-form `by` (`mode:deny`, `auto`, `manual`, ...). */
export function eventActor(by: string): EventActor {
  const value = by.toLowerCase();
  if (value.startsWith('mode')) return 'mode';
  if (value.startsWith('auto') || value.startsWith('agent')) return 'agent';
  if (value.startsWith('manual') || value.startsWith('user')) return 'you';
  return 'system';
}

export type RecentRequest = {event: PermissionAuditEvent; app: ManagedAppEntry | null};
/** Newest first, one row per app and scope, so a just-blocked app is one tap away. */
export function recentRequests(events: readonly PermissionAuditEvent[], apps: readonly ManagedAppEntry[], limit = 4): RecentRequest[] {
  const byPackage = new Map(apps.map(entry => [entry.packageName, entry]));
  const seen = new Set<string>(), rows: RecentRequest[] = [];
  for (const event of events.slice().sort((left, right) => right.at - left.at)) {
    const key = `${event.packageName}\n${event.scope}`;
    if (seen.has(key)) continue;
    seen.add(key);
    rows.push({event, app: byPackage.get(event.packageName) ?? null});
    if (rows.length >= limit) break;
  }
  return rows;
}

export type RelativeTime = {kind: 'now'} | {kind: 'minutes'; value: number} | {kind: 'time'; value: string} | {kind: 'date'; month: number; day: number};
/** 24-hour local times per the Taku copy rules. */
export function relativeTime(at: number, now: number): RelativeTime {
  const elapsed = now - at;
  if (elapsed < 60_000) return {kind: 'now'};
  if (elapsed < 3_600_000) return {kind: 'minutes', value: Math.floor(elapsed / 60_000)};
  const date = new Date(at), today = new Date(now);
  if (date.getFullYear() === today.getFullYear() && date.getMonth() === today.getMonth() && date.getDate() === today.getDate()) {
    return {kind: 'time', value: `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`};
  }
  return {kind: 'date', month: date.getMonth() + 1, day: date.getDate()};
}

/**
 * Applies a response only if no newer request has already been applied. Reads issued before a
 * mutation can resolve after it; request order, not arrival order, decides which snapshot wins.
 */
export function createSnapshotGate() {
  let issued = 0, applied = 0;
  return {
    issue: (): number => ++issued,
    accept(ticket: number): boolean {
      if (ticket < applied) return false;
      applied = ticket;
      return true;
    },
  };
}
