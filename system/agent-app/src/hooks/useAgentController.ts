import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {AppState, BackHandler} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {useLocales} from 'expo-localization';
import {callAgent, closeAgent, consumePanelLaunch, isPanelLaunchConsumed, launchPanelTarget, launchThreadId, newRequestId, onOpenThread, selectConversation, subscribeSession, watchConversationVisible, AgentGatewayError, type PanelTarget} from '../native/agent';
import {continuePanel} from '../panels/client';
import {projectSession, projectThreads, projectWorkspaces, failedTaskDraft, record, string, type Json, type SessionView, type ThreadSummary, type WorkspaceView} from '../model/session';
import {isAcceptedReceipt, isNotSentReceipt, preserveAndReleaseUnconfirmed, requestReceiptWithRetry, retireAcceptedSubmission, type PendingSubmission} from './submission';
import {readCurrent, restoreConversation} from './readLifecycle';
import {sessionReadOptions} from './sessionRead';
import {emptySessionStream, reduceSessionBatch, retainSessionBlocks, streamCursor, streamSessionRoot, sessionRootForDisplay, reconcileStreamHistory} from '../model/sessionStream';
import {acceptOptimisticReceipt, beginOptimisticSubmission, reconcileOptimisticSubmission, recoverQueuedSubmission, FIRST_RESPONSE_TIMEOUT_MS, type OptimisticSubmission} from '../model/optimisticChat';

const SELECTED = 'taku.agent.selected.v1';
/** A continue publishes one new task: optimistic correlation treats it as a fresh message. */
type ChatSubmission = {threadId: string; requestId: string; text: string; action: 'sendMessage' | 'steer'; expectedTurnId?: string};
const asChatSubmission = (pending: PendingSubmission): ChatSubmission => ({
  threadId: pending.threadId, requestId: pending.requestId, text: pending.text,
  action: pending.action === 'continue' ? 'sendMessage' : pending.action, expectedTurnId: pending.expectedTurnId});
/** panels/continue returns the task receipt, optionally alongside its panel association. */
const unwrapPanelReceipt = (value: Json): Json => {
  const receipt = record(value.receipt);
  return Object.keys(receipt).length ? receipt : value;
};
const draftKey = (id: string) => `taku.agent.draft.v1.${id || 'new'}`;
const pendingKey = (id: string) => `taku.agent.pending.v1.${id}`;
const unconfirmedArchiveKey = (threadId: string, requestId: string) => `taku.agent.unconfirmed.v1.${threadId}.${requestId}`;

export function useAgentController() {
  const [session, setSession] = useState<SessionView | null>(null);
  const [threads, setThreads] = useState<ThreadSummary[]>([]);
  const [workspaces, setWorkspaces] = useState<WorkspaceView[]>([]);
  const [selected, setSelected] = useState('');
  const [conversationKey, setConversationKey] = useState('new');
  const [optimisticSubmissions, setOptimisticSubmissions] = useState<OptimisticSubmission[]>([]);
  const visibleOptimisticSubmissions = useMemo(() => optimisticSubmissions.filter(request => request.threadId === selected), [optimisticSubmissions, selected]);
  const [draft, setDraft] = useState('');
  const [loading, setLoading] = useState(true);
  const [restoring, setRestoring] = useState(true);
  const [pendingSubmission, setPendingSubmission] = useState<PendingSubmission | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [readError, setReadError] = useState('');
  const [selectionError, setSelectionError] = useState('');
  const [historyError, setHistoryError] = useState('');
  const [workspaceError, setWorkspaceError] = useState('');
  const [refreshRevision, setRefreshRevision] = useState(0);
  const [visible, setVisible] = useState(false);
  const locale = useLocales()[0]?.languageCode;
  const zh = locale === 'zh';
  const mounted = useRef(true), generation = useRef(0), actionRunning = useRef(false);
  const selectedRef = useRef(''), draftRef = useRef(''), sessionRef = useRef<SessionView | null>(null), taskRef = useRef<Json>({});
  const streamRef = useRef({state: emptySessionStream(''), healthy: false, failed: false});
  const sidebandRef = useRef<Json>({});
  const draftWrites = useRef(Promise.resolve());
  const navigationWrites = useRef(Promise.resolve());
  const selectionWrites = useRef(Promise.resolve());
  const historyRevision = useRef(0);
  const draftRevision = useRef(0);
  const pendingOpen = useRef('');
  const launchFailure = useRef<unknown>(null);
  /** Navigation context only: the panel whose original conversation this send continues. */
  const panelTarget = useRef<PanelTarget | null>(null);
  const recoveredPending = useRef(new Set<string>());
  const draftReady = useRef(false);
  const optimisticRef = useRef<OptimisticSubmission[]>([]);
  sessionRef.current = session;

  const updateOptimistic = useCallback((update: (requests: OptimisticSubmission[]) => OptimisticSubmission[]) => {
    const next = update(optimisticRef.current);
    if (next.length === optimisticRef.current.length && next.every((request, index) => request === optimisticRef.current[index])) return;
    optimisticRef.current = next;
    if (mounted.current) setOptimisticSubmissions(next);
  }, []);

  const reconcileOptimistic = useCallback((view: SessionView) => {
    updateOptimistic(requests => {
      const next = requests.map(request => request.threadId === view.id ? reconcileOptimisticSubmission(request, view) : request);
      const recovered = recoverQueuedSubmission(view, next);
      return recovered ? [...next, recovered] : next;
    });
  }, [updateOptimistic]);

  useEffect(() => {
    const waiting = optimisticSubmissions.filter(request => request.state === 'sending' || request.state === 'accepted');
    if (!waiting.length) return;
    const deadline = Math.min(...waiting.map(request => request.startedAt + FIRST_RESPONSE_TIMEOUT_MS));
    const timer = setTimeout(() => updateOptimistic(requests => requests.map(request =>
      reconcileOptimisticSubmission(request, sessionRef.current?.id === request.threadId ? sessionRef.current : null))), Math.max(0, deadline - Date.now()));
    return () => clearTimeout(timer);
  }, [optimisticSubmissions, updateOptimistic]);

  const message = useCallback((problem: unknown) => {
    const code = problem instanceof AgentGatewayError ? problem.code : '';
    const messages: Record<string, [string, string]> = {
      THREAD_UNAVAILABLE: ['This conversation is unavailable. Choose another conversation or try refreshing.', '此会话暂不可用，请刷新或选择其他会话。'],
      TURN_CHANGED: ['The turn has changed. Refresh before sending this requirement.', '当前轮次已变化，请刷新后再发送补充要求。'],
      THREAD_QUEUED: ['This conversation already has a queued task. Wait for it to start.', '此会话已有排队中的任务，请稍候。'],
      THREAD_BUSY: ['This conversation is already working. Refresh to add a requirement to its active turn.', '此会话正在执行，请刷新后向当前轮次补充要求。'],
      TURN_STARTED: ['The queued task has started. Refresh to stop the active turn.', '排队任务已开始执行，请刷新后停止当前轮次。'],
      BUSY: ['Agent is busy. Please try again.', 'Agent 正忙，请稍后重试。'],
      RUNTIME_UNAVAILABLE: ['The Agent service is unavailable. Open Settings to check the connection.', 'Agent 服务暂不可用，请打开设置检查连接。'],
      HISTORY_UNAVAILABLE: ['Conversation history could not refresh. Please try again.', '会话列表暂未刷新，请稍后重试。'],
      WORKSPACE_UNAVAILABLE: ['Workspace preview is temporarily unavailable.', '工作区预览暂不可用。'],
      DRAFT_RESTORE_UNAVAILABLE: ['Your saved draft could not be restored. Refresh before sending.', '暂时无法恢复已保存的草稿，请刷新后再发送。'],
      UNKNOWN_DELIVERY: ['Delivery is not confirmed. Your draft is saved; do not submit it as a new request.', '发送状态尚未确认。草稿已保存，请勿作为新请求重复提交。'],
      PENDING_DIFFERENT: ['A previous message still needs confirmation. Use “Retry original request”; your new draft will be retained.', '上一条消息尚未确认，请使用“重试原请求”。新的草稿会保留。'],
      QUEUED: ['Your message is queued. You can add a requirement once execution starts.', '消息正在排队，开始执行后可补充要求。'],
      NO_TURN: ['There is no active turn to stop.', '当前没有正在执行的轮次。'],
      TASK_FAILURE_CHANGED: ['The task has changed. Refresh before restoring its request.', '任务状态已变化，请刷新后再恢复原请求。'],
      DRAFT_NOT_EMPTY: ['Your newer draft has been kept. Save or clear it before restoring this request.', '已保留你正在编辑的新草稿，请先保存或清空，再恢复原请求。'],
      NO_FAILED_PROMPT: ['The original request is unavailable. Its failure record is still shown.', '原请求内容暂不可用，失败记录仍会保留显示。'],
      SOURCE_THREAD_NOT_FOUND: ['The panel\u2019s original conversation is unavailable. Open the panel from its list to check its source.', '该面板的原对话已不可用，请从面板列表检查其来源。'],
      SOURCE_CHANGED: ['The panel source changed before this request was sent. Open the panel again for its current source.', '发送前面板来源已变化，请重新打开面板以使用当前来源。'],
      SOURCE_UNAVAILABLE: ['The panel source is unavailable right now. Your text is kept.', '面板来源暂时不可用，内容已保留。'],
      PANEL_NOT_FOUND: ['This panel no longer exists. Reopen it from the Panels list.', '该面板已不存在，请从面板列表重新打开。'],
      PANEL_CONTRACT_MISMATCH: ['Update the Assistant to continue editing this panel.', '请更新 Assistant 后继续修改此面板。'],
      VERSION_CONFLICT: ['The panel changed on another surface. Review it before sending again.', '面板在另一个界面已变化，请核对后再发送。'],
      IDEMPOTENCY_CONFLICT: ['A different request already used this request ID. Start a new message.', '另一个请求已使用此请求 ID，请重新发送。'],
    };
    return messages[code]?.[zh ? 1 : 0] || (zh ? '暂时无法连接 Agent。内容已保留，请重试。' : 'Could not reach Agent. Your content is retained; please try again.');
  }, [zh]);
  const messageRef = useRef(message);
  messageRef.current = message;

  const saveDraft = useCallback((id: string, value: string) => {
    // Serialize writes so rapid input cannot restore an older draft after a newer one.
    draftWrites.current = draftWrites.current.catch(() => {}).then(() => AsyncStorage.setItem(draftKey(id), value));
    return draftWrites.current;
  }, []);

  const updateDraft = useCallback((value: string) => {
    if (!draftReady.current || actionRunning.current) return false;
    ++draftRevision.current;
    draftRef.current = value;
    setDraft(value);
    void saveDraft(selectedRef.current, value).catch(() => setError(zh ? '草稿暂时无法保存。' : 'Your draft could not be saved.'));
    return true;
  }, [saveDraft, zh]);

  const syncNativeSelection = useCallback((id: string, ticket: number) => {
    const resume = navigationWrites.current.catch(() => {}).then(async () => {
      if (id && mounted.current && generation.current === ticket) await callAgent('resumeThread', {threadId: id});
    });
    navigationWrites.current = resume;
    return resume;
  }, []);

  const select = useCallback(async (id: string, preserveConversationKey = false) => {
    if (!mounted.current) return;
    if (panelTarget.current && panelTarget.current.originThreadId !== id) panelTarget.current = null;
    const ticket = ++generation.current;
    const draftTicket = ++draftRevision.current;
    draftReady.current = false; setRestoring(true); setPendingSubmission(null);
    selectedRef.current = id;
    if (!preserveConversationKey) setConversationKey(id || 'new');
    streamRef.current = {state: emptySessionStream(id), healthy: false, failed: false};
    sidebandRef.current = {};
    setSelected(id); setSession(null); sessionRef.current = null; taskRef.current = {};
    // Reopening the already-selected thread also invalidates the previous polling ticket.
    setRefreshRevision(value => value + 1);
    setWorkspaces([]); setWorkspaceError(''); setError(''); setReadError(''); setSelectionError(''); setLoading(Boolean(id));
    draftRef.current = ''; setDraft('');
    // Native settings and the frontend keep the same selected conversation.
    // A slow earlier resume must finish before a later selection writes the preference.
    void syncNativeSelection(id, ticket).catch(e => {
      if (mounted.current && generation.current === ticket) setSelectionError(messageRef.current(e));
    });
    const isCurrent = () => mounted.current && generation.current === ticket && draftRevision.current === draftTicket;
    await readCurrent({
      isCurrent,
      read: async () => {
        await draftWrites.current.catch(() => {});
        selectionWrites.current = selectionWrites.current.catch(() => {}).then(async () => {
          if (isCurrent()) await AsyncStorage.setItem(SELECTED, id);
        });
        await selectionWrites.current;
        return Promise.all([
          AsyncStorage.getItem(draftKey(id)),
          id ? AsyncStorage.getItem(pendingKey(id)) : Promise.resolve(null),
        ]);
      },
      accept: ([text, pendingValue]) => {
        let restored = text || '';
        if (pendingValue) {
          try {
            const pending = JSON.parse(pendingValue) as PendingSubmission;
            if (pending.threadId === id && typeof pending.text === 'string' && typeof pending.requestId === 'string') {
              setPendingSubmission(pending);
              updateOptimistic(requests => requests.some(request => request.requestId === pending.requestId) ? requests
                : [...requests, {...beginOptimisticSubmission(asChatSubmission(pending), null), startedAt: pending.startedAt || Date.now(),
                  baselineBlockIds: pending.baselineBlockIds || [], baselineTurnIds: pending.baselineTurnIds || [],
                  correlationPending: !pending.baselineBlockIds, state: 'failed', errorKey: 'replyFailed',
                  error: messageRef.current(new AgentGatewayError('Unconfirmed delivery', 'UNKNOWN_DELIVERY'))}]);
              if (!restored) restored = pending.text;
              setError(messageRef.current(new AgentGatewayError('Unconfirmed delivery', 'UNKNOWN_DELIVERY')));
            }
          } catch { setError(messageRef.current(new AgentGatewayError('Unconfirmed delivery', 'UNKNOWN_DELIVERY'))); }
        }
        draftRef.current = restored; setDraft(restored);
        draftReady.current = true;
      },
      // A missing receipt read must never enable a new request ID. Release the spinner, keep Send guarded.
      reject: () => setError(messageRef.current(new AgentGatewayError('Draft restore failed', 'DRAFT_RESTORE_UNAVAILABLE'))),
      settled: () => setRestoring(false),
    });
  }, [syncNativeSelection, updateOptimistic]);

  const loadThreads = useCallback(async () => {
    const ticket = ++historyRevision.current;
    try {
      const result = projectThreads(await callAgent('listThreads'));
      if (mounted.current && historyRevision.current === ticket) { setThreads(result); setHistoryError(''); }
      return result;
    } catch (problem) {
      if (mounted.current && historyRevision.current === ticket)
        setHistoryError(messageRef.current(new AgentGatewayError('History list unavailable', 'HISTORY_UNAVAILABLE')));
      throw problem;
    }
  }, []);

  const hide = useCallback(() => {
    void draftWrites.current.catch(() => {}).then(() => closeAgent()).catch(e => {
      if (mounted.current) setError(messageRef.current(e));
    });
  }, []);

  useEffect(() => {
    const back = BackHandler.addEventListener('hardwareBackPress', () => { hide(); return true; });
    return () => back.remove();
  }, [hide]);

  useEffect(() => {
    mounted.current = true;
    let appActive = AppState.currentState === 'active', conversationStarted = false;
    const updateVisibility = () => setVisible(appActive && conversationStarted);
    const listener = AppState.addEventListener('change', state => { appActive = state === 'active'; updateVisibility(); });
    const visibility = watchConversationVisible(started => { conversationStarted = started; updateVisibility(); });
    const openListener = onOpenThread((id, target, problem) => {
      if (problem) {
        launchFailure.current = problem;
        setError(messageRef.current(problem));
        return;
      }
      launchFailure.current = null;
      panelTarget.current = target;
      if (actionRunning.current) pendingOpen.current = id;
      else void select(id).catch(e => { if (mounted.current && selectedRef.current === id) setError(messageRef.current(e)); });
    });
    const initialGeneration = generation.current;
    void (async () => {
      try {
        const target = await launchPanelTarget();
        if (!mounted.current || generation.current !== initialGeneration) return;
        panelTarget.current = target;
        const previous = panelTarget.current?.originThreadId || launchThreadId() || await AsyncStorage.getItem(SELECTED);
        await restoreConversation({preferred: previous || '', list: loadThreads, select,
          isCurrent: () => mounted.current && generation.current === initialGeneration,
          onListError: () => { /* The history index owns its separate error state. */ },
        });
      } catch (problem) {
        if (mounted.current && generation.current === initialGeneration) {
          setError(messageRef.current(problem));
          launchFailure.current = problem;
          setLoading(false); setRestoring(false);
        }
      }
    })();
    return () => {
      mounted.current = false;
      // This is a request-generation counter, not a rendered node reference.
      // eslint-disable-next-line react-hooks/exhaustive-deps
      ++generation.current;
      listener.remove(); visibility.remove(); openListener.remove();
    };
    // Locale changes do not recreate the session or discard a draft.
  }, [loadThreads, select]);

  useEffect(() => {
    if (!selected || !visible) return;
    let cancelled = false, retry: ReturnType<typeof setTimeout> | undefined;
    let subscription: {remove(): void} | undefined, failures = 0;
    const ticket = generation.current;
    const isCurrent = () => !cancelled && mounted.current && generation.current === ticket;
    const reconnect = () => {
      subscription?.remove(); subscription = undefined;
      streamRef.current.healthy = false;
      streamRef.current.failed = failures >= 3 && Boolean(streamRef.current.state.brokerEpoch);
      if (streamRef.current.failed) setReadError(messageRef.current(new AgentGatewayError('Session stream unavailable', 'RUNTIME_UNAVAILABLE')));
      if (isCurrent()) retry = setTimeout(connect, Math.min(5000, 250 * 2 ** Math.min(failures++, 4)));
    };
    const connect = () => {
      if (!isCurrent()) return;
      try {
        subscription = subscribeSession(selected, streamCursor(streamRef.current.state), batch => {
          if (!isCurrent()) return;
          const before = streamRef.current.state;
          const next = batch.requiresResync ? {...before, needsResync: true, ready: false}
            : reduceSessionBatch(before, batch.events);
          sidebandRef.current = reconcileStreamHistory(before, next, sidebandRef.current);
          streamRef.current = {state: next, healthy: next.ready && !next.needsResync && !batch.closed, failed: false};
          if (next.needsResync) { reconnect(); return; }
          if (next.ready && next.revision !== before.revision) {
            const view = retainSessionBlocks(projectSession(streamSessionRoot(next, sidebandRef.current)), sessionRef.current);
            reconcileOptimistic(view);
            sessionRef.current = view; setSession(view); setLoading(false);
          }
          if (next.ready && batch.events.length) {
            failures = 0;
            if (record(sidebandRef.current.native).ready === true) setReadError('');
          }
          if (batch.closed) reconnect();
        });
      } catch { reconnect(); }
    };
    connect();
    return () => {
      cancelled = true;
      if (retry) clearTimeout(retry);
      subscription?.remove();
      streamRef.current.healthy = false;
    };
  }, [selected, visible, refreshRevision, reconcileOptimistic]);

  useEffect(() => {
    if (!selected || !visible) return;
    let cancelled = false, timer: ReturnType<typeof setTimeout> | undefined;
    let cacheFirst = true, hydrate = true, failures = 0;
    const ticket = generation.current;
    const isCurrent = () => !cancelled && mounted.current && generation.current === ticket;
    const poll = async () => {
      const first = cacheFirst;
      await readCurrent({
        isCurrent,
        read: async () => {
          const readOptions = sessionReadOptions({attached: Boolean(streamRef.current.state.brokerEpoch),
            streamFailed: streamRef.current.failed, sideband: sidebandRef.current, cacheFirst,
            hydrate: hydrate || !(sessionRef.current?.historyComplete || sessionRef.current?.knownEmpty),
            ready: sessionRef.current?.ready === true, running: sessionRef.current?.running === true});
          const streamBeforeRead = streamRef.current.state;
          const result = await callAgent('readSession', {threadId: selected, ...readOptions});
          if (record(result.thread).id !== selected) throw new AgentGatewayError('Session identity changed', 'THREAD_UNAVAILABLE');
          return {result, streamBeforeRead};
        },
        accept: ({result, streamBeforeRead}) => {
          cacheFirst = false;
          failures = 0;
          // A status-only response never replaces history, even if the pipe closed in flight.
          const previous = sidebandRef.current;
          sidebandRef.current = result.statusOnly === true ? {...previous, task: result.task, request: result.request, runtime: result.runtime, ...result, thread: previous.thread} : result;
          taskRef.current = record(result.task);
          const stream = streamRef.current;
          // A full read that began before a broker restart cannot reintroduce the old
          // generation's completeness proof after the new empty snapshot arrived.
          sidebandRef.current = reconcileStreamHistory(streamBeforeRead, stream.state, sidebandRef.current);
          const root = sessionRootForDisplay(stream.state, sidebandRef.current);
          const next = retainSessionBlocks(projectSession(root), sessionRef.current);
          reconcileOptimistic(next);
          hydrate = !(next.historyComplete || next.knownEmpty);
          sessionRef.current = next;
          setSession(next);
          if (next.ready) setSelectionError('');
          setReadError(next.ready && !stream.failed ? '' : messageRef.current(new AgentGatewayError('Runtime unavailable', 'RUNTIME_UNAVAILABLE')));
        },
        reject: problem => { ++failures; setReadError(messageRef.current(problem)); },
        settled: () => setLoading(false),
      });
      if (isCurrent()) timer = setTimeout(poll, failures ? Math.min(15000, 2500 * failures)
        : first && !cacheFirst && hydrate && sessionRef.current?.ready ? 0 : sessionRef.current?.running ? 1000 : 3500);
    };
    void poll();
    return () => { cancelled = true; if (timer) clearTimeout(timer); };
  }, [selected, visible, refreshRevision, reconcileOptimistic]);

  useEffect(() => {
    if (!selected || !visible) return;
    let cancelled = false, timer: ReturnType<typeof setTimeout> | undefined, failures = 0;
    const ticket = generation.current;
    const isCurrent = () => !cancelled && mounted.current && generation.current === ticket;
    const poll = async () => {
      await readCurrent({
        isCurrent,
        read: async () => projectWorkspaces(await callAgent('getWorkspaces', {threadId: selected})),
        accept: screens => {
          failures = 0;
          setWorkspaces(screens.filter(screen => screen.threadId === selected));
          setWorkspaceError('');
        },
        reject: () => {
          ++failures;
          setWorkspaces([]);
          setWorkspaceError(messageRef.current(new AgentGatewayError('Workspace unavailable', 'WORKSPACE_UNAVAILABLE')));
        },
      });
      // One catalog read at a time. Chat/history latency cannot delay the five-second preview lease.
      if (isCurrent()) timer = setTimeout(poll, failures ? Math.min(15000, 2500 * failures) : 1000);
    };
    void poll();
    return () => { cancelled = true; if (timer) clearTimeout(timer); };
  }, [selected, visible, refreshRevision]);

  const run = useCallback(async (operation: () => Promise<void>) => {
    if (actionRunning.current) return;
    actionRunning.current = true; setBusy(true); setError('');
    try { await operation(); }
    catch (e) { if (mounted.current) { setError(message(e)); if (e instanceof AgentGatewayError && ['TURN_STARTED', 'THREAD_BUSY', 'THREAD_QUEUED'].includes(e.code)) setRefreshRevision(v => v + 1); } }
    finally {
      actionRunning.current = false;
      if (mounted.current) {
        setBusy(false);
        const id = pendingOpen.current; pendingOpen.current = '';
        if (id) void select(id).catch(e => { if (selectedRef.current === id) setError(messageRef.current(e)); });
      }
    }
  }, [message, select]);

  const create = useCallback(async (migrateDraft = false, optimisticRequestId?: string) => {
    const result = await callAgent('createThread');
    const id = string(record(result.thread).id);
    if (!id) throw new AgentGatewayError('Missing session ID');
    if (migrateDraft) await saveDraft(id, draftRef.current);
    if (migrateDraft) updateOptimistic(requests => requests.map(request => request.requestId === optimisticRequestId ? {...request, threadId: id} : request));
    await select(id, migrateDraft);
    void loadThreads().catch(() => {});
    return id;
  }, [loadThreads, saveDraft, select, updateOptimistic]);

  const deliver = useCallback(async (pending: PendingSubmission) => {
    const id = pending.threadId, text = pending.text;
    const params: Json = {threadId: id, text, requestId: pending.requestId};
    if (pending.expectedTurnId) params.expectedTurnId = pending.expectedTurnId;
    const receipt = pending.action === 'continue'
      ? unwrapPanelReceipt(await requestReceiptWithRetry(() => continuePanel(pending.panel!.panelId, text,
        pending.panel!.expectedSourceVersion, pending.requestId)))
      : await requestReceiptWithRetry(() => callAgent(pending.action === 'steer' ? 'steer' : 'sendMessage', params));
    if (isNotSentReceipt(pending, receipt)) {
      updateOptimistic(requests => requests.map(request => request.requestId === pending.requestId
        ? {...request, state: 'failed', nonDeliverable: true, errorKey: 'replyFailed',
          error: messageRef.current(new AgentGatewayError('Request was not submitted', string(receipt.code)))} : request));
      await AsyncStorage.removeItem(pendingKey(id));
      if (selectedRef.current === id) setPendingSubmission(null);
      throw new AgentGatewayError('Request was not submitted', string(receipt.code));
    }
    if (!isAcceptedReceipt(pending, receipt)) throw new AgentGatewayError('Unknown delivery', 'UNKNOWN_DELIVERY');
    if (pending.action === 'continue') {
      const launchId = pending.panel!.launchId;
      await consumePanelLaunch(launchId);
      if (panelTarget.current?.launchId === launchId) panelTarget.current = null;
    }
    updateOptimistic(requests => requests.map(request => request.requestId === pending.requestId
      ? reconcileOptimisticSubmission(acceptOptimisticReceipt(request, receipt), sessionRef.current) : request));
    if (selectedRef.current === id) await saveDraft(id, draftRef.current);
    else await draftWrites.current;
    await retireAcceptedSubmission(pending, {
      readDraft: () => AsyncStorage.getItem(draftKey(id)),
      writeDraft: value => saveDraft(id, value),
      removePending: () => AsyncStorage.removeItem(pendingKey(id)),
    });
    if (selectedRef.current === id) {
      setPendingSubmission(null);
      if (draftRef.current.trim() === text) {
        ++draftRevision.current; draftRef.current = ''; setDraft('');
      }
    }
    setRefreshRevision(v => v + 1);
    void loadThreads().catch(() => {});
  }, [loadThreads, saveDraft, updateOptimistic]);

  const submit = useCallback(async (text: string) => {
    if (launchFailure.current) throw launchFailure.current;
    if (!draftReady.current) return;
    if (!text || text.length > 16000) return;
    let id = selectedRef.current;
    const current = sessionRef.current;
    const existingPending = pendingSubmission?.threadId === id ? pendingSubmission : null;
    if (existingPending && existingPending.text !== text) throw new AgentGatewayError('Pending message differs', 'PENDING_DIFFERENT');
    if (!existingPending && ['queued', 'running'].includes(string(taskRef.current.state)) && !current?.activeTurnId)
      throw new AgentGatewayError('Task is queued', 'QUEUED');
    if (!existingPending && !current?.activeTurnId && optimisticRef.current.some(request => request.threadId === id
      && (request.state === 'sending' || request.state === 'accepted' || request.state === 'timedOut')))
      throw new AgentGatewayError('Task is queued', 'QUEUED');
    if (!existingPending && optimisticRef.current.some(request => request.threadId === id && !request.userBlockId
      && !request.nonDeliverable && !['failed', 'stopped'].includes(request.state)))
      throw new AgentGatewayError('Previous message is awaiting its native record', 'QUEUED');
    if (panelTarget.current && isPanelLaunchConsumed(panelTarget.current.launchId)) panelTarget.current = null;
    const target = panelTarget.current;
    const continuing = !!target && !!id && target.originThreadId === id && !existingPending;
    let pending: PendingSubmission = existingPending || {threadId: id, text, requestId: newRequestId(),
      action: continuing ? 'continue' : current?.activeTurnId ? 'steer' : 'sendMessage',
      ...(continuing && target ? {panel: {panelId: target.panelId, expectedSourceVersion: target.sourceVersion, launchId: target.launchId}} : {}),
      ...(!continuing && current?.activeTurnId ? {expectedTurnId: current.activeTurnId} : {})};
    const requestId = pending.requestId;
    if (!existingPending) {
      const optimistic = beginOptimisticSubmission(asChatSubmission(pending), current);
      pending = {...pending, startedAt: optimistic.startedAt, baselineBlockIds: optimistic.baselineBlockIds, baselineTurnIds: optimistic.baselineTurnIds};
    }
    // Publish both chat rows before the first storage, thread-creation or gateway await.
    updateOptimistic(requests => {
      const previous = requests.find(request => request.requestId === requestId);
      return previous ? requests.map(request => request === previous
        ? {...request, state: 'sending', startedAt: Date.now(), error: undefined, errorKey: undefined} : request)
        : [...requests, beginOptimisticSubmission(asChatSubmission(pending), current)];
    });
    let deliveryAttempted = false;
    try {
      if (!id) id = await create(true, requestId);
      pending = {...pending, threadId: id};
      const existing = await AsyncStorage.getItem(pendingKey(id));
      if (existing) {
        const stored = JSON.parse(existing) as PendingSubmission;
        if (stored.threadId !== id || stored.text !== text || stored.requestId !== pending.requestId)
          throw new AgentGatewayError('Pending message differs', 'PENDING_DIFFERENT');
        pending = stored;
      } else {
        // Persist BEFORE crossing IPC; an interrupted UI can only retry the same request ID.
        await AsyncStorage.setItem(pendingKey(id), JSON.stringify(pending));
      }
      setPendingSubmission(pending);
      deliveryAttempted = true;
      await deliver(pending);
    } catch (problem) {
      updateOptimistic(requests => requests.map(request => request.requestId === requestId
        ? reconcileOptimisticSubmission({...request, state: 'failed', nonDeliverable: request.nonDeliverable || !deliveryAttempted,
          error: message(problem), errorKey: 'replyFailed'}, sessionRef.current) : request));
      throw problem;
    }
  }, [create, deliver, message, pendingSubmission, updateOptimistic]);

  const send = useCallback(() => run(() => submit(draftRef.current.trim())), [run, submit]);

  // A card action is the user's own reply in the card's thread, with the same receipt and retry rules as typed text.
  const sendText = useCallback((threadId: string, text: string) => run(async () => {
    if (threadId && threadId !== selectedRef.current) await select(threadId);
    await submit(text.trim());
  }), [run, select, submit]);

  const retrySubmission = useCallback(() => run(async () => {
    if (!draftReady.current) return;
    const id = selectedRef.current;
    const stored = await AsyncStorage.getItem(pendingKey(id));
    if (!stored) { setPendingSubmission(null); return; }
    const pending = JSON.parse(stored) as PendingSubmission;
    if (pending.threadId !== id) throw new AgentGatewayError('Pending message differs', 'PENDING_DIFFERENT');
    updateOptimistic(requests => requests.map(request => request.requestId === pending.requestId
      ? {...request, state: 'sending', startedAt: Date.now(), error: undefined, errorKey: undefined} : request));
    try { await deliver(pending); }
    catch (problem) {
      updateOptimistic(requests => requests.map(request => request.requestId === pending.requestId
        ? reconcileOptimisticSubmission({...request, state: 'failed', error: message(problem), errorKey: 'replyFailed'}, sessionRef.current) : request));
      throw problem;
    }
  }), [deliver, message, run, updateOptimistic]);

  const continueWithoutResending = useCallback(() => run(async () => {
    if (!draftReady.current) return;
    const id = selectedRef.current;
    const stored = await AsyncStorage.getItem(pendingKey(id));
    if (!stored) { setPendingSubmission(null); return; }
    const pending = JSON.parse(stored) as PendingSubmission;
    if (pending.threadId !== id || !pending.requestId || pending.requestId !== pendingSubmission?.requestId)
      throw new AgentGatewayError('Pending message changed', 'PENDING_DIFFERENT');
    const archive = unconfirmedArchiveKey(id, pending.requestId);
    await preserveAndReleaseUnconfirmed(stored, {
      readPending: () => AsyncStorage.getItem(pendingKey(id)),
      readArchive: () => AsyncStorage.getItem(archive),
      writeArchive: value => AsyncStorage.setItem(archive, value),
      removePending: () => AsyncStorage.removeItem(pendingKey(id)),
    });
    updateOptimistic(requests => requests.filter(request => request.requestId !== pending.requestId));
    if (selectedRef.current === id) { setPendingSubmission(null); setError(''); }
  }), [pendingSubmission, run, updateOptimistic]);

  useEffect(() => {
    if (!pendingSubmission || restoring || !draftReady.current || actionRunning.current) return;
    const key = `${pendingSubmission.threadId}/${pendingSubmission.requestId}`;
    if (recoveredPending.current.has(key)) return;
    recoveredPending.current.add(key);
    void retrySubmission();
  }, [pendingSubmission, restoring, retrySubmission]);

  const restoreFailedTask = useCallback((taskId: string) => run(async () => {
    if (!draftReady.current) return;
    const id = selectedRef.current, current = sessionRef.current;
    const failure = current?.taskFailure;
    if (!current || current.id !== id || !failure || failure.taskId !== taskId)
      throw new AgentGatewayError('Task changed', 'TASK_FAILURE_CHANGED');
    if (!failure.prompt.trim()) throw new AgentGatewayError('Original request unavailable', 'NO_FAILED_PROMPT');
    const restored = failedTaskDraft(failure, draftRef.current);
    if (restored === null) throw new AgentGatewayError('Newer draft retained', 'DRAFT_NOT_EMPTY');
    // Saving does not clear a pending receipt or issue any gateway/model operation.
    await saveDraft(id, restored);
    if (mounted.current && selectedRef.current === id) {
      ++draftRevision.current; draftRef.current = restored; setDraft(restored);
    }
  }), [run, saveDraft]);

  const stop = useCallback(() => run(async () => {
    const current = sessionRef.current, task = taskRef.current;
    if (current?.activeTurnId) await callAgent('interrupt', {threadId: current.id, turnId: current.activeTurnId});
    else if (task.id && ['queued', 'running'].includes(string(task.state))) await callAgent('cancelQueued', {threadId: selectedRef.current, taskId: task.id});
    else throw new AgentGatewayError('No active turn', 'NO_TURN');
    setRefreshRevision(v => v + 1);
  }), [run]);

  const refresh = useCallback(() => run(async () => {
    const ticket = generation.current;
    if (launchFailure.current) {
      try {
        const target = await launchPanelTarget(true);
        if (!mounted.current || generation.current !== ticket) return;
        panelTarget.current = target;
        launchFailure.current = null;
        const id = target?.originThreadId || launchThreadId() || selectedRef.current;
        await select(id);
        if (pendingOpen.current === id) pendingOpen.current = '';
      } catch (problem) {
        launchFailure.current = problem;
        throw problem;
      }
      return;
    }
    await restoreConversation({preferred: selectedRef.current, list: loadThreads, select,
      isCurrent: () => mounted.current && generation.current === ticket,
      onListError: () => { /* Kept in historyError, separate from the selected conversation. */ },
    });
  }), [loadThreads, run, select]);

  return {
    session, threads, workspaces, loading: loading || restoring, busy, error: error || selectionError || readError, draft,
    historyError, workspaceError, visible,
    draftSessionId: selected, pendingSubmission, conversationKey,
    optimisticSubmissions: visibleOptimisticSubmissions,
    onRetrySubmission: () => { void retrySubmission(); },
    onContinueWithoutResending: () => { void continueWithoutResending(); },
    onRestoreFailedTask: (taskId: string) => { void restoreFailedTask(taskId); },
    onDraftChange: updateDraft,
    onSend: () => { void send(); }, onStop: () => { void stop(); },
    onSendText: (threadId: string, text: string) => { void sendText(threadId, text); },
    onSelectThread: (id: string) => {
      if (actionRunning.current) return;
      selectConversation(id);
      panelTarget.current = null;
      launchFailure.current = null;
      void select(id).catch(e => { if (selectedRef.current === id) setError(message(e)); });
    },
    onCreateThread: () => { void run(async () => {
      selectConversation(null);
      panelTarget.current = null;
      launchFailure.current = null;
      pendingOpen.current = '';
      const id = await create();
      selectConversation(id);
    }); },
    onRefresh: () => { void refresh(); },
    onOpenHistory: () => { void loadThreads().catch(() => {}); },
    onAnswer: (key: string, response: Json) => { void run(async () => { await callAgent('answer', {threadId: selectedRef.current, key, response}); setRefreshRevision(v => v + 1); }); },
    onOpenWorkspace: (screen: WorkspaceView) => { void run(async () => { await callAgent('openWorkspace', {threadId: screen.threadId, screenKey: screen.screenKey, generation: screen.generation}); }); },
    onOpenSettings: () => { void run(async () => { await callAgent('openSettings', {page: 'models'}); }); },
    onOpenNativeControls: () => { void run(async () => { await callAgent('openNativeConversation', {threadId: selectedRef.current}); }); },
    onClose: hide,
  };
}
