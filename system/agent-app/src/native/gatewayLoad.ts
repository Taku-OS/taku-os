/**
 * This app's share of the Assistant gateway. The provider serves four calls at a time and refuses
 * a fifth at once with BUSY (AgentGatewayProvider), before running anything. The open conversation
 * and the user's own actions come first, so reads nobody is waiting on (the Task status board, a
 * count, a periodic list) ask [quiet] before they go and never take the slot those need. Free of
 * React and native imports.
 */

/** A background read goes while at most this many of the app's calls are in flight. */
export const BACKGROUND_SHARE = 1;
/** How long a background read waits for room before it goes anyway. */
export const BACKGROUND_PATIENCE_MS = 3000;
const CHECK_MS = 50;

export interface GatewayLoad {
  /** Counts [call] as in flight until it settles, and returns it. */
  track<T>(call: () => Promise<T>): Promise<T>;
  /** Resolves once the app has room for a background read, or after [patienceMs]. */
  quiet(patienceMs?: number): Promise<void>;
  inFlight(): number;
}

export function createGatewayLoad(clock: {now(): number; sleep(ms: number): Promise<void>} = {
  now: () => Date.now(), sleep: ms => new Promise(resolve => setTimeout(resolve, ms)),
}): GatewayLoad {
  let inFlight = 0;
  return {
    async track(call) {
      inFlight++;
      try { return await call(); } finally { inFlight--; }
    },
    async quiet(patienceMs = BACKGROUND_PATIENCE_MS) {
      const deadline = clock.now() + patienceMs;
      while (inFlight > BACKGROUND_SHARE && clock.now() < deadline) await clock.sleep(CHECK_MS);
    },
    inFlight: () => inFlight,
  };
}

/** The gateway had no room for the call. */
export const isGatewayBusy = (problem: unknown): boolean =>
  typeof problem === 'object' && problem !== null && (problem as {code?: unknown}).code === 'BUSY';

/**
 * Gateway actions that only read (or, for `resumeThread`, set the same selection again), so asking
 * a second time is always safe. While a Task's turn runs, broker-bound calls take longer and the
 * four slots fill up; without a second ask, opening the chat then could fail its first reads
 * (the saved draft, the conversation list) and show an error until the user refreshed by hand.
 */
const READS: ReadonlySet<string> = new Set([
  'listThreads', 'readSession', 'resumeThread', 'composer/read', 'composer/receipt', 'composer/options/read',
  'getWorkspaces', 'requests/list', 'requests/read', 'readTaskDetail', 'workspaces/list', 'workspaces/apps',
  'workspaces/preview', 'workspaces/executionStatus', 'capture/read', 'accounts/read', 'accounts/keys',
  'mainReply', 'readMatter', 'audioCapabilities', 'readSystemAi', 'readAppPermissions',
]);
/** Whether [action] may simply be asked again when the gateway answers BUSY. */
export const isGatewayRead = (action: string): boolean => READS.has(action);

/**
 * Runs [call], and again after a short pause each time the gateway answers BUSY. For a read, or
 * for a call with a small answer: there BUSY is decided before the action runs, so nothing is
 * done twice. (A large answer can also be refused with BUSY after its action ran, which is why an
 * action that changes something and may answer at length is never passed here.)
 */
export async function retryWhileBusy<T>(call: () => Promise<T>, attempts = 3, pause: (ms: number) => Promise<void> =
  ms => new Promise(resolve => setTimeout(resolve, ms))): Promise<T> {
  for (let attempt = 1; ; attempt++) {
    try { return await call(); } catch (problem) {
      if (!isGatewayBusy(problem) || attempt >= attempts) throw problem;
      await pause(150 * attempt);
    }
  }
}
