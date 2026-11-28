export interface WorkspaceLease {
  threadId: string;
  screenKey: string;
  generation: string;
}

export interface ManagedWorkspace extends WorkspaceLease {
  allocationId?: string;
  packageName: string;
  appLabel?: string;
  taskTitle?: string;
  label?: string;
  status: string;
  controlOwner: string;
  controlRevision: number;
  admitted: boolean;
  stopping?: boolean;
  resumeRequired?: boolean;
  activeTurnId?: string;
  waitingForUser?: boolean;
  agentAllowed?: boolean;
  lastError?: string;
  waitReason?: {text?: string} | null;
}

export interface WorkspacePolicy {
  revision: number;
  allowBackground: boolean;
  maxActiveScreens: number;
  blockedPackages: string[];
}

export interface WorkspaceCatalog {
  policy: WorkspacePolicy;
  limits?: {maxActiveScreens?: {min: number; max: number}};
  screens: ManagedWorkspace[];
  revocations?: (WorkspaceLease & {status: string; taskTitle?: string; packageNames?: string[]; error?: string})[];
}

export interface WorkspaceApps {
  apps: {packageName: string; label: string; installed: boolean; allowed: boolean}[];
  policy: WorkspacePolicy;
}

const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;

export function workspaceLease(value: {threadId?: unknown; screenKey?: unknown; generation?: unknown}): WorkspaceLease | null {
  return typeof value.threadId === 'string' && uuid.test(value.threadId)
    && typeof value.screenKey === 'string' && /^task-[a-f0-9]{20}$/.test(value.screenKey)
    && typeof value.generation === 'string' && uuid.test(value.generation)
    ? {threadId: value.threadId, screenKey: value.screenKey, generation: value.generation} : null;
}

export function sameWorkspace(left: WorkspaceLease, right: WorkspaceLease): boolean {
  return left.threadId === right.threadId && left.screenKey === right.screenKey && left.generation === right.generation;
}

export function workspaceCanPreview(screen: ManagedWorkspace): boolean {
  return screen.admitted && !screen.stopping && !['stale', 'disconnected', 'unavailable', 'rebind_required', 'cleanup_failed', 'stopping'].includes(screen.status);
}
