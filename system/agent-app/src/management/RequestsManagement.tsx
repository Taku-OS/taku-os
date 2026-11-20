import {useCallback, useEffect, useRef, useState} from 'react';
import {Linking, StyleSheet, View} from 'react-native';
import {callAgent, holdSecureScreen, submitSecretFields} from '../native/agent';
import {newSecretFieldId, SecretField} from '../native/SecretField';
import {colors} from '../theme';
import {Button, Card, confirm, Copy, Field, Status, useManagementData} from './controls';

/** `requests/list`: `inline` requests are the question cards the conversation answers in place. */
type Pending = {threadId: string; key: string; method: string; secure: boolean; inline: boolean};
type FormField = {id: string; label: string; secret: boolean; required: boolean; type: 'string' | 'boolean' | 'integer' | 'number'; multiline: boolean; options: string[]};
/** `requests/read`: a request with no fields is an approval. */
type Form = Pending & {kind: string; url: string; details: string[]; fields: FormField[]};

const titles: Record<string, string> = {
  attestation: 'Device attestation',
  accountAuthorization: 'External account authorization',
  mcp: 'MCP request',
  questions: 'Answer requested questions',
  toolResult: 'Extension tool result',
  permissionApproval: 'Permission approval',
  commandApproval: 'Command approval',
  fileChangeApproval: 'File change approval',
  action: 'Pending action',
};
const kinds: Record<string, string> = {
  'attestation/generate': 'attestation',
  'account/chatgptAuthTokens/refresh': 'accountAuthorization',
  'mcpServer/elicitation/request': 'mcp',
  'item/tool/requestUserInput': 'questions',
  'item/tool/call': 'toolResult',
  'item/permissions/requestApproval': 'permissionApproval',
  'item/commandExecution/requestApproval': 'commandApproval',
  execCommandApproval: 'commandApproval',
  'item/fileChange/requestApproval': 'fileChangeApproval',
  applyPatchApproval: 'fileChangeApproval',
};
const title = (request: {method: string; kind?: string}) => titles[request.kind ?? kinds[request.method] ?? 'action'] ?? titles.action;
const guidance: Record<string, string> = {
  attestation: 'Submit an existing attestation token. This device cannot issue one automatically.',
  accountAuthorization: 'Submit valid authorization for the externally managed account.',
};

/**
 * Requests that wait on the user outside a conversation's question cards: command, file-change
 * and permission approvals, MCP requests, extension tool results, questions that ask for a
 * secret, and external account authorization. [requestKey] opens one request directly.
 */
export function RequestsPanel({threadId, requestKey}: {threadId?: string; requestKey?: string}) {
  const thread = threadId ?? '';
  const load = useCallback(async () => (await callAgent<{requests: Pending[]}>('requests/list', {threadId: thread})).requests, [thread]);
  const {data, refresh, run, busy, error} = useManagementData(load);
  const [form, setForm] = useState<Form>();
  const [values, setValues] = useState<Record<string, string>>({});
  const [secretIds, setSecretIds] = useState<Record<string, string>>({});
  const opened = useRef(false);
  const secret = !!form && form.fields.some(field => field.secret);
  // Secret answers must not reach screenshots or the recents preview.
  useEffect(() => (secret ? holdSecureScreen() : undefined), [secret]);

  const open = useCallback((request: {threadId: string; key: string}) => void run(async () => {
    const next = await callAgent<Form>('requests/read', {threadId: request.threadId, key: request.key});
    setValues({});
    setSecretIds(Object.fromEntries(next.fields.filter(field => field.secret).map(field => [field.id, newSecretFieldId()])));
    setForm(next);
  }), [run]);
  const requested = requestKey ? data?.find(request => request.key === requestKey) : undefined;
  useEffect(() => {
    // A conversation card that cannot be answered in place links to its own request once.
    if (opened.current || !requested) return;
    opened.current = true;
    open(requested);
  }, [open, requested]);

  const close = () => {
    setForm(undefined);
    setValues({});
    setSecretIds({});
  };
  const settle = (action: () => Promise<unknown>) => void run(async () => {
    await action();
    close();
    await refresh();
  });
  const submit = (request: Form) => settle(() => {
    const plain = Object.fromEntries(request.fields.filter(field => !field.secret && values[field.id]).map(field => [field.id, values[field.id]]));
    const params = {threadId: request.threadId, key: request.key, values: plain};
    return Object.keys(secretIds).length ? submitSecretFields(secretIds, 'requests/submit', params) : callAgent('requests/submit', params);
  });
  const cancel = (request: Form) => confirm('Cancel this request?', 'The task is told that you declined.', () =>
    settle(() => callAgent('requests/cancel', {threadId: request.threadId, key: request.key})), true);
  const set = (id: string, value: string) => setValues(previous => ({...previous, [id]: value}));

  const waiting = data?.filter(request => !request.inline) ?? [];
  const incomplete = !!form && form.fields.some(field => field.required && !field.secret && !values[field.id]?.trim());
  return <>
    <Status busy={busy} error={error}/>
    {!form && <Card title="Pending requests">
      {data && waiting.length === 0 && <Copy muted>No pending requests.</Copy>}
      {waiting.map(request => <Button key={request.key} label={title(request)} disabled={busy} onPress={() => open(request)}/>)}
      {data?.some(request => request.inline) ? <Copy muted>Other questions are answered in the conversation.</Copy> : null}
      <Button label="Refresh" disabled={busy} onPress={() => void run(refresh)}/>
    </Card>}
    {form && <Card title={title(form)}>
      {guidance[form.kind] ? <Copy>{guidance[form.kind]}</Copy> : null}
      {form.details.map((detail, index) => <Copy key={index}>{detail}</Copy>)}
      {form.url ? <Button label="Open authorization page" disabled={busy} onPress={() => void Linking.openURL(form.url).catch(() => {})}/> : null}
      {form.fields.map(field => field.secret ? <View key={field.id} style={styles.field}>
        <Copy muted>{field.required ? field.label : `${field.label} (optional)`}</Copy>
        <View style={styles.secret}>
          <SecretField fieldId={secretIds[field.id]} placeholder={field.label} textColor={colors.ink} placeholderColor={colors.secondary} style={styles.secretInput}/>
        </View>
      </View> : <View key={field.id} style={styles.field}>
        <Field label={field.required ? field.label : `${field.label} (optional)`} value={values[field.id] ?? ''} onChangeText={value => set(field.id, value)}
          editable={!busy} multiline={field.multiline} autoCorrect={false}
          keyboardType={field.type === 'integer' ? 'number-pad' : field.type === 'number' ? 'decimal-pad' : 'default'}/>
        {(field.type === 'boolean' ? ['true', 'false'] : field.options).map(option => <Button key={option}
          label={values[field.id] === option ? `${option} · Selected` : option} disabled={busy} onPress={() => set(field.id, option)}/>)}
      </View>)}
      <Button label={form.fields.length ? 'Submit response' : 'Approve'} disabled={busy || incomplete} onPress={() => submit(form)}/>
      <Button label={form.fields.length ? 'Cancel request' : 'Decline'} disabled={busy} onPress={() => cancel(form)}/>
      <Button label="All requests" disabled={busy} onPress={close}/>
    </Card>}
  </>;
}

const styles = StyleSheet.create({
  field: {gap: 6},
  secret: {minHeight: 48, paddingHorizontal: 10, borderWidth: 1, borderColor: colors.border, borderRadius: 8, justifyContent: 'center'},
  secretInput: {height: 46},
});
