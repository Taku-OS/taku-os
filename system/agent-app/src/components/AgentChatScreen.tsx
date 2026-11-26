import React, {useCallback, useEffect, useLayoutEffect, useMemo, useReducer, useRef, useState, type ReactNode} from 'react';
import {ActivityIndicator, Alert, AppState, BackHandler, FlatList, Image, Keyboard, KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, ToastAndroid, useWindowDimensions, View} from 'react-native';
import type {NativeScrollEvent, NativeSyntheticEvent, TextProps} from 'react-native';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import {useLocales} from 'expo-localization';
import {useRouter} from 'expo-router';
import {StatusBar} from 'expo-status-bar';
import Svg, {Defs, LinearGradient, Rect, Stop} from 'react-native-svg';
import {VoiceEffect} from '../native/VoiceEffect';
import {VoiceIcon} from '../chat/VoiceIcon';
import {AssistantBubble, ComposerGlassRim, GlassRim, QuickStarts} from '../chat/ChatSurfaces';
import {useLiveTalk} from '../chat/useLiveTalk';
import {liveLightStyle, shouldShowLiveLight} from '../chat/liveGlow';
import * as Haptics from 'expo-haptics';
import {colors as baseColors, containsChinese, layout, typography, type TypeRole} from '../theme';
import {languageFor, textFor, type Language, type TextKey} from '../i18n';
import {activityName, list, record, string, type Activity, type Json, type MessagePart, type PendingQuestion, type SessionView, type ThreadSummary, type WorkspaceView} from '../model/session';
import {humanizeToolNames} from '../model/phoneTools';
import {Icon, IconButton} from './Icon';
import {chatPresentation, type ChatMessageBlock} from '../model/chatPresentation';
import {FIRST_RESPONSE_TIMEOUT_MS, type OptimisticSubmission} from '../model/optimisticChat';
import {ReplyLoading} from './ReplyLoading';
import {ThinkingBlock} from '../chat/ThinkingBlock';
import {gatewayLoad, onImeInsets, showKeyboard, type PanelTarget} from '../native/agent';
import {useChatBackdrop} from '../chat/useChatBackdrop';
import {ReplyMenu, type ReplyMenuTarget} from './ReplyMenu';
import {cardPanelActions, openPinnedCard, pinCardToPanels, unpinCard} from '../hooks/useGenUi';
import {cardId} from '../model/genuiStore';
import {presentationRequest} from '../model/genui';
import {EdgeBlur} from '../native/EdgeBlur';
import {ChatIcon} from '../chat/ChatIcon';
import {chatBlur, chatColors, getChatAppearance, homeColors, chatMetrics, chatTitle, chatType, themed, themedStyles} from '../chat/chatTheme';
import {chatText} from '../chat/chatText';
import {useDictation, type DictationFailure} from '../chat/useDictation';
import {failureOf, insertDictation, type DraftSelection} from '../chat/dictationText';
import {useReplySpeechState, useVoiceChatFocus} from '../chat/useVoiceChatFocus';
import {openVoiceSettings, speakReply, stopReplySpeech} from '../native/voice';
import {markdownSpeechText} from '../native/MarkdownStream';
import {openAiSetup} from '../native/aiSetup';
import {DictationBar} from './DictationBar';
import {ChatSidebar} from '../chat/ChatSidebar';
import {PanelContextChip, PanelEditPrompt, usePanelTitle} from '../chat/PanelEditing';
import {TaskStrip} from '../chat/TaskStrip';
import {TaskCard} from '../chat/TaskCard';
import {isMainConversation, partitionThreads} from '../model/singleChat';
import {DRAWER_TASKS, STRIP_TASKS, liveTasks, taskCards} from '../model/taskStatus';
import {useTaskStatuses} from '../hooks/useTaskStatuses';
import {sheetSpace, type ImeGeometry} from '../model/sheetGeometry';
import {emptyPreview, PREVIEW_FRESH_MS, PREVIEW_LOAD_TIMEOUT_MS, PREVIEW_REFRESH_MS, previewReducer} from '../model/preview';
import {rowDemand, sameBand, visibleBand, type CardDemand, type RowBand} from '../chat/cardDemand';
import {createChatScrollState, createScrollIntent, nextScrollIntent, distanceFromBottom, findPinnedTurn, findSentUser, knownUsers, type KnownUsers, PIN_SETTLE_MS, pinnedSpacerHeight, reduceChatScroll, sameChatScrollState, type ChatScrollEffect, type ChatScrollEvent, type ChatScrollState, type ScrollIntentEvent} from '../chat/chatScroll';
import {AttachMenu, PluginChip, PluginSuggestions, type AttachChoice} from '../chat/ComposerMenus';
import {ChromeIcon} from '../chat/ChromeIcon';
import {insertMentionTrigger, mentionAt, removeMention} from '../chat/pluginMention';
import type {ComposerAttachment, ComposerPlugin, ComposerRecovery} from '../hooks/composerDraft';
import type {PluginRepository} from '../plugins/contracts';
import {attachmentPreviewUri} from '../native/composerPicker';
import type {ComposerPickerKind} from '../native/composerPicker';

const colors = themed(() => ({...baseColors, ink: chatColors.textPrimary, secondary: chatColors.textSecondary,
  muted: chatColors.textTertiary, border: chatColors.hairline, subtle: chatColors.fillControl,
  control: chatColors.fillControl, surface: chatColors.surfaceRaised, page: chatColors.backdropFallback,
  error: chatColors.textError, errorSurface: chatColors.errorSurface}));

export interface AgentChatScreenProps {
  session: SessionView | null;
  threads: ThreadSummary[];
  workspaces: WorkspaceView[];
  loading: boolean;
  busy: boolean;
  error?: string;
  historyError?: string;
  workspaceError?: string;
  draft: string;
  draftSessionId: string;
  draftRevision: string | null;
  attachments: readonly ComposerAttachment[];
  recovery: readonly ComposerRecovery[];
  onRestoreSavedDraft: (id: string) => void;
  onPickAttachment: (kind: ComposerPickerKind) => void;
  onRemoveAttachment: (id: string) => void;
  /** Plugins attached to the next message with `@` (Figma 1-3); the native draft owns them. */
  plugins: readonly ComposerPlugin[];
  onAddPlugin: (pluginId: string) => void;
  onRemovePlugin: (pluginId: string) => void;
  /** The panel the next send edits (Edit with Taku, Figma 4-1), and removing it from the composer. */
  panelTarget?: PanelTarget | null;
  onDetachPanel?: () => void;
  /** Open with the sidebar showing (the Panels page's menu button returns here). */
  initialSidebar?: boolean;
  /** Where `@` suggestions come from; the native plugin manager (as Plugin Market) by default. */
  pluginRepository?: PluginRepository;
  conversationKey?: string;
  /** Increments whenever a conversation is opened from outside the chat (e.g. the island card). */
  externalOpens?: number;
  optimisticSubmissions?: readonly OptimisticSubmission[];
  pendingSubmission?: {threadId: string; text: string; requestId: string; action: 'sendMessage' | 'steer' | 'continue'} | null;
  onRetrySubmission: () => void;
  onContinueWithoutResending: () => void;
  onRestoreFailedTask: (taskId: string) => void;
  onDraftChange: (value: string) => void;
  onDictationDraftChange: (value: string, expected: {threadId: string; revision: string; text: string}) => Promise<boolean>;
  onSend: () => void;
  onStop: () => void;
  onSelectThread: (id: string) => void;
  onCreateThread: () => void;
  primaryThreadId?: string;
  isMainChat?: boolean;
  onSelectMainChat?: () => void;
  /** TAKU carries on in a new conversation, taking the given unsent request along as a draft. */
  onStartMainAfresh?: (carry?: string) => void;
  onCreateTaskThread?: () => void;
  onRefresh: () => void;
  onAnswer: (key: string, response: Json) => void;
  onOpenWorkspace: (workspace: WorkspaceView) => void;
  onClose: () => void;
  /** Reads the Task list again. Stable across renders. */
  onOpenHistory?: () => void;
  /** The conversation is on screen (the app is in front and its Activity started). */
  visible?: boolean;
  renderMarkdown?: (text: string, active: boolean, itemId: string) => ReactNode;
  /**
   * Generated cards render through the app; the chat only places them in order with the text.
   * `demand` is the row's share of native demand: visible on screen, preload in the bounded band
   * around it, or null once it is scrolled away.
   */
  renderCard?: (part: MessagePart, language: Language, demand: CardDemand) => ReactNode;
  /** Collapse (2-1 → 1-1): hand the conversation to the home Dynamic Island. */
  onCollapse?: () => void;
}

/** A missing fal key is fixable from the chat: the prompt opens the fal connection card (Figma 2-5). */
function promptFalKey(ct: ReturnType<typeof chatText>, message: 'voiceFalKeyMissing' | 'liveFalKeyMissing') {
  Alert.alert(ct('voiceFalKeyTitle'), ct(message), [
    {text: ct('cancel'), style: 'cancel'},
    {text: ct('addFalKey'), onPress: () => { openAiSetup('fal'); }},
  ]);
}

function Label({children, role = 'body', style, ...props}: Omit<TextProps, 'role'> & {role?: TypeRole}) {
  const value = typeof children === 'string' ? children : '';
  return <Text {...props} style={[typography(role, containsChinese(value)), {color: colors.ink}, style]}>{children}</Text>;
}

function OriginalRequestPreview({text, language}: {text: string; language: Language}) {
  const [expanded, setExpanded] = useState(false), t = textFor(language);
  return <>
    <Pressable accessibilityRole="button" accessibilityState={{expanded}} accessibilityLabel={t(expanded ? 'hideOriginalRequest' : 'showOriginalRequest')}
      onPress={() => setExpanded(!expanded)} style={styles.pendingPreviewToggle}>
      <Label role="caption" numberOfLines={2} style={styles.flex}>{expanded ? t('originalRequest') : text}</Label>
      <Icon name={expanded ? 'chevron_down' : 'chevron_right'} size={16} color={colors.secondary}/>
    </Pressable>
    {expanded && <ScrollView style={styles.pendingPreviewExpanded} nestedScrollEnabled keyboardShouldPersistTaps="handled"><Label selectable role="caption">{text}</Label></ScrollView>}
  </>;
}

function PendingSubmissionCard({text, language, onRetry, onContinue}: {text: string; language: Language; onRetry: () => void; onContinue: () => void}) {
  const t = textFor(language);
  return <View style={styles.pendingSubmission}>
    <Label role="meta">{t('deliveryUnconfirmed')}</Label>
    <Label role="caption" style={styles.secondary}>{t('retryOriginalExplanation')}</Label>
    <OriginalRequestPreview text={text} language={language}/>
    <Pressable testID="retry-original-submission" accessibilityRole="button" accessibilityLabel={t('retryOriginalRequest')} onPress={onRetry} style={styles.pendingRetry}>
      <Icon name="refresh" size={16} color={chatColors.icon}/><Label role="meta">{t('retryOriginalRequest')}</Label>
    </Pressable>
    <Label role="caption" style={styles.secondary}>{t('continueUnconfirmedExplanation')}</Label>
    <Pressable testID="continue-unconfirmed" accessibilityRole="button" accessibilityLabel={t('continueWithoutResending')} onPress={onContinue} style={styles.pendingRetry}>
      <Label role="meta">{t('continueWithoutResending')}</Label>
    </Pressable>
  </View>;
}

/**
 * [afresh] is offered on TAKU's own conversation only: when a request fails there, TAKU can carry
 * on in a new conversation instead of leaving the user with a main conversation that cannot answer.
 */
function FailedTaskCard({failure, language, busy, onRestore, afresh}: {failure: {taskId: string; message: string; prompt: string}; language: Language; busy: boolean; onRestore: (taskId: string) => void;
  afresh?: {label: string; hint: string; run: () => void}}) {
  const t = textFor(language);
  return <View testID="failed-task-card" style={styles.pendingSubmission}>
    <Label role="meta" style={{color: colors.error}}>{t('taskFailed')}</Label>
    {!!failure.message && <Label selectable role="meta">{failure.message}</Label>}
    {!!failure.prompt && <OriginalRequestPreview text={failure.prompt} language={language}/>}
    <Label role="caption" style={styles.secondary}>{t('restoreDraftExplanation')}</Label>
    <Pressable testID="restore-failed-task" accessibilityRole="button" accessibilityLabel={t('restoreFailedRequest')}
      accessibilityState={{disabled: busy}} disabled={busy} onPress={() => onRestore(failure.taskId)}
      style={[styles.pendingRetry, busy && styles.sendDisabled]}>
      <Icon name="arrow_left" size={16} color={chatColors.icon}/><Label role="meta">{t('restoreFailedRequest')}</Label>
    </Pressable>
    {afresh && <>
      <Label role="caption" style={styles.secondary}>{afresh.hint}</Label>
      <Pressable testID="continue-afresh" accessibilityRole="button" accessibilityLabel={afresh.label} accessibilityState={{disabled: busy}} disabled={busy}
        onPress={afresh.run} style={[styles.pendingRetry, busy && styles.sendDisabled]}>
        <ChatIcon name="plus" size={16} color={chatColors.icon}/><Label role="meta">{afresh.label}</Label>
      </Pressable>
    </>}
  </View>;
}

const statusKey: Record<string, TextKey> = {running: 'working', completed: 'completed', failed: 'failed', stopped: 'stopped', waiting: 'waiting', unknown: 'unknown'};

function ActivityRow({item, language}: {item: Activity; language: Language}) {
  const [open, setOpen] = useState(false), t = textFor(language);
  const name = activityName(item, key => t(key as TextKey));
  return <View style={styles.activityItem}>
    <Pressable accessibilityRole="button" accessibilityLabel={`${name}. ${t(statusKey[item.status])}. ${t(open ? 'hideDetails' : 'showDetails')}`}
      accessibilityState={{expanded: open}} onPress={() => setOpen(!open)} style={styles.activityRow}>
      <View style={[styles.statusDot, {backgroundColor: item.status === 'failed' ? colors.error : item.status === 'running' ? colors.brand : colors.muted}]}/>
      <Label role="meta" style={[styles.activityName, styles.secondary]}>{name}</Label>
      <Label role="caption" style={[styles.activityStatus, {color: item.status === 'failed' ? colors.error : colors.secondary}]}>{t(statusKey[item.status])}</Label>
      <View style={styles.activityChevron}>{!!item.text && <Icon name={open ? 'chevron_down' : 'chevron_right'} size={16} color={colors.secondary}/>}</View>
    </Pressable>
    {open && !!item.text && <Label selectable role="caption" style={styles.details}>{humanizeToolNames(item.text, key => t(key as TextKey))}</Label>}
  </View>;
}

type ViewHandle = React.ComponentRef<typeof View>;

const hasBody = (block: ChatMessageBlock) => !!block.text || !!block.parts?.some(part => part.card || part.replyState);


function MessageBody({block, language, renderMarkdown, renderCard, demand}: {block: ChatMessageBlock; language: Language; renderMarkdown?: AgentChatScreenProps['renderMarkdown']; renderCard?: AgentChatScreenProps['renderCard']; demand: CardDemand}) {
  if (!renderMarkdown) return <AssistantBubble><Label style={styles.replyText}>{block.text}</Label></AssistantBubble>;
  return <View style={styles.messageParts}>{block.parts.map(part =>
    <React.Fragment key={part.id}>{part.card && renderCard ? renderCard(part, language, demand) : <>
      {!!part.text && <AssistantBubble>{renderMarkdown(part.text, part.active, part.id)}</AssistantBubble>}
      {part.replyState && <Label role="meta" style={styles.secondary}>{textFor(language)(part.replyState.phase === 'interrupted' ? 'cardReplyInterrupted'
        : part.replyState.phase === 'failed' ? 'cardPresentationFailed' : 'cardUpdating')}</Label>}
    </>}</React.Fragment>)}</View>;
}

function Message({block, language, renderMarkdown, renderCard, expanded, thinkingExpanded, demand, onToggleActivity, onToggleThinking, onLongPressReply}: {block: ChatMessageBlock; language: Language; renderMarkdown?: AgentChatScreenProps['renderMarkdown']; renderCard?: AgentChatScreenProps['renderCard']; expanded: boolean; thinkingExpanded: boolean; demand: CardDemand; onToggleActivity: () => void; onToggleThinking: () => void; onLongPressReply?: (block: ChatMessageBlock, body: ViewHandle) => void}) {
  const t = textFor(language);
  const body = useRef<ViewHandle>(null);
  const {fontScale} = useWindowDimensions();
  const hasText = Boolean(block.text.trim());
  // Live reasoning is its own sign of work. Between its end and the first word of the reply the
  // dots return, whether or not a request is tracked for this reply.
  const waiting = block.role === 'assistant' && block.active && !['failed', 'stopped'].includes(block.status)
    && !hasBody(block) && !block.activity.length && !block.thinking?.active;
  // A restored turn/started snapshot can precede both its user item and task metadata.
  // Bound even that native-only placeholder; never fabricate a request to track it.
  const untrackedPending = waiting && block.responsePending === undefined;
  // The timeout belongs to the block it was started for; a different block starts unexpired.
  const [timedOutBlock, setTimedOutBlock] = useState<string | null>(null);
  const untrackedTimeout = timedOutBlock === block.id;
  useEffect(() => {
    if (!untrackedPending) return;
    const id = block.id;
    const timer = setTimeout(() => setTimedOutBlock(id), FIRST_RESPONSE_TIMEOUT_MS);
    return () => clearTimeout(timer);
  }, [block.id, untrackedPending]);
  if (block.role === 'user') {
    const bubble = <View style={styles.userBubble}>
      {!!block.text && <Text selectable style={[chatType.bodyLg(containsChinese(block.text)), styles.userText]}>{block.text}</Text>}
      {block.attachments.map((name, i) => <Label key={`${name}-${i}`} role="caption" style={styles.attachment}>{name === 'image' ? t('image') : name}</Label>)}
      {block.requestStopped && <Label role="caption" style={styles.secondary}>{t('requestStopped')}</Label>}
    </View>;
    return <View style={styles.userWrap}>{block.reaction ? <View>
      <View style={styles.userWrapReacted}>{bubble}</View>
      <View testID={`reaction-${block.id}`} accessible accessibilityLabel={`${t('reactionLabel')} ${block.reaction}`} style={styles.reaction}>
        <Svg pointerEvents="none" style={StyleSheet.absoluteFill} width="100%" height="100%">
          <Defs><LinearGradient id="reaction-frost" x1="0%" y1="0%" x2="0%" y2="100%">
            <Stop offset="0" stopColor={chatColors.bubble} stopOpacity={0.32}/>
            <Stop offset="1" stopColor={chatColors.bubble} stopOpacity={0.12}/>
          </LinearGradient></Defs>
          <Rect width="100%" height="100%" fill="url(#reaction-frost)"/>
        </Svg>
        <View pointerEvents="none" style={styles.reactionTint}/>
        <Text allowFontScaling={false} style={styles.reactionText}>{block.reaction}</Text>
      </View>
    </View> : bubble}</View>;
  }
  // Long press opens Copy / Select once the reply text is final (or a primary card while streaming).
  const canCopy = !!block.text && (!block.active || block.parts.some(part => part.card?.kind === 'primary'));
  const responseError = block.responseError || (block.responseErrorKey ? t(block.responseErrorKey) : untrackedPending && untrackedTimeout ? t('replyTimeout') : '');
  const pending = !responseError && ((block.responsePending ?? untrackedPending) || waiting && !!block.thinking);
  const showState = !responseError && (block.status === 'failed' || block.status === 'stopped');
  return <View style={styles.assistant}>
    {block.thinking && <ThinkingBlock thinking={block.thinking} language={language} expanded={thinkingExpanded} onToggle={onToggleThinking}/>}
    {(hasBody(block) || pending || !!responseError) && <Pressable ref={body} testID={`reply-body-${block.id}`} style={{minHeight: 26 * fontScale}}
      disabled={!canCopy || !onLongPressReply} onLongPress={() => { if (body.current) onLongPressReply?.(block, body.current); }}
      accessibilityActions={canCopy ? [{name: 'longpress', label: t('copy')}] : undefined}
      onAccessibilityAction={() => { if (body.current) onLongPressReply?.(block, body.current); }}>
      {hasBody(block) && <MessageBody block={block} language={language} renderMarkdown={renderMarkdown} renderCard={renderCard} demand={demand}/>}
      {pending && !hasBody(block) && <View style={{height: 26 * fontScale}}><ReplyLoading label={t('working')}/></View>}
      {!!responseError && <Label accessibilityLiveRegion="polite" role="meta" style={{color: colors.error}}>{responseError}</Label>}
    </Pressable>}
    {block.activity.length > 0 && <View style={styles.activityGroup}>
      <View style={styles.replyActions}>
        {block.activity.length > 0 && <Pressable testID={`activity-${block.id}`} style={[styles.activityToggle, expanded && styles.activityToggleExpanded]} accessibilityRole="button" accessibilityState={{expanded}}
          accessibilityLabel={t(expanded ? 'collapseActivity' : 'expandActivity')} onPress={onToggleActivity}>
          <Icon name={block.status === 'completed' ? 'check' : 'history'} size={16} color={colors.secondary}/>
          <Label role="meta" style={[styles.secondary, styles.activityHeading]}>{`${t('activity')}  ${block.activity.length}`}</Label>
          <Icon name={expanded ? 'chevron_down' : 'chevron_right'} size={16} color={colors.secondary}/>
        </Pressable>}
      </View>
      {expanded && <View style={styles.activityPanel}><View style={styles.activities}>{block.activity.map(item => <ActivityRow key={item.id} item={item} language={language}/>)}</View></View>}
    </View>}
    {showState && <View style={styles.stateRow}><Label role="meta" style={{color: block.status === 'failed' ? colors.error : colors.secondary}}>{t(block.status === 'stopped' ? (hasText || block.activity.length ? 'responseStopped' : 'requestStopped') : statusKey[block.status])}</Label></View>}
  </View>;
}

function WorkspacePreview({uri, language}: {uri: string; language: Language}) {
  const [state, dispatch] = useReducer(previewReducer, undefined, emptyPreview);
  const [viewport, setViewport] = useState({width: 0, height: 0});
  const t = textFor(language);
  useEffect(() => {
    const tick = () => {if (AppState.currentState === 'active') dispatch({type: 'tick', now: Date.now(), uri});};
    tick();
    const timer = setInterval(tick, PREVIEW_REFRESH_MS);
    const lifecycle = AppState.addEventListener('change', status => {
      dispatch({type: 'hide'});
      if (status === 'active') tick();
    });
    return () => {clearInterval(timer); lifecycle.remove();};
  }, [uri]);
  const expiry = Math.min(
    state.visible?.loadedAt !== null && state.visible ? state.visible.loadedAt + PREVIEW_FRESH_MS : Infinity,
    state.pending ? state.pending.requestedAt + PREVIEW_LOAD_TIMEOUT_MS : Infinity,
  );
  useEffect(() => {
    if (!Number.isFinite(expiry)) return;
    const timer = setTimeout(() => {
      if (AppState.currentState === 'active') dispatch({type: 'tick', now: Date.now(), uri});
    }, Math.max(1, expiry - Date.now()));
    return () => clearTimeout(timer);
  }, [expiry, uri]);
  const frames = [state.visible, state.pending].filter(frame => frame !== null);
  const unavailable = state.failed || !uri || uri.startsWith('data:') && state.inlineConsumed && !state.visible && !state.pending;
  return <View style={styles.preview} onLayout={event => setViewport({width: event.nativeEvent.layout.width, height: event.nativeEvent.layout.height})}>
    {frames.map(frame => {
      const scale = Number.isFinite(frame.width) && Number.isFinite(frame.height) && frame.width > 0 && frame.height > 0 && viewport.width > 0 && viewport.height > 0
        ? Math.max(viewport.width / frame.width, viewport.height / frame.height) : null;
      // Each Image retains its source. Promote the loaded back buffer without reloading it.
      const imageFrame = scale !== null
        ? {position: 'absolute' as const, top: 0, left: (viewport.width - frame.width * scale) / 2,
          width: frame.width * scale, height: frame.height * scale}
        : StyleSheet.absoluteFill;
      return <Image key={frame.id} source={{uri: frame.uri}} resizeMode="contain" fadeDuration={0}
        style={[imageFrame, state.visible?.id !== frame.id && {opacity: 0}]} onLoad={event => {
          if (AppState.currentState !== 'active') return;
          const {width, height} = event.nativeEvent.source;
          dispatch({type: 'loaded', id: frame.id, now: Date.now(), width, height});
        }} onError={() => dispatch({type: 'error', id: frame.id, now: Date.now()})}/>;
    })}
    {!state.visible && <View style={styles.previewPlaceholder}>{!unavailable && <ActivityIndicator color={colors.white}/>}<Label role="meta" style={styles.previewPlaceholderText}>{t(unavailable ? 'previewUnavailable' : 'loadingPreview')}</Label></View>}
  </View>;
}

function WorkspaceCard({workspace, language, onPress}: {workspace: WorkspaceView; language: Language; onPress: () => void}) {
  const t = textFor(language), identity = `${workspace.threadId}/${workspace.screenKey}/${workspace.generation}/${workspace.previewUri}`;
  return <Pressable accessibilityRole="button" accessibilityLabel={`${t('viewScreen')}: ${workspace.title || workspace.packageName}`} onPress={onPress} style={styles.workspace}>
    <View style={styles.workspaceHeading}><View style={[styles.statusDot, {backgroundColor: colors.muted}]}/><Label role="caption" numberOfLines={1} style={styles.workspaceTitle}>{workspace.title || workspace.packageName || t('virtualScreen')}</Label><Icon name="expand" size={16} color={colors.white}/></View>
    <WorkspacePreview key={identity} uri={workspace.previewUri} language={language}/>
  </Pressable>;
}

function PendingCard({question, language, busy, onAnswer, onOpenRequest}: {question: PendingQuestion; language: Language; busy: boolean; onAnswer: AgentChatScreenProps['onAnswer']; onOpenRequest: () => void}) {
  const t = textFor(language), [answers, setAnswers] = useState<Record<string, string>>({});
  const args = record(question.params.arguments), method = question.method;
  let title = string(args.title) || string(args.question) || t('needsYou');
  let description = string(args.summary), options: {label: string; response: Json; primary?: boolean}[] = [];
  if (method === 'phone/askUser') options = list(args.options).filter(v => typeof v === 'string').map((label, optionIndex) => ({label: string(label), response: {optionIndex}, primary: optionIndex === 0}));
  if (method === 'phone/confirmAction') options = [{label: string(args.confirmLabel) || t('confirm'), response: {decision: 'confirm'}, primary: true}, {label: t('editDetails'), response: {decision: 'modify'}}, {label: t('notNow'), response: {decision: 'cancel'}}];
  if (method === 'phone/action/requestApproval') { title = string(question.params.reason) || t('needsYou'); options = [{label: t('allowOnce'), response: {decision: 'accept'}, primary: true}, {label: t('decline'), response: {decision: 'decline'}}]; }
  const questions = method === 'item/tool/requestUserInput' ? list(question.params.questions).map(record) : [];
  const supported = options.length > 0 || questions.length > 0;
  const submitAnswers = () => onAnswer(question.key, {answers: Object.fromEntries(questions.map(q => [string(q.id), {answers: [answers[string(q.id)].trim()]}]))});
  return <View style={styles.question}>
    <View style={styles.questionEyebrow}><View style={[styles.statusDot, {backgroundColor: colors.brand}]}/><Label role="caption" style={styles.secondary}>{t('needsYou')}</Label></View>
    <Label role="label">{title}</Label>
    {!!description && <Label role="meta" style={styles.questionDescription}>{description}</Label>}
    {method === 'phone/confirmAction' && list(args.fields).map((field, i) => { const f = record(field); return <View key={i} style={[styles.fact, f.style === 'notice' && styles.notice]}><Label role="caption" style={styles.secondary}>{string(f.label)}</Label><Label>{string(f.value)}</Label></View>; })}
    {questions.map((q, i) => <View key={string(q.id) || i} style={styles.questionInput}>
      <Label>{string(q.question)}</Label>
      {list(q.options).map((option, j) => { const o = record(option), label = string(o.label), selected = answers[string(q.id)] === label; return <Pressable key={j} accessibilityRole="button" accessibilityState={{selected, disabled: busy}} disabled={busy} onPress={() => setAnswers(previous => ({...previous, [string(q.id)]: label}))} style={[styles.option, selected && styles.selectedOption]}><Label role="meta">{label}</Label>{!!o.description && <Label role="caption" style={styles.secondary}>{string(o.description)}</Label>}</Pressable>; })}
      <TextInput accessibilityLabel={string(q.question)} placeholder={t('answerPlaceholder')} placeholderTextColor={colors.muted}
        secureTextEntry={q.isSecret === true} autoCorrect={q.isSecret !== true} value={answers[string(q.id)] || ''} editable={!busy}
        onChangeText={value => setAnswers(previous => ({...previous, [string(q.id)]: value}))} style={[typography('body', language === 'zh'), styles.answerInput]}/>
    </View>)}
    {options.map((option, i) => <Pressable key={i} accessibilityRole="button" disabled={busy} onPress={() => onAnswer(question.key, option.response)}
      style={({pressed}) => [styles.answerButton, option.primary && styles.primaryButton, busy && {opacity: 0.4}, pressed && {opacity: 0.7}]}><Label role="meta" style={{color: option.primary ? colors.white : colors.ink}}>{option.label}</Label></Pressable>)}
    {questions.length > 0 && <Pressable accessibilityRole="button" disabled={busy || questions.some(q => !answers[string(q.id)]?.trim())} onPress={submitAnswers}
      style={[styles.answerButton, styles.primaryButton, (busy || questions.some(q => !answers[string(q.id)]?.trim())) && {opacity: 0.4}]}><Label role="meta" style={{color: colors.white}}>{t('submit')}</Label></Pressable>}
    {!supported && <><Label role="meta" style={styles.questionDescription}>{t('unsupportedRequest')}</Label><Pressable accessibilityRole="button" onPress={onOpenRequest} style={styles.answerButton}><Label role="meta">{t('nativeControls')}</Label></Pressable></>}
  </View>;
}

/** [opens] is the outside-open count when the layer was shown; a later outside open hides it. */
type Layer = {kind: 'sidebar'; opens: number};

export function AgentChatScreen(props: AgentChatScreenProps) {
  const router = useRouter();
  const locales = useLocales(), language = languageFor(locales[0]?.languageTag), t = textFor(language), ct = chatText(language), insets = useSafeAreaInsets();
  const dimensions = useWindowDimensions();
  const [measuredHostHeight, setMeasuredHostHeight] = useState<number | null>(null);
  const nativeIme = Platform.OS === 'android' && Number(Platform.Version) >= 30;
  const [imeGeometry, setImeGeometry] = useState<ImeGeometry | null>(null);
  const [legacyKeyboardVisible, setKeyboardVisible] = useState(() => Keyboard.isVisible());
  const keyboardVisible = nativeIme ? imeGeometry?.visible === true : legacyKeyboardVisible;
  useEffect(() => {
    if (nativeIme) {
      const subscription = onImeInsets(setImeGeometry);
      return () => subscription.remove();
    }
    const show = Keyboard.addListener(Platform.OS === 'ios' ? 'keyboardWillShow' : 'keyboardDidShow', () => setKeyboardVisible(true));
    const hide = Keyboard.addListener(Platform.OS === 'ios' ? 'keyboardWillHide' : 'keyboardDidHide', () => setKeyboardVisible(false));
    const lifecycle = AppState.addEventListener('change', state => {
      if (state === 'active') setKeyboardVisible(Keyboard.isVisible());
    });
    return () => {show.remove(); hide.remove(); lifecycle.remove();};
  }, [nativeIme]);
  // An outside open (island card, Feed, Panel) shows that conversation, and dismisses any open navigation overlay.
  const externalOpens = props.externalOpens ?? 0;
  const [openedLayer, setLayer] = useState<Layer | null>(() => props.initialSidebar ? {kind: 'sidebar', opens: externalOpens} : null);
  const layer = openedLayer?.opens === externalOpens ? openedLayer : null;
  useChatBackdrop();
  // Taku Home lists panels; opening one hands off to the desktop.
  const [composerHeight, setComposerHeight] = useState(40);
  const [composerBox, setComposerBox] = useState(64);
  const [expandedActivities, setExpandedActivities] = useState<Set<string>>(() => new Set());
  const [expandedThinking, setExpandedThinking] = useState<Set<string>>(() => new Set());
  // Android uses one complete native geometry snapshot, never KAV's asynchronously resized child.
  const space = sheetSpace(nativeIme ? imeGeometry : null, measuredHostHeight ?? dimensions.height, 0);
  // Full host height; the keyboard covers its bottom `space.bottom` dp. Absolutely placed children
  // ignore the host's padding, so the composer and the list add that overlap themselves.
  const hostHeight = space.available + space.bottom;
  // Input limits follow the space left above the keyboard; shell insets are separate.
  const composerMaximum = space.available - insets.top < 500 ? 96 : 144;
  const multilineComposer = props.draft.includes('\n') || composerHeight > Math.max(40, 24 * dimensions.fontScale + 16) + 2;
  // Figma 2-1: the title row sits under the status bar; the composer floats 40 dp above the screen
  // bottom (16 dp above the gesture bar). Figma 1-3: 16 dp above the keyboard.
  const headerBottom = insets.top + chatMetrics.header;
  const composerBottom = (keyboardVisible ? 16 : insets.bottom + 16) + space.bottom;
  const composerTop = hostHeight - composerBottom - composerBox;
  const scroll = useRef<FlatList<ChatMessageBlock>>(null);
  const [scrollState, setScrollState] = useState<ChatScrollState>(() => createChatScrollState());
  const scrollStateRef = useRef<ChatScrollState>(scrollState);
  const pinSettleUntil = useRef<number>(0);
  const preSendRef = useRef<KnownUsers>(knownUsers([]));
  const scrollMetrics = useRef({contentHeight: 0, viewportHeight: 0, offsetY: 0});
  const [turnHeights, setTurnHeights] = useState({key: '', user: 0, assistant: 0, footer: 0});
  const input = useRef<React.ComponentRef<typeof TextInput>>(null);
  const [demandBand, setDemandBand] = useState<RowBand | null>(null);
  // RN forbids replacing these handlers on the fly, so they live for the screen's lifetime.
  const [viewability] = useState(() => ({
    viewabilityConfig: {itemVisiblePercentThreshold: 10},
    onViewableItemsChanged: ({viewableItems}: {viewableItems: readonly {index?: number | null}[]}) => {
      const next = visibleBand(viewableItems.map(item => item.index));
      setDemandBand(previous => sameBand(previous, next) ? previous : next);
    },
  }));
  // Only a finger (a drag, or the fling right after it) moves the list as the user; see nextScrollIntent.
  const scrollIntent = useRef(createScrollIntent());
  const markScroll = (event: ScrollIntentEvent) => { scrollIntent.current = nextScrollIntent(scrollIntent.current, event); };
  const positions = useRef<Record<string, {y: number; follow: boolean}>>({});
  const restoreY = useRef<number | null>(null);
  // Read at effect time (a re-pin can run before the render that binds the anchor commits).
  const pinGeometry = useRef<{blocks: readonly ChatMessageBlock[]; topPadding: number}>({blocks: [], topPadding: 0});
  // The native ScrollView's own end, not VirtualizedList.scrollToEnd: that one computes the offset from
  // JS cell metrics and the list's visible length, which are still empty right after a conversation
  // opens, so "the end" lands near the top and nothing later corrects it.
  const scrollToEnd = useCallback((animated: boolean) => {
    const native = scroll.current?.getNativeScrollRef();
    if (native) native.scrollToEnd({animated});
    else scroll.current?.scrollToEnd({animated});
  }, []);
  const applyScrollEffect = useCallback((effect: ChatScrollEffect) => {
    if (effect === 'none') return;
    scrollIntent.current = createScrollIntent();
    if (effect === 'toPinAnimated') {
      const turn = findPinnedTurn(pinGeometry.current.blocks, scrollStateRef.current.anchor);
      if (turn) {
        scroll.current?.scrollToIndex({index: turn.userIndex, viewPosition: 0, viewOffset: pinGeometry.current.topPadding, animated: true});
        return;
      }
    }
    scrollToEnd(effect !== 'toBottomInstant');
  }, [scrollToEnd]);
  const dispatchScroll = useCallback((event: ChatScrollEvent) => {
    const {state: next, effect} = reduceChatScroll(scrollStateRef.current, event);
    scrollStateRef.current = next;
    setScrollState(previous => sameChatScrollState(previous, next) ? previous : next);
    applyScrollEffect(effect);
  }, [applyScrollEffect]);
  const sessionId = props.session?.id || '';
  const resolvedPrimaryId = props.primaryThreadId ?? '';
  const isMainChat = props.isMainChat ?? isMainConversation(sessionId, resolvedPrimaryId);
  const {mainThread, taskThreads} = useMemo(() => partitionThreads(props.threads, resolvedPrimaryId), [props.threads, resolvedPrimaryId]);
  const sidebarOpen = layer?.kind === 'sidebar';
  // TAKU follows the newest Tasks' status while it is on screen; the open sidebar follows its longer list.
  const followedTasks = useMemo(() => taskThreads.slice(0, sidebarOpen ? DRAWER_TASKS : STRIP_TASKS).map(thread => thread.id), [taskThreads, sidebarOpen]);
  const visible = props.visible ?? true;
  // Following starts once TAKU's own conversation has loaded, never during its restore.
  const tasks = useTaskStatuses(followedTasks, visible && isMainChat && !props.loading, props.session);
  const capsules = useMemo(() => liveTasks(taskThreads, tasks.board), [taskThreads, tasks.board]);
  const {onOpenHistory, onStartMainAfresh} = props;
  // TAKU may start or finish a Task during its own turn, and anything may have while the chat was
  // away or on a Task's page: read the Task list again when a TAKU turn ends, when the chat returns
  // to the screen and when the user comes back to TAKU. On those events only, never on a timer:
  // every listing is a broker control request that leaves a record on the phone.
  const mainTurnRunning = isMainChat && !!props.session?.running;
  const listedOnce = useRef(false);
  useEffect(() => {
    if (!visible || mainTurnRunning) return;
    let current = true;
    // The first listing is the controller's own, at startup. Nobody waits on the later ones, so
    // they go when the conversation leaves the gateway room.
    if (listedOnce.current) void gatewayLoad.quiet().then(() => { if (current) onOpenHistory?.(); });
    listedOnce.current = true;
    return () => { current = false; };
  }, [visible, mainTurnRunning, isMainChat, onOpenHistory]);
  // Requests the cards cannot answer in place are handled on the pending-requests page.
  const openRequests = (requestKey?: string) => router.push({pathname: '/assistant-management',
    params: {section: 'requests', ...(sessionId ? {threadId: sessionId} : {}), ...(requestKey ? {requestKey} : {})}});
  const live = useLiveTalk(props.draftSessionId, props.loading, props.onSelectThread);
  const pendingSubmission = props.pendingSubmission?.threadId === props.draftSessionId ? props.pendingSubmission : null;
  const viewKey = `${props.conversationKey ?? sessionId}/chat`;
  // Entering Panel editing follows the latest turn, so Taku's question and its suggestions show above
  // the composer even in a conversation whose reading position was restored (issue #52 item 5).
  const panelLaunch = props.panelTarget?.launchId ?? '';
  useEffect(() => {
    if (panelLaunch) positions.current[viewKey] = {y: 0, follow: true};
    const position = positions.current[viewKey];
    const browsing = !!(position && !position.follow);
    pinSettleUntil.current = 0;
    scrollIntent.current = createScrollIntent();
    dispatchScroll({type: 'reset', browsing});
    restoreY.current = browsing && position ? position.y : null;
    if (browsing && position) requestAnimationFrame(() => {
      if (restoreY.current === position.y && scrollStateRef.current.mode === 'browsing') {
        scrollIntent.current = createScrollIntent();
        scroll.current?.scrollToOffset({offset: position.y, animated: false});
      }
    });
    if (panelLaunch) requestAnimationFrame(() => { if (scrollStateRef.current.mode === 'following') scrollToEnd(false); });
  }, [viewKey, panelLaunch, dispatchScroll, scrollToEnd]);
  const activeTurnHeights = turnHeights.key === viewKey ? turnHeights : {key: viewKey, user: 0, assistant: 0, footer: 0};
  const workspaces = useMemo(() => props.workspaces.filter(w => w.threadId === sessionId), [props.workspaces, sessionId]);
  const blocks = props.session?.blocks || [];
  const visibleBlocks = useMemo(() => {
    const nativeBlocks = props.session?.blocks || [];
    return chatPresentation(nativeBlocks, props.optimisticSubmissions);
  }, [props.session?.blocks, props.optimisticSubmissions]);
  const error = props.error || props.session?.error;
  const taskFailure = props.session?.taskFailure;
  // No thread is open: a fresh device, or TAKU started afresh. Its first message creates the thread.
  const newConversation = props.session?.knownEmpty || (!props.session && !props.loading && !error && !props.draftSessionId);
  // The 2-1 greeting opens the conversation, unless it is still loading or its history is unknown.
  // Issue #32 C2: the greeting is the empty state of a new conversation, not a line above messages.
  const greeting = visibleBlocks.length === 0 && !!newConversation && !props.panelTarget;
  // The Task row and the Task cards belong to TAKU's own page only.
  const onMainPage = isMainChat && !live.active;
  const stripShown = onMainPage && capsules.length > 0;
  const cards = onMainPage ? taskCards(taskThreads, tasks.board, {allowOnce: t('allowOnce'), decline: t('decline')}) : [];
  // A quick start opens the composer with a line the user finishes; nothing is sent for them.
  const startWith = (prompt: string) => {
    props.onDraftChange(prompt);
    setInsertedCaret({threadId: props.draftSessionId, text: prompt, caret: prompt.length});
    input.current?.focus();
  };
  // Where the conversation starts: under the title row, and under the Task row when it shows.
  const contentTop = headerBottom + (stripShown ? chatMetrics.strip : 0);
  const listTopPadding = contentTop + (error ? 56 : 0);
  // Layout effects run before the passive effect that binds a sent message and before its rAF re-pin.
  useLayoutEffect(() => { pinGeometry.current = {blocks: visibleBlocks, topPadding: listTopPadding}; }, [visibleBlocks, listTopPadding]);
  const pinnedTurn = useMemo(() => findPinnedTurn(visibleBlocks, scrollState.anchor), [visibleBlocks, scrollState.anchor]);
  const pinnedSpacer = pinnedTurn
    ? pinnedSpacerHeight({
        viewportHeight: Math.max(0, composerTop - listTopPadding),
        userHeight: activeTurnHeights.user,
        assistantHeight: activeTurnHeights.assistant,
        footerHeight: activeTurnHeights.footer,
        itemGap: chatMetrics.itemGap,
      })
    : 0;
  useEffect(() => {
    if (!scrollState.pendingSend) return;
    const lastUser = findSentUser(visibleBlocks, preSendRef.current);
    if (!lastUser) return;
    pinSettleUntil.current = Date.now() + PIN_SETTLE_MS;
    dispatchScroll({type: 'anchorBound', userId: lastUser.id, turnId: lastUser.turnId});
    requestAnimationFrame(() => {
      if (scrollStateRef.current.mode === 'pinned' && !scrollIntent.current.byUser) applyScrollEffect('toPinAnimated');
    });
  }, [scrollState.pendingSend, visibleBlocks, dispatchScroll, applyScrollEffect]);
  // The Task row appearing or leaving moves the conversation by its height. While the user reads
  // history on the same page, the list moves with it so the line under their eyes stays put.
  // (Following re-applies the content end and a pinned turn re-aligns to the new top by themselves.)
  const stripLayout = useRef({viewKey, shown: stripShown});
  useEffect(() => {
    const previous = stripLayout.current;
    stripLayout.current = {viewKey, shown: stripShown};
    const position = positions.current[viewKey];
    if (previous.viewKey !== viewKey || previous.shown === stripShown || scrollStateRef.current.mode !== 'browsing' || !position || position.follow) return;
    const y = Math.max(0, position.y + (stripShown ? chatMetrics.strip : -chatMetrics.strip));
    positions.current[viewKey] = {y, follow: false};
    scrollIntent.current = createScrollIntent();
    scroll.current?.scrollToOffset({offset: y, animated: false});
  }, [stripShown, viewKey]);
  // The title row names where the user is: TAKU, or the open Task.
  const pageTitle = live.active ? ct('liveTalk') : isMainChat ? chatTitle
    : props.session?.title || props.threads.find(thread => thread.id === props.draftSessionId)?.title || ct('task');
  const composerHint = ct(isMainChat ? 'messageTaku' : 'replyInTask');
  const onScroll = (event: NativeSyntheticEvent<NativeScrollEvent>) => {
    const {contentOffset, contentSize, layoutMeasurement} = event.nativeEvent;
    scrollMetrics.current = {contentHeight: contentSize.height, viewportHeight: layoutMeasurement.height, offsetY: contentOffset.y};
    if (restoreY.current !== null) return;
    const dist = distanceFromBottom(contentSize.height, layoutMeasurement.height, contentOffset.y);
    dispatchScroll({type: 'scrolled', distance: dist, byUser: scrollIntent.current.byUser, spacerActive: pinnedSpacer > 0});
    if (scrollIntent.current.byUser) {
      positions.current[viewKey] = {y: contentOffset.y, follow: scrollStateRef.current.mode === 'following'};
    }
  };
  const sendAndFollow = () => {
    // Dismiss the keyboard and pin the newly sent user message to the top of the viewport.
    preSendRef.current = knownUsers(visibleBlocks);
    scrollIntent.current = createScrollIntent();
    restoreY.current = null;
    positions.current[viewKey] = {y: 0, follow: true};
    pinSettleUntil.current = Date.now() + PIN_SETTLE_MS;
    setTurnHeights(previous => {
      const base = previous.key === viewKey ? previous : {key: viewKey, user: 0, assistant: 0, footer: 0};
      return base.user === 0 && base.assistant === 0 ? base : {...base, user: 0, assistant: 0};
    });
    Keyboard.dismiss();
    dispatchScroll({type: 'sent'});
    props.onSend();
  };
  const scrollToLatest = () => {
    pinSettleUntil.current = 0;
    scrollIntent.current = createScrollIntent();
    restoreY.current = null;
    positions.current[viewKey] = {y: 0, follow: true};
    dispatchScroll({type: 'tappedToBottom'});
  };
  const collapseChat = () => { cancelDictation(); stopReading(); live.stop(); (props.onCollapse ?? props.onClose)(); };
  const openSidebar = () => {
    live.stop();
    cancelDictation();
    stopReading();
    props.onOpenHistory?.();
    Keyboard.dismiss();
    setReplyMenu(null);
    setLayer({kind: 'sidebar', opens: externalOpens});
  };
  const selectMainChat = () => {
    live.stop();
    if (props.onSelectMainChat) props.onSelectMainChat();
    else if (mainThread) props.onSelectThread(mainThread.id);
  };
  const selectTaskThread = (id: string) => {
    live.stop();
    props.onSelectThread(id);
  };
  const createIndependentTask = () => {
    live.stop();
    (props.onCreateTaskThread ?? props.onCreateThread)();
  };
  const running = props.session?.running;
  const hasDraft = !!props.draft.trim() || props.attachments.length > 0;
  // The "+" menu (1-1) belongs to the conversation it was opened in.
  const [attachMenu, setAttachMenu] = useState<string | null>(null);
  const [inputFocused, setInputFocused] = useState(false);
  const [caretAt, setCaretAt] = useState<{threadId: string; caret: number} | null>(null);
  // A mention the user dismissed with Back stays closed until its `@` changes.
  const [dismissedMention, setDismissedMention] = useState<string | null>(null);
  // Only a send in flight or a queued task holds the send button back.
  const sendHeld = props.busy || props.loading || !!props.session?.queued;
  const [insertedCaret, setInsertedCaret] = useState<{threadId: string; text: string; caret: number} | null>(null);
  const selection = useRef<{threadId: string; range: DraftSelection} | null>(null);
  const voiceOwner = useRef<string | null>(null);
  const setVoiceOwner = useCallback((owner: string | null) => { voiceOwner.current = owner; }, []);
  const voiceEnabled = !props.loading && !layer;
  const {draftSessionId, draftRevision, draft} = props;
  const draftNow = useRef({threadId: draftSessionId, revision: draftRevision, text: draft});
  useEffect(() => { draftNow.current = {threadId: draftSessionId, revision: draftRevision, text: draft}; }, [draftSessionId, draftRevision, draft]);
  const onVoiceFailure = (reason: DictationFailure) => {
    if (reason === 'falKeyMissing') { promptFalKey(ct, 'voiceFalKeyMissing'); return; }
    const key = {disabled: 'voiceDisabled', busy: 'voiceBusy', unavailable: 'voiceUnavailable', draftChanged: 'dictationDraftChanged', draftFull: 'dictationDraftFull'} as const;
    if (Platform.OS === 'android') ToastAndroid.show(ct(key[reason]), ToastAndroid.SHORT);
  };
  const dictation = useDictation(props.draftSessionId, voiceOwner, () => {
    const {threadId, revision, text} = draftNow.current;
    if (!voiceEnabled || props.busy || live.active || revision === null) return null;
    const range = selection.current?.threadId === threadId ? selection.current.range : {start: text.length, end: text.length};
    return heard => {
      const current = draftNow.current;
      if (current.threadId !== threadId) return;
      if (current.revision !== revision || current.text !== text) { onVoiceFailure('draftChanged'); return; }
      const inserted = insertDictation(text, range, heard);
      if (inserted.text.length > 16_000) { onVoiceFailure('draftFull'); return; }
      void props.onDictationDraftChange(inserted.text, {threadId, revision, text}).then(applied => {
        if (draftNow.current.threadId !== threadId) return;
        if (applied) setInsertedCaret({threadId, ...inserted});
        else onVoiceFailure('draftChanged');
      }).catch(() => onVoiceFailure('draftChanged'));
    };
  }, onVoiceFailure);
  const dictating = dictation.phase !== 'idle';
  const {cancel: cancelDictation} = dictation;
  useVoiceChatFocus(props.draftSessionId, !layer, setVoiceOwner, cancelDictation);
  const speech = useReplySpeechState();
  const speaking = speech?.threadId === props.draftSessionId && speech.phase !== 'failed' ? speech : null;
  useEffect(() => {
    if (speech?.threadId === props.draftSessionId && speech.phase === 'failed' && Platform.OS === 'android')
      ToastAndroid.show(chatText(language)('replySpeechUnavailable'), ToastAndroid.SHORT);
  }, [speech, props.draftSessionId, language]);
  const stopReading = () => { if (speaking) void stopReplySpeech(speaking.threadId, speaking.replyId).catch(() => {}); };
  const voice = live.active ? live.state : null;
  const liveLevel = voice?.capturing ? voice.levels.at(-1) ?? 0 : 0;
  const liveFalKeyMissing = voice?.setup === 'fal';
  const liveStatus = liveFalKeyMissing ? 'liveFalKeyMissing'
    : voice?.lifecycle === 'connecting' ? 'liveConnecting'
    : voice?.lifecycle === 'disconnected' ? 'liveDisconnected'
    : voice?.lifecycle === 'failed' ? 'liveFailed'
    : voice?.muted ? 'liveMicPaused' : voice?.playback.state === 'speaking' ? 'liveSpeaking'
    : voice?.capturing ? 'listening' : 'liveReady';
  const liveDisconnected = voice?.lifecycle === 'disconnected' || voice?.lifecycle === 'failed';
  const beginLiveTalk = () => { void live.start().catch(error => onVoiceFailure(failureOf(error?.code))); };
  // Live Talk learns of a missing fal key only after it tried to connect: prompt once per failed attempt.
  const falPrompted = useRef<string | null>(null);
  const falAttempt = liveFalKeyMissing && voice ? `${voice.sessionId}:${voice.generation}` : null;
  useEffect(() => {
    if (!falAttempt || falPrompted.current === falAttempt) return;
    falPrompted.current = falAttempt;
    promptFalKey(chatText(language), 'liveFalKeyMissing');
  }, [falAttempt, language]);
  const caret = insertedCaret?.threadId === props.draftSessionId && insertedCaret.text === props.draft ? insertedCaret.caret : null;
  // Figma 1-2: an `@` at a word start in the focused composer opens the plugin suggestions.
  const caretNow = caretAt?.threadId === props.draftSessionId ? Math.min(caretAt.caret, props.draft.length) : props.draft.length;
  const typedMention = inputFocused && !dictating && !voice && !props.loading && !props.busy ? mentionAt(props.draft, caretNow) : null;
  const mentionKey = typedMention ? `${props.draftSessionId}/${typedMention.start}` : null;
  const mention = typedMention && mentionKey !== dismissedMention ? typedMention : null;
  const placeCaret = (text: string, at: number) => {
    setInsertedCaret({threadId: props.draftSessionId, text, caret: at});
    setCaretAt({threadId: props.draftSessionId, caret: at});
  };
  // Picking a plugin replaces the typed `@query` with the plugin's chip (Figma 1-3).
  const pickPlugin = (pluginId: string) => {
    if (mention) {
      const next = removeMention(props.draft, mention);
      props.onDraftChange(next.text);
      placeCaret(next.text, next.caret);
    }
    props.onAddPlugin(pluginId);
    // Picking keeps the user writing: the composer stays focused with its keyboard (issue #52 item 2).
    input.current?.focus();
  };
  const chooseAttachment = (choice: AttachChoice) => {
    setAttachMenu(null);
    if (choice !== 'plugins') { props.onPickAttachment(choice); return; }
    // Plugins types the `@` itself, so the suggestions open where the user was writing.
    const next = insertMentionTrigger(props.draft, caretNow);
    props.onDraftChange(next.text);
    placeCaret(next.text, next.caret);
    setDismissedMention(null);
    input.current?.focus();
  };
  useEffect(() => {
    if (!dictating) return;
    const back = BackHandler.addEventListener('hardwareBackPress', () => { cancelDictation(); return true; });
    return () => back.remove();
  }, [dictating, cancelDictation]);
  useEffect(() => {
    if (!live.active) return;
    const back = BackHandler.addEventListener('hardwareBackPress', () => { live.stop(); return true; });
    return () => back.remove();
  }, [live]);
  // Back: the drawer closes itself first (TaskDrawer), then Taku Home returns to the conversation.
  useEffect(() => {
    if (!layer) return;
    const back = BackHandler.addEventListener('hardwareBackPress', () => { setLayer(null); return true; });
    return () => back.remove();
  }, [layer]);
  useEffect(() => {
    if (!attachMenu && !mentionKey) return;
    const back = BackHandler.addEventListener('hardwareBackPress', () => {
      if (attachMenu) setAttachMenu(null);
      else if (mentionKey && mentionKey !== dismissedMention) setDismissedMention(mentionKey);
      else return false;
      return true;
    });
    return () => back.remove();
  }, [attachMenu, mentionKey, dismissedMention]);
  const [replyMenu, setReplyMenu] = useState<ReplyMenuTarget | null>(null);
  // The long-pressed reply's card, when it may offer Panels actions (a panel reply, or pinned).
  const [menuCard, setMenuCard] = useState<{id: string; pinned: boolean} | null>(null);
  const openReplyMenu = (block: ChatMessageBlock, view: ViewHandle) => {
    void Haptics.performAndroidHapticsAsync(Haptics.AndroidHaptics.Long_Press).catch(() => {});
    const card = block.parts.find(part => part.card && !part.superseded)?.card;
    const id = card && sessionId ? cardId(sessionId, presentationRequest(card).key) : '';
    const actions = id ? cardPanelActions(id) : null;
    setMenuCard(actions ? {id, pinned: actions.pinned} : null);
    view.measureInWindow((x, y, width, height) => setReplyMenu({id: block.id, threadId: props.draftSessionId, text: block.text, x, y, width, height}));
  };
  const panelAction = (run: (id: string) => Promise<unknown>, done?: 'pinned' | 'unpinned') => {
    const card = menuCard;
    if (!card) return;
    void run(card.id).then(() => { if (done && Platform.OS === 'android') ToastAndroid.show(ct(done), ToastAndroid.SHORT); },
      () => { if (Platform.OS === 'android') ToastAndroid.show(ct('panelActionFailed'), ToastAndroid.SHORT); });
  };
  const closeReplyMenu = useCallback(() => setReplyMenu(null), []);
  // Figma 1-2 / 1-3: with an `@` open or a plugin attached, the composer stacks its entry row over its actions.
  const panelTarget = props.panelTarget ?? null;
  const panelTitle = usePanelTitle(panelTarget?.panelId);
  const composerExpanded = !dictating && (!!mention || props.plugins.length > 0 || !!panelTarget);
  // A suggestion starts the request in the composer; the user still sends it.
  const suggestEdit = (text: string) => {
    props.onDraftChange(text);
    placeCaret(text, text.length);
    input.current?.focus();
  };
  const plusButton = <Pressable testID="composer-tools" accessibilityRole="button" accessibilityLabel={ct('attachMenu')}
    accessibilityState={{expanded: attachMenu === props.draftSessionId, disabled: props.busy || props.loading}}
    disabled={props.busy || props.loading} onPress={() => setAttachMenu(attachMenu === props.draftSessionId ? null : props.draftSessionId)}
    style={({pressed}) => [styles.composerAction, pressed && styles.pressed]}>
    <ChromeIcon name="plus" size={18}/>
  </Pressable>;
  const messageInput = <TextInput key={props.draftSessionId} ref={input} testID="message-input" accessibilityLabel={running ? t('addRequirement') : composerHint}
    placeholder={props.plugins.length ? '' : running ? t('addRequirement') : composerHint}
    placeholderTextColor={chatColors.placeholder} value={props.draft} onChangeText={props.onDraftChange} multiline textAlignVertical={multilineComposer || composerExpanded ? 'top' : 'center'}
    selection={caret === null ? undefined : {start: caret, end: caret}}
    onSelectionChange={event => {
      const range = event.nativeEvent.selection;
      selection.current = {threadId: props.draftSessionId, range};
      setCaretAt({threadId: props.draftSessionId, caret: range.end});
      if (caret !== null && range.start === caret && range.end === caret) setInsertedCaret(null);
    }}
    // Backspace at the very start of the entry removes the last attached plugin, as with a chip.
    onKeyPress={event => {
      const range = selection.current?.threadId === props.draftSessionId ? selection.current.range : null;
      const last = props.plugins.at(-1);
      if (event.nativeEvent.key === 'Backspace' && last && range?.start === 0 && range.end === 0) props.onRemovePlugin(last.id);
    }}
    editable={!props.loading && !props.busy} onFocus={() => { setInputFocused(true); void showKeyboard().catch(() => {}); }}
    onBlur={() => setInputFocused(false)}
    onContentSizeChange={event => setComposerHeight(Math.max(40, Math.min(144, Math.ceil(event.nativeEvent.contentSize.height))))}
    style={[chatType.bodyLg(language === 'zh' || containsChinese(props.draft)), styles.input, composerExpanded && styles.inputExpanded,
      {height: Math.min(composerHeight, composerMaximum)}]} maxLength={16000}/>;
  const trailingActions = <>
    {(running || props.session?.queued) && <Pressable testID="stop-turn" accessibilityRole="button" accessibilityLabel={t(props.session?.queued ? 'cancelQueued' : 'stop')}
      accessibilityState={{disabled: props.busy}} disabled={props.busy} onPress={props.onStop}
      style={({pressed}) => [styles.composerAction, styles.stop, props.busy && styles.sendDisabled, pressed && styles.stopPressed]}><View style={styles.stopGlyph}/></Pressable>}
    {speaking && <Pressable testID="stop-reply-speech" accessibilityRole="button" accessibilityLabel={ct('replySpeechStop')}
      onPress={stopReading} style={({pressed}) => [styles.composerAction, styles.stop, pressed && styles.stopPressed]}><View style={styles.stopGlyph}/></Pressable>}
    <Pressable testID="voice-input" accessibilityRole="button" accessibilityLabel={ct('voice')} accessibilityHint={ct('voiceSettingsHint')}
      accessibilityState={{disabled: sendHeld || props.draftRevision === null || !voiceEnabled}} disabled={sendHeld || props.draftRevision === null || !voiceEnabled}
      onLongPress={() => { void openVoiceSettings().catch(() => onVoiceFailure('unavailable')); }}
      onPress={dictation.start} style={({pressed}) => [styles.composerAction, pressed && styles.pressed]}><ChromeIcon name="mic" size={18}/></Pressable>
    {hasDraft && <Pressable testID="send-message" accessibilityRole="button" accessibilityLabel={t('send')} accessibilityState={{busy: props.busy}}
      onPress={() => { if (!sendHeld) sendAndFollow(); }} style={({pressed}) => [styles.composerAction, styles.send, pressed && {opacity: 0.8}]}>
      {/* Figma 1-3 send (node 1:3663): #1B1B1B arrow on Main/Brand (issue #32 C3). */}
      {props.busy ? <ActivityIndicator size="small" color={homeColors.icon}/> : <ChatIcon name="arrowUp" color={homeColors.icon} size={18}/>}
    </Pressable>}
    {!hasDraft && <Pressable testID="live-talk-start" accessibilityRole="button" accessibilityLabel={ct('liveTalk')}
      disabled={!voiceEnabled} onPress={() => {
        Keyboard.dismiss();
        beginLiveTalk();
      }} style={styles.composerAction}><ChromeIcon name="voice" size={18}/></Pressable>}
  </>;
  return <KeyboardAvoidingView style={styles.flex} behavior="padding" enabled={!nativeIme && keyboardVisible}
    onLayout={event => {if (nativeIme) setMeasuredHostHeight(event.nativeEvent.layout.height);}}>
    <StatusBar style={getChatAppearance() === 'dark' ? 'light' : 'dark'}/>
    <View style={[styles.host, nativeIme && {paddingBottom: space.bottom}]}
      onLayout={event => {if (!nativeIme) setMeasuredHostHeight(event.nativeEvent.layout.height);}}>
    <View style={StyleSheet.absoluteFill} pointerEvents={layer ? 'none' : 'box-none'} importantForAccessibility={layer ? 'no-hide-descendants' : 'auto'}>
    <View style={[StyleSheet.absoluteFill, layer && styles.hidden]} pointerEvents="box-none">
    {shouldShowLiveLight(live.active) && <VoiceEffect kind="light" target="live" level={liveLevel}
      style={liveLightStyle(dimensions.width)}/>}
    {/* The conversation runs under the title row and the composer; both edges blur and fade. Under
        the Task row it is gone by the capsules' lower edge, so a message never shows through them. */}
    <EdgeBlur style={StyleSheet.absoluteFill} topSharp={contentTop + (stripShown ? 12 : 8)} topFull={stripShown ? contentTop - 8 : 0}
      bottomSharp={composerTop - 32} bottomFull={composerTop + 16} sigma={chatBlur.edgeSigma} fade uniformSigma={replyMenu ? chatBlur.menuSigma : 0}>
    <FlatList key={viewKey} ref={scroll} data={visibleBlocks} keyExtractor={block => block.id} style={styles.flex}
      contentContainerStyle={[styles.messages,
        {paddingTop: listTopPadding, paddingBottom: hostHeight - composerTop + chatMetrics.itemGap}]}
      initialNumToRender={12} maxToRenderPerBatch={8} windowSize={7} removeClippedSubviews={false}
      onScroll={onScroll} scrollEventThrottle={32}
      // The list's own height (first layout, host or IME resize) re-applies the current mode, as content changes do.
      onLayout={event => {
        const height = event.nativeEvent.layout.height;
        if (Math.abs(height - scrollMetrics.current.viewportHeight) < 1) return;
        scrollMetrics.current.viewportHeight = height;
        if (restoreY.current !== null) return;
        const {contentHeight, offsetY} = scrollMetrics.current;
        dispatchScroll({type: 'contentResized', withinPinSettle: Date.now() <= pinSettleUntil.current,
          distance: contentHeight > 0 ? distanceFromBottom(contentHeight, height, offsetY) : undefined, spacerActive: pinnedSpacer > 0});
      }}
      // An unmeasured pinned row (rare: it is the newest row) falls back to the content end.
      onScrollToIndexFailed={() => { scrollIntent.current = createScrollIntent(); scrollToEnd(true); }}
      onScrollBeginDrag={() => {markScroll('dragBegin'); pinSettleUntil.current = 0;}}
      onScrollEndDrag={event => {onScroll(event); markScroll('dragEnd');}}
      onMomentumScrollBegin={() => markScroll('momentumBegin')}
      onMomentumScrollEnd={event => {onScroll(event); markScroll('momentumEnd');}}
      extraData={demandBand} onViewableItemsChanged={viewability.onViewableItemsChanged}
      viewabilityConfig={viewability.viewabilityConfig}
      keyboardShouldPersistTaps="handled" keyboardDismissMode="on-drag" onContentSizeChange={(_w, height) => {
        scrollMetrics.current.contentHeight = height;
        if (restoreY.current !== null && blocks.length) { scrollIntent.current = createScrollIntent(); scroll.current?.scrollToOffset({offset: restoreY.current, animated: false}); restoreY.current = null; return; }
        const {viewportHeight, offsetY} = scrollMetrics.current;
        const dist = viewportHeight > 0 ? distanceFromBottom(height, viewportHeight, offsetY) : undefined;
        dispatchScroll({type: 'contentResized', withinPinSettle: Date.now() <= pinSettleUntil.current, distance: dist, spacerActive: pinnedSpacer > 0});
      }}
      ListEmptyComponent={taskFailure || newConversation ? undefined : props.loading ? <View style={styles.empty}><ActivityIndicator color={colors.secondary}/><Label role="meta" style={styles.secondary}>{t('loading')}</Label></View> : <View style={styles.empty}>
        <Icon name="tako" size={40}/><Label role="context" style={styles.center}>{t('historyUnknown')}</Label>
        <Label role="meta" style={[styles.secondary, styles.center]}>{t('historyIncomplete')}</Label>
        <Pressable accessibilityRole="button" onPress={props.onRefresh} style={styles.emptyRefresh}><Label role="meta">{t('refresh')}</Label></Pressable>
        {/* TAKU's own conversation cannot be read: the user is never left without a main conversation. */}
        {isMainChat && !!props.draftSessionId && onStartMainAfresh && <Pressable testID="continue-afresh" accessibilityRole="button"
          onPress={() => onStartMainAfresh()} style={styles.emptyRefresh}><Label role="meta">{ct('continueAfresh')}</Label></Pressable>}
      </View>}
      ListHeaderComponent={<View style={styles.stack}>
      {greeting && <AssistantBubble><Text style={[chatType.bodyLg(language === 'zh'), styles.replyText]}>{ct(isMainChat ? 'greeting' : 'taskGreeting')}</Text></AssistantBubble>}
      {greeting && onMainPage && !props.draft && !props.loading && <QuickStarts language={language} onStart={startWith}/>}
      {!props.session?.historyComplete && blocks.length > 0 && <Label role="caption" style={[styles.secondary, styles.historyHint]}>{t('historyIncomplete')}</Label>}
      </View>}
      renderItem={({item: block, index}) => {
        // Keyed by the reasoning itself: a reply keeps its opened thinking when its row id changes hands.
        const thinkingKey = `${sessionId}/${block.thinking?.id ?? ''}`;
        const messageNode = <Message block={block} language={language} renderMarkdown={props.renderMarkdown} renderCard={props.renderCard} expanded={expandedActivities.has(`${sessionId}/${block.id}`)}
        thinkingExpanded={expandedThinking.has(thinkingKey)} demand={rowDemand(demandBand, index)}
        onToggleThinking={() => {
          // Opening it is reading, as with activity: the list stays where the reader is.
          if (!expandedThinking.has(thinkingKey)) dispatchScroll({type: 'scrolled', distance: Number.POSITIVE_INFINITY, byUser: true, spacerActive: pinnedSpacer > 0});
          setExpandedThinking(previous => {const next = new Set(previous); if (next.has(thinkingKey)) next.delete(thinkingKey); else next.add(thinkingKey); return next;});
        }}
        onLongPressReply={openReplyMenu} onToggleActivity={() => {
          const key = `${sessionId}/${block.id}`;
          // Expanding is a reading action: keep the tapped reply in view instead of following the footer.
          if (!expandedActivities.has(key)) dispatchScroll({type: 'scrolled', distance: Number.POSITIVE_INFINITY, byUser: true, spacerActive: pinnedSpacer > 0});
          setExpandedActivities(previous => {const next = new Set(previous); if (next.has(key)) next.delete(key); else next.add(key); return next;});
        }}/>;
        if (pinnedTurn && (block.id === pinnedTurn.userId || block.id === pinnedTurn.assistantId)) {
          const roleKey = block.id === pinnedTurn.userId ? 'user' : 'assistant';
          return <View onLayout={event => {
            const h = Math.round(event.nativeEvent.layout.height);
            setTurnHeights(previous => {
              const base = previous.key === viewKey ? previous : {key: viewKey, user: 0, assistant: 0, footer: 0};
              return base[roleKey] === h ? previous : {...base, [roleKey]: h};
            });
          }}>{messageNode}</View>;
        }
        return messageNode;
      }}
      ListFooterComponent={<View>
      <View style={styles.stack} onLayout={event => {
        const h = Math.round(event.nativeEvent.layout.height);
        setTurnHeights(previous => {
          const base = previous.key === viewKey ? previous : {key: viewKey, user: 0, assistant: 0, footer: 0};
          return base.footer === h ? previous : {...base, footer: h};
        });
      }}>
      {!!props.workspaceError && <Label role="meta" style={styles.secondary}>{props.workspaceError}</Label>}
      {workspaces.map(workspace => <WorkspaceCard key={`${workspace.screenKey}/${workspace.generation}`} workspace={workspace} language={language} onPress={() => props.onOpenWorkspace(workspace)}/>)}
      {cards.map(card => <TaskCard key={card.thread.id} language={language} threadId={card.thread.id} title={card.thread.title || ct('task')}
        status={card.status} askedKey={card.decision?.key ?? ''} prompt={card.decision?.prompt ?? ''} options={card.decision?.options ?? []} answer={card.answer}
        onChoose={option => tasks.answer(card.thread.id, card.decision?.key ?? '', option.label, option.response)}
        onView={() => selectTaskThread(card.thread.id)}/>)}
      {props.session?.questions.map(question => <PendingCard key={question.key} question={question} language={language} busy={props.busy} onAnswer={props.onAnswer} onOpenRequest={() => openRequests(question.key)}/>)}
      {!!props.session?.nativeOnlyRequests && <View style={styles.question}><Label role="label">{t('needsYou')}</Label><Pressable accessibilityRole="button" onPress={() => openRequests()} style={styles.answerButton}><Label role="meta">{language === 'zh' ? '查看待处理请求' : 'Review pending requests'}</Label></Pressable></View>}
      {taskFailure && <FailedTaskCard key={taskFailure.taskId} failure={taskFailure} language={language} busy={props.busy || props.loading} onRestore={props.onRestoreFailedTask}
        afresh={isMainChat && sessionId && onStartMainAfresh ? {label: ct('continueAfresh'), hint: ct('continueAfreshHint'), run: () => onStartMainAfresh(taskFailure.prompt)} : undefined}/>}
      {props.recovery.map(entry => <View key={entry.id} style={styles.pendingSubmission}>
        {!!entry.text && <OriginalRequestPreview text={entry.text} language={language}/>}
        {entry.attachments.map(item => <Label key={item.id} role="caption">{item.name}</Label>)}
        <Pressable testID="restore-saved-draft" accessibilityRole="button" disabled={props.busy || props.loading} style={styles.pendingRetry}
          onPress={() => props.onRestoreSavedDraft(entry.id)}><Label role="meta">{language === 'zh' ? '恢复已保存草稿' : 'Restore saved draft'}</Label></Pressable>
      </View>)}
      {panelTarget && !running && !props.session?.queued && !pendingSubmission && <PanelEditPrompt language={language} onSuggest={suggestEdit}/>}
      {pendingSubmission && !props.busy && <PendingSubmissionCard key={`${pendingSubmission.threadId}/${pendingSubmission.requestId}`} text={pendingSubmission.text} language={language} onRetry={props.onRetrySubmission} onContinue={props.onContinueWithoutResending}/>}
      </View>
      {pinnedSpacer > 0 && <View testID="pinned-turn-spacer" style={{height: pinnedSpacer}}/>}
      </View>}/>
    </EdgeBlur>
    {/* Chat Polish glass title row with Single Chat navigation: sidebar menu on TAKU (or back to TAKU from a Task), centered title, collapse to the island. */}
    <View style={[styles.header, {top: insets.top}]} pointerEvents="box-none">
      <Text testID="chat-title" accessibilityRole="header" numberOfLines={1} style={[chatType.bodyLg(containsChinese(pageTitle)), styles.title]}>{pageTitle}</Text>
      {isMainChat ? (
        <Pressable testID="open-history" accessibilityRole="button" accessibilityLabel={ct('openMenu')} accessibilityState={{expanded: sidebarOpen}} onPress={openSidebar} hitSlop={4}
          style={({pressed}) => [styles.headerButton, pressed && styles.headerButtonPressed]}><GlassRim radius={20}/><ChromeIcon name="burger"/></Pressable>
      ) : (
        <Pressable testID="return-main-chat" accessibilityRole="button" accessibilityLabel={ct('backToTaku')} onPress={selectMainChat} hitSlop={4}
          style={({pressed}) => [styles.headerButton, pressed && styles.headerButtonPressed]}><GlassRim radius={20}/><ChatIcon name="chevronLeft" size={22}/></Pressable>
      )}
      <Pressable testID="collapse-chat" accessibilityRole="button" accessibilityLabel={ct('collapseToIsland')} onPress={collapseChat} hitSlop={4}
        style={({pressed}) => [styles.headerButton, pressed && styles.headerButtonPressed]}><GlassRim radius={20}/><ChromeIcon name="collapse"/></Pressable>
    </View>
    {stripShown && <TaskStrip language={language} top={headerBottom} onOpen={selectTaskThread}
      tasks={capsules.map(item => ({id: item.thread.id, title: item.thread.title || ct('task'), status: item.status}))}/>}
    {!!error && <View style={[styles.error, styles.errorBar, {top: contentTop}]}><Label role="meta" style={styles.errorText}>{error}</Label><IconButton name="refresh" label={t('retry')} color={chatColors.icon} onPress={props.onRefresh}/></View>}
    {voice ? <View style={[styles.livePanel, {bottom: composerBottom}]} onLayout={event => setComposerBox(event.nativeEvent.layout.height)}>
      <View style={styles.liveTranscript}>
        {/* Figma 297:433: speech and waveform only; connection state remains accessible. */}
        <View accessible accessibilityLabel={ct(liveStatus)} accessibilityLiveRegion="polite">
          <VoiceEffect kind="wave" target="live" level={liveLevel} flat={!voice.capturing} style={{width: 66, height: 28, opacity: voice.capturing ? 1 : 0.45}}/>
        </View>
        {!!voice.input?.text && <Text numberOfLines={2} style={[chatType.bodyMd(language === 'zh'), styles.center]}>{voice.input.text}</Text>}
        {!!voice.output?.text && <Text numberOfLines={3} style={[chatType.bodyMd(language === 'zh'), styles.center, styles.secondary]}>{voice.output.text}</Text>}
        {liveFalKeyMissing && <Label role="meta" style={[styles.center, styles.secondary]}>{ct('liveFalKeyMissing')}</Label>}
        {liveFalKeyMissing &&
          <Pressable testID="live-talk-add-fal-key" accessibilityRole="button" onPress={() => { openAiSetup('fal'); }}><Label role="label">{ct('addFalKey')}</Label></Pressable>}
        {liveDisconnected &&
          <Pressable testID="live-talk-reconnect" accessibilityRole="button" onPress={beginLiveTalk}><Label role="label">{ct('liveReconnect')}</Label></Pressable>}
      </View>
      <View style={styles.liveControls}>
        <View style={styles.liveControlGroup}>
          <Pressable accessibilityRole="button" accessibilityLabel="More tools" onPress={openSidebar} style={styles.liveControl}><VoiceIcon name="plusLive"/></Pressable>
          <View accessibilityRole="button" accessibilityLabel="Video input unavailable" accessibilityState={{disabled: true}} style={[styles.liveControl, {opacity: 0.45}]}><VoiceIcon name="video"/></View>
        </View>
        <View style={styles.liveControlGroup}>
          <Pressable testID="live-talk-mic" accessibilityRole="button" accessibilityLabel={ct(voice.muted ? 'liveMicResume' : 'liveMicPause')}
            accessibilityState={{disabled: liveDisconnected, selected: !voice.muted && !liveDisconnected}} disabled={liveDisconnected}
            onPress={() => { void live.toggleMic().catch(error => onVoiceFailure(failureOf(error?.code))); }} style={[styles.liveControl, (voice.muted || liveDisconnected) && {opacity: 0.5}]}><VoiceIcon name="micLive"/></Pressable>
          <Pressable testID="live-talk-close" accessibilityRole="button" accessibilityLabel={ct('liveStop')} onPress={live.stop} style={[styles.liveControl, {backgroundColor: 'rgba(255,255,255,0.92)'}]}><VoiceIcon name="close"/></Pressable>
        </View>
      </View>
    </View> : <>
    {scrollState.showToBottom && visibleBlocks.length > 0 && (
      <Pressable testID="scroll-to-bottom" accessibilityRole="button"
        accessibilityLabel={language === 'zh' ? '回到底部' : 'Scroll to bottom'}
        onPress={scrollToLatest}
        style={({pressed}) => [styles.scrollToBottom, {bottom: composerBottom + composerBox + 12}, pressed && styles.pressed]}>
        <Icon name="chevron_down" size={18} color={chatColors.icon}/>
      </Pressable>
    )}
    <View style={[styles.composerStack, {bottom: composerBottom}]}
      onLayout={event => setComposerBox(Math.round(event.nativeEvent.layout.height))}>
    {!dictating && props.attachments.length > 0 && <ScrollView horizontal style={styles.attachmentStrip} keyboardShouldPersistTaps="handled">
      {props.attachments.map(item => <Pressable key={item.id} accessibilityRole="button" disabled={props.busy || props.loading}
        accessibilityLabel={`${language === 'zh' ? '移除附件' : 'Remove attachment'} ${item.name}`} onPress={() => props.onRemoveAttachment(item.id)} style={styles.attachmentChip}>
        {item.mime.startsWith('image/') && <Image source={{uri: attachmentPreviewUri(sessionId, item.id)}} resizeMode="cover" style={styles.attachmentThumb}/>}
        <Label role="meta" numberOfLines={1} style={styles.attachmentLabel}>{item.name}</Label><Icon name="close" size={16} color={chatColors.icon}/>
      </Pressable>)}
    </ScrollView>}
    {/* One tree for both layouts: the input keeps its place, so an `@` never remounts it or drops the keyboard. */}
    <View style={[styles.composer, composerExpanded ? styles.composerExpanded : multilineComposer && !dictating && styles.composerMultiline]}>
      <ComposerGlassRim multiline={!composerExpanded && multilineComposer && !dictating}/>
      {composerExpanded && panelTarget && <PanelContextChip title={panelTitle} language={language} disabled={props.busy || props.loading}
        onRemove={props.onDetachPanel}/>}
      <View style={composerExpanded ? styles.composerEntry : [styles.composerRow, {alignItems: multilineComposer && !dictating ? 'flex-end' : 'center'}]}>
        {!composerExpanded && !dictating && plusButton}
        {/* Chips get their own row; the text then uses the composer's full width below them (issue #52 item 4). */}
        {composerExpanded && props.plugins.length > 0 && <View style={styles.chipRow}>
          {props.plugins.map(plugin => <PluginChip key={plugin.id} plugin={plugin} language={language} disabled={props.busy || props.loading}
            onRemove={() => props.onRemovePlugin(plugin.id)}/>)}
        </View>}
        {dictating ? <DictationBar transcript={dictation.transcript} placeholder={ct('listening')} levels={dictation.levels}
          finishing={dictation.phase === 'finishing'} stopLabel={ct('stopVoice')} onStop={dictation.stop}/> : messageInput}
        {!composerExpanded && !dictating && trailingActions}
      </View>
      {composerExpanded && <View style={styles.composerActions}>{plusButton}<View style={styles.composerTrailing}>{trailingActions}</View></View>}
    </View>
    </View>
    {mention && attachMenu !== props.draftSessionId && <PluginSuggestions language={language} query={mention.query}
      bottom={composerBottom + composerBox + 8} exclude={props.plugins.map(plugin => plugin.id)} repository={props.pluginRepository}
      onPick={plugin => pickPlugin(plugin.id)}/>}
    {attachMenu === props.draftSessionId && <AttachMenu language={language} bottom={composerBottom + composerBox + 8}
      attachmentLimit={props.attachments.length >= 8} onChoose={chooseAttachment} onClose={() => setAttachMenu(null)}/>}
    </>}
    {replyMenu?.threadId === props.draftSessionId && <ReplyMenu target={replyMenu} language={language} top={contentTop} bottom={composerTop} screenWidth={dimensions.width}
      panel={menuCard ? {pinned: menuCard.pinned, pin: () => panelAction(pinCardToPanels, 'pinned'),
        open: () => panelAction(openPinnedCard), unpin: () => panelAction(unpinCard, 'unpinned')} : undefined}
      renderMarkdown={props.renderMarkdown} onClose={closeReplyMenu}
      speechAction={speaking?.replyId === replyMenu.id ? {speaking: true, run: stopReading}
        : voiceEnabled && !live.active && !dictating ? {speaking: false, run: () => {
          void speakReply(replyMenu.threadId, replyMenu.id, markdownSpeechText(replyMenu.text)).catch(() => {
            if (Platform.OS === 'android') ToastAndroid.show(ct('replySpeechUnavailable'), ToastAndroid.SHORT);
          });
        }} : undefined}/>}
    </View>
    </View>
    {layer?.kind === 'sidebar' && <View style={StyleSheet.absoluteFill}>
      <ChatSidebar language={language} threads={props.threads} threadId={sessionId} primaryThreadId={resolvedPrimaryId} isMainChat={isMainChat}
        taskStates={tasks.board.states} historyError={props.historyError} insets={insets}
        imeBottom={keyboardVisible ? space.bottom : insets.bottom}
        actions={{
          openThread: id => { live.stop(); setLayer(null); selectTaskThread(id); },
          openMainChat: () => { live.stop(); setLayer(null); if (!isMainChat) selectMainChat(); },
          newChat: () => { live.stop(); setLayer(null); createIndependentTask(); },
          openSettings: () => { setLayer(null); router.push('/system-ai'); },
          openPanels: () => { setLayer(null); router.push('/panels'); },
          openPlugins: () => { setLayer(null); router.push('/plugin-market'); },
          close: () => setLayer(null),
        }}/>
    </View>}
    </View>
  </KeyboardAvoidingView>;
}

const styles = themedStyles(() => StyleSheet.create({
  host: {flex: 1},
  livePanel: {position: 'absolute', left: 16, right: 16},
  liveTranscript: {alignItems: 'center', gap: 24, marginBottom: 24},
  liveControls: {flexDirection: 'row', justifyContent: 'space-between'},
  liveControlGroup: {flexDirection: 'row', gap: 16},
  liveControl: {width: 50, height: 50, borderRadius: 25, alignItems: 'center', justifyContent: 'center', backgroundColor: 'rgba(255,255,255,0.08)'},
  root: {flex: 1, backgroundColor: colors.page}, flex: {flex: 1}, center: {textAlign: 'center'}, secondary: {color: colors.secondary},
  // Figma 2-1: 64 dp title row (16 dp padding around 32 dp buttons), title centred.
  header: {position: 'absolute', left: 0, right: 0, height: chatMetrics.header, paddingHorizontal: chatMetrics.side, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between'},
  // Chat Polish 1-0: 40 dp glass circles around 24 dp icons; Body/lg title centred in Surface/900.
  headerButton: {width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center', backgroundColor: chatColors.glass},
  headerButtonPressed: {opacity: 0.7}, pressed: {backgroundColor: chatColors.fillControl},
  title: {position: 'absolute', left: 64, right: 64, textAlign: 'center', color: chatColors.textPrimary},
  modalHeader: {minHeight: 56, paddingLeft: layout.gutter, paddingRight: 4, flexDirection: 'row', alignItems: 'center', borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.border},
  headerTitle: {flex: 1, minWidth: 0, marginRight: 8}, messages: {paddingHorizontal: chatMetrics.side, gap: chatMetrics.itemGap}, stack: {gap: layout.gap},
  userText: {color: chatColors.userText},
  replyText: {color: chatColors.textSecondary}, errorBar: {position: 'absolute', left: 0, right: 0},
  empty: {alignItems: 'center', justifyContent: 'center', paddingHorizontal: 24, paddingVertical: 64, gap: 16}, emptyRefresh: {minHeight: 48, justifyContent: 'center', paddingHorizontal: 16},
  // Figma 2-1 bubble: 8 dp padding, 16 dp corners but the bottom-right, Content/100. A short message
  // shrinks to its text as in the frame; a long one uses the width up to a 48 dp left margin
  // instead of the frame's 129 dp column (issue #32 C4).
  userWrap: {alignItems: 'flex-end', paddingLeft: chatMetrics.bubbleMinInset}, userBubble: {backgroundColor: chatColors.bubble, padding: 8, borderRadius: 16, borderBottomRightRadius: 4, gap: 8},
  attachment: {color: chatColors.userText},
  // Figma 297:462: the 32 × 24 badge starts 8 dp above the bubble's bottom edge.
  userWrapReacted: {paddingBottom: 16},
  reaction: {position: 'absolute', right: 8, bottom: 0, width: 32, height: 24, borderRadius: 12,
    alignItems: 'center', justifyContent: 'center', backgroundColor: chatColors.backdropFallback, overflow: 'hidden'},
  reactionTint: {...StyleSheet.absoluteFill, borderRadius: 12, borderWidth: 2,
    borderColor: chatColors.reactionRing, backgroundColor: chatColors.reactionFill},
  reactionText: {...chatType.bodyLg(), color: '#FFFFFF'}, assistant: {gap: chatMetrics.replyGap}, messageParts: {gap: chatMetrics.replyGap},
  replyActions: {flexDirection: 'row', alignItems: 'center', alignSelf: 'flex-start', maxWidth: '100%', gap: 8},
  activityToggle: {flexDirection: 'row', alignItems: 'center', gap: 8, minHeight: 48, flexShrink: 1, paddingRight: 4},
  activityGroup: {gap: 8}, activityHeading: {lineHeight: 20},
  // Leave 8dp outside the panel; its first 32dp row centers a 20dp label with 6dp of inner space.
  activityToggleExpanded: {minHeight: 32, paddingTop: 12},
  activityPanel: {backgroundColor: colors.subtle, borderRadius: 12, paddingHorizontal: 12},
  activities: {gap: 0}, activityItem: {gap: 0},
  activityRow: {minHeight: 32, paddingVertical: 2, flexDirection: 'row', alignItems: 'center', gap: 8},
  activityName: {flex: 1, minWidth: 0, lineHeight: 20}, activityStatus: {flexShrink: 0, lineHeight: 20},
  activityChevron: {width: 16, alignItems: 'center', justifyContent: 'center'},
  statusDot: {width: 6, height: 6, borderRadius: 3, flexShrink: 0}, details: {color: colors.secondary, paddingLeft: 14, marginBottom: 4},
  stateRow: {flexDirection: 'row', alignItems: 'center', gap: 8, minHeight: 28}, historyHint: {color: colors.muted},
  workspace: {backgroundColor: chatColors.surfaceRaised, borderRadius: 16, padding: 8, gap: 8}, workspaceHeading: {minHeight: 28, flexDirection: 'row', alignItems: 'center', gap: 8, paddingHorizontal: 4},
  workspaceTitle: {color: colors.white, flex: 1}, preview: {width: '100%', aspectRatio: 1.6, maxHeight: 244, backgroundColor: '#252525', overflow: 'hidden', borderRadius: 12},
  previewPlaceholder: {flex: 1, alignItems: 'center', justifyContent: 'center', gap: 12}, previewPlaceholderText: {color: '#CCCCCC'},
  question: {padding: 16, backgroundColor: colors.surface, borderRadius: 16, borderWidth: 1, borderColor: colors.border, gap: 8},
  questionEyebrow: {flexDirection: 'row', gap: 8, alignItems: 'center', marginBottom: 4}, questionDescription: {color: colors.secondary, marginBottom: 4},
  fact: {gap: 4, paddingVertical: 8, borderBottomColor: colors.border, borderBottomWidth: StyleSheet.hairlineWidth},
  notice: {paddingHorizontal: 10, backgroundColor: colors.subtle, borderRadius: 8}, selectedOption: {borderColor: colors.brand, backgroundColor: colors.subtle},
  answerButton: {minHeight: 48, paddingHorizontal: 16, paddingVertical: 12, justifyContent: 'center', alignItems: 'center', borderRadius: 6, backgroundColor: colors.control, marginTop: 4},
  primaryButton: {backgroundColor: chatColors.inverse}, questionInput: {gap: 8, marginTop: 8}, option: {minHeight: 48, justifyContent: 'center', borderColor: colors.border, borderWidth: 1, borderRadius: 8, padding: 12, gap: 4},
  answerInput: {color: chatColors.textPrimary, minHeight: 48, paddingHorizontal: 12, paddingVertical: 10, borderWidth: 1, borderColor: colors.border, borderRadius: 6},
  error: {flexDirection: 'row', backgroundColor: colors.errorSurface, paddingLeft: 16, alignItems: 'center'}, errorText: {flex: 1, color: colors.error},
  // Figma 2-1 field: Surface/900 pill, 12 dp padding, 48 dp ambient shadow, 16 dp from the sides.
  hidden: {opacity: 0},
  composerStack: {position: 'absolute', left: chatMetrics.side, right: chatMetrics.side, gap: 8},
  scrollToBottom: {position: 'absolute', alignSelf: 'center', width: 36, height: 36, borderRadius: 18,
    alignItems: 'center', justifyContent: 'center', backgroundColor: chatColors.composer,
    borderWidth: 1, borderColor: chatColors.hairline, boxShadow: `0px 3px 12px 0px ${chatColors.shadow}`},
  attachmentStrip: {flexGrow: 0},
  attachmentChip: {flexDirection: 'row', alignItems: 'center', gap: 8, minHeight: 44, paddingHorizontal: 12, marginRight: 8, borderRadius: 16, backgroundColor: chatColors.surfaceRaised},
  attachmentLabel: {maxWidth: 180},
  attachmentThumb: {width: 36, height: 36, borderRadius: 8, marginLeft: -4, backgroundColor: chatColors.fillControl},
  composer: {minHeight: 64, flexDirection: 'row', backgroundColor: chatColors.composer,
    borderRadius: 32, padding: 12, gap: 0},
  input: {flex: 1, minWidth: 0, paddingHorizontal: 0, paddingVertical: 8, margin: 0},
  composerAction: {width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center', flexShrink: 0},
  // Several lines: a rounded rectangle (Rounded/3xl) instead of the one-line pill.
  composerMultiline: {borderRadius: 24},
  // Figma 1-2 / 1-3 (1482:1184, 1482:1241): 32 dp corners, 12 dp padding, entry row over a 40 dp action row.
  composerExpanded: {flexDirection: 'column', alignItems: 'stretch', borderRadius: 32, gap: 12},
  composerEntry: {flexDirection: 'column', alignItems: 'stretch', gap: 8, minHeight: 40},
  chipRow: {flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 8},
  composerRow: {flex: 1, flexDirection: 'row', alignSelf: 'stretch', minWidth: 0},
  inputExpanded: {flex: 0, alignSelf: 'stretch'},
  composerActions: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', height: 40},
  composerTrailing: {flexDirection: 'row', alignItems: 'center'},
  // Figma 1-3 send: Main/Brand circle with the white arrow.
  send: {backgroundColor: chatColors.send},
  sendDisabled: {opacity: 0.35},
  // The stop control (as Figma 1-5's stop): a solid 40 dp disc with a 12 dp rounded square.
  stop: {backgroundColor: chatColors.stopFill}, stopPressed: {opacity: 0.8},
  stopGlyph: {width: 12, height: 12, borderRadius: 4, backgroundColor: chatColors.stopGlyph},
  historyList: {padding: 16, gap: 8}, historyItem: {padding: 16, borderRadius: 12, gap: 4, minHeight: 64}, historySelected: {backgroundColor: colors.surface},
  advancedControls: {minHeight: 48, marginHorizontal: 16, marginTop: 8, paddingHorizontal: 12, paddingVertical: 8, flexDirection: 'row', alignItems: 'center', gap: 12, borderRadius: 8, backgroundColor: colors.surface},
  pendingSubmission: {padding: 16, gap: 8, borderRadius: 16, borderColor: colors.border, borderWidth: 1, backgroundColor: colors.surface},
  pendingPreviewToggle: {minHeight: 48, flexDirection: 'row', alignItems: 'center', gap: 8}, pendingPreviewExpanded: {maxHeight: 96},
  pendingRetry: {minHeight: 48, flexDirection: 'row', alignItems: 'center', justifyContent: 'flex-start', gap: 8, paddingHorizontal: 8, borderRadius: 6, backgroundColor: colors.control},
}));
