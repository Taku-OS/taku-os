/**
 * The single owner of native Matter reader leases in the shared React runtime.
 *
 * Chat cards and desktop panel roots both register visible demand here instead of opening readers of
 * their own, because the device allows two leases of at most 32 matters and a second reader used to
 * stop the first. Demand is reference counted per surface, so leaving one screen releases only its
 * own ids. Every lease renewal re-reads full snapshots: cursors do not replay history, so a renewed
 * reader must not assume it missed nothing.
 */
import {AppState} from 'react-native';
import {callAgent, newRequestId, subscribeMatter} from '../native/agent';
import {applyMatterSnapshot, getCard, updateCard} from '../model/genuiStore';
import {record} from '../model/session';

const MAX_LEASES = 2;
const LEASE_IDS = 32;
const RENEW_MS = 90_000;
const RECONNECT_MS = 3_000;
/** Coalesces the churn of a scrolling list into one lease decision. */
const SETTLE_MS = 100;

export interface MatterDemand {
  /** Matters whose rendered content the user can actually see. */
  visible?: Iterable<string>;
  /** Lower priority: dropped first when the two leases cannot hold everything. */
  preload?: Iterable<string>;
}
export interface MatterSurface {setDemand(demand: MatterDemand): void; release(): void}

type Timer = ReturnType<typeof setTimeout>;

interface Lease {
  subscriptionId: string;
  ids: string[];
  subscription: {remove(): void} | null;
  renewal?: Timer;
  reconnect?: Timer;
  failuresForRetry: number;
  closed: boolean;
}

const surfaces = new Map<number, {name: string; visible: Set<string>; preload: Set<string>}>();
const leases = new Map<string, Lease>();
let surfaceSequence = 0;
let foreground = AppState.currentState === 'active';
let settleTimer: Timer | undefined;

const errorCode = (error: unknown): string => {
  const code = record(error).code;
  return typeof code === 'string' && code ? code : error instanceof Error ? error.message : 'UNAVAILABLE';
};
const reportSync = (id: string, code: string) => {
  if (getCard(id)) updateCard(id, card => card.syncError === code ? card : {...card, syncError: code});
};

function schedule(): void {
  if (settleTimer) clearTimeout(settleTimer);
  settleTimer = setTimeout(() => { settleTimer = undefined; reconcile(); }, SETTLE_MS);
}

/** Visible demand first, then preload-only ids, each in stable order. */
function orderedDemand(): string[] {
  const visible: string[] = [], preload = new Set<string>();
  for (const surface of surfaces.values()) {
    for (const id of surface.visible) if (!visible.includes(id)) visible.push(id);
    for (const id of surface.preload) if (!surface.visible.has(id)) preload.add(id);
  }
  return [...visible, ...[...preload].filter(id => !visible.includes(id))];
}

/** Keeps existing groups that are still fully needed, then fills from the overflow. */
function groupIds(ids: string[]): string[][] {
  const remaining = new Set(ids), groups: string[][] = [];
  for (const lease of leases.values()) {
    const kept = lease.ids.filter(id => remaining.has(id));
    if (!kept.length) continue;
    for (const id of kept) remaining.delete(id);
    groups.push(kept);
  }
  for (const id of ids) {
    if (!remaining.delete(id)) continue;
    const open = groups.find(group => group.length < LEASE_IDS);
    if (open) open.push(id);
    else if (groups.length < MAX_LEASES) groups.push([id]);
    else break;
  }
  return groups;
}

function closeLease(lease: Lease): void {
  lease.closed = true;
  clearTimeout(lease.renewal);
  clearTimeout(lease.reconnect);
  lease.renewal = undefined;
  lease.reconnect = undefined;
  try { lease.subscription?.remove(); } catch {}
  lease.subscription = null;
  leases.delete(lease.subscriptionId);
}

async function resync(ids: readonly string[], generation: () => boolean): Promise<void> {
  for (const id of ids) {
    if (!generation()) return;
    try {
      const result = await callAgent<{snapshot: unknown}>('readMatter', {matterId: id});
      if (generation()) applyMatterSnapshot(result.snapshot);
    } catch (error) {
      if (generation()) reportSync(id, errorCode(error));
    }
  }
}

function openLease(ids: string[]): void {
  const lease: Lease = {subscriptionId: newRequestId(), ids, subscription: null, failuresForRetry: 0, closed: false};
  leases.set(lease.subscriptionId, lease);
  const current = () => !lease.closed && leases.get(lease.subscriptionId) === lease;
  const reconnect = () => {
    if (!current() || lease.reconnect || !foreground) return;
    lease.reconnect = setTimeout(() => {
      lease.reconnect = undefined;
      if (!current()) return;
      closeLease(lease);
      reconcile();
    }, Math.min(5_000, RECONNECT_MS * 2 ** Math.min(lease.failuresForRetry++, 1)));
  };
  try {
    lease.subscription = subscribeMatter(ids, undefined, batch => {
      if (!current()) return;
      for (const raw of batch.events) {
        const event = record(raw);
        if (event.schema !== 'taku.matter-events/1') continue;
        if (event.method === 'matter/snapshot') applyMatterSnapshot(event.snapshot);
        if (event.method === 'matter/resync') void resync(ids, current);
      }
      if (batch.requiresResync) void resync(ids, current);
      if (batch.closed) {
        for (const id of ids) if (batch.error) reportSync(id, batch.error);
        reconnect();
      }
    });
  } catch (error) {
    for (const id of ids) reportSync(id, errorCode(error));
    reconnect();
    return;
  }
  lease.renewal = setTimeout(() => {
    lease.renewal = undefined;
    if (!current()) return;
    closeLease(lease);
    reconcile();
  }, RENEW_MS);
  void resync(ids, current);
}

function reconcile(): void {
  const demand = foreground ? orderedDemand() : [];
  const served = demand.slice(0, MAX_LEASES * LEASE_IDS);
  const visible = new Set([...surfaces.values()].flatMap(surface => [...surface.visible]));
  // Preload is dropped silently; only a visible matter the leases cannot hold is a real error.
  for (const id of demand.slice(served.length)) if (visible.has(id)) reportSync(id, 'SUBSCRIPTION_LIMITED');
  const groups = groupIds(served);
  const wanted = groups.map(group => [...group].sort().join('\n'));
  for (const lease of [...leases.values()]) if (!wanted.includes([...lease.ids].sort().join('\n'))) closeLease(lease);
  for (const group of groups) {
    const key = [...group].sort().join('\n');
    const existing = [...leases.values()].find(lease => [...lease.ids].sort().join('\n') === key);
    if (existing) existing.failuresForRetry = 0;
    else openLease(group);
  }
}

export function openMatterSurface(name: string): MatterSurface {
  const id = ++surfaceSequence;
  surfaces.set(id, {name, visible: new Set(), preload: new Set()});
  return {
    setDemand(demand) {
      const surface = surfaces.get(id);
      if (!surface) return;
      const visible = new Set(demand.visible ?? []), preload = new Set(demand.preload ?? []);
      if (sameSet(surface.visible, visible) && sameSet(surface.preload, preload)) return;
      surface.visible = visible;
      surface.preload = preload;
      schedule();
    },
    release() {
      if (!surfaces.delete(id)) return;
      schedule();
    },
  };
}

function sameSet(before: ReadonlySet<string>, after: ReadonlySet<string>): boolean {
  if (before.size !== after.size) return false;
  for (const value of before) if (!after.has(value)) return false;
  return true;
}

AppState.addEventListener('change', state => {
  const active = state === 'active';
  if (active === foreground) return;
  foreground = active;
  if (active) schedule();
  else reconcile();
});
