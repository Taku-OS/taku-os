import {useCallback, useEffect, useRef, useState, type ReactNode} from 'react';
import {ActivityIndicator, Alert, Pressable, StyleSheet, Text, TextInput, View, type TextInputProps} from 'react-native';
import {callAgent} from '../native/agent';
import {colors, typography} from '../theme';

export function manage<T>(operation: string, params: Record<string, unknown> = {}) {
  return callAgent<T>('manageAssistant', {operation, ...params});
}

const errors: Record<string, string> = {
  INVALID_ARGUMENT: 'Check the values and try again.',
  INVALID_THREAD_ID: 'Open a conversation before using this control.',
  THREAD_UNAVAILABLE: 'This conversation is unavailable.',
  TASK_THREAD_MISMATCH: 'This task belongs to another conversation.',
  TASK_NOT_FOUND: 'This task no longer exists.',
  TASK_UNAVAILABLE: 'The task record could not be read.',
  INVALID_FILE_PATH: 'Use a visible path relative to the task workspace.',
  UNSUPPORTED_FILE_TYPE: 'Choose a supported text file.',
  FILE_NOT_FOUND: 'This file or folder is unavailable.',
  FILE_TOO_LARGE: 'This file exceeds the editor’s size limit.',
  FILES_UNAVAILABLE: 'The task workspace could not be accessed.',
  USER_UNAVAILABLE: 'Assistant management is unavailable for this Android user.',
  RUNTIME_UNAVAILABLE: 'The native runtime could not complete this operation. Refresh before trying again.',
};

export function useOperation() {
  const pending = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const run = useCallback(async (action: () => Promise<void>) => {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    setError('');
    try {
      await action();
    } catch (failure) {
      const message = failure instanceof Error ? failure.message : String(failure);
      setError(errors[message] ?? message);
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }, []);
  return {busy, error, run, setError};
}

export function useManagementData<T>(load: () => Promise<T>) {
  const state = useOperation();
  const [data, setData] = useState<T>();
  const refresh = useCallback(async () => { setData(await load()); }, [load]);
  const {run} = state;
  useEffect(() => { void run(refresh); }, [run, refresh]);
  return {...state, data, setData, refresh};
}

export function confirm(title: string, description: string, execute: () => void, destructive = false) {
  Alert.alert(title, description, [{text: 'Cancel', style: 'cancel'}, {text: 'Confirm', style: destructive ? 'destructive' : 'default', onPress: execute}]);
}

export function Copy({children, muted = false}: {children: ReactNode; muted?: boolean}) {
  return <Text selectable style={muted ? styles.secondary : styles.copy}>{children}</Text>;
}

export function Card({title, children}: {title?: string; children: ReactNode}) {
  return <View style={styles.card}>{title ? <Text style={styles.title}>{title}</Text> : null}{children}</View>;
}

export function Button({label, onPress, disabled = false}: {label: string; onPress: () => void; disabled?: boolean}) {
  return <Pressable accessibilityRole="button" accessibilityState={{disabled}} disabled={disabled} onPress={onPress}
    style={({pressed}) => [styles.button, (pressed || disabled) && styles.dim]}>
    <Text style={styles.buttonText}>{label}</Text>
  </Pressable>;
}

export function Field({label, multiline, ...props}: TextInputProps & {label: string}) {
  return <View style={styles.field}>
    <Text style={styles.secondary}>{label}</Text>
    <TextInput accessibilityLabel={label} multiline={multiline} autoCapitalize="none" placeholderTextColor={colors.secondary}
      style={[styles.input, multiline && styles.multiline]} {...props}/>
  </View>;
}

export function Status({busy, error}: {busy: boolean; error: string}) {
  return <>{busy && <ActivityIndicator accessibilityLabel="Working" color={colors.brand}/>}
    {error ? <Text selectable accessibilityLiveRegion="polite" style={styles.error}>{error}</Text> : null}</>;
}

export function Details({label, value}: {label: string; value: string | object | undefined}) {
  const [open, setOpen] = useState(false);
  if (value === undefined) return null;
  return <View style={styles.field}>
    <Button label={open ? `Hide ${label}` : `View ${label}`} onPress={() => setOpen(!open)}/>
    {open && <Copy>{typeof value === 'string' ? value : JSON.stringify(value, null, 2)}</Copy>}
  </View>;
}

const styles = StyleSheet.create({
  card: {backgroundColor: colors.surface, padding: 16, borderRadius: 16, gap: 12},
  title: typography('context'),
  copy: typography(),
  secondary: {...typography('meta'), color: colors.secondary},
  button: {minHeight: 48, borderRadius: 8, paddingHorizontal: 12, paddingVertical: 10, justifyContent: 'center', backgroundColor: colors.control},
  buttonText: typography('label'),
  dim: {opacity: 0.5},
  field: {gap: 6},
  input: {...typography(), minHeight: 48, padding: 10, borderWidth: 1, borderColor: colors.border, borderRadius: 8},
  multiline: {minHeight: 110, textAlignVertical: 'top'},
  error: {...typography('meta'), color: colors.error, backgroundColor: colors.errorSurface, borderRadius: 8, padding: 12},
});
