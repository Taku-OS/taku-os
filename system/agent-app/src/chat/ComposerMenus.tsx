/**
 * The composer's floating menus (Figma "Chat Polish"): the "+" attachment menu (1-1) and the
 * `@` plugin suggestions (1-2). Both open just above the composer so the keyboard stays usable.
 */
import React, {useEffect, useState} from 'react';
import {ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import {ChromeIcon, type ChromeIconName} from './ChromeIcon';
import {chatColors, chatMetrics, chatType, themedStyles} from './chatTheme';
import {chatText} from './chatText';
import {matchingPlugins} from './pluginMention';
import type {ComposerPickerKind} from '../native/composerPicker';
import {callAgent} from '../native/agent';
import {createNativePluginRepository} from '../plugins/nativeRepository';
import {PluginMark} from '../plugins/PluginMark';
import type {PluginRepository, PluginSummary} from '../plugins/contracts';
import {containsChinese} from '../theme';

type Language = 'en' | 'zh';
export type AttachChoice = ComposerPickerKind | 'plugins';

const attachRows: {choice: AttachChoice; icon: ChromeIconName; label: 'attachCamera' | 'attachPhotos' | 'attachFiles' | 'attachPlugins'}[] = [
  {choice: 'camera', icon: 'camera', label: 'attachCamera'},
  {choice: 'image', icon: 'photos', label: 'attachPhotos'},
  {choice: 'file', icon: 'files', label: 'attachFiles'},
  {choice: 'plugins', icon: 'plugins', label: 'attachPlugins'},
];

/** Figma 1-1 (node 1482:1072): 182 dp glass card, 8 dp padding, 24 dp corners; 24 dp icon + Body/xl rows. */
export function AttachMenu({language, bottom, attachmentLimit, onChoose, onClose}: {
  language: Language; bottom: number; attachmentLimit: boolean;
  onChoose: (choice: AttachChoice) => void; onClose: () => void;
}) {
  const t = chatText(language);
  return <View style={StyleSheet.absoluteFill} pointerEvents="box-none">
    <Pressable testID="attach-menu-scrim" style={StyleSheet.absoluteFill} accessibilityLabel={t('done')} onPress={onClose}/>
    <View testID="attach-menu" accessibilityRole="menu" accessibilityLabel={t('attachMenu')} style={[styles.attachCard, {bottom}]}>
      {attachRows.map(row => {
        const disabled = row.choice !== 'plugins' && attachmentLimit;
        return <Pressable key={row.choice} testID={`attach-${row.choice}`} accessibilityRole="menuitem" accessibilityState={{disabled}} disabled={disabled}
          onPress={() => onChoose(row.choice)} style={({pressed}) => [styles.menuRow, pressed && styles.pressed, disabled && styles.disabled]}>
          <ChromeIcon name={row.icon}/>
          <Text numberOfLines={1} style={[chatType.bodyXl(language === 'zh'), styles.primary]}>{t(row.label)}</Text>
        </Pressable>;
      })}
    </View>
  </View>;
}

type PluginState = {status: 'loading' | 'ready' | 'error'; items: PluginSummary[]};

const nativePlugins = () => createNativePluginRepository((action, params) => callAgent(action, params));

/** The installed, enabled plugins from the same plugin repository the Plugin Market reads. */
function useEnabledPlugins(repository?: PluginRepository): [PluginState, () => void] {
  const [state, setState] = useState<PluginState>({status: 'loading', items: []});
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let current = true;
    void (repository ?? nativePlugins()).listInstalled().then(result => {
      if (!current) return;
      setState(result.ok ? {status: 'ready', items: result.value.filter(item => item.enabled === true)} : {status: 'error', items: []});
    }, () => { if (current) setState({status: 'error', items: []}); });
    return () => { current = false; };
  }, [attempt, repository]);
  return [state, () => { setState({status: 'loading', items: []}); setAttempt(value => value + 1); }];
}

/** Figma 1-2 (node 1482:1131): composer-wide glass panel, at most 240 dp, hint row then plugin rows. */
export function PluginSuggestions({language, query, bottom, exclude, repository, onPick}: {
  language: Language; query: string; bottom: number; exclude: readonly string[]; repository?: PluginRepository; onPick: (plugin: PluginSummary) => void;
}) {
  const t = chatText(language);
  const [plugins, retry] = useEnabledPlugins(repository);
  const shown = matchingPlugins(plugins.items.filter(item => !exclude.includes(item.id)), query);
  const status = plugins.status === 'loading' ? t('pluginsLoading') : plugins.status === 'error' ? t('pluginsUnavailable')
    : shown.length ? '' : plugins.items.length ? t('noMatchingPlugins') : t('noEnabledPlugins');
  return <View testID="plugin-suggestions" accessibilityLabel={t('pluginSuggestions')} style={[styles.suggestions, {bottom}]}>
    <View style={styles.menuRow}>
      <Text numberOfLines={1} style={[chatType.bodyMd(language === 'zh'), styles.tertiary, styles.grow]}>{t('pluginSearchHint')}</Text>
    </View>
    <ScrollView keyboardShouldPersistTaps="always" style={styles.suggestionList} contentContainerStyle={styles.suggestionItems}>
      {shown.map(plugin => <Pressable key={plugin.id} testID={`plugin-option-${plugin.id}`} accessibilityRole="button"
        accessibilityLabel={`${plugin.name}, ${t('pluginKind')}`} onPress={() => onPick(plugin)}
        style={({pressed}) => [styles.menuRow, styles.pluginRow, pressed && styles.pressed]}>
        <View style={styles.pluginName}>
          <PluginMark plugin={plugin} size={20}/>
          <Text numberOfLines={1} style={[chatType.bodyLg(containsChinese(plugin.name)), styles.primary, styles.shrink]}>{plugin.name}</Text>
        </View>
        <Text style={[chatType.bodyMd(language === 'zh'), styles.tertiary]}>{t('pluginKind')}</Text>
      </Pressable>)}
      {!!status && <Pressable accessibilityRole={plugins.status === 'error' ? 'button' : 'text'} disabled={plugins.status !== 'error'}
        onPress={retry} style={[styles.menuRow, styles.statusRow]} accessibilityLiveRegion="polite">
        {plugins.status === 'loading' && <ActivityIndicator size="small" color={chatColors.textTertiary}/>}
        <Text style={[chatType.bodyMd(language === 'zh'), plugins.status === 'error' ? styles.error : styles.tertiary, styles.shrink]}>{status}</Text>
      </Pressable>}
    </ScrollView>
  </View>;
}

/** Figma 1-3 (node 1482:1242): the attached plugin, a 16 dp mark and its name. Tap removes it. */
export function PluginChip({plugin, language, disabled, onRemove}: {plugin: {id: string; name: string}; language: Language; disabled: boolean; onRemove: () => void}) {
  const t = chatText(language);
  return <Pressable testID={`plugin-chip-${plugin.id}`} accessibilityRole="button" accessibilityLabel={`${t('removePlugin')} ${plugin.name}`}
    accessibilityState={{disabled}} disabled={disabled} onPress={onRemove} hitSlop={8} style={({pressed}) => [styles.chip, pressed && styles.chipPressed]}>
    <PluginMark plugin={plugin} size={16}/>
    <Text numberOfLines={1} style={[chatType.bodyLg(containsChinese(plugin.name)), styles.primary, styles.shrink]}>{plugin.name}</Text>
  </Pressable>;
}

const styles = themedStyles(() => StyleSheet.create({
  attachCard: {position: 'absolute', left: chatMetrics.side, width: 182, padding: 8, gap: 4, borderRadius: 24, backgroundColor: chatColors.glass},
  suggestions: {position: 'absolute', left: chatMetrics.side, right: chatMetrics.side, maxHeight: 240, padding: 8, gap: 4,
    borderRadius: 24, backgroundColor: chatColors.glass, overflow: 'hidden'},
  suggestionList: {flexGrow: 0},
  suggestionItems: {gap: 4},
  menuRow: {flexDirection: 'row', alignItems: 'center', gap: 8, paddingHorizontal: 8, paddingVertical: 8, borderRadius: 9999},
  pluginRow: {justifyContent: 'space-between'},
  pluginName: {flexDirection: 'row', alignItems: 'center', gap: 8, flexShrink: 1},
  statusRow: {minHeight: 40},
  chip: {flexDirection: 'row', alignItems: 'center', gap: 4, borderRadius: 9999, maxWidth: '100%'},
  chipPressed: {opacity: 0.6},
  pressed: {backgroundColor: chatColors.fillControl},
  disabled: {opacity: 0.4},
  primary: {color: chatColors.textPrimary},
  tertiary: {color: chatColors.textTertiary},
  error: {color: chatColors.textError},
  grow: {flex: 1},
  shrink: {flexShrink: 1},
}));
