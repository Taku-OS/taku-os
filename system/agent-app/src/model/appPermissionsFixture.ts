import {
  emptyScopes,
  isPackageName,
  isPermissionMode,
  isPermissionScope,
  PERMISSION_SCOPES,
  permissionFailure,
  type AppPermissionsRepository,
  type AppPermissionsSnapshot,
  type ManagedAppEntry,
  type PermissionAuditEvent,
  type PermissionErrorCode,
  type PermissionMode,
  type PermissionResult,
  type ScopeGrantMap,
} from './appPermissions.ts';

/**
 * Deterministic, in-memory stand-in for the native permission gateway, for the component gallery
 * and unit tests only. It mirrors the policy described in the App Permission Center design so the
 * screen can be exercised off-device; it is never used as a fallback for a failed native call.
 */

export type FixtureApp = Pick<ManagedAppEntry, 'packageName' | 'label' | 'versionName' | 'versionCode' | 'enabled' | 'certSha256'>;
type GrantRecord = {source: 'manual' | 'auto'; scopes: ScopeGrantMap; autoReason: string | null; updatedAt: number};
export type FixtureGrant = {packageName: string; source: 'manual' | 'auto'; scopes: Partial<ScopeGrantMap>; autoReason?: string};
type Operation = 'read' | 'setMode' | 'setScope' | 'reset';

export type PermissionFixtureSeed = {
  mode?: PermissionMode;
  allowRootInYolo?: boolean;
  apps?: FixtureApp[];
  grants?: FixtureGrant[];
  events?: PermissionAuditEvent[];
  /** Every call of this operation fails with the code, to exercise error states. */
  failures?: Partial<Record<Operation, PermissionErrorCode>>;
  now?: () => number;
};

export const FIXTURE_NOW = Date.UTC(2026, 10, 19, 9, 30);

export const fixtureApps: FixtureApp[] = [
  {packageName: 'com.user.habittracker', label: 'HabitTracker', versionName: '0.3.1', versionCode: 31, enabled: true, certSha256: 'a1b2c3d4'},
  {packageName: 'com.user.localwidget', label: 'Local Widget', versionName: '1.0', versionCode: 1, enabled: true, certSha256: '11223344'},
  {packageName: 'com.example.shop', label: 'Example Shop', versionName: '8.2.0', versionCode: 820, enabled: true, certSha256: 'ffeeddcc'},
  {packageName: 'org.community.rootkit', label: 'Root Toolbox', versionName: '2.4', versionCode: 24, enabled: true, certSha256: '99887766'},
  {packageName: 'com.example.notes', label: '随手记', versionName: null, versionCode: 5, enabled: false, certSha256: '55443322'},
];

export const fixtureGrants: FixtureGrant[] = [
  {packageName: 'com.user.habittracker', source: 'manual', scopes: {'memory.read': true, 'taku.control': true}},
  {packageName: 'com.user.localwidget', source: 'auto', scopes: {'memory.read': true, 'taku.control': true}, autoReason: 'Built on this device and has no network access.'},
  {packageName: 'org.community.rootkit', source: 'manual', scopes: {'taku.root': true}},
];

export const fixtureEvents: PermissionAuditEvent[] = [
  {at: FIXTURE_NOW - 40_000, packageName: 'com.example.shop', label: 'Example Shop', scope: 'memory.read', decision: 'denied', by: 'mode:manual'},
  {at: FIXTURE_NOW - 5 * 60_000, packageName: 'com.user.localwidget', label: 'Local Widget', scope: 'taku.control', decision: 'allowed', by: 'auto'},
  {at: FIXTURE_NOW - 3 * 3_600_000, packageName: 'com.example.shop', label: 'Example Shop', scope: 'memory.read', decision: 'denied', by: 'mode:manual'},
];

const scopesFrom = (partial: Partial<ScopeGrantMap>): ScopeGrantMap => ({...emptyScopes(), ...partial});

/** The policy matrix from the design: Deny blocks everything; Auto never grants memory.write or Root by itself. */
export function fixtureEffectiveScopes(mode: PermissionMode, allowRootInYolo: boolean, grant: GrantRecord | undefined): ScopeGrantMap {
  const manual = grant?.source === 'manual' ? grant.scopes : null;
  switch (mode) {
    case 'deny': return emptyScopes();
    case 'manual': return manual ? {...manual} : emptyScopes();
    case 'auto':
      if (manual) return {...manual};
      return grant ? {...emptyScopes(), 'memory.read': grant.scopes['memory.read'], 'taku.control': grant.scopes['taku.control']} : emptyScopes();
    case 'yolo': {
      const open: ScopeGrantMap = {'memory.read': true, 'memory.write': true, 'taku.control': true, 'taku.root': allowRootInYolo};
      if (!manual) return open;
      return {'memory.read': manual['memory.read'], 'memory.write': manual['memory.write'], 'taku.control': manual['taku.control'], 'taku.root': allowRootInYolo || manual['taku.root']};
    }
  }
}

export function createFixturePermissionsRepository(seed: PermissionFixtureSeed = {}): AppPermissionsRepository {
  const now = seed.now ?? (() => FIXTURE_NOW);
  const apps = (seed.apps ?? fixtureApps).map(app => ({...app}));
  const events = (seed.events ?? fixtureEvents).map(event => ({...event}));
  const grants = new Map<string, GrantRecord>((seed.grants ?? fixtureGrants).map(grant => [grant.packageName, {
    source: grant.source, scopes: scopesFrom(grant.scopes), autoReason: grant.autoReason ?? null, updatedAt: now(),
  }]));
  let mode: PermissionMode = seed.mode ?? 'deny';
  let allowRootInYolo = seed.allowRootInYolo ?? false;
  let revision = 1;

  const entry = (app: FixtureApp): ManagedAppEntry => {
    const grant = grants.get(app.packageName);
    return {
      ...app,
      grantSource: grant?.source ?? 'default',
      scopes: grant ? {...grant.scopes} : emptyScopes(),
      effectiveScopes: fixtureEffectiveScopes(mode, allowRootInYolo, grant),
      autoReason: grant?.source === 'auto' ? grant.autoReason : null,
      updatedAt: grant?.updatedAt ?? null,
    };
  };
  const snapshot = (query = ''): PermissionResult<AppPermissionsSnapshot> => {
    const needle = query.trim().toLocaleLowerCase();
    const visible = apps.filter(app => !needle || app.label.toLocaleLowerCase().includes(needle) || app.packageName.toLocaleLowerCase().includes(needle));
    return {ok: true, value: {revision, mode, allowRootInYolo, apps: visible.map(entry), recentEvents: events.map(event => ({...event}))}};
  };
  const failure = (operation: Operation) => {
    const code = seed.failures?.[operation];
    return code ? permissionFailure(code, `Fixture ${operation} failure`) : null;
  };
  const known = (packageName: string) => apps.some(app => app.packageName === packageName);

  return {
    async read(query) {
      return failure('read') ?? snapshot(query);
    },
    async setMode(nextMode, nextRoot) {
      const failed = failure('setMode');
      if (failed) return failed;
      if (!isPermissionMode(nextMode)) return permissionFailure('INVALID_ARGUMENT', 'mode');
      if (nextRoot === true && nextMode !== 'yolo') return permissionFailure('INVALID_ARGUMENT', 'allowRootInYolo');
      mode = nextMode;
      if (nextRoot !== undefined) allowRootInYolo = nextRoot;
      if (mode !== 'yolo') allowRootInYolo = false;
      revision += 1;
      return snapshot();
    },
    async setScope(packageName, scope, allowed) {
      const failed = failure('setScope');
      if (failed) return failed;
      if (!isPackageName(packageName) || !isPermissionScope(scope) || typeof allowed !== 'boolean') return permissionFailure('INVALID_ARGUMENT', 'scope');
      const app = apps.find(item => item.packageName === packageName);
      if (!app) return permissionFailure('NOT_FOUND', packageName);
      // Materialize what the user currently sees before editing one scope, so toggling one switch
      // never silently flips the others (for example, every scope a YOLO default app inherited).
      const existing = grants.get(packageName);
      const base = existing?.source === 'manual' ? existing.scopes : entry(app).effectiveScopes;
      const scopes = PERMISSION_SCOPES.reduce((next, key) => ({...next, [key]: key === scope ? allowed : base[key]}), emptyScopes());
      grants.set(packageName, {source: 'manual', scopes, autoReason: null, updatedAt: now()});
      revision += 1;
      return snapshot();
    },
    async reset(packageName) {
      const failed = failure('reset');
      if (failed) return failed;
      if (!isPackageName(packageName)) return permissionFailure('INVALID_ARGUMENT', packageName);
      if (!known(packageName)) return permissionFailure('NOT_FOUND', packageName);
      grants.delete(packageName);
      revision += 1;
      return snapshot();
    },
  };
}
