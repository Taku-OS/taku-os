import React, {useCallback, useEffect, useRef, useState} from 'react';
import {ActivityIndicator, Alert, AppState, Linking, Pressable, StyleSheet, Text, View} from 'react-native';
import * as Clipboard from 'expo-clipboard';
import {callAgent, holdSecureScreen} from '../native/agent';
import {SecretField, type SecretFieldHandle} from '../native/SecretField';
import type {AccountText, AccountTextKey} from '../i18n/accounts';
import {colors} from '../theme';

export type AccountPalette = {ink: string; sub: string; line: string; soft: string; card: string};
type Shared = {palette: AccountPalette; t: AccountText; onChanged?: () => void};

/** `accounts/read`: `code` and `url` are present only while a sign-in is pending. */
type AccountStatus = {connected: boolean; phase: string; kind: string; plan: string; code: string; url: string};
type SavedKey = {id: string; ref: string; name: string; masked: string; provider: string};
type SavedKeys = {keys?: SavedKey[]};

const danger = '#CC2A4A';
const officialHosts = ['auth.openai.com', 'chatgpt.com'];
const errorKeys: Record<string, AccountTextKey> = {
  INVALID_CREDENTIAL: 'errorInvalidCredential',
  SECRET_EMPTY: 'enterKey',
  ACCOUNT_LOGIN_FAILED: 'errorLogin',
  ACCOUNT_CANCEL_FAILED: 'errorCancel',
  KEY_OPERATION_FAILED: 'errorKeyOperation',
  USER_UNAVAILABLE: 'errorUser',
};

function failure(problem: unknown, t: AccountText): string {
  const code = problem instanceof Error ? problem.message : String(problem);
  return t(errorKeys[code] ?? 'errorGeneric');
}

function officialPage(url: string): string | null {
  const match = /^https:\/\/([^/?#]+)(?:[/?#]|$)/.exec(url);
  return match && officialHosts.includes(match[1]) ? url : null;
}

/** Holds the secure window flag for as long as [active] stays true. */
function useSecureScreen(active: boolean) {
  useEffect(() => (active ? holdSecureScreen() : undefined), [active]);
}

/** One operation at a time; a second tap while one is running is ignored. */
function useWork(t: AccountText) {
  const running = useRef(false);
  const mounted = useRef(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);
  const work = useCallback(async (operation: () => Promise<void>) => {
    if (running.current) return;
    running.current = true;
    setBusy(true);
    setError('');
    try {
      await operation();
    } catch (problem) {
      if (mounted.current) setError(failure(problem, t));
    } finally {
      running.current = false;
      if (mounted.current) setBusy(false);
    }
  }, [t]);
  const isMounted = useCallback(() => mounted.current, []);
  return {busy, error, setError, work, running, mounted, isMounted};
}

function Copy({children, color, size = 14, medium = false, selectable = false}: {
  children: React.ReactNode; color: string; size?: number; medium?: boolean; selectable?: boolean;
}) {
  return <Text selectable={selectable} style={{fontFamily: medium ? 'TakuChineseMedium' : 'TakuChinese', color, fontSize: size, includeFontPadding: false}}>
    {children}
  </Text>;
}

function Action({label, onPress, palette, primary = false, destructive = false, disabled = false}: {
  label: string; onPress: () => void; palette: AccountPalette; primary?: boolean; destructive?: boolean; disabled?: boolean;
}) {
  return <Pressable accessibilityRole="button" accessibilityState={{disabled}} disabled={disabled} onPress={onPress}
    style={({pressed}) => [styles.action, {backgroundColor: primary ? colors.brand : palette.soft, opacity: disabled ? 0.45 : pressed ? 0.65 : 1}]}>
    <Copy color={primary ? '#FFFFFF' : destructive ? danger : palette.ink} size={15} medium>{label}</Copy>
  </Pressable>;
}

function Status({busy, error, notice, palette, t}: {busy: boolean; error: string; notice: string; palette: AccountPalette; t: AccountText}) {
  return <>
    {busy ? <ActivityIndicator accessibilityLabel={t('working')} color={colors.brand}/> : null}
    {error ? <View accessibilityLiveRegion="polite"><Copy color={danger}>{error}</Copy></View> : null}
    {notice && !error ? <View accessibilityLiveRegion="polite"><Copy color={palette.sub}>{notice}</Copy></View> : null}
  </>;
}

/**
 * ChatGPT sign-in: device code or browser authorization, cancel, and connection status. The
 * runtime owns the login; this view only starts it and shows the state the runtime reports.
 */
export function ChatGptAccount({palette, t, onChanged}: Shared) {
  const [status, setStatus] = useState<AccountStatus | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  const [notice, setNotice] = useState('');
  const {busy, error, setError, work, running, mounted, isMounted} = useWork(t);
  const reading = useRef(false);
  const connected = useRef<boolean | null>(null);
  const changed = useRef(onChanged);
  useEffect(() => { changed.current = onChanged; }, [onChanged]);

  const show = useCallback((next: AccountStatus) => {
    if (!isMounted()) return;
    setStatus(next);
    setUnavailable(false);
    if (connected.current !== null && connected.current !== next.connected) changed.current?.();
    connected.current = next.connected;
  }, [isMounted]);

  useEffect(() => {
    let foreground = AppState.currentState === 'active';
    const read = async () => {
      if (!foreground || reading.current || running.current) return;
      reading.current = true;
      try {
        show(await callAgent<AccountStatus>('accounts/read'));
      } catch {
        if (mounted.current) setUnavailable(true);
      } finally {
        reading.current = false;
      }
    };
    void read();
    // The runtime reports sign-in progress only through its account state, so follow it while visible.
    const timer = setInterval(() => void read(), 1000);
    const subscription = AppState.addEventListener('change', state => {
      foreground = state === 'active';
      if (foreground) void read();
    });
    return () => { clearInterval(timer); subscription.remove(); };
  }, [mounted, running, show]);

  const challenge = status && status.phase === 'pending' ? status : null;
  const pending = challenge !== null;
  const page = challenge ? officialPage(challenge.url) : null;
  // The one-time code must not reach screenshots or the recents preview.
  useSecureScreen(pending);

  const command = (action: 'accounts/refresh' | 'accounts/login' | 'accounts/cancel', params: Record<string, unknown> = {}) =>
    void work(async () => {
      setNotice('');
      show(await callAgent<AccountStatus>(action, params));
    });
  const copy = (value: string, done: AccountTextKey) => void Clipboard.setStringAsync(value).then(() => {
    if (mounted.current) setNotice(t(done));
  }).catch(() => {});
  const open = () => {
    if (!page) { setError(t('pageUnavailable')); return; }
    void Linking.openURL(page).catch(() => { if (mounted.current) setError(t('browserRequired')); });
  };

  const summary = !status ? '' : status.connected
    ? status.kind === 'magpie' ? t('phaseMagpie') : status.plan ? t('phaseConnectedPlan', {plan: status.plan}) : t('phaseConnected')
    : status.phase === 'starting' ? t('phaseStarting')
      : pending ? t('phasePending')
        : status.phase === 'verifying' ? t('phaseVerifying')
          : status.phase === 'failed' ? t('phaseFailed') : t('phaseDisconnected');
  const idle = !!status && !status.connected && !pending && status.phase !== 'starting' && status.phase !== 'verifying';

  return <View style={styles.block}>
    <Copy color={palette.ink} size={16} medium>{t('chatGptTitle')}</Copy>
    {!status && !unavailable ? <ActivityIndicator color={colors.brand}/> : null}
    {unavailable ? <Copy color={danger}>{t('statusUnavailable')}</Copy> : null}
    {summary ? <Copy color={palette.sub}>{summary}</Copy> : null}
    {challenge ? <>
      {challenge.code ? <View style={[styles.code, {backgroundColor: palette.soft}]}>
        <Copy color={palette.sub} size={12}>{t('deviceCode')}</Copy>
        <Text accessibilityLabel={`${t('deviceCode')} ${challenge.code.split('').join(' ')}`} selectable
          style={[styles.codeText, {color: palette.ink}]}>{challenge.code}</Text>
      </View> : null}
      {challenge.code ? <Action palette={palette} label={t('copyCode')} disabled={busy} onPress={() => copy(challenge.code, 'codeCopied')}/> : null}
      <Action palette={palette} primary label={t('openPage')} disabled={busy} onPress={open}/>
      {page ? <Action palette={palette} label={t('copyLink')} disabled={busy} onPress={() => copy(page, 'linkCopied')}/> : null}
      <Action palette={palette} destructive label={t('cancelSignIn')} disabled={busy} onPress={() => command('accounts/cancel')}/>
    </> : null}
    {idle ? <>
      <Action palette={palette} primary label={t('signInDeviceCode')} disabled={busy} onPress={() => command('accounts/login', {type: 'chatgptDeviceCode'})}/>
      <Action palette={palette} label={t('signInBrowser')} disabled={busy} onPress={() => command('accounts/login', {type: 'chatgpt'})}/>
    </> : null}
    <Status busy={busy} error={error} notice={notice} palette={palette} t={t}/>
    <Action palette={palette} label={t('refreshStatus')} disabled={busy} onPress={() => command('accounts/refresh')}/>
  </View>;
}

/**
 * Saved OpenRouter keys: add, replace and remove. The key is typed into a native field and goes
 * straight to the system gateway; this component never holds it, and only masked summaries are
 * ever read back.
 */
export function OpenRouterKeys({palette, t, onChanged}: Shared) {
  const [keys, setKeys] = useState<SavedKey[] | null>(null);
  const [editor, setEditor] = useState<{existing: SavedKey | null} | null>(null);
  const [visible, setVisible] = useState(false);
  const field = useRef<SecretFieldHandle>(null);
  const [notice, setNotice] = useState('');
  const [unavailable, setUnavailable] = useState(false);
  const {busy, error, work, mounted} = useWork(t);
  useSecureScreen(editor !== null);

  const show = useCallback((view: SavedKeys) => {
    if (!mounted.current) return;
    setKeys((view.keys ?? []).filter(key => key.provider === 'openrouter'));
    setUnavailable(false);
  }, [mounted]);
  const read = useCallback(async () => show(await callAgent<SavedKeys>('accounts/keys')), [show]);
  useEffect(() => {
    let alive = true;
    void callAgent<SavedKeys>('accounts/keys').then(view => { if (alive) show(view); }).catch(() => { if (alive) setUnavailable(true); });
    return () => { alive = false; };
  }, [show]);

  const close = () => {
    setEditor(null);
    setVisible(false);
  };
  const edit = (existing: SavedKey | null) => {
    close();
    setNotice('');
    setEditor({existing});
  };
  // The editor stays open while saving: the native field holds the key until the gateway accepts it.
  const save = () => {
    const existing = editor?.existing;
    const entry = field.current;
    if (!entry) return;
    void work(async () => {
      const view = await entry.submit<SavedKeys>('accounts/saveKey', existing ? {id: existing.id, ref: existing.ref} : {});
      if (!mounted.current) return;
      close();
      if (view.keys) show(view); else await read();
      if (mounted.current) setNotice(t('keySaved'));
      onChanged?.();
    });
  };
  const remove = (key: SavedKey) => Alert.alert(t('removeKeyTitle'), key.masked, [
    {text: t('cancel'), style: 'cancel'},
    {text: t('remove'), style: 'destructive', onPress: () => void work(async () => {
      const view = await callAgent<SavedKeys>('accounts/removeKey', {id: key.id, ref: key.ref});
      if (view.keys) show(view); else await read();
      if (mounted.current) setNotice(t('keyRemoved'));
      onChanged?.();
    })},
  ]);

  return <View style={styles.block}>
    <Copy color={palette.ink} size={16} medium>{t('keysTitle')}</Copy>
    {keys === null && !unavailable ? <ActivityIndicator color={colors.brand}/> : null}
    {unavailable ? <Copy color={danger}>{t('errorGeneric')}</Copy> : null}
    {keys?.length === 0 ? <Copy color={palette.sub}>{t('noKeys')}</Copy> : null}
    {keys?.map(key => <View key={`${key.id}:${key.ref}`} style={[styles.key, {borderColor: palette.line}]}>
      <Copy color={palette.ink} size={15} medium>{`${key.name} · ${key.masked}`}</Copy>
      <View style={styles.pair}>
        <View style={styles.half}><Action palette={palette} label={t('replaceKey')} disabled={busy} onPress={() => edit(key)}/></View>
        <View style={styles.half}><Action palette={palette} destructive label={t('removeKey')} disabled={busy} onPress={() => remove(key)}/></View>
      </View>
    </View>)}
    {editor ? <View style={[styles.key, {borderColor: palette.line}]}>
      <Copy color={palette.ink} size={15} medium>{t(editor.existing ? 'replaceKeyTitle' : 'addKeyTitle')}</Copy>
      {editor.existing ? <Copy color={palette.sub}>{editor.existing.masked}</Copy> : null}
      <View style={[styles.field, {borderColor: palette.line}]}>
        <SecretField ref={field} placeholder={t('keyField')} textColor={palette.ink} placeholderColor={palette.sub}
          revealed={visible} autoFocus onSubmitEditing={save} style={styles.input}/>
        <Pressable accessibilityRole="button" accessibilityLabel={t(visible ? 'hideKey' : 'showKey')} hitSlop={8}
          onPress={() => setVisible(!visible)} style={styles.reveal}>
          <Copy color={palette.sub} size={13}>{t(visible ? 'hideKey' : 'showKey')}</Copy>
        </Pressable>
      </View>
      <View style={styles.pair}>
        <View style={styles.half}><Action palette={palette} label={t('cancel')} disabled={busy} onPress={close}/></View>
        <View style={styles.half}><Action palette={palette} primary label={t('save')} disabled={busy} onPress={save}/></View>
      </View>
    </View> : <Action palette={palette} primary label={t('addKey')} disabled={busy || keys === null} onPress={() => edit(null)}/>}
    <Status busy={busy} error={error} notice={notice} palette={palette} t={t}/>
    <Action palette={palette} label={t('refresh')} disabled={busy} onPress={() => void work(read)}/>
  </View>;
}

const styles = StyleSheet.create({
  block: {gap: 10},
  action: {minHeight: 48, borderRadius: 12, paddingHorizontal: 16, paddingVertical: 10, alignItems: 'center', justifyContent: 'center'},
  code: {borderRadius: 12, paddingVertical: 14, paddingHorizontal: 16, alignItems: 'center', gap: 6},
  codeText: {fontFamily: 'TakuChineseMedium', fontSize: 28, letterSpacing: 3, includeFontPadding: false},
  key: {borderWidth: StyleSheet.hairlineWidth, borderRadius: 12, padding: 12, gap: 10},
  pair: {flexDirection: 'row', gap: 10},
  half: {flex: 1},
  field: {flexDirection: 'row', alignItems: 'center', borderWidth: 1, borderRadius: 12, paddingLeft: 12},
  input: {flex: 1, height: 48},
  reveal: {paddingHorizontal: 12, paddingVertical: 12},
});
