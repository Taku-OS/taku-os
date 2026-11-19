import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {
  ActivityIndicator, AppState, BackHandler, Modal, Pressable, RefreshControl, ScrollView, StyleSheet, Switch, Text, TextInput,
  useWindowDimensions, View,
} from 'react-native';
import {useLocales} from 'expo-localization';
import {useRouter} from 'expo-router';
import {SafeAreaView, useSafeAreaInsets} from 'react-native-safe-area-context';
import Svg, {Circle, Path} from 'react-native-svg';
import {Icon, IconButton} from './Icon';
import {languageFor} from '../i18n';
import {permissionTextFor, type PermissionText} from '../i18n/permissions';
import {closeAgent} from '../native/agent';
import {
  accessSummary, createSnapshotGate, eventActor, filterApps, filterCount, modeChange, PERMISSION_MODES, PERMISSION_SCOPES,
  recentRequests, relativeTime, scopeEditable, scopeNote,
  type AppFilter, type AppPermissionsRepository, type AppPermissionsSnapshot, type ManagedAppEntry, type PermissionErrorCode,
  type PermissionMode, type PermissionScope, type RecentRequest,
} from '../model/appPermissions';
import {colors, containsChinese, layout, typography, type TypeRole} from '../theme';
import {useTakuFonts} from '../theme/useTakuFonts';

type Operation =
  | {kind: 'mode'; mode: PermissionMode; allowRootInYolo: boolean}
  | {kind: 'scope'; packageName: string; scope: PermissionScope; allowed: boolean}
  | {kind: 'reset'; packageName: string};
type Failure = {code: PermissionErrorCode; message: string};
type Confirmation = {kind: 'yolo'} | {kind: 'rootAll'} | {kind: 'root'; packageName: string; label: string};
type TypeFor = (role: TypeRole, content?: string) => ReturnType<typeof typography>;

const DEFAULT_POLL_MS = 5000;
const appTints = [colors.sage, colors.peach, colors.lavender, colors.mist, colors.rose, colors.sand];

function tintFor(value: string): string {
  let hash = 0;
  for (const character of value) hash = (hash * 31 + character.charCodeAt(0)) >>> 0;
  return appTints[hash % appTints.length];
}

function SearchGlyph() {
  return <Svg width={layout.glyph} height={layout.glyph} viewBox="0 0 24 24" accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
    <Circle cx="10.5" cy="10.5" r="6.5" fill="none" stroke={colors.secondary} strokeWidth="1.8"/>
    <Path d="m15.5 15.5 4.5 4.5" fill="none" stroke={colors.secondary} strokeWidth="1.8" strokeLinecap="round"/>
  </Svg>;
}

/** The DS switch drawing (controls/switch-*.svg): brand track with ink thumb on, content-300 track with white thumb off. */
function TakuSwitch({value, disabled}: {value: boolean; disabled?: boolean}) {
  return <View pointerEvents="none" importantForAccessibility="no-hide-descendants" accessibilityElementsHidden>
    <Switch value={value} disabled={disabled} trackColor={{false: colors.switchOff, true: colors.brand}}
      thumbColor={value ? colors.ink : colors.white} ios_backgroundColor={colors.switchOff}/>
  </View>;
}

function Dot({color}: {color: string}) {
  return <View style={[styles.dot, {backgroundColor: color}]}/>;
}

function AppMark({entry, size = 40, type}: {entry: Pick<ManagedAppEntry, 'packageName' | 'label'>; size?: number; type: TypeFor}) {
  const letter = Array.from(entry.label.trim())[0]?.toLocaleUpperCase() ?? '?';
  return <View style={[styles.mark, {width: size, height: size, borderRadius: size >= 48 ? 14 : 12, backgroundColor: tintFor(entry.packageName)}]}
    accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
    <Text style={[type('label', letter), styles.markLetter]}>{letter}</Text>
  </View>;
}

function Button({label, tone = 'gray', onPress, disabled, type}: {label: string; tone?: 'ink' | 'gray' | 'danger'; onPress: () => void; disabled?: boolean; type: TypeFor}) {
  return <Pressable accessibilityRole="button" accessibilityState={{disabled: Boolean(disabled)}} disabled={disabled} onPress={onPress}
    style={({pressed}) => [styles.button, tone === 'ink' && styles.buttonInk, tone === 'danger' && styles.buttonDanger, disabled && styles.dim, pressed && styles.pressed]}>
    <Text style={[type('label'), styles.buttonText, tone !== 'gray' && styles.buttonTextInverse]}>{label}</Text>
  </Pressable>;
}

function FailedStrip({title, detail, retryLabel, onRetry, type}: {title: string; detail?: string; retryLabel: string; onRetry: () => void; type: TypeFor}) {
  return <View style={styles.failed} accessibilityRole="alert">
    <View style={styles.failedCopy}>
      <View style={styles.inlineStatus}><Dot color={colors.error}/><Text style={type('meta')}>{title}</Text></View>
      {!!detail && <Text style={[type('meta'), styles.secondary]}>{detail}</Text>}
    </View>
    <Pressable accessibilityRole="button" onPress={onRetry} hitSlop={8} style={({pressed}) => [styles.linkButton, pressed && styles.pressed]}>
      <Text style={[type('label'), styles.link]}>{retryLabel}</Text>
    </Pressable>
  </View>;
}

function summaryText(entry: ManagedAppEntry, t: PermissionText): string {
  const access = accessSummary(entry.effectiveScopes);
  const parts: string[] = [];
  if (entry.grantSource !== 'default') parts.push(t(`source.${entry.grantSource}`));
  if (access.memory !== 'none') parts.push(t(access.memory === 'readWrite' ? 'memoryReadWrite' : access.memory === 'read' ? 'memoryRead' : 'memoryWrite'));
  if (access.control) parts.push(t('takuModules'));
  if (access.memory === 'none' && !access.control && !access.root) parts.push(t('noAccess'));
  if (!entry.enabled) parts.push(t('disabledApp'));
  return parts.join(' · ');
}

export function AppPermissionsScreen({repository, pollMs = DEFAULT_POLL_MS, now = Date.now}: {
  /** Keep this instance stable (for example in `useState`); a new repository triggers a fresh read. */
  repository: AppPermissionsRepository;
  /** Live refresh while visible; 0 disables polling (gallery). */
  pollMs?: number;
  /** Clock for relative request times; injectable for deterministic fixtures. */
  now?: () => number;
}) {
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const {height: windowHeight} = useWindowDimensions();
  const language = languageFor(useLocales()[0]?.languageTag);
  const zh = language === 'zh';
  const t = useMemo(() => permissionTextFor(language), [language]);
  const type = useCallback<TypeFor>((role, content) => typography(role, content === undefined ? zh : containsChinese(content)), [zh]);
  const [fonts, fontError] = useTakuFonts();

  const [snapshot, setSnapshot] = useState<AppPermissionsSnapshot | null>(null);
  const [loadError, setLoadError] = useState<Failure | null>(null);
  const [actionError, setActionError] = useState<(Failure & {retry: Operation}) | null>(null);
  const [pending, setPending] = useState<Operation | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [clock, setClock] = useState(now);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<AppFilter>('all');
  // The selected app outlives `sheetOpen` so the sheet keeps its content while it slides out.
  const [selected, setSelected] = useState<string | null>(null);
  const [sheetOpen, setSheetOpen] = useState(false);
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null);
  const [gate] = useState(createSnapshotGate);
  const mounted = useRef(true), reading = useRef(false), mutating = useRef(false);
  // An inline `now` must not change `load`'s identity, which would restart polling on every render.
  const clockSource = useRef(now);
  useEffect(() => { clockSource.current = now; }, [now]);
  const foreground = useRef(AppState.currentState !== 'background' && AppState.currentState !== 'inactive');

  const apply = useCallback((ticket: number, next: AppPermissionsSnapshot) => {
    if (!mounted.current || !gate.accept(ticket)) return;
    setSnapshot(next);
    setLoadError(null);
    setClock(clockSource.current());
  }, [gate]);

  const load = useCallback(async (pull = false) => {
    // A mutation returns the authoritative snapshot itself; never let a concurrent read race it.
    if (mutating.current || reading.current) return;
    reading.current = true;
    if (pull) setRefreshing(true);
    const ticket = gate.issue();
    try {
      const result = await repository.read();
      if (result.ok) apply(ticket, result.value);
      else if (mounted.current && gate.accept(ticket)) setLoadError(result);
    } catch (cause) {
      if (mounted.current && gate.accept(ticket)) setLoadError({code: 'OPERATION_FAILED', message: String(cause)});
    } finally {
      reading.current = false;
      if (mounted.current) setRefreshing(false);
    }
  }, [apply, gate, repository]);

  const run = useCallback(async (operation: Operation) => {
    // The ref blocks a second tap before React re-renders the disabled controls.
    if (mutating.current) return;
    mutating.current = true;
    setPending(operation);
    setActionError(null);
    const ticket = gate.issue();
    let failed = false;
    try {
      const result = operation.kind === 'mode' ? await repository.setMode(operation.mode, operation.allowRootInYolo)
        : operation.kind === 'scope' ? await repository.setScope(operation.packageName, operation.scope, operation.allowed)
          : await repository.reset(operation.packageName);
      if (result.ok) apply(ticket, result.value);
      else {
        failed = true;
        if (mounted.current) setActionError({code: result.code, message: result.message, retry: operation});
      }
    } catch (cause) {
      failed = true;
      if (mounted.current) setActionError({code: 'OPERATION_FAILED', message: String(cause), retry: operation});
    } finally {
      mutating.current = false;
      if (mounted.current) setPending(null);
    }
    // Resynchronize after a failure so the screen never keeps showing a state the gateway rejected.
    if (failed) void load();
  }, [apply, gate, load, repository]);

  useEffect(() => {
    mounted.current = true;
    // Reads the native permission policy (external system); state is set only after the async read resolves.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load();
    const timer = pollMs > 0 ? setInterval(() => { if (foreground.current) void load(); }, pollMs) : null;
    const subscription = AppState.addEventListener('change', state => {
      foreground.current = state === 'active';
      // Returning from an app that was just blocked should show its request immediately.
      if (foreground.current) void load();
    });
    return () => {
      mounted.current = false;
      if (timer) clearInterval(timer);
      subscription.remove();
    };
  }, [load, pollMs]);

  const exit = useCallback(() => {
    if (router.canGoBack()) { router.back(); return; }
    // Opened directly (deep link): leave the agent window instead of inventing a parent screen.
    router.replace('/');
    try { void closeAgent(); } catch { /* No native host (web gallery). */ }
  }, [router]);

  const apps = useMemo(() => snapshot?.apps ?? [], [snapshot]);
  const visible = useMemo(() => filterApps(apps, query, filter), [apps, query, filter]);
  const recent = useMemo(() => snapshot ? recentRequests(snapshot.recentEvents, snapshot.apps) : [], [snapshot]);
  const entry = selected ? apps.find(app => app.packageName === selected) ?? null : null;
  // An app uninstalled while its sheet is open closes the sheet; Back then leaves the screen directly.
  const sheetVisible = sheetOpen && entry !== null;

  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      if (confirmation) setConfirmation(null);
      else if (sheetVisible) setSheetOpen(false);
      else exit();
      return true;
    });
    return () => subscription.remove();
  }, [confirmation, exit, sheetVisible]);
  const busy = pending !== null;
  const openApp = (packageName: string) => {
    setSelected(packageName);
    setActionError(null);
    setSheetOpen(true);
  };
  const closeSheet = () => setSheetOpen(false);

  const chooseMode = (mode: PermissionMode) => {
    if (!snapshot || busy || snapshot.mode === mode) return;
    if (mode === 'yolo') setConfirmation({kind: 'yolo'});
    else void run({kind: 'mode', ...modeChange(mode)});
  };
  const toggleRootForAll = () => {
    if (!snapshot || busy) return;
    if (snapshot.allowRootInYolo) void run({kind: 'mode', mode: 'yolo', allowRootInYolo: false});
    else setConfirmation({kind: 'rootAll'});
  };
  const toggleScope = (app: ManagedAppEntry, scope: PermissionScope) => {
    if (!snapshot || busy || !scopeEditable(snapshot, scope)) return;
    const allowed = !app.effectiveScopes[scope];
    if (scope === 'taku.root' && allowed) setConfirmation({kind: 'root', packageName: app.packageName, label: app.label});
    else void run({kind: 'scope', packageName: app.packageName, scope, allowed});
  };
  const confirm = () => {
    const request = confirmation;
    setConfirmation(null);
    if (!request) return;
    if (request.kind === 'yolo') void run({kind: 'mode', ...modeChange('yolo')});
    else if (request.kind === 'rootAll') void run({kind: 'mode', mode: 'yolo', allowRootInYolo: true});
    else void run({kind: 'scope', packageName: request.packageName, scope: 'taku.root', allowed: true});
  };

  const header = <View style={styles.header}>
    <IconButton name="arrow_left" label={t('back')} onPress={exit}/>
    <Text accessibilityRole="header" numberOfLines={1} style={[type('heading'), styles.headerTitle]}>{t('title')}</Text>
  </View>;

  if (!fonts && !fontError) return <View style={styles.center}><ActivityIndicator color={colors.ink}/></View>;

  if (!snapshot) return <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
    {header}
    <View style={styles.center}>
      {loadError ? <View style={styles.stateBlock}>
        <Text style={[type('context'), styles.centerText]}>{t('readFailed')}</Text>
        <Text style={[type('meta'), styles.secondary, styles.centerText]}>{t.error(loadError.code)}</Text>
        <Button label={t('retry')} tone="ink" onPress={() => void load()} type={type}/>
      </View> : <View style={styles.stateBlock}>
        <ActivityIndicator color={colors.ink}/>
        <Text style={[type('meta'), styles.secondary]}>{t('loading')}</Text>
      </View>}
    </View>
  </SafeAreaView>;

  const failedStrip = actionError && <FailedStrip title={t('updateFailed')} detail={t.error(actionError.code)} retryLabel={t('retry')}
    onRetry={() => void run(actionError.retry)} type={type}/>;

  const modeCard = <View style={styles.card} accessibilityRole="radiogroup">
    {PERMISSION_MODES.map((mode, index) => {
      const checked = snapshot.mode === mode;
      const target = pending?.kind === 'mode' && pending.mode === mode && !checked;
      return <View key={mode} style={index > 0 && styles.divider}>
        <Pressable accessibilityRole="radio" accessibilityLabel={`${t.mode(mode)}. ${t.modeBody(mode)}`}
          accessibilityState={{checked, disabled: busy, busy: target}} disabled={busy} onPress={() => chooseMode(mode)}
          style={({pressed}) => [styles.modeRow, pressed && styles.pressedRow]}>
          <View style={styles.flexCopy}>
            <View style={styles.titleLine}>
              <Text style={[type('label'), styles.shrink]}>{t.mode(mode)}</Text>
              {mode === 'deny' && <View style={styles.pill}><Text style={type('caption')}>{t('recommended')}</Text></View>}
            </View>
            <Text style={[type('meta'), styles.secondary]}>{t.modeBody(mode)}</Text>
          </View>
          {target ? <View style={styles.radioSlot}><ActivityIndicator size="small" color={colors.ink}/></View>
            : <View style={[styles.radio, checked && styles.radioChecked]}>{checked && <View style={styles.radioInner}/>}</View>}
        </Pressable>
        {mode === 'yolo' && checked && (() => {
          const rootPending = pending?.kind === 'mode' && pending.mode === 'yolo';
          const value = rootPending ? pending.allowRootInYolo : snapshot.allowRootInYolo;
          return <Pressable accessibilityRole="switch" accessibilityLabel={t('rootForAll')} accessibilityHint={t(value ? 'rootForAllOn' : 'rootForAllOff')}
            accessibilityState={{checked: value, disabled: busy, busy: rootPending}} disabled={busy} onPress={toggleRootForAll}
            style={({pressed}) => [styles.dangerRow, value && styles.dangerRowOn, pressed && styles.pressed]}>
            <View style={styles.flexCopy}>
              <View style={styles.titleLine}>
                <Text style={[type('label'), styles.shrink]}>{t('rootForAll')}</Text>
                <View style={styles.riskPill}><Text style={[type('caption'), styles.riskText]}>{t('highRisk')}</Text></View>
              </View>
              <Text style={[type('meta'), value ? styles.riskText : styles.secondary]}>{t(value ? 'rootForAllOn' : 'rootForAllOff')}</Text>
            </View>
            {rootPending && <ActivityIndicator size="small" color={colors.ink}/>}
            <TakuSwitch value={value} disabled={busy}/>
          </Pressable>;
        })()}
      </View>;
    })}
  </View>;

  const recentRow = ({event, app}: RecentRequest, index: number) => {
    const actor = eventActor(event.by);
    const status = event.decision === 'denied' ? t(actor === 'agent' ? 'agentBlocked' : 'blocked') : t(actor === 'agent' ? 'agentAllowed' : 'allowed');
    const facts = [t.scope(event.scope), t.time(relativeTime(event.at, clock))].join(' · ');
    return <Pressable key={`${event.packageName}-${event.scope}`} accessibilityRole="button" accessibilityLabel={`${app?.label ?? event.label}. ${status}. ${facts}`}
      accessibilityState={{disabled: !app}} disabled={!app} onPress={() => app && openApp(app.packageName)}
      style={({pressed}) => [styles.listRow, index > 0 && styles.divider, pressed && styles.pressedRow]}>
      <AppMark entry={app ?? event} type={type}/>
      <View style={styles.flexCopy}>
        <Text numberOfLines={1} style={type('label', app?.label ?? event.label)}>{app?.label ?? event.label}</Text>
        <View style={styles.wrapLine}>
          <View style={styles.inlineStatus}><Dot color={event.decision === 'denied' ? colors.attention : colors.successDot}/><Text style={type('meta')}>{status}</Text></View>
          <Text style={[type('meta'), styles.secondary, styles.shrink]}>{app ? facts : `${facts} · ${t('notInstalled')}`}</Text>
        </View>
      </View>
      {app && <Icon name="chevron_right" size={layout.glyph} color={colors.secondary}/>}
    </Pressable>;
  };

  const appRow = (app: ManagedAppEntry, index: number) => {
    const root = app.effectiveScopes['taku.root'];
    const summary = summaryText(app, t);
    return <Pressable key={app.packageName} accessibilityRole="button" accessibilityLabel={`${app.label}. ${app.packageName}. ${summary}${root ? ` · ${t('root')}` : ''}`}
      onPress={() => openApp(app.packageName)} style={({pressed}) => [styles.listRow, index > 0 && styles.divider, pressed && styles.pressedRow]}>
      <AppMark entry={app} type={type}/>
      <View style={styles.flexCopy}>
        <Text numberOfLines={1} style={type('label', app.label)}>{app.label}</Text>
        <Text numberOfLines={1} style={[type('caption', app.packageName), styles.secondary]}>{app.packageName}</Text>
        <View style={styles.wrapLine}>
          {root && <View style={styles.riskPill}><Text style={[type('caption'), styles.riskText]}>{t('root')}</Text></View>}
          <Text style={[type('meta'), styles.secondary, styles.shrink]}>{summary}</Text>
        </View>
      </View>
      <Icon name="chevron_right" size={layout.glyph} color={colors.secondary}/>
    </Pressable>;
  };

  const filters: AppFilter[] = ['all', 'allowed', 'custom', ...(filter === 'root' || filterCount(apps, 'root') > 0 ? ['root' as const] : [])];
  const appsSection = <>
    <View style={styles.sectionHeader}>
      <Text accessibilityRole="header" style={[type('context'), styles.shrink]}>{t('appsSection')}</Text>
      {apps.length > 0 && <Text style={[type('meta'), styles.secondary]}>{t.appCount(visible.length)}</Text>}
    </View>
    {apps.length > 0 && <>
      <View style={styles.search}>
        <SearchGlyph/>
        <TextInput value={query} onChangeText={setQuery} placeholder={t('search')} placeholderTextColor={colors.secondary}
          accessibilityLabel={t('search')} autoCapitalize="none" autoCorrect={false} returnKeyType="search"
          style={[type('body', query || undefined), styles.searchInput]}/>
        {!!query && <IconButton name="close" label={t('clearSearch')} onPress={() => setQuery('')} size={16} color={colors.secondary}/>}
      </View>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chips} keyboardShouldPersistTaps="handled">
        {filters.map(value => <Pressable key={value} accessibilityRole="button" accessibilityState={{selected: filter === value}} hitSlop={{top: 4, bottom: 4}}
          onPress={() => setFilter(value)} style={({pressed}) => [styles.chip, filter === value && styles.chipSelected, pressed && styles.pressed]}>
          <Text style={[type('body'), filter === value && styles.inverse]}>{t(`filter.${value}`)}</Text>
        </Pressable>)}
      </ScrollView>
    </>}
    {apps.length === 0 ? <View style={styles.emptyCard}>
      <Text style={[type('label'), styles.centerText]}>{t('noApps')}</Text>
      <Text style={[type('meta'), styles.secondary, styles.centerText]}>{t('noAppsBody')}</Text>
    </View> : visible.length === 0 ? <View style={styles.emptyCard}>
      <Text style={[type('label'), styles.centerText]}>{t('noMatches')}</Text>
      <Button label={t('clearFilters')} onPress={() => { setQuery(''); setFilter('all'); }} type={type}/>
    </View> : <View style={styles.card}>{visible.map(appRow)}</View>}
  </>;

  const scopeRow = (app: ManagedAppEntry, scope: PermissionScope, index: number) => {
    const editable = scopeEditable(snapshot, scope);
    const rowPending = pending?.kind === 'scope' && pending.packageName === app.packageName && pending.scope === scope;
    const value = rowPending ? pending.allowed : app.effectiveScopes[scope];
    const note = scopeNote(snapshot, app, scope);
    const root = scope === 'taku.root';
    return <Pressable key={scope} accessibilityRole="switch" accessibilityLabel={t.scope(scope)} accessibilityHint={t.scopeHint(scope)}
      accessibilityState={{checked: value, disabled: !editable || busy, busy: rowPending}} disabled={!editable || busy} onPress={() => toggleScope(app, scope)}
      style={({pressed}) => [styles.scopeRow, index > 0 && styles.divider, root && value && styles.scopeRowRisk, pressed && styles.pressedRow]}>
      <View style={styles.flexCopy}>
        <View style={styles.titleLine}>
          <Text style={[type('label'), styles.shrink]}>{t.scope(scope)}</Text>
          {root && <View style={styles.riskPill}><Text style={[type('caption'), styles.riskText]}>{t('highRisk')}</Text></View>}
        </View>
        <Text style={[type('meta'), styles.secondary]}>{t.scopeHint(scope)}</Text>
        {note && <Text style={type('meta')}>{t(`note.${note}`)}</Text>}
      </View>
      {rowPending && <ActivityIndicator size="small" color={colors.ink}/>}
      <TakuSwitch value={value} disabled={!editable || busy}/>
    </Pressable>;
  };

  const sheet = entry && <View style={[styles.sheet, {maxHeight: windowHeight * 0.88, paddingBottom: Math.max(insets.bottom, 16)}]}>
    <View style={styles.grabber}/>
    <View style={styles.sheetHeader}>
      <AppMark entry={entry} size={48} type={type}/>
      <View style={styles.flexCopy}>
        <Text accessibilityRole="header" style={type('context', entry.label)}>{entry.label}</Text>
        <Text selectable style={[type('caption', entry.packageName), styles.secondary]}>{entry.packageName}</Text>
        <Text style={[type('caption'), styles.secondary]}>
          {[entry.versionName ? `${t('version')} ${entry.versionName}` : null, entry.grantSource !== 'default' ? t(`source.${entry.grantSource}`) : null, entry.enabled ? null : t('disabledApp')]
            .filter(Boolean).join(' · ')}
        </Text>
      </View>
      <IconButton name="close" label={t('close')} onPress={closeSheet}/>
    </View>
    <ScrollView style={styles.sheetScroll} contentContainerStyle={styles.sheetBody} keyboardShouldPersistTaps="handled">
      {entry.grantSource === 'auto' && !!entry.autoReason && <View style={styles.assessment}>
        <Text style={[type('caption'), styles.secondary]}>{t('agentAssessment')}</Text>
        <Text style={type('meta', t.autoReason(entry.autoReason))}>{t.autoReason(entry.autoReason)}</Text>
      </View>}
      {snapshot.mode === 'deny' && <View style={styles.banner}>
        <Text style={type('meta')}>{t('denyBanner')}</Text>
        <Button label={t('switchToManual')} tone="ink" disabled={busy} onPress={() => void run({kind: 'mode', ...modeChange('manual')})} type={type}/>
      </View>}
      <View style={styles.scopeCard}>{PERMISSION_SCOPES.map((scope, index) => scopeRow(entry, scope, index))}</View>
      {failedStrip}
      {entry.grantSource !== 'default' && <Button label={t('resetToMode')} disabled={busy} onPress={() => void run({kind: 'reset', packageName: entry.packageName})} type={type}/>}
    </ScrollView>
  </View>;

  const confirmCopy = confirmation?.kind === 'root'
    ? {title: t('confirmRootTitle', {app: confirmation.label}), body: t('confirmRootBody', {app: confirmation.label}), action: t('confirmRootAction')}
    : confirmation?.kind === 'rootAll' ? {title: t('confirmRootAllTitle'), body: t('confirmRootAllBody'), action: t('confirmRootAllAction')}
      : {title: t('confirmYoloTitle'), body: t('confirmYoloBody'), action: t('confirmYoloAction')};

  return <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
    {header}
    <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled"
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void load(true)} colors={[colors.ink]} tintColor={colors.ink}/>}>
      {loadError && <FailedStrip title={t('refreshFailed')} detail={t.error(loadError.code)} retryLabel={t('retry')} onRetry={() => void load()} type={type}/>}
      {!sheetVisible && failedStrip}
      <Text accessibilityRole="header" style={[type('context'), styles.sectionTitle]}>{t('modeSection')}</Text>
      {modeCard}
      {recent.length > 0 && <>
        <Text accessibilityRole="header" style={[type('context'), styles.sectionTitle]}>{t('recentSection')}</Text>
        <View style={styles.card}>{recent.map(recentRow)}</View>
      </>}
      {appsSection}
    </ScrollView>
    <Modal visible={sheetVisible} transparent animationType="slide" onRequestClose={closeSheet} statusBarTranslucent>
      <View style={styles.overlay}>
        <Pressable style={styles.flex} accessibilityRole="button" accessibilityLabel={t('close')} onPress={closeSheet}/>
        {sheet}
      </View>
    </Modal>
    <Modal visible={confirmation !== null} transparent animationType="fade" onRequestClose={() => setConfirmation(null)} statusBarTranslucent>
      <View style={[styles.overlay, styles.dialogOverlay]}>
        <View style={styles.dialog} accessibilityViewIsModal>
          <Text accessibilityRole="header" style={type('context')}>{confirmCopy.title}</Text>
          <Text style={[type('body'), styles.secondary]}>{confirmCopy.body}</Text>
          <View style={styles.dialogActions}>
            <View style={styles.flex}><Button label={t('cancel')} onPress={() => setConfirmation(null)} type={type}/></View>
            <View style={styles.flex}><Button label={confirmCopy.action} tone="danger" onPress={confirm} type={type}/></View>
          </View>
        </View>
      </View>
    </Modal>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  screen: {flex: 1, backgroundColor: colors.page},
  center: {flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.page, paddingHorizontal: layout.gutter},
  flex: {flex: 1},
  header: {minHeight: 64, flexDirection: 'row', alignItems: 'center', paddingHorizontal: 4, gap: 4},
  headerTitle: {flex: 1, minWidth: 0},
  content: {paddingHorizontal: layout.gutter, paddingBottom: 40, gap: 12},
  stateBlock: {alignItems: 'center', gap: 12, maxWidth: 320},
  centerText: {textAlign: 'center'},
  secondary: {color: colors.secondary},
  inverse: {color: colors.white},
  shrink: {flexShrink: 1},
  sectionTitle: {marginTop: 12},
  sectionHeader: {marginTop: 12, flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between', gap: 12},
  card: {backgroundColor: colors.surface, borderRadius: layout.radius, overflow: 'hidden'},
  divider: {borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: colors.border},
  pressed: {opacity: 0.72},
  pressedRow: {backgroundColor: colors.subtle},
  dim: {opacity: 0.38},
  flexCopy: {flex: 1, minWidth: 0, gap: 2},
  titleLine: {flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: 8},
  wrapLine: {flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', columnGap: 8, rowGap: 4, marginTop: 2},
  inlineStatus: {flexDirection: 'row', alignItems: 'center', gap: 6},
  dot: {width: 8, height: 8, borderRadius: 4},
  pill: {borderRadius: 12, paddingHorizontal: 8, paddingVertical: 2, backgroundColor: colors.control},
  riskPill: {borderRadius: 12, paddingHorizontal: 8, paddingVertical: 2, backgroundColor: colors.errorSurface},
  riskText: {color: colors.error},
  modeRow: {minHeight: 72, padding: layout.gutter, flexDirection: 'row', alignItems: 'center', gap: layout.gap},
  radioSlot: {width: 24, height: 24, alignItems: 'center', justifyContent: 'center'},
  radio: {width: 24, height: 24, borderRadius: 12, borderWidth: 2, borderColor: colors.switchOff, alignItems: 'center', justifyContent: 'center'},
  radioChecked: {borderColor: colors.ink},
  radioInner: {width: 12, height: 12, borderRadius: 6, backgroundColor: colors.ink},
  dangerRow: {marginHorizontal: layout.gutter, marginBottom: layout.gutter, padding: layout.gap, borderRadius: 12, backgroundColor: colors.subtle,
    flexDirection: 'row', alignItems: 'center', gap: layout.gap, minHeight: 72},
  dangerRowOn: {backgroundColor: colors.errorSurface},
  listRow: {minHeight: 72, paddingHorizontal: layout.gutter, paddingVertical: 12, flexDirection: 'row', alignItems: 'center', gap: layout.gap},
  mark: {alignItems: 'center', justifyContent: 'center', overflow: 'hidden'},
  markLetter: {color: colors.ink},
  search: {minHeight: 48, borderRadius: 12, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.surface, paddingLeft: 14,
    flexDirection: 'row', alignItems: 'center', gap: 8},
  searchInput: {flex: 1, minHeight: 48, paddingVertical: 12, paddingRight: 14, color: colors.ink},
  chips: {gap: 8, paddingVertical: 4},
  chip: {minHeight: 40, borderRadius: 20, paddingHorizontal: layout.gutter, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.control},
  chipSelected: {backgroundColor: colors.ink},
  emptyCard: {backgroundColor: colors.surface, borderRadius: layout.radius, padding: 24, alignItems: 'center', gap: 12},
  button: {minHeight: layout.touch, borderRadius: layout.buttonRadius, paddingVertical: 12, paddingHorizontal: layout.gutter,
    alignItems: 'center', justifyContent: 'center', backgroundColor: colors.control},
  buttonInk: {backgroundColor: colors.ink},
  buttonDanger: {backgroundColor: colors.error},
  buttonText: {color: colors.ink, textAlign: 'center'},
  buttonTextInverse: {color: colors.white},
  failed: {backgroundColor: colors.errorSurface, borderRadius: 12, paddingVertical: 12, paddingLeft: layout.gutter, paddingRight: 8,
    flexDirection: 'row', alignItems: 'center', gap: 8},
  failedCopy: {flex: 1, minWidth: 0, gap: 2},
  linkButton: {minHeight: layout.touch, paddingHorizontal: 8, justifyContent: 'center'},
  link: {color: colors.ink, textDecorationLine: 'underline'},
  overlay: {flex: 1, justifyContent: 'flex-end', backgroundColor: colors.overlay},
  sheet: {backgroundColor: colors.surface, borderTopLeftRadius: 24, borderTopRightRadius: 24, paddingTop: 8},
  grabber: {width: 36, height: 4, borderRadius: 2, alignSelf: 'center', backgroundColor: colors.switchOff, marginBottom: 4},
  sheetHeader: {flexDirection: 'row', alignItems: 'flex-start', gap: layout.gap, paddingLeft: layout.gutter, paddingRight: 4, paddingTop: 8},
  sheetScroll: {flexGrow: 0, flexShrink: 1},
  sheetBody: {paddingHorizontal: layout.gutter, paddingTop: layout.gutter, paddingBottom: 8, gap: layout.gap},
  assessment: {backgroundColor: colors.mist, borderRadius: 12, padding: layout.gap, gap: 4},
  banner: {backgroundColor: colors.subtle, borderRadius: 12, padding: layout.gutter, gap: layout.gap},
  scopeCard: {borderRadius: 12, borderWidth: StyleSheet.hairlineWidth, borderColor: colors.border, overflow: 'hidden'},
  scopeRow: {minHeight: 72, paddingHorizontal: layout.gap, paddingVertical: 12, flexDirection: 'row', alignItems: 'center', gap: layout.gap},
  scopeRowRisk: {backgroundColor: colors.errorSurface},
  dialogOverlay: {justifyContent: 'center', paddingHorizontal: layout.gutter},
  dialog: {backgroundColor: colors.surface, borderRadius: layout.radius, padding: 20, gap: layout.gap, maxWidth: 440, width: '100%', alignSelf: 'center'},
  dialogActions: {flexDirection: 'row', gap: 8, marginTop: 8},
});
