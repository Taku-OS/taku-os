import {useLocalSearchParams, useRouter} from 'expo-router';
import {ScrollView, StyleSheet, Text, View} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import {Button, Card, Copy} from '../management/controls';
import {ConversationCleanupPanel, ConversationTitlePanel, FilesPanel, GoalPanel, HarnessPanel, QueuePanel} from '../management/ConversationManagement';
import {CodexPluginsPanel, McpPanel} from '../management/CodexManagement';
import {RequestsPanel} from '../management/RequestsManagement';
import {SkillsPanel} from '../management/SkillsManagement';
import {colors, typography} from '../theme';
import {useTakuFonts} from '../theme/useTakuFonts';

const titles = {
  requests: 'Pending requests',
  title: 'Conversation title',
  goal: 'Conversation goal',
  queue: 'Task queue',
  files: 'Task files',
  skills: 'Skills',
  harness: 'Harness',
  cleanup: 'Conversation cleanup',
  'codex-plugins': 'Codex plugins',
  mcp: 'Codex apps and MCP',
} as const;
type Section = keyof typeof titles;
const sections = Object.keys(titles) as Section[];

export default function AssistantManagementRoute() {
  useTakuFonts();
  const router = useRouter();
  const query = useLocalSearchParams<{section?: Section; threadId?: string; taskId?: string; requestKey?: string}>();
  const section = sections.find(name => name === query.section);
  const threadId = typeof query.threadId === 'string' ? query.threadId : undefined;
  const taskId = typeof query.taskId === 'string' ? query.taskId : undefined;
  const requestKey = typeof query.requestKey === 'string' ? query.requestKey : undefined;
  const needsThread = (section === 'title' || section === 'goal' || section === 'queue' || section === 'harness') && !threadId;
  const back = () => {
    if (router.canGoBack()) router.back();
    else if (section) router.replace({pathname: '/assistant-management', params: threadId ? {threadId} : {}});
    else router.replace('/');
  };
  return <SafeAreaView style={styles.root}>
    <View style={styles.header}>
      <Button label="Back" onPress={back}/>
      <Text accessibilityRole="header" style={styles.heading}>{section ? titles[section] : 'Assistant management'}</Text>
    </View>
    <ScrollView key={`${section ?? 'index'}/${threadId ?? ''}/${taskId ?? ''}`} contentContainerStyle={styles.body} keyboardShouldPersistTaps="handled">
      {!section && <>
        {query.section ? <Copy>That management page is unavailable.</Copy> : null}
        <Card>
          {sections.map(name => <Button key={name} label={titles[name]} onPress={() => router.push({pathname: '/assistant-management',
            params: {section: name, ...(threadId ? {threadId} : {}), ...(name === 'files' && taskId ? {taskId} : {})}})}/>)}
          <Button label="God Mode plugin market" onPress={() => router.push('/plugin-market')}/>
        </Card>
      </>}
      {needsThread && <Card><Copy>Open a conversation to use this control.</Copy></Card>}
      {section === 'requests' && <RequestsPanel threadId={threadId} requestKey={requestKey}/>}
      {section === 'title' && threadId && <ConversationTitlePanel threadId={threadId}/>}
      {section === 'goal' && threadId && <GoalPanel threadId={threadId}/>}
      {section === 'queue' && threadId && <QueuePanel threadId={threadId}/>}
      {section === 'harness' && threadId && <HarnessPanel threadId={threadId}/>}
      {section === 'cleanup' && <ConversationCleanupPanel threadId={threadId}/>}
      {section === 'files' && <FilesPanel threadId={threadId} taskId={taskId}/>}
      {section === 'skills' && <SkillsPanel threadId={threadId}/>}
      {section === 'codex-plugins' && <CodexPluginsPanel/>}
      {section === 'mcp' && <McpPanel threadId={threadId}/>}
    </ScrollView>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: colors.page},
  header: {paddingHorizontal: 16, paddingVertical: 8, flexDirection: 'row', alignItems: 'center', gap: 12},
  heading: {...typography('heading'), flex: 1},
  body: {padding: 16, paddingBottom: 32, gap: 12},
});
