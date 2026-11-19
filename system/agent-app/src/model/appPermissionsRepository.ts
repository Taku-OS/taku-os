import {
  isPackageName,
  isPermissionMode,
  isPermissionScope,
  normalizePermissionError,
  parseAppPermissionsSnapshot,
  permissionFailure,
  type AppPermissionAction,
  type AppPermissionParams,
  type AppPermissionsRepository,
  type AppPermissionsSnapshot,
  type PermissionResult,
} from './appPermissions.ts';

/** `callAgent` from `src/native/agent.ts`; it resolves with `result` and throws `AgentGatewayError` on `{ok:false}`. */
export type CallPermissionGateway = <A extends AppPermissionAction>(action: A, params: AppPermissionParams[A]) => Promise<unknown>;

const MAX_QUERY = 200;

function errorCode(error: unknown): unknown {
  return error && typeof error === 'object' && 'code' in error ? (error as {code?: unknown}).code : undefined;
}
function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * Native gateway repository. Requests are serialized exactly as `AppPermissionParams` (no extra
 * keys, optional keys omitted) and every response is validated before the screen sees it.
 */
export function createNativeAppPermissionsRepository(call: CallPermissionGateway): AppPermissionsRepository {
  async function run<A extends AppPermissionAction>(action: A, params: AppPermissionParams[A]): Promise<PermissionResult<AppPermissionsSnapshot>> {
    let raw: unknown;
    try {
      raw = await call(action, params);
    } catch (error) {
      return permissionFailure(normalizePermissionError(errorCode(error)), errorMessage(error));
    }
    return parseAppPermissionsSnapshot(raw);
  }
  return {
    read(query) {
      const text = typeof query === 'string' ? query.trim().slice(0, MAX_QUERY) : '';
      return run('readAppPermissions', text ? {query: text} : {});
    },
    async setMode(mode, allowRootInYolo) {
      if (!isPermissionMode(mode)) return permissionFailure('INVALID_ARGUMENT', 'Unknown permission mode');
      if (allowRootInYolo !== undefined && typeof allowRootInYolo !== 'boolean') return permissionFailure('INVALID_ARGUMENT', 'allowRootInYolo must be a boolean');
      if (allowRootInYolo === true && mode !== 'yolo') return permissionFailure('INVALID_ARGUMENT', 'Root for all apps requires Open access');
      return run('setAppPermissionMode', allowRootInYolo === undefined ? {mode} : {mode, allowRootInYolo});
    },
    async setScope(packageName, scope, allowed) {
      if (!isPackageName(packageName)) return permissionFailure('INVALID_ARGUMENT', 'Invalid package name');
      if (!isPermissionScope(scope)) return permissionFailure('INVALID_ARGUMENT', 'Unknown permission scope');
      if (typeof allowed !== 'boolean') return permissionFailure('INVALID_ARGUMENT', 'allowed must be a boolean');
      return run('setAppPermissionScope', {packageName, scope, allowed});
    },
    async reset(packageName) {
      if (!isPackageName(packageName)) return permissionFailure('INVALID_ARGUMENT', 'Invalid package name');
      return run('resetAppPermission', {packageName});
    },
  };
}
