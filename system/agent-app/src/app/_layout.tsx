import React, {useEffect} from 'react';
import {Slot, usePathname, useRouter} from 'expo-router';
import {SafeAreaProvider} from 'react-native-safe-area-context';
import {StatusBar} from 'expo-status-bar';
import {onOpenConversation} from '../native/agent';
import {startChatAppearance} from '../native/chatAppearance';

// The chat background setting, loaded before the chat first renders and followed from then on.
startChatAppearance();

export default function AgentLayout() {
  const pathname = usePathname();
  const router = useRouter();
  useEffect(() => {
    const subscription = onOpenConversation(() => {
      if (pathname !== '/') router.replace('/');
    });
    return () => subscription.remove();
  }, [pathname, router]);
  return <SafeAreaProvider><StatusBar style="dark"/><Slot/></SafeAreaProvider>;
}
