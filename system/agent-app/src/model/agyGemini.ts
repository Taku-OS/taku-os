/**
 * Gemini for the main assistant: Codex keeps running every turn and talks to the local Magpie
 * gateway, which answers the `taku-agy` provider by running Google's Antigravity CLI (`agy acp`).
 * This is the view of `accounts/agyRead` the Accounts page shows; the broker owns the state.
 */

/** `accounts/agy*` reply. `signIn.url` is present only while a sign-in waits for its code. */
export type AgyStatus = {
  state: 'agy_missing' | 'not_configured' | 'checking' | 'ready' | 'needs_login' | 'unavailable' | 'unsupported' | string;
  checking: boolean;
  installed: boolean;
  configured: boolean;
  models: string[];
  defaultModel: string;
  plan: string;
  restingUntil: string;
  assistantModel: string;
  active: boolean;
  signIn: {phase: 'idle' | 'starting' | 'pending' | 'verifying' | 'done' | 'failed' | 'cancelled' | string; url: string; error: string};
};

export type AgySummaryKey =
  | 'agyMissing' | 'agyUnsupported' | 'agyNotConfigured' | 'agyChecking' | 'agyNeedsLogin'
  | 'agyReady' | 'agyReadyPlan' | 'agyActive' | 'agyUnavailable' | 'agyStarting' | 'agyPending' | 'agyVerifying' | 'agyFailed';

export type AgyView = {
  summary: AgySummaryKey;
  plan: string;
  /** A sign-in is running; the screen follows it closely and keeps screenshots out. */
  signingIn: boolean;
  /** Google's page is ready and agy waits for the code it shows. */
  awaitingCode: boolean;
  page: string | null;
  canSignIn: boolean;
  canUse: boolean;
  active: boolean;
  model: string;
  restingUntil: string;
};

const signInHosts = ['accounts.google.com'];

/** Google's sign-in page, or null for anything else (another host, plain http, userinfo). */
export function googleSignInPage(url: string): string | null {
  const match = /^https:\/\/([^/?#@]+)(?:[/?#]|$)/.exec(url);
  return match && signInHosts.includes(match[1].toLowerCase()) ? url : null;
}

/** The model the assistant would switch to: agy's default when it offers it, else its first. */
export function agyModel(status: AgyStatus): string {
  if (!status.models.length || status.models.includes(status.defaultModel)) return status.defaultModel;
  return status.models[0];
}

export function agyView(status: AgyStatus): AgyView {
  const phase = status.signIn?.phase ?? 'idle';
  const signingIn = ['starting', 'pending', 'verifying'].includes(phase);
  const page = phase === 'pending' ? googleSignInPage(status.signIn.url) : null;
  const summary: AgySummaryKey = !status.installed ? 'agyMissing'
    : phase === 'starting' ? 'agyStarting'
      : phase === 'pending' ? 'agyPending'
        : phase === 'verifying' ? 'agyVerifying'
          : status.state === 'unsupported' ? 'agyUnsupported'
            : status.state === 'not_configured' ? 'agyNotConfigured'
              : status.state === 'checking' || status.checking ? 'agyChecking'
                : status.state === 'needs_login' ? phase === 'failed' ? 'agyFailed' : 'agyNeedsLogin'
                  : status.state === 'ready' ? status.active ? 'agyActive' : status.plan ? 'agyReadyPlan' : 'agyReady'
                    : 'agyUnavailable';
  const usable = status.installed && status.state !== 'unsupported';
  return {
    summary,
    plan: status.plan,
    signingIn,
    awaitingCode: page !== null,
    page,
    canSignIn: usable && !signingIn && !status.checking && (status.state === 'needs_login' || status.state === 'not_configured'),
    canUse: usable && !signingIn && !status.checking && status.state === 'ready' && !status.active,
    active: status.active,
    model: agyModel(status),
    restingUntil: status.restingUntil,
  };
}
