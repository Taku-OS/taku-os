import {useCallback, useEffect, useState} from 'react';
import {AppState} from 'react-native';
import {useFocusEffect} from 'expo-router';
import {enterVoiceChat, leaveVoiceChat, onReplySpeech, type ReplySpeech} from '../native/voice';

const ownerPrefix = Date.now().toString(36);
let nextOwner = 0;

export function useVoiceChatFocus(threadId: string, enabled: boolean, setOwner: (owner: string | null) => void, cancel: () => void) {
  useFocusEffect(useCallback(() => {
    if (!enabled) return;
    let owner: string | null = null;
    function release() {
      if (owner === null) return;
      setOwner(null);
      cancel();
      void leaveVoiceChat(owner).catch(() => {});
      owner = null;
    }
    function activate() {
      if (owner !== null) return;
      owner = `chat-${ownerPrefix}-${++nextOwner}`;
      setOwner(owner);
      void enterVoiceChat(owner, threadId).catch(() => {});
    }
    const lifecycle = AppState.addEventListener('change', state => {
      if (state === 'active') activate();
      else release();
    });
    if (AppState.currentState !== 'background' && AppState.currentState !== 'inactive') activate();
    return () => { lifecycle.remove(); release(); };
  }, [threadId, enabled, setOwner, cancel]));
}

export function useReplySpeechState() {
  const [speech, setSpeech] = useState<ReplySpeech | null>(null);
  useEffect(() => {
    const subscription = onReplySpeech(setSpeech);
    return () => subscription.remove();
  }, []);
  return speech;
}
