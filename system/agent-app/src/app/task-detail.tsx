import React, {useEffect, useState} from 'react';
import {ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import {useLocalSearchParams, useRouter} from 'expo-router';
import {addEventListener} from 'expo-linking';
import {Button} from '../management/controls';
import {AgentGatewayError, callAgent} from '../native/agent';
import {redirectSystemPath, taskIdParam} from '../native/assistantRoute';
import {colors, layout} from '../theme';

type TaskDetail = {taskId: string; state: string; prompt: string; message: string};
type TaskRead = {kind: 'loading'} | {kind: 'ready'; detail: TaskDetail} | {kind: 'error'; message: string};

const taskStates: Record<string, string> = {
  queued: 'Queued',
  running: 'Running',
  completed: 'Completed',
  failed: 'Failed',
  cancelled: 'Cancelled',
  needs_user: 'Needs your attention',
};

function taskError(error: unknown): string {
  const code = error instanceof AgentGatewayError ? error.code : '';
  switch (code) {
    case 'TASK_NOT_FOUND': return 'This task is no longer available.';
    case 'INVALID_TASK_ID': return 'This link does not identify a valid task.';
    case 'USER_LOCKED': return 'Unlock your device to view this task.';
    case 'USER_UNAVAILABLE': return 'Task details are unavailable for this Android user.';
    default: return 'Taku could not load the task details.';
  }
}

function TaskBody({taskId}: {taskId: string}) {
  const router = useRouter();
  const [read, setRead] = useState<TaskRead>({kind: 'loading'});
  useEffect(() => {
    let revision = 0;
    const load = () => {
      const current = ++revision;
      void callAgent<TaskDetail>('readTaskDetail', {taskId}).then(
        detail => {
          if (current !== revision) return;
          setRead(detail?.taskId === taskId ? {kind: 'ready', detail} : {kind: 'error', message: taskError(null)});
        },
        error => { if (current === revision) setRead({kind: 'error', message: taskError(error)}); },
      );
    };
    load();
    // A second tap on the same task may leave this route mounted with unchanged parameters.
    const links = addEventListener('url', ({url}) => {
      if (redirectSystemPath({path: url, initial: false}) === `/task-detail?taskId=${taskId}`) load();
    });
    return () => { revision++; links.remove(); };
  }, [taskId]);

  if (read.kind === 'loading') {
    return <ActivityIndicator color={colors.brand} accessibilityLabel="Loading task details"/>;
  }
  if (read.kind === 'error') {
    return <Text accessibilityRole="alert" style={styles.body}>{read.message}</Text>;
  }
  const {detail} = read;
  const status = Object.hasOwn(taskStates, detail.state) ? taskStates[detail.state] : 'Status unavailable';
  return <>
    <Text accessibilityRole="header" style={styles.status}>{status}</Text>
    {detail.message ? <Text selectable style={styles.body}>{detail.message}</Text> : null}
    {detail.prompt ? <View style={styles.prompt}>
      <Text accessibilityRole="header" style={styles.label}>Request</Text>
      <Text selectable style={styles.body}>{detail.prompt}</Text>
    </View> : null}
    <Button label="Task files" onPress={() => router.push({
      pathname: '/assistant-management', params: {section: 'files', taskId: detail.taskId},
    })}/>
  </>;
}

export default function TaskDetailRoute() {
  const router = useRouter();
  const {taskId: rawTaskId} = useLocalSearchParams();
  const taskId = taskIdParam(rawTaskId);
  return <SafeAreaView style={styles.page}>
    <View style={styles.header}>
      <Pressable accessibilityRole="button" style={styles.back} onPress={() => {
        if (router.canGoBack()) router.back();
        else router.replace('/');
      }}>
        <Text style={styles.backText}>Back</Text>
      </Pressable>
      <Text accessibilityRole="header" style={styles.title}>Task details</Text>
    </View>
    <ScrollView contentContainerStyle={styles.content}>
      {taskId ? <TaskBody key={taskId} taskId={taskId}/>
        : <Text accessibilityRole="alert" style={styles.body}>This link does not identify a valid task.</Text>}
    </ScrollView>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  page: {flex: 1, backgroundColor: colors.page},
  header: {alignItems: 'center', flexDirection: 'row', gap: layout.gap, paddingHorizontal: layout.gutter},
  back: {justifyContent: 'center', minHeight: layout.touch, minWidth: layout.touch},
  backText: {color: colors.ink, fontSize: 16},
  title: {color: colors.ink, fontSize: 22, fontWeight: '600', flex: 1},
  content: {gap: layout.gap, padding: layout.gutter},
  status: {color: colors.ink, fontSize: 18, fontWeight: '600'},
  body: {color: colors.ink, fontSize: 16, lineHeight: 24},
  prompt: {gap: layout.gap, padding: layout.gutter, backgroundColor: colors.surface, borderRadius: layout.radius},
  label: {color: colors.secondary, fontSize: 14, fontWeight: '600'},
});
