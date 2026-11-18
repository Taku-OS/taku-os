import React, {useEffect} from 'react';
import {AppState, StyleSheet, View} from 'react-native';
import {create, useStore} from 'zustand';
import {File, Paths} from 'expo-file-system';
import {copyAsync} from 'expo-file-system/legacy';
import type {Language} from '../i18n';
import type {UiConfig} from '../model/genui';
import {genUiStore} from '../model/genuiStore';
import {seekPlayback, togglePlayback, useMediaStore} from '../model/genuiMediaStore';
import {formatUiValue} from './genuiFormat';
import {audioCapabilities, audioCommand, newRequestId, type AudioCommand} from '../native/agent';
import {spacing} from '../feed/feedTokens';
import {MediaContent} from './genuiMedia';
import {FeedButton, FeedInset, FeedStatus, FeedText} from '../feed/feedVisual';

type Json = Record<string, unknown>;
const object = (value: unknown): Json => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Json : {};
const array = (value: unknown): Json[] => Array.isArray(value) ? value.map(object) : [];
type Controls = {busy: boolean; error: string; elapsedMs?: number; sourceLanguage?: string; targetLanguage?: string; capabilities?: Json; transcript?: {sessionId: string; page: number; total: number; rows: Json[]}};
const empty: Controls = {busy: false, error: ''};
const useCaptureControls = create<{entries: Record<string, Controls>}>(() => ({entries: {}}));
const update = (key: string, patch: Partial<Controls>) => useCaptureControls.setState(state => ({entries: {...state.entries, [key]: {...(state.entries[key] ?? empty), ...patch}}}));
const errorText = (error: unknown) => error instanceof Error ? error.message : String(error);

/** Explicit native capture controls; source snapshots own status, transcript, recordings and summaries. */
export function AudioCapture({matterId, config, language}: {matterId: string; config: UiConfig; language: Language}) {
  const zh = language === 'zh', playback = config.captureSource === 'playback', purpose = playback ? 'MEDIA_TRANSLATE' : 'DICTATION';
  const controlKey = `${matterId}/${purpose}`;
  const controls = useCaptureControls(state => state.entries[controlKey] ?? empty);
  const meta = useStore(genUiStore, state => state.cards[matterId]?.snapshot.meta);
  const sources = Object.values(object(object(meta).sources)).map(object).filter(source =>
    object(source.provenance).kind === 'audio-session' && object(source.params).purpose === purpose);
  const source = sources.sort((a, b) => Number(b.createdAt ?? 0) - Number(a.createdAt ?? 0))[0];
  const sessionId = typeof source?.resourceId === 'string' ? source.resourceId : '';
  const status = String(source?.status ?? ''), active = ['preparing', 'running', 'draining'].includes(status), paused = status === 'paused';
  const sourceLanguage = controls.sourceLanguage ?? config.language ?? (zh ? 'zh-CN' : 'en-US');
  const requestedTarget = controls.targetLanguage ?? config.targetLanguage;
  const targetLanguage = requestedTarget && requestedTarget !== sourceLanguage ? requestedTarget : sourceLanguage === 'zh-CN' ? 'en-US' : 'zh-CN';
  const summary = object(source?.summary), coverage = object(summary.coverage);
  const recognized = typeof source?.transcriptAnchor === 'string';
  const recordedElapsed = typeof source?.elapsedMs === 'number' ? source.elapsedMs : undefined;
  const recording = source?.recordingState === 'recording';
  useEffect(() => {
    if (recordedElapsed === undefined) return;
    const started = performance.now();
    let timer: ReturnType<typeof setInterval> | undefined;
    const display = () => update(controlKey, {elapsedMs: recordedElapsed + (recording ? performance.now() - started : 0)});
    const visibility = () => {
      if (timer) clearInterval(timer);
      timer = undefined;
      if (AppState.currentState === 'active') {
        display();
        // Display interpolation only; native capture owns recording and performs no JS polling.
        if (recording) timer = setInterval(display, 1000);
      }
    };
    visibility();
    const listener = AppState.addEventListener('change', visibility);
    return () => {if (timer) clearInterval(timer);listener.remove();};
  }, [controlKey, recordedElapsed, recording]);

  async function inspect() {
    if(useCaptureControls.getState().entries[controlKey]?.busy)return;
    update(controlKey, {busy: true, error: ''});
    try {update(controlKey, {capabilities: await audioCapabilities()});}
    catch(error) {update(controlKey, {error: errorText(error)});}
    finally {update(controlKey, {busy: false});}
  }
  async function command(operation: AudioCommand['operation']) {
    if(useCaptureControls.getState().entries[controlKey]?.busy)return;
    update(controlKey, {busy: true, error: ''});
    try {
      const params: AudioCommand = {matterId, requestId: newRequestId(), operation};
      if(operation === 'start') {
        const capabilities = await audioCapabilities();update(controlKey, {capabilities});
        const available = array(capabilities.availability).filter(row => row.purpose === purpose && row.language === sourceLanguage && (playback ? row.output === 'CAPTIONS' : row.profileId === 'zipformer'));
        const chosen = available.find(row => row.available === true && row.profileId === (playback ? 'local-hybrid' : 'zipformer')) ?? available.find(row => row.available === true);
        if(!chosen)throw new Error(available.map(row => `${String(row.profileId)}: ${Array.isArray(row.unavailableReasons) ? row.unavailableReasons.join(', ') : 'SOURCE_UNAVAILABLE'}`).join('\n') || 'SOURCE_UNAVAILABLE');
        Object.assign(params, {purpose, language: sourceLanguage, profileId: chosen.profileId, recordAudio: playback ? false : config.recordAudio ?? true});
        if(playback)params.targetLanguage = targetLanguage;
      } else {
        if(!sessionId)throw new Error('AUDIO_SESSION_NOT_FOUND');
        params.sessionId = sessionId;
        if(operation === 'summarize')params.language = language;
      }
      const result = await audioCommand(params);
      const receipt = object(result.receipt);
      if(['notSent', 'deliveryUnknown', 'failed', 'unknown'].includes(String(receipt.state))) {
        const code = String(receipt.code ?? receipt.error ?? receipt.state);
        const messages: Record<string, string> = {
          PLAYBACK_SOURCE_UNAVAILABLE: zh ? '没有检测到可捕获的播放来源。请先播放视频，再重试。' : 'No capturable playback was found. Play the video, then try again.',
          PLAYBACK_SOURCE_AMBIGUOUS: zh ? '有多个应用正在播放，请暂停其他来源后重试。' : 'Several apps are playing. Pause the other sources and try again.',
          RECORD_AUDIO_PERMISSION_DENIED: zh ? '麦克风权限未获准，请在系统设置中授权。' : 'Microphone permission was denied. Grant it in system settings.',
          RESOURCE_BUSY: zh ? '已有录音或翻译正在运行，请先暂停或停止该任务。' : 'Recording or translation is already active. Pause or stop that session first.',
        };
        throw new Error(messages[code] ?? code);
      }
      const observed = object(result.source);
      if(observed.error || observed.summaryError)update(controlKey, {error: String(observed.summaryError ?? observed.error)});
    } catch(error) {update(controlKey, {error: errorText(error)});}
    finally {update(controlKey, {busy: false});}
  }
  async function readTranscript(page: number) {
    if(!source || typeof source.transcriptAnchor !== 'string' || controls.busy)return;
    update(controlKey, {busy: true, error: ''});
    let temporary: File | undefined;
    try {
      // Expo copies a generic protected content URI through ContentResolver; only one page enters Zustand.
      temporary = new File(Paths.cache, `audio-transcript-${newRequestId()}.jsonl`);
      await copyAsync({from: source.transcriptAnchor, to: temporary.uri});
      const text = await temporary.text(), rows: Json[] = [];
      let offset = 0, total = 0;
      while(offset < text.length) {
        const end = text.indexOf('\n', offset), stop = end < 0 ? text.length : end;
        if(stop > offset) {if(total >= page * 20 && total < (page + 1) * 20)rows.push(object(JSON.parse(text.slice(offset, stop))));total++;}
        offset = stop + 1;
      }
      update(controlKey, {transcript: {sessionId, page, total, rows}});
    } catch(error) {update(controlKey, {error: errorText(error)});}
    finally {
      try {if(temporary?.exists)temporary.delete();}finally {update(controlKey, {busy: false});}
    }
  }
  const transcript = controls.transcript?.sessionId === sessionId ? controls.transcript : undefined;
  function playEvidence(citation: Json) {
    const url = String(citation.recordingUrl ?? ''), entry = useMediaStore.getState().playback[url];
    if (!entry?.player || !entry.duration) {update(controlKey, {error: zh ? '录音尚未加载，请先检查录音播放器。' : 'Recording is not ready. Check its player first.'});return;}
    seekPlayback(url, Math.max(0, Number(citation.recordingObservedOffsetMs) / 1000 - 5));
    if (!entry.playing) togglePlayback(url);
  }
  const segments = transcript?.rows ?? array(source?.segments);
  const statusLabels: Record<string, string> = zh ? {preparing: '正在准备', running: '录制中', draining: '正在结束并保存', paused: '已暂停', stopped: '已停止', failed: '失败', interrupted: '已中断'} : {};
  function summaryRows(kind: 'overview' | 'decisions' | 'tasks', title: string) {
    const rows = array(summary[kind]);
    return <View style={styles.stack}><FeedText selectable variant="body-md-medium" text={title}/>{rows.length ? rows.map((row, index) => <View key={`${kind}-${index}`} style={styles.stack}>
      <FeedText selectable variant="body-md" text={String(row.text ?? '')}/>
      {kind === 'tasks' && <FeedText selectable variant="body-sm" tone="secondary" text={[zh ? '负责人' : 'Owner', ": ", row.owner === null ? zh ? '未知' : 'Unknown' : String(row.owner), " · ", zh ? '期限' : 'Due', ": ", row.due === null ? zh ? '未知' : 'Unknown' : String(row.due)].join('')}/>}
      {array(row.evidence).map((citation, citationIndex) => <View key={`${String(citation.segmentId)}-${citationIndex}`} style={styles.stack}>
        <FeedText selectable variant="body-sm" tone="secondary" text={[String(citation.segmentId), " · “", String(citation.quote), "”"].join('')}/>
        {typeof citation.recordingUrl === 'string' && typeof citation.recordingObservedOffsetMs === 'number' && <FeedButton label={zh ? '回听识别时间附近（提前5秒，非逐字对齐）' : 'Play near recognition time (5s earlier; not word alignment)'} onPress={() => playEvidence(citation)}/>}
      </View>)}
    </View>) : <FeedText selectable variant="body-sm" tone="secondary" text={zh ? '来源中未明确提及。' : 'None explicitly stated in the covered source.'}/>}</View>;
  }
  return <View style={styles.content}>
    <FeedText selectable variant="body-lg-medium" text={playback ? zh ? '当前播放音频的转写与翻译' : 'Transcribe and translate active playback' : zh ? '会议录音与原文转写' : 'Meeting recording and source transcript'}/>
    <FeedText selectable variant="body-sm" tone="secondary" text={[zh ? '语言' : 'Source language', ": ", sourceLanguage].join('')}/>
    <FeedInset ><View  style={styles.options}>{['zh-CN', 'en-US', ...(playback ? ['ja-JP', 'ko-KR'] : [])].map(value => <FeedButton key={value} label={value} selected={sourceLanguage === value} disabled={controls.busy || active || paused} onPress={() => update(controlKey, {sourceLanguage: value})}/>)}</View></FeedInset>
    {playback && <><FeedText selectable variant="body-sm" tone="secondary" text={[zh ? '目标语言' : 'Translation language', ": ", targetLanguage].join('')}/><FeedInset ><View  style={styles.options}>{['zh-CN', 'en-US', 'ja-JP', 'ko-KR'].filter(value => value !== sourceLanguage).map(value => <FeedButton key={value} label={value} selected={targetLanguage === value} disabled={controls.busy || active || paused} onPress={() => update(controlKey, {targetLanguage: value})}/>)}</View></FeedInset>
      <FeedText selectable variant="body-sm" tone="secondary" text={zh ? '字幕按识别结果展示，暂不支持与视频时间轴对齐。' : 'Captions follow recognition results and cannot yet align to the video timeline.'}/></>}
    <FeedStatus selectable state={status === 'failed' ? 'error' : 'info'} text={status ? statusLabels[status] ?? status : zh ? '尚未启动；麦克风与播放捕获均需主动开始。' : 'Not started. Capture begins only when you press Start.'}>
      {recordedElapsed !== undefined && <FeedText selectable variant="body-sm" tone="secondary" text={[zh ? '累计录制' : 'Recorded duration', " · ", formatUiValue({type: 'duration', value: controls.elapsedMs ?? recordedElapsed}, language)].join('')}/>}
    </FeedStatus>
    <View style={styles.row}>
      <FeedButton variant="primary" label={zh ? '开始' : 'Start'} disabled={controls.busy || active || paused} onPress={() => void command('start')}/>
      {active && <FeedButton label={zh ? '暂停' : 'Pause'} disabled={controls.busy || status === 'draining'} onPress={() => void command('pause')}/>}
      {paused && <FeedButton variant="primary" label={zh ? '继续' : 'Resume'} disabled={controls.busy} onPress={() => void command('resume')}/>}
      {(active || paused) && <FeedButton variant="primary" label={zh ? '停止并保存' : 'Stop and save'} disabled={controls.busy || status === 'draining'} onPress={() => void command('stop')}/>}
      {!!sessionId && <FeedButton label={zh ? '读取原生状态' : 'Read native status'} disabled={controls.busy} onPress={() => void command('status')}/>}
      <FeedButton label={zh ? '检查可用能力' : 'Inspect capabilities'} disabled={controls.busy} onPress={() => void inspect()}/>
    </View>
    {!!controls.capabilities && <FeedText selectable variant="body-sm" tone="secondary" text={zh ? '引擎能力已读取；启动时将重新核对真实可用条件。' : 'Engine capabilities loaded; Start checks current availability again.'}/>}
    {array(controls.capabilities?.availability).filter(row => row.purpose === purpose && row.language === sourceLanguage && (!playback || row.output === 'CAPTIONS')).map(row =>
      <FeedStatus key={`${String(row.profileId)}-${String(row.output)}`} selectable state={row.available === true ? 'info' : 'error'} text={[String(row.profileId), ' · ', row.available === true ? zh ? '本地前置条件已满足；启动时核对真实来源' : 'Local prerequisites ready; source checked on Start' : Array.isArray(row.unavailableReasons) ? row.unavailableReasons.join(', ') : 'SOURCE_UNAVAILABLE'].join('')}/>)}
    {controls.busy && <FeedStatus state="pending" text={zh ? '正在等待原生操作…' : 'Waiting for the native operation…'}/>}
    {[controls.error, source?.error, source?.recordingError, source?.summaryError].filter(Boolean).map((error, index) => <FeedStatus key={index} state="error" selectable text={String(error)}/>)}
    {segments.map(segment => <FeedInset key={String(segment.id)}><View  style={styles.notice}>
      <FeedText selectable variant="body-md" text={String(segment.text ?? '')}/><FeedText selectable variant="body-sm" tone="secondary" text={[segment.textKind === 'target' ? zh ? '译文' : 'Translation' : zh ? '识别原文' : 'Recognized source', " · ", segment.final ? zh ? '已定稿' : 'Final' : zh ? '识别中' : 'Interim', typeof segment.recordingObservedOffsetMs === 'number' ? ` · ≈${(segment.recordingObservedOffsetMs / 1000).toFixed(1)}s ${zh ? '录音中的识别观察时间，非字词对齐' : 'recognition-observed recording offset; not word alignment'}` : ''].join('')}/>
    </View></FeedInset>)}
    {!!source?.segmentsDropped && !transcript && <FeedText selectable variant="body-sm" tone="secondary" text={zh ? '这里仅显示最近的识别片段；完整定稿记录保留在原生来源中。' : 'Showing recent recognition segments; the complete finalized transcript is retained natively.'}/>}
    {recognized && <View style={styles.row}><FeedButton label={zh ? '完整定稿记录' : 'Read complete finalized transcript'} disabled={controls.busy} onPress={() => void readTranscript(0)}/>
      {transcript && <><FeedButton label={zh ? '上一页' : 'Previous'} disabled={controls.busy || transcript.page === 0} onPress={() => void readTranscript(transcript.page - 1)}/><FeedText selectable variant="body-sm" tone="secondary" text={[transcript.page + 1, " / ", Math.max(1, Math.ceil(transcript.total / 20))].join('')}/><FeedButton label={zh ? '下一页' : 'Next'} disabled={controls.busy || (transcript.page + 1) * 20 >= transcript.total} onPress={() => void readTranscript(transcript.page + 1)}/></>}
    </View>}
    {array(source?.recordings).map(recording => <FeedInset key={String(recording.id)}><View  style={styles.notice}><FeedText selectable variant="body-sm" tone="secondary" text={[zh ? '已保存的原始录音' : 'Saved source recording', " · ", String(recording.id)].join('')}/><MediaContent mediaType="audio" url={typeof recording.url === 'string' ? recording.url : undefined} language={language}/></View></FeedInset>)}
    {!playback && status === 'stopped' && <FeedButton variant="primary" label={zh ? '根据定稿原文生成纪要' : 'Summarize finalized transcript'} disabled={controls.busy || !recognized || source?.summaryState === 'generating'} onPress={() => void command('summarize')}/>}
    {source?.summaryState === 'generating' && <FeedStatus state="pending" text={zh ? '正在通过已配置的系统文字模型提取纪要…' : 'Extracting the summary with the configured system text model…'}/>}
    {summary.model !== undefined && <FeedInset ><View  style={styles.notice}><FeedText selectable variant="body-sm" tone="secondary" text={[String(summary.model), " · ", zh ? '模型根据识别文本提取，请对照录音核实。' : 'Model extraction from recognized speech; verify against recordings.'].join('')}/>
      <FeedText selectable variant="body-sm" tone="secondary" text={[coverage.partial ? zh ? '部分覆盖' : 'Partial coverage' : zh ? '全部原文片段已覆盖' : 'All source segments covered', " · ", String(coverage.includedSegments), " / ", String(coverage.totalSegments), " ", zh ? '片段' : 'segments'].join('')}/>
      {summaryRows('overview', zh ? '要点' : 'Overview')}{summaryRows('decisions', zh ? '明确决定' : 'Explicit decisions')}{summaryRows('tasks', zh ? '明确待办' : 'Explicit tasks')}
    </View></FeedInset>}
  </View>;
}
const styles = StyleSheet.create({
  content: {gap: spacing[12], minWidth: 0}, stack: {gap: spacing[8], minWidth: 0},
  row: {flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: spacing[8]},
  options: {flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: spacing[4], padding: spacing[4]},
  notice: {gap: spacing[8], minWidth: 0, padding: spacing[12]},
});
