import React, {useCallback, useEffect, useRef, useState} from 'react';
import {ActivityIndicator, Alert, AppState, BackHandler, Modal, Pressable, ScrollView, StyleSheet, Switch, Text, useColorScheme, View} from 'react-native';
import {useLocalSearchParams, useRouter} from 'expo-router';
import {useLocales} from 'expo-localization';
import {StatusBar} from 'expo-status-bar';
import {SafeAreaView} from 'react-native-safe-area-context';
import {languageFor} from '../i18n';
import {workspaceText, type WorkspaceText} from '../i18n/workspaces';
import {sameWorkspace, workspaceCanPreview, workspaceLease, type ManagedWorkspace, type WorkspaceApps, type WorkspaceCatalog, type WorkspaceLease} from '../model/workspaces';
import {AgentGatewayError, callAgent, closeAgent, type AgentAction} from '../native/agent';
import {colors, layout, typography} from '../theme';
import {useTakuFonts} from '../theme/useTakuFonts';

function status(screen: ManagedWorkspace, t: WorkspaceText): string {
  if (screen.status === 'cleanup_failed') return t('cleanup');
  if (['stale', 'disconnected', 'unavailable', 'rebind_required'].includes(screen.status)) return t('disconnected');
  if (screen.stopping || screen.status === 'stopping' || screen.controlOwner === 'stopping') return t('stopping');
  if (screen.controlOwner === 'user') return t('userControl');
  if (screen.controlOwner === 'paused') return t('agentPaused');
  if (screen.resumeRequired || screen.status === 'awaiting_resume') return t('resume');
  if (screen.status === 'waiting_for_app') return screen.waitReason?.text || t('waitingApp');
  if (screen.waitingForUser || ['waiting', 'waiting_for_user'].includes(screen.status)) return t('waiting');
  if (screen.activeTurnId || ['running', 'working'].includes(screen.status)) return t('working');
  if (screen.controlOwner === 'agent' || ['idle', 'ready'].includes(screen.status)) return t('ready');
  return t('unknown');
}

function errorText(code: string, t: WorkspaceText): string {
  if (code === 'USER_UNAVAILABLE' || code === 'USER_LOCKED') return t('userUnavailable');
  if (code === 'THREAD_UNAVAILABLE') return t('threadUnavailable');
  if (code === 'AGENT_FRONTEND_UNAVAILABLE') return t('frontendUnavailable');
  if (/GENERATION|OWNER_MISMATCH|NOT_BOUND|REBIND|CHANGED|INVALID_SCREEN/.test(code)) return t('changed');
  if (/BUSY|STOPPING|STOP_UNCONFIRMED/.test(code)) return t('busy');
  if (/DENIED|DISABLED|NOT_ALLOWED/.test(code)) return t('policy');
  return t('failed');
}

export default function WorkspaceManagementRoute() {
  const {threadId, screenKey, generation} = useLocalSearchParams<{threadId?: string | string[]; screenKey?: string | string[]; generation?: string | string[]}>();
  const linked = threadId !== undefined || screenKey !== undefined || generation !== undefined;
  const lease = linked ? workspaceLease({threadId, screenKey, generation}) : null;
  const screen = lease ? `${lease.threadId}/${lease.screenKey}/${lease.generation}` : linked ? 'invalid' : 'list';
  return <WorkspaceManagementScreen key={screen} initialLease={lease} invalidLease={linked && !lease}/>;
}

function WorkspaceButton({title, onPress, disabled = false, destructive = false, palette, chinese}: {
  title: string; onPress: () => void; disabled?: boolean; destructive?: boolean;
  palette: {control: string; ink: string}; chinese: boolean;
}) {
  return <Pressable accessibilityRole="button" accessibilityState={{disabled}} disabled={disabled} onPress={onPress}
    style={({pressed}) => [styles.button, {backgroundColor: palette.control, opacity: disabled ? 0.4 : pressed ? 0.65 : 1}]}>
    <Text style={[typography('label', chinese), {color: destructive ? colors.error : palette.ink}]}>{title}</Text>
  </Pressable>;
}

function WorkspaceManagementScreen({initialLease, invalidLease}: {initialLease: WorkspaceLease | null; invalidLease: boolean}) {
  const router = useRouter();
  const language = languageFor(useLocales()[0]?.languageTag), t = workspaceText(language);
  const [fonts, fontError] = useTakuFonts();
  const dark = useColorScheme() === 'dark';
  const palette = dark
    ? {page: '#141416', surface: '#242427', ink: '#F5F5F5', secondary: '#A8A8AE', border: '#38383D', control: '#343438'}
    : colors;
  const [data, setData] = useState<WorkspaceCatalog | null>(null);
  const [selected, setSelected] = useState(initialLease);
  const [error, setError] = useState(invalidLease ? 'INVALID_SCREEN_LEASE' : '');
  const [notice, setNotice] = useState<'closed' | 'returnFailed' | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [picker, setPicker] = useState<WorkspaceApps | null>(null);
  const [blocked, setBlocked] = useState<string[]>([]);
  const [limitOpen, setLimitOpen] = useState(false);
  const mounted = useRef(false), foreground = useRef(AppState.currentState === 'active');
  const changing = useRef(false), reading = useRef(false), revision = useRef(0);

  const refresh = useCallback(async () => {
    if (!mounted.current || !foreground.current || changing.current || reading.current) return;
    const version = ++revision.current;
    reading.current = true;
    try {
      const result = await callAgent<WorkspaceCatalog>('workspaces/list');
      if (mounted.current && version === revision.current) {
        setData(result);
      }
    } catch (failure) {
      if (mounted.current && version === revision.current) {
        setData(null);
        setError(failure instanceof AgentGatewayError ? failure.code : 'SOURCE_UNAVAILABLE');
      }
    } finally {
      reading.current = false;
      if (mounted.current && version === revision.current) setLoading(false);
    }
  }, [setData, setError, setLoading]);

  useEffect(() => {
    mounted.current = true;
    const invalidateRead = () => { ++revision.current; };
    const firstRead = setTimeout(() => { void refresh(); }, 0);
    const timer = setInterval(() => { void refresh(); }, 4000);
    const subscription = AppState.addEventListener('change', state => {
      foreground.current = state === 'active';
      if (foreground.current) void refresh();
      else invalidateRead();
    });
    return () => {
      mounted.current = false;
      invalidateRead();
      clearTimeout(firstRead);
      clearInterval(timer);
      subscription.remove();
    };
  }, [refresh]);

  const run = async <T,>(action: AgentAction, input: Record<string, unknown> = {}): Promise<T | undefined> => {
    if (!mounted.current || !foreground.current || changing.current) return undefined;
    changing.current = true;
    ++revision.current;
    setBusy(true);
    setLoading(false);
    setError('');
    setNotice(null);
    try {
      return await callAgent<T>(action, input);
    } catch (failure) {
      if (mounted.current) setError(failure instanceof AgentGatewayError ? failure.code : 'SOURCE_UNAVAILABLE');
      return undefined;
    } finally {
      changing.current = false;
      if (mounted.current) {
        setBusy(false);
        void refresh();
      }
    }
  };

  const back = useCallback(() => {
    if (selected) { setSelected(null); return; }
    if (router.canGoBack()) router.back();
    else void closeAgent().catch(() => router.replace('/'));
  }, [router, selected, setSelected]);
  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => { back(); return true; });
    return () => subscription.remove();
  }, [back]);

  const screen = selected ? data?.screens.find(value => sameWorkspace(value, selected)) : undefined;
  const range = data?.limits?.maxActiveScreens;
  const count = data ? new Set(data.screens.map(value => value.threadId)).size : 0;
  const copy = (value: string, secondary = false) => <Text style={[typography(secondary ? 'meta' : 'body', language === 'zh'), {color: secondary ? palette.secondary : palette.ink}]}>{value}</Text>;
  const heading = (value: string) => <Text accessibilityRole="header" style={[typography('context', language === 'zh'), {color: palette.ink}]}>{value}</Text>;
  const buttonStyle = {palette, chinese: language === 'zh'};
  const panel = [styles.panel, {backgroundColor: palette.surface, borderColor: palette.border}];

  const closeWorkspace = () => {
    if (!screen) return;
    const lease = workspaceLease(screen)!;
    Alert.alert(t('closeTitle'), t('closeCopy'), [
      {text: t('cancel'), style: 'cancel'},
      {text: t('close'), style: 'destructive', onPress: () => {
        const allocation = screen.allocationId ? {allocationId: screen.allocationId} : {};
        void run<{opened?: boolean; returnError?: string}>('workspaces/release', {...lease, ...allocation, returnToThread: true}).then(result => {
          if (result && mounted.current) {
            setSelected(null);
            setNotice(result.returnError ? 'returnFailed' : 'closed');
          }
        });
      }},
    ]);
  };
  const chooseApps = async () => {
    const result = await run<WorkspaceApps>('workspaces/apps');
    if (result && mounted.current) {
      setPicker(result);
      setBlocked(result.policy.blockedPackages);
    }
  };
  const setLimit = async (value: number) => {
    const result = await run<WorkspaceCatalog>('workspaces/configure', {patch: {maxActiveScreens: value}});
    if (result && mounted.current) setLimitOpen(false);
  };
  const saveApps = async () => {
    const result = await run<WorkspaceCatalog>('workspaces/configure', {patch: {blockedPackages: blocked}});
    if (result && mounted.current) setPicker(null);
  };
  const limitChoices = [];
  if (range) for (let value = range.min; value <= range.max; value++) {
    limitChoices.push(<WorkspaceButton key={value} {...buttonStyle}
      title={`${value}${value === data?.policy.maxActiveScreens ? ' ✓' : ''}`} disabled={busy}
      onPress={() => { void setLimit(value); }}/>);
  }

  if (!fonts && !fontError) return <SafeAreaView style={[styles.root, {backgroundColor: palette.page}]}><ActivityIndicator accessibilityLabel={t('loading')}/></SafeAreaView>;
  return <SafeAreaView style={[styles.root, {backgroundColor: palette.page}]}>
    <StatusBar style={dark ? 'light' : 'dark'}/>
    <ScrollView contentContainerStyle={styles.content}>
      <View style={styles.header}>
        <WorkspaceButton {...buttonStyle} title={t('back')} onPress={back}/>
        <WorkspaceButton {...buttonStyle} title={t('refresh')} onPress={() => { setError(''); void refresh(); }} disabled={busy || loading}/>
      </View>
      <Text accessibilityRole="header" style={[typography('heading', language === 'zh'), {color: palette.ink}]}>{selected ? t('details') : t('title')}</Text>
      {copy(t('summary'), true)}
      {loading && <ActivityIndicator accessibilityLabel={t('loading')} color={colors.brand}/>}
      {!!error && <View accessibilityLiveRegion="polite" style={panel}>{copy(errorText(error, t))}{copy(error, true)}</View>}
      {!!notice && <View accessibilityLiveRegion="polite" style={panel}>{copy(t(notice))}</View>}
      {!data && !loading && copy(t('unavailable'))}
      {selected && data && !screen && <View style={panel}>{copy(t('changed'))}<WorkspaceButton {...buttonStyle} title={t('active')} onPress={() => setSelected(null)}/></View>}
      {screen && <>
        <View style={panel}>
          {heading(screen.appLabel || screen.packageName || t('appTask'))}
          {copy(screen.taskTitle || screen.label || t('appTask'))}
          {copy(`${t('status')} · ${status(screen, t)}`)}
          {copy(`${t('input')} · ${screen.status === 'cleanup_failed' || screen.controlOwner === 'paused' ? t('paused') : screen.controlOwner === 'user' ? t('you') : t('agent')}`)}
          {copy(t('shared'), true)}
          {screen.status === 'cleanup_failed' && copy(t('cleanupCopy'), true)}
          {screen.agentAllowed === false && copy(t('blocked'), true)}
          {!!screen.lastError && copy(screen.lastError, true)}
        </View>
        <View style={panel}>
          <WorkspaceButton {...buttonStyle} title={t('preview')} onPress={() => { void run('workspaces/preview', {...workspaceLease(screen)!}); }} disabled={busy || !workspaceCanPreview(screen)}/>
          {copy(t('controlsCopy'), true)}
          <WorkspaceButton {...buttonStyle} title={t('pause')} onPress={() => { void run('workspaces/pause', {...workspaceLease(screen)!}); }} disabled={busy || !workspaceCanPreview(screen) || screen.controlOwner === 'paused'}/>
          <WorkspaceButton {...buttonStyle} title={t('open')} onPress={() => { void run('workspaces/open', {...workspaceLease(screen)!}); }} disabled={busy}/>
          <WorkspaceButton {...buttonStyle} title={t('close')} onPress={closeWorkspace} disabled={busy || screen.status === 'cleanup_failed'} destructive/>
        </View>
      </>}
      {!selected && data && <>
        {heading(t('active'))}
        {copy(language === 'zh' ? `${data.screens.length} 个工作区 · ${count} 个任务` : `${data.screens.length} ${data.screens.length === 1 ? 'workspace' : 'workspaces'} · ${count} ${count === 1 ? 'task' : 'tasks'}`, true)}
        {!data.screens.length && <View style={panel}>{heading(t('empty'))}{copy(t('emptyCopy'), true)}</View>}
        {data.screens.map(value => <Pressable key={`${value.threadId}/${value.screenKey}/${value.generation}`} accessibilityRole="button"
          onPress={() => { setSelected(workspaceLease(value)); setError(''); setNotice(null); }} style={panel}>
          {heading(value.appLabel || value.packageName || t('appTask'))}
          {copy(value.taskTitle || value.label || t('appTask'))}{copy(status(value, t), true)}
        </Pressable>)}
        {!!data.revocations?.length && <View style={panel}>
          {heading(t('revocations'))}
          {data.revocations.map(item => <View key={`${item.threadId}/${item.screenKey}/${item.generation}`} style={styles.section}>
            {copy(item.taskTitle || t('appTask'))}{copy(item.packageNames?.join(', ') || '', true)}
            {copy(item.status === 'pending' ? t('pending') : item.status === 'released' ? t('released') : item.status === 'retained' ? t('retained') : item.status === 'superseded' ? t('superseded') : t('cleanup'), true)}
            {!!item.error && copy(item.error, true)}
          </View>)}
        </View>}
        {heading(t('preferences'))}
        <View style={panel}>
          <View style={styles.row}><View style={styles.flex}>{heading(t('allow'))}{copy(t('allowCopy'), true)}</View>
            <Switch accessibilityLabel={t('allow')} disabled={busy} value={data.policy.allowBackground}
              onValueChange={value => { void run('workspaces/configure', {patch: {allowBackground: value}}); }}/></View>
          <View style={styles.section}>{heading(t('limit'))}{copy(t('limitCopy'), true)}
            <WorkspaceButton {...buttonStyle} title={String(data.policy.maxActiveScreens)} onPress={() => setLimitOpen(true)} disabled={busy || !range}/>
          </View>
          <View style={styles.section}><WorkspaceButton {...buttonStyle} title={t('apps')} onPress={() => { void chooseApps(); }} disabled={busy}/>{copy(t('appsCopy'), true)}</View>
        </View>
        {copy(t(data.policy.allowBackground ? 'shared' : 'disabled'), true)}
      </>}
    </ScrollView>
    <Modal visible={limitOpen} transparent animationType="fade" onRequestClose={() => setLimitOpen(false)}>
      <View style={styles.scrim}><SafeAreaView style={[styles.dialog, {backgroundColor: palette.surface}]}>
        {heading(t('limit'))}
        <ScrollView contentContainerStyle={styles.section}>
          {limitChoices}
        </ScrollView>
        {!!error && copy(errorText(error, t))}<WorkspaceButton {...buttonStyle} title={t('cancel')} onPress={() => setLimitOpen(false)} disabled={busy}/>
      </SafeAreaView></View>
    </Modal>
    <Modal visible={!!picker} transparent animationType="slide" onRequestClose={() => { if (!busy) setPicker(null); }}>
      <View style={styles.scrim}><SafeAreaView style={[styles.dialog, {backgroundColor: palette.surface}]}>
        {heading(t('apps'))}{copy(t('appsNote'), true)}
        <ScrollView contentContainerStyle={styles.section}>
          {picker?.apps.length === 0 && copy(t('appsEmpty'))}
          {picker?.apps.map(app => <View key={app.packageName} style={styles.row}>
            <View style={styles.flex}>{copy(app.label)}{copy(app.packageName, true)}{!app.installed && copy(t('uninstalled'), true)}</View>
            <Switch accessibilityLabel={app.label} disabled={busy} value={!blocked.includes(app.packageName)} onValueChange={allowed => {
              setBlocked(previous => allowed ? previous.filter(pkg => pkg !== app.packageName) : previous.includes(app.packageName) ? previous : [...previous, app.packageName]);
            }}/>
          </View>)}
        </ScrollView>
        {!!error && copy(errorText(error, t))}
        <View style={styles.header}>
          <WorkspaceButton {...buttonStyle} title={t('cancel')} onPress={() => setPicker(null)} disabled={busy}/>
          <WorkspaceButton {...buttonStyle} title={t('save')} onPress={() => { void saveApps(); }} disabled={busy}/>
        </View>
      </SafeAreaView></View>
    </Modal>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  root: {flex: 1}, content: {padding: layout.gutter, gap: layout.gap, paddingBottom: 32},
  header: {flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between', gap: layout.gap},
  panel: {padding: 16, borderRadius: layout.radius, borderWidth: StyleSheet.hairlineWidth, gap: 12},
  row: {flexDirection: 'row', alignItems: 'center', gap: 12, minHeight: layout.touch, paddingVertical: 8},
  flex: {flex: 1, gap: 4}, section: {gap: 10, paddingVertical: 8},
  button: {minHeight: layout.touch, borderRadius: layout.buttonRadius, paddingHorizontal: 16, paddingVertical: 12, justifyContent: 'center'},
  scrim: {flex: 1, backgroundColor: 'rgba(0,0,0,0.45)', padding: 20, justifyContent: 'center'},
  dialog: {maxHeight: '90%', padding: 20, borderRadius: layout.radius, gap: 12},
});
