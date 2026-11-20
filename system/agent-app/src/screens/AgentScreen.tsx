import React, {useCallback, useEffect, useRef, useState} from 'react';
import {ActivityIndicator, StyleSheet, View} from 'react-native';
import {AgentChatScreen} from '../components/AgentChatScreen';
import {GenUiCard} from '../components/GenUiCard';
import {useAgentController} from '../hooks/useAgentController';
import {useTakuFonts} from '../theme/useTakuFonts';
import {chatColors} from '../chat/chatTheme';
import {AgentMarkdown} from '../native/Markdown';
import {collapseToIsland, consumeChatCameraDraft, launchDraft, onChatCameraLaunch, onDraft} from '../native/agent';
import type {Language} from '../i18n';
import {appendHomeDrafts} from '../chat/homeDraft';
import type {CardDemand} from '../chat/cardDemand';
import type {MessagePart} from '../model/session';

const renderMarkdown = (text: string, active: boolean, itemId: string) => <AgentMarkdown key={itemId} text={text} active={active}/>;

export default function AgentScreen() {
  const controller = useAgentController();
  // The controller owns the one surface signal: the app is in front and the conversation Activity
  // is started, so a chat still mounted behind Taku Home reports hidden and its cards go quiet.
  const {visible: conversationVisible} = controller;
  const [fonts, fontError] = useTakuFonts();
  const threadId = controller.session?.id || '';
  const {loading, busy, draft, draftSessionId, onDraftChange} = controller;
  // Ask-Taku from Home arrives as editable composer text; it is never sent automatically.
  // Each request is queued once, applied once after the session has loaded, then dropped, so
  // the user may edit or delete it freely and later requests never repeat it (issue #35).
  const [initialDrafts] = useState<string[]>(() => {
    const text = launchDraft();
    return text.trim() ? [text] : [];
  });
  const pendingDrafts = useRef(initialDrafts);
  const cameraDrafts = useRef(new Map<string, string[]>());
  const [draftArrivals, setDraftArrivals] = useState(0);
  useEffect(() => {
    const receiveDraft = () => {
      const text = launchDraft();
      if (!text.trim()) return;
      pendingDrafts.current = [...pendingDrafts.current, text];
      setDraftArrivals(count => count + 1);
    };
    const subscription = onDraft(receiveDraft);
    const cameraSubscription = onChatCameraLaunch(() => setDraftArrivals(count => count + 1));
    receiveDraft();
    return () => { subscription.remove(); cameraSubscription.remove(); };
  }, []);
  useEffect(() => {
    if (loading || busy) return;
    const cameraDraft = consumeChatCameraDraft(draftSessionId);
    const threadDrafts = cameraDrafts.current.get(draftSessionId) ?? [];
    if (cameraDraft.trim()) {
      threadDrafts.push(cameraDraft);
      cameraDrafts.current.set(draftSessionId, threadDrafts);
    }
    const incoming = [...pendingDrafts.current, ...threadDrafts];
    // Keep whatever the user had already typed; append rather than replace it.
    if (incoming.length && onDraftChange(appendHomeDrafts(draft, incoming))) {
      pendingDrafts.current = [];
      cameraDrafts.current.delete(draftSessionId);
    }
  }, [draftArrivals, loading, busy, draft, draftSessionId, onDraftChange]);
  // A card holds a native reader only while the conversation shows and its row is in demand; a
  // row just off screen is preload, which the coordinator drops before visible demand.
  const renderCard = useCallback((part: MessagePart, cardLanguage: Language, demand: CardDemand) => part.card && threadId
    ? <GenUiCard threadId={threadId} presentation={part.card} superseded={part.superseded} language={cardLanguage}
      active={conversationVisible && demand !== null} preload={demand === 'preload'}/>
    : null, [conversationVisible, threadId]);
  // Collapse (2-1 → 1-1): the home island continues this thread; without Taku Home, just hide.
  const {onClose} = controller;
  const collapse = useCallback(() => { collapseToIsland(threadId).catch(onClose); }, [threadId, onClose]);
  if (!fonts && !fontError) return <View style={styles.loading}><ActivityIndicator color={chatColors.textPrimary}/></View>;
  return <AgentChatScreen {...controller} renderMarkdown={renderMarkdown} renderCard={renderCard} onCollapse={collapse}/>;
}

const styles = StyleSheet.create({loading: {flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: chatColors.backdropFallback}});
