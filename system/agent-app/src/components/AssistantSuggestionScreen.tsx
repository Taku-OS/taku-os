import React, {useCallback, useEffect, useRef, useState} from 'react';
import {ActivityIndicator, BackHandler, Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import {useFocusEffect, useRouter} from 'expo-router';
import {SafeAreaView} from 'react-native-safe-area-context';
import {AgentGatewayError, callAgent, closeAgent} from '../native/agent';
import {colors, layout, typography} from '../theme';

type CaptureState = 'capturing' | 'analyzing' | 'ready' | 'no_suggestion' | 'no_screenshot' | 'expired' | 'dismissed' | 'accepted' | 'failed';
interface CaptureSnapshot {
  captureId: string;
  state: CaptureState;
  manual: boolean;
  title: string;
  suggestion: string;
  message: string;
  expiresAt: number;
  job?: {id: string; threadId: string; taskId: string; state: string; result: string};
}

function failure(error: unknown): string {
  const code = error instanceof AgentGatewayError ? error.code : '';
  switch (code) {
    case 'CAPTURE_EXPIRED': return 'This suggestion expired. Capture the screen again.';
    case 'CAPTURE_NOT_ACTIONABLE': return 'This screen has no suggestion to accept.';
    case 'BACKGROUND_TASK_LIMIT': return 'Complete or stop an existing background task before accepting this suggestion.';
    case 'USER_LOCKED': return 'Unlock the phone to view this suggestion.';
    case 'USER_UNAVAILABLE': return 'Screen suggestions are unavailable for this Android user.';
    case 'THREAD_UNAVAILABLE': return 'This suggestion has no conversation available.';
    case 'AGENT_UI_UNAVAILABLE': return 'Taku could not open the conversation.';
    default: return 'Could not read the screen suggestion. Retry to check its current status.';
  }
}

export function AssistantSuggestionScreen({captureId}: {captureId: string}) {
  const router = useRouter();
  const [snapshot, setSnapshot] = useState<CaptureSnapshot | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const mutation = useRef(0);
  const inFlight = useRef(false);
  const activeCapture = useRef(captureId);
  useEffect(() => { activeCapture.current = captureId; }, [captureId]);
  const valid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(captureId);
  const current = snapshot?.captureId === captureId ? snapshot : null;

  const refresh = useCallback(async () => {
    if (!valid || inFlight.current) return;
    const version = mutation.current;
    try {
      const value = await callAgent<CaptureSnapshot>('capture/read', {captureId});
      if (activeCapture.current === captureId && mutation.current === version) {
        setSnapshot(value);
        setError('');
      }
    } catch (problem) {
      if (activeCapture.current === captureId && mutation.current === version) setError(failure(problem));
    }
  }, [captureId, valid]);

  const close = useCallback(async () => {
    if (inFlight.current) return;
    inFlight.current = true;
    mutation.current++;
    setBusy(true);
    try {
      if (valid) await callAgent('capture/dismiss', {captureId});
      if (activeCapture.current !== captureId) return;
      if (router.canGoBack()) router.back();
      else await closeAgent();
    } catch (problem) {
      if (activeCapture.current === captureId) setError(failure(problem));
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }, [captureId, router, valid]);

  useFocusEffect(useCallback(() => {
    let active = true;
    let cancelPoll = () => {};
    const poll = async () => {
      await refresh();
      if (active) {
        const timer = setTimeout(() => { void poll(); }, 1_000);
        cancelPoll = () => clearTimeout(timer);
      }
    };
    void poll();
    const back = BackHandler.addEventListener('hardwareBackPress', () => { void close(); return true; });
    return () => {
      active = false;
      mutation.current++;
      cancelPoll();
      back.remove();
    };
  }, [close, refresh]));

  const accept = async () => {
    if (inFlight.current || current?.state !== 'ready') return;
    inFlight.current = true;
    mutation.current++;
    setBusy(true);
    setError('');
    try {
      const value = await callAgent<CaptureSnapshot>('capture/accept', {captureId});
      if (activeCapture.current === captureId) setSnapshot(value);
    } catch (problem) {
      if (activeCapture.current === captureId) setError(failure(problem));
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  };

  const waiting = current?.state === 'capturing' || current?.state === 'analyzing';
  const accepted = current?.state === 'accepted';
  const job = current?.job;
  const ready = current?.state === 'ready';
  return <SafeAreaView style={styles.screen}>
    <View style={styles.header}>
      <Text style={styles.brand}>Taku</Text>
      <Pressable accessibilityRole="button" accessibilityLabel="Close screen suggestions" disabled={busy}
        onPress={() => { void close(); }} style={styles.close}>
        <Text style={styles.secondary}>Close</Text>
      </Pressable>
    </View>
    <ScrollView contentContainerStyle={styles.content}>
      <View style={styles.card}>
        <Text accessibilityRole="header" style={styles.title}>
          {!valid ? 'Suggestion unavailable' : current?.title || 'Loading screen suggestion'}
        </Text>
        {current?.suggestion ? <Text style={styles.body}>{current.suggestion}</Text> : null}
        <Text style={styles.secondary}>
          {!valid ? 'This link does not identify a screen suggestion.' : current?.message || 'Reading the current screenshot result.'}
        </Text>
        {waiting || valid && !current && !error ? <ActivityIndicator color={colors.brand} accessibilityLabel="Reading screen suggestion"/> : null}
        {ready ? <>
          <Text style={styles.secondary}>Taku will work in the background. Any action that needs confirmation will ask you before it proceeds.</Text>
          <Pressable accessibilityRole="button" accessibilityLabel="Accept screen suggestion and hand to Agent" disabled={busy}
            onPress={() => { void accept(); }} style={[styles.primary, busy && styles.disabled]}>
            <Text style={styles.primaryLabel}>{busy ? 'Accepting…' : 'Accept suggestion'}</Text>
          </Pressable>
          <Pressable accessibilityRole="button" disabled={busy} onPress={() => { void close(); }} style={styles.button}>
            <Text style={styles.label}>No thanks</Text>
          </Pressable>
        </> : null}
        {accepted && job ? <>
          <Text style={styles.label}>{job.state === 'starting' ? 'Starting background task…' : `Task ${job.state.replace(/_/g, ' ')}`}</Text>
          {job.result ? <Text style={styles.body}>{job.result}</Text> : null}
          {job.taskId ? <Pressable accessibilityRole="button" onPress={() => router.push({pathname: '/task-detail', params: {taskId: job.taskId}})} style={styles.primary}>
            <Text style={styles.primaryLabel}>Open task</Text>
          </Pressable> : null}
          {job.threadId ? <Pressable accessibilityRole="button" onPress={() => {
            void callAgent('capture/openConversation', {captureId}).catch(problem => setError(failure(problem)));
          }} style={styles.button}>
            <Text style={styles.label}>Open conversation</Text>
          </Pressable> : null}
        </> : null}
        {current?.state === 'no_screenshot' ? <Pressable accessibilityRole="button" onPress={() => router.push('/system-ai')} style={styles.button}>
          <Text style={styles.label}>System AI settings</Text>
        </Pressable> : null}
        {error ? <View accessibilityLiveRegion="polite" style={styles.problem}>
          <Text style={styles.error}>{error}</Text>
          <Pressable accessibilityRole="button" disabled={busy} onPress={() => { void refresh(); }} style={styles.button}>
            <Text style={styles.label}>Refresh status</Text>
          </Pressable>
        </View> : null}
      </View>
    </ScrollView>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  screen: {flex: 1, backgroundColor: colors.page},
  header: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: layout.gutter},
  brand: {...typography('context')},
  close: {minHeight: layout.touch, minWidth: layout.touch, alignItems: 'center', justifyContent: 'center'},
  content: {flexGrow: 1, justifyContent: 'center', padding: layout.gutter},
  card: {backgroundColor: colors.surface, borderRadius: layout.radius, padding: 24, gap: layout.gap},
  title: {...typography('heading')},
  body: {...typography('body')},
  secondary: {...typography('meta'), color: colors.secondary},
  label: {...typography('label')},
  button: {minHeight: layout.touch, borderRadius: layout.buttonRadius, padding: 12, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.control},
  primary: {minHeight: layout.touch, borderRadius: layout.buttonRadius, padding: 12, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.brand},
  primaryLabel: {...typography('label'), color: colors.white},
  disabled: {opacity: 0.5},
  problem: {gap: layout.gap},
  error: {...typography('meta'), color: colors.error},
});
