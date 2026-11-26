/**
 * Plugin Market building blocks (Figma 4-1 … 4-3): the plugin tile, list row and the actions every
 * page shares (Chat with Taku, enable/disable, reload, uninstall with confirmation). Plugins carry
 * no artwork, so the tile holds the Plugin Market mark where the design shows a brand logo.
 */
import React, {useState} from 'react';
import {Pressable, StyleSheet, Text, View, type StyleProp, type ViewStyle} from 'react-native';
import {useRouter} from 'expo-router';
import {ChromeIcon} from '../chat/ChromeIcon';
import {DeleteDialog} from '../chat/ChatOverlays';
import {chatColors, chatType, themedStyles} from '../chat/chatTheme';
import {chatText} from '../chat/chatText';
import {containsChinese} from '../theme';
import {PluginMark} from './PluginMark';
import {pluginRepository} from './repository';
import type {PluginOperationResult, PluginSummary} from './contracts';

type Language = 'en' | 'zh';
const tileSizes = {strip: {mark: 24, radius: 12}, row: {mark: 24, radius: 12}, hero: {mark: 32, radius: 16}};

/** Surface/200 tile around the plugin's mark: 48 (strip, flexible), 44 (rows) or 64 (detail). */
export function PluginTile({plugin, kind, style}: {plugin: Pick<PluginSummary, 'id' | 'name'>; kind: keyof typeof tileSizes; style?: StyleProp<ViewStyle>}) {
  const size = tileSizes[kind];
  return <View style={[styles.tile, {borderRadius: size.radius}, kind === 'row' && styles.rowTile, kind === 'hero' && styles.heroTile, style]}>
    <PluginMark plugin={plugin} size={size.mark}/>
  </View>;
}

/** Figma 4-1 row: 44 dp tile, Body/lg name over a Body/sm line; More (installed) or Plus (install) at the end. */
export function PluginRow({plugin, language, onPress, onMore, onInstall, moreRef}: {
  plugin: PluginSummary; language: Language; onPress: () => void; onMore?: () => void; onInstall?: () => void;
  moreRef?: (view: React.ComponentRef<typeof View> | null) => void;
}) {
  const t = chatText(language);
  const subtitle = plugin.installed && plugin.enabled === false ? t('pluginDisabled') : plugin.description;
  return <View style={styles.row}>
    <Pressable testID={`plugin-row-${plugin.id}`} accessibilityRole="button" accessibilityLabel={`${plugin.name}. ${subtitle}`} onPress={onPress}
      style={({pressed}) => [styles.rowMain, pressed && styles.pressed]}>
      <PluginTile plugin={plugin} kind="row"/>
      <View style={styles.rowText}>
        <Text numberOfLines={1} style={[chatType.bodyLg(containsChinese(plugin.name)), styles.primary]}>{plugin.name}</Text>
        <Text numberOfLines={1} style={[chatType.bodySm(containsChinese(subtitle)), styles.tertiary]}>{subtitle}</Text>
      </View>
    </Pressable>
    {plugin.installed ? onMore && <View ref={moreRef} collapsable={false}>
      <Pressable testID={`plugin-more-${plugin.id}`} accessibilityRole="button" accessibilityLabel={t('moreFor').replace('{name}', plugin.name)}
        onPress={onMore} hitSlop={12} style={({pressed}) => [styles.end, pressed && styles.pressed]}><ChromeIcon name="more" size={18}/></Pressable>
    </View> : onInstall && <Pressable testID={`plugin-install-${plugin.id}`} accessibilityRole="button" accessibilityLabel={`${t('install')} ${plugin.name}`}
      onPress={onInstall} hitSlop={12} style={({pressed}) => [styles.end, pressed && styles.pressed]}><ChromeIcon name="plus" size={18}/></Pressable>}
  </View>;
}

/** Space Grotesk section title with an optional "See all" (Figma 4-1). */
export function SectionTitle({title, language, action}: {title: string; language: Language; action?: {label: string; onPress: () => void}}) {
  return <View style={styles.sectionHead}>
    <Text accessibilityRole="header" style={[chatType.headingMd(language === 'zh' || containsChinese(title)), styles.primary]}>{title}</Text>
    {action && <Pressable accessibilityRole="button" onPress={action.onPress} hitSlop={8}>
      <Text style={[chatType.bodyMd(language === 'zh'), styles.secondary]}>{action.label}</Text>
    </Pressable>}
  </View>;
}

/** Chat with Taku, enable/disable, reload and uninstall, each read back from the plugin manager. */
export function usePluginActions({language, onChanged, onUninstalled}: {language: Language; onChanged: () => void; onUninstalled?: (plugin: PluginSummary) => void}) {
  const t = chatText(language);
  const router = useRouter();
  const repository = pluginRepository();
  const [uninstalling, setUninstalling] = useState<PluginSummary | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const run = async (operation: () => Promise<PluginOperationResult>, done?: () => void) => {
    setBusy(true); setError(''); setNotice('');
    try {
      const result = await operation();
      if (!result.ok) { const text = result.code === 'NOT_FOUND' ? t('pluginMissing') : t('pluginFailed'); if (done) setError(text); else setNotice(text); return; }
      done?.(); onChanged();
    } catch { (done ? setError : setNotice)(t('pluginFailed')); }
    finally { setBusy(false); }
  };
  return {
    busy, notice, clearNotice: () => setNotice(''),
    /** The chat opens with this plugin attached to the next message (the `@` chip, Figma 1-3). */
    chat: (plugin: PluginSummary) => router.replace({pathname: '/', params: {plugin: plugin.id}}),
    setEnabled: (plugin: PluginSummary, enabled: boolean) => run(() => enabled ? repository.enable(plugin.id) : repository.disable(plugin.id)),
    reload: (plugin: PluginSummary) => run(() => repository.reload(plugin.id)),
    uninstall: (plugin: PluginSummary) => { setError(''); setUninstalling(plugin); },
    install: (plugin: PluginSummary) => run(() => repository.install(plugin.id)),
    dialog: uninstalling && <DeleteDialog title={t('uninstallTitle')} name={uninstalling.name} body={t('uninstallBody')} language={language}
      busy={busy} error={error} confirmLabel={t('uninstall')} onCancel={() => setUninstalling(null)}
      onConfirm={() => { const plugin = uninstalling; void run(() => repository.remove(plugin.id), () => { setUninstalling(null); onUninstalled?.(plugin); }); }}/>,
  };
}

const styles = themedStyles(() => StyleSheet.create({
  tile: {alignItems: 'center', justifyContent: 'center', backgroundColor: chatColors.fillControl, overflow: 'hidden'},
  rowTile: {width: 44, height: 44},
  heroTile: {width: 64, height: 64},
  row: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12},
  rowMain: {flex: 1, minWidth: 0, flexDirection: 'row', alignItems: 'center', gap: 12},
  rowText: {flex: 1, minWidth: 0, height: 44, justifyContent: 'space-between'},
  end: {width: 32, height: 32, alignItems: 'center', justifyContent: 'center'},
  sectionHead: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between'},
  pressed: {opacity: 0.7},
  primary: {color: chatColors.textPrimary},
  secondary: {color: chatColors.textSecondary},
  tertiary: {color: chatColors.textTertiary},
}));
