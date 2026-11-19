import React, {useEffect, useState} from 'react';
import {ActivityIndicator, Modal, Pressable, ScrollView, StyleSheet, Switch, Text, View} from 'react-native';
import {AgentGatewayError, callAgent} from '../native/agent';
import type {ComposerPickerKind} from '../native/composerPicker';
import type {Language} from '../i18n';
import {ComposerModelOptions, type ComposerOptionPatch, type ComposerOptionsSnapshot} from './ComposerModelOptions';
import {chatColors, chatType} from './chatTheme';

const copy = {
  en: {tools: 'Composer tools', options: 'Model and response options', file: 'Choose file', image: 'Choose image', camera: 'Take photo', done: 'Done', unavailable: 'Could not load or save model options.', retry: 'Try again',
    suggest: 'Suggest from previous screen', includeScreen: 'Include previous screen when sending', screenHint: 'Uses the screenshot Android supplied when you invoked the assistant.', screenUnavailable: 'Could not load or save the screenshot preference.', captureUnavailable: 'Screen suggestion could not open. Try again.', captureBusy: 'A screen suggestion is already open. Accept or dismiss it before trying again.'},
  zh: {tools: '输入工具', options: '模型与回复选项', file: '选择文件', image: '选择图片', camera: '拍照', done: '完成', unavailable: '无法加载或保存模型选项。', retry: '重试',
    suggest: '根据唤起前的屏幕获取建议', includeScreen: '发送时附上唤起前的屏幕', screenHint: '使用唤起助手时 Android 提供的截图。', screenUnavailable: '无法加载或保存截图偏好。', captureUnavailable: '暂时无法打开屏幕建议，请重试。', captureBusy: '已有屏幕建议，请先接受或关闭后再试。'},
};

export function ComposerTools({threadId, language, attachmentLimit, onPick, onClose}: {
  threadId: string; language: Language; attachmentLimit: boolean;
  onPick: (kind: ComposerPickerKind) => void; onClose: () => void;
}) {
  const labels = copy[language], chinese = language === 'zh';
  const [optionsOpen, setOptionsOpen] = useState(false);
  const [snapshot, setSnapshot] = useState<ComposerOptionsSnapshot | null>(null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [includeScreen, setIncludeScreen] = useState<boolean | null>(null);
  const [screenBusy, setScreenBusy] = useState(false);
  const [screenFailed, setScreenFailed] = useState(false);
  const [captureBusy, setCaptureBusy] = useState(false);
  const [captureError, setCaptureError] = useState('');

  useEffect(() => {
    let current = true;
    void callAgent<{enabled: boolean}>('manageSystemAi', {operation: 'readComposerScreenshots'}).then(value => {
      if (current) setIncludeScreen(value.enabled);
    }, () => { if (current) setScreenFailed(true); });
    return () => { current = false; };
  }, [attempt]);

  useEffect(() => {
    if (!optionsOpen) return;
    let current = true;
    void callAgent<ComposerOptionsSnapshot>('composer/options/read', {threadId}).then(value => {
      if (current) setSnapshot(value);
    }, () => { if (current) setFailed(true); }).finally(() => { if (current) setBusy(false); });
    return () => { current = false; };
  }, [attempt, optionsOpen, threadId]);

  const write = async (options: ComposerOptionPatch) => {
    setBusy(true); setFailed(false);
    try { setSnapshot(await callAgent<ComposerOptionsSnapshot>('composer/options/write', {threadId, options})); }
    catch { setFailed(true); }
    finally { setBusy(false); }
  };

  const writeScreenshots = async (enabled: boolean) => {
    setScreenBusy(true); setScreenFailed(false);
    try { setIncludeScreen((await callAgent<{enabled: boolean}>('manageSystemAi', {operation: 'setComposerScreenshots', enabled})).enabled); }
    catch { setScreenFailed(true); }
    finally { setScreenBusy(false); }
  };

  const suggest = async () => {
    setCaptureBusy(true); setCaptureError('');
    try { await callAgent('capture/request', {manual: true, source: 'invocation'}); onClose(); }
    catch (failure) { setCaptureError(failure instanceof AgentGatewayError && failure.code === 'CAPTURE_BUSY' ? labels.captureBusy : labels.captureUnavailable); }
    finally { setCaptureBusy(false); }
  };

  return <Modal transparent animationType="fade" onRequestClose={onClose}>
    <View style={styles.backdrop}>
      <Pressable style={StyleSheet.absoluteFill} accessibilityLabel={labels.done} onPress={onClose}/>
      <View style={styles.sheet} accessibilityViewIsModal>
        <Text accessibilityRole="header" style={chatType.headingLg(chinese)}>{optionsOpen ? labels.options : labels.tools}</Text>
        <ScrollView style={styles.scroll} keyboardShouldPersistTaps="handled">
          {optionsOpen ? <>
            {busy && !snapshot && <ActivityIndicator color={chatColors.icon}/>}
            {failed && <Pressable style={styles.row} onPress={() => { setBusy(true); setFailed(false); setAttempt(value => value + 1); }} accessibilityRole="button" accessibilityLabel={labels.retry}>
              <Text style={[chatType.bodyMd(chinese), styles.error]}>{labels.unavailable} {labels.retry}</Text>
            </Pressable>}
            <ComposerModelOptions language={language} value={snapshot} busy={busy} onChange={options => { void write(options); }}/>
          </> : <>
            {(['file', 'image', 'camera'] as const).map(kind => <Pressable key={kind} testID={`attach-${kind}`} style={[styles.row, attachmentLimit && styles.disabled]}
              accessibilityRole="button" accessibilityState={{disabled: attachmentLimit}} disabled={attachmentLimit}
              onPress={() => { onClose(); onPick(kind); }}>
              <Text style={chatType.bodyMd(chinese)}>{labels[kind]}</Text>
            </Pressable>)}
            <Pressable testID="composer-screen-suggestion" style={[styles.row, captureBusy && styles.disabled]} accessibilityRole="button"
              accessibilityState={{disabled: captureBusy}} disabled={captureBusy} onPress={() => { void suggest(); }}>
              <Text style={chatType.bodyMd(chinese)}>{labels.suggest}</Text>
            </Pressable>
            {captureBusy && <ActivityIndicator color={chatColors.icon}/>}
            {!!captureError && <Text accessibilityRole="alert" style={[chatType.bodyMd(chinese), styles.error]}>{captureError}</Text>}
            <View style={[styles.row, styles.toggle]}>
              <Text style={[chatType.bodyMd(chinese), styles.toggleLabel]}>{labels.includeScreen}</Text>
              <Switch testID="composer-include-screenshot" accessibilityLabel={labels.includeScreen} value={includeScreen === true}
                disabled={includeScreen === null || screenBusy} onValueChange={enabled => { void writeScreenshots(enabled); }}/>
            </View>
            <Text style={chatType.bodyMd(chinese)}>{labels.screenHint}</Text>
            {screenFailed && <Pressable style={styles.row} accessibilityRole="button" onPress={() => { setScreenFailed(false); setAttempt(value => value + 1); }}>
              <Text style={[chatType.bodyMd(chinese), styles.error]}>{labels.screenUnavailable} {labels.retry}</Text>
            </Pressable>}
            <Pressable testID="composer-model-options" style={styles.row} accessibilityRole="button" onPress={() => { setBusy(true); setOptionsOpen(true); }}>
              <Text style={chatType.bodyMd(chinese)}>{labels.options}</Text>
            </Pressable>
          </>}
        </ScrollView>
        <Pressable style={styles.row} accessibilityRole="button" onPress={onClose}><Text style={chatType.bodyMdMedium(chinese)}>{labels.done}</Text></Pressable>
      </View>
    </View>
  </Modal>;
}

const styles = StyleSheet.create({
  backdrop: {flex: 1, justifyContent: 'center', alignItems: 'center', backgroundColor: 'rgba(0,0,0,0.48)', padding: 24},
  sheet: {width: '100%', maxWidth: 440, maxHeight: '86%', borderRadius: 24, padding: 20, gap: 12, backgroundColor: chatColors.surfaceRaised},
  scroll: {flexGrow: 0},
  row: {minHeight: 48, paddingHorizontal: 12, justifyContent: 'center', borderRadius: 12},
  toggle: {flexDirection: 'row', alignItems: 'center', gap: 12},
  toggleLabel: {flex: 1},
  disabled: {opacity: 0.45},
  error: {color: chatColors.textError},
});
