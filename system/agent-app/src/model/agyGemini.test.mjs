import assert from 'node:assert/strict';
import test from 'node:test';
import {agyModel, agyView, googleSignInPage} from './agyGemini.ts';
import {accountTextFor} from '../i18n/accounts.ts';

const base = {
  state: 'ready', checking: false, installed: true, configured: true, models: ['gemini-3.8-flash', 'gemini-4-neon'],
  defaultModel: 'gemini-3.8-flash', plan: '', restingUntil: '', assistantModel: '', active: false,
  signIn: {phase: 'idle', url: '', error: ''},
};
const status = (patch = {}) => ({...base, ...patch, signIn: {...base.signIn, ...(patch.signIn ?? {})}});

test('only Google sign-in pages over https are opened', () => {
  assert.equal(googleSignInPage('https://accounts.google.com/o/oauth2/auth?x=1'), 'https://accounts.google.com/o/oauth2/auth?x=1');
  assert.equal(googleSignInPage('https://ACCOUNTS.GOOGLE.COM/o'), 'https://ACCOUNTS.GOOGLE.COM/o');
  assert.equal(googleSignInPage('http://accounts.google.com/o'), null);
  assert.equal(googleSignInPage('https://accounts.google.com.evil.example/o'), null);
  assert.equal(googleSignInPage('https://user@accounts.google.com/o'), null);
  assert.equal(googleSignInPage('https://evil.example/?u=https://accounts.google.com/'), null);
  assert.equal(googleSignInPage(''), null);
});

test('a signed-in profile can switch the assistant; an active one cannot switch again', () => {
  const ready = agyView(status({plan: 'Google AI Pro'}));
  assert.equal(ready.summary, 'agyReadyPlan');
  assert.equal(ready.canUse, true);
  assert.equal(ready.canSignIn, false);
  assert.equal(ready.model, 'gemini-3.8-flash');
  const active = agyView(status({active: true, assistantModel: 'group/route-assistant'}));
  assert.equal(active.summary, 'agyActive');
  assert.equal(active.canUse, false);
});

test('missing agy, an older Magpie and a missing sign-in say so and offer only what works', () => {
  const missing = agyView(status({installed: false, state: 'agy_missing'}));
  assert.equal(missing.summary, 'agyMissing');
  assert.equal(missing.canSignIn || missing.canUse, false);
  const old = agyView(status({state: 'unsupported'}));
  assert.equal(old.summary, 'agyUnsupported');
  assert.equal(old.canSignIn || old.canUse, false);
  const login = agyView(status({state: 'needs_login', models: []}));
  assert.equal(login.summary, 'agyNeedsLogin');
  assert.equal(login.canSignIn, true);
  assert.equal(login.canUse, false);
  const fresh = agyView(status({state: 'not_configured', configured: false}));
  assert.equal(fresh.summary, 'agyNotConfigured');
  assert.equal(fresh.canSignIn, true);
  const checking = agyView(status({state: 'needs_login', checking: true}));
  assert.equal(checking.summary, 'agyChecking');
  assert.equal(checking.canSignIn, false);
});

test('a sign-in follows its phases and exposes the page only while agy waits for the code', () => {
  const starting = agyView(status({state: 'needs_login', signIn: {phase: 'starting'}}));
  assert.equal(starting.summary, 'agyStarting');
  assert.equal(starting.signingIn, true);
  assert.equal(starting.awaitingCode, false);
  const pending = agyView(status({state: 'needs_login', signIn: {phase: 'pending', url: 'https://accounts.google.com/o/oauth2/auth'}}));
  assert.equal(pending.summary, 'agyPending');
  assert.equal(pending.awaitingCode, true);
  assert.equal(pending.page, 'https://accounts.google.com/o/oauth2/auth');
  assert.equal(pending.canSignIn, false);
  const hidden = agyView(status({state: 'needs_login', signIn: {phase: 'pending', url: ''}}));
  assert.equal(hidden.awaitingCode, false, 'a caller without the challenge gets no page');
  const verifying = agyView(status({state: 'needs_login', signIn: {phase: 'verifying', url: 'https://accounts.google.com/x'}}));
  assert.equal(verifying.summary, 'agyVerifying');
  assert.equal(verifying.page, null);
  const failed = agyView(status({state: 'needs_login', signIn: {phase: 'failed', error: 'SIGN_IN_FAILED'}}));
  assert.equal(failed.summary, 'agyFailed');
  assert.equal(failed.canSignIn, true);
});

test('the switch picks agy default model when offered, else the first offered', () => {
  assert.equal(agyModel(status()), 'gemini-3.8-flash');
  assert.equal(agyModel(status({models: ['gemini-4-neon']})), 'gemini-4-neon');
  assert.equal(agyModel(status({models: []})), 'gemini-3.8-flash');
});

test('every summary has English and Chinese text', () => {
  const keys = ['agyMissing', 'agyUnsupported', 'agyNotConfigured', 'agyChecking', 'agyNeedsLogin', 'agyReady', 'agyReadyPlan',
    'agyActive', 'agyUnavailable', 'agyStarting', 'agyPending', 'agyVerifying', 'agyFailed'];
  for (const language of ['en', 'zh']) {
    const t = accountTextFor(language);
    for (const key of keys) assert.ok(t(key, {plan: 'P'}).length > 0 && t(key) !== key, `${language} ${key}`);
  }
});
