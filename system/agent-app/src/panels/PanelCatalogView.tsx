/**
 * The Panel directory on Taku Home. Every row is one native PanelRecord. Opening navigates through
 * the Assistant, so the desktop and this list agree on IDs and order.
 */
import React, {useState} from 'react';
import {ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View} from 'react-native';
import {chatColors, chatType} from '../chat/chatTheme';
import {chatText} from '../chat/chatText';
import {shortDate} from '../chat/homeModel';
import {movePanel, openPanel, renamePanel, unpinPanel, requirePanelContract} from './client';
import {usePanelCatalog} from './usePanelCatalog';
import type {PanelRecord} from '../model/panel';
import {record} from '../model/session';

type Language = 'en' | 'zh';
const code = (error: unknown): string => {
  const value = record(error).code;
  return typeof value === 'string' && value ? value : error instanceof Error ? error.message : 'UNAVAILABLE';
};

export function PanelCatalogView({language, active, limit}: {language: Language; active: boolean; limit?: number}) {
  const t = chatText(language), catalog = usePanelCatalog(active);
  const [menu, setMenu] = useState<string | null>(null);
  const [editing, setEditing] = useState<{panelId: string; title: string} | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const items = limit ? catalog.items.slice(0, limit) : catalog.items;
  const run = (run: () => Promise<unknown>) => {
    if (busy) return;
    setBusy(true); setError('');
    run().then(() => { setMenu(null); setEditing(null); return catalog.refresh(); })
      .catch(problem => setError(code(problem))).finally(() => setBusy(false));
  };
  const open = (panel: PanelRecord) => run(async () => { await requirePanelContract(); await openPanel(panel.panelId); });
  const move = (panel: PanelRecord, index: number, offset: -1 | 1) => {
    const before = offset < 0 ? catalog.items[index - 1]?.panelId : catalog.items[index + 2]?.panelId ?? null;
    return run(() => movePanel(panel.panelId, before ?? null, catalog.listRevision));
  };
  const message = error === 'PANEL_CONTRACT_MISMATCH' ? t('panelsUpdateRequired')
    : error === 'PANEL_NOT_FOUND' ? t('panelMissing')
    : error === 'LIST_CHANGED' || error === 'IDEMPOTENCY_CONFLICT' ? t('panelStaleState')
    : error === 'INVALID_ARGUMENT' ? t('panelTitleInvalid') : error ? t('panelsError') : '';
  return <View style={styles.root}>
    {catalog.loading && !catalog.items.length && <View style={styles.loading}><ActivityIndicator color={chatColors.textTertiary}/></View>}
    {!!catalog.error && catalog.error !== 'PANEL_CONTRACT_MISMATCH' && <Text style={[chatType.bodyMd(language === 'zh'), styles.error]}>{t('panelsError')}</Text>}
    {catalog.error === 'PANEL_CONTRACT_MISMATCH' && <Text style={[chatType.bodyMd(language === 'zh'), styles.error]}>{t('panelsUpdateRequired')}</Text>}
    {!catalog.loading && !catalog.error && !catalog.items.length && <Text style={[chatType.bodyMd(language === 'zh'), styles.empty]}>{t('panelsEmpty')}</Text>}
    {!!items.length && <View style={styles.list}>{items.map(panel => {
      const index = catalog.items.findIndex(item => item.panelId === panel.panelId);
      const expanded = menu === panel.panelId, renaming = editing?.panelId === panel.panelId;
      return <View key={panel.panelId} style={styles.row}>
        <View style={styles.head}>
          <Pressable accessibilityRole="button" accessibilityLabel={`${t('openPanel')}: ${panel.title || panel.panelId}`}
            disabled={busy} onPress={() => open(panel)} style={({pressed}) => [styles.headMain, pressed && styles.pressed]}>
            <Text numberOfLines={1} style={[chatType.bodyLg(language === 'zh' || /[\u4e00-\u9fff]/.test(panel.title)), styles.title]}>{panel.title || panel.panelId}</Text>
            {panel.updatedAt > 0 && <Text style={[chatType.bodyMd(language === 'zh'), styles.tertiary]}>{shortDate(panel.updatedAt, language)}</Text>}
          </Pressable>
          <Pressable accessibilityRole="button" accessibilityLabel={t('panelManage')} disabled={busy}
            onPress={() => { setEditing(null); setMenu(expanded ? null : panel.panelId); setError(''); }}
            style={({pressed}) => [styles.manage, pressed && styles.pressed]}>
            <Text style={[chatType.bodyLg(false), styles.secondary]}>{expanded ? '×' : '⋯'}</Text>
          </Pressable>
        </View>
        {renaming && <View style={styles.renameRow}>
          <TextInput value={editing.title} autoFocus accessibilityLabel={t('panelTitleLabel')} maxLength={120}
            onChangeText={title => setEditing({panelId: panel.panelId, title})} style={[chatType.bodyLg(language === 'zh'), styles.input]}/>
          <Pressable accessibilityRole="button" accessibilityLabel={t('panelSaveTitle')} disabled={busy || !editing.title.trim()}
            onPress={() => run(() => renamePanel(panel.panelId, editing.title.trim(), panel.metadataRevision))}
            style={({pressed}) => [styles.action, pressed && styles.pressed]}>
            <Text style={[chatType.bodyMd(language === 'zh'), styles.primary]}>{t('save')}</Text>
          </Pressable>
          <Pressable accessibilityRole="button" accessibilityLabel={t('cancel')} onPress={() => setEditing(null)} style={styles.action}>
            <Text style={[chatType.bodyMd(language === 'zh'), styles.secondary]}>{t('cancel')}</Text>
          </Pressable>
        </View>}
        {expanded && <View style={styles.actions}>
          <Pressable accessibilityRole="button" accessibilityLabel={t('panelRename')} disabled={busy}
            onPress={() => setEditing({panelId: panel.panelId, title: panel.title})} style={({pressed}) => [styles.action, pressed && styles.pressed]}>
            <Text style={[chatType.bodyMd(language === 'zh'), styles.primary]}>{t('panelRename')}</Text>
          </Pressable>
          <Pressable accessibilityRole="button" accessibilityLabel={t('panelMoveUp')} disabled={busy || index <= 0}
            onPress={() => move(panel, index, -1)} style={({pressed}) => [styles.action, pressed && styles.pressed]}>
            <Text style={[chatType.bodyMd(language === 'zh'), index <= 0 ? styles.disabled : styles.primary]}>{t('panelMoveUp')}</Text>
          </Pressable>
          <Pressable accessibilityRole="button" accessibilityLabel={t('panelMoveDown')} disabled={busy || index < 0 || index >= catalog.items.length - 1}
            onPress={() => move(panel, index, 1)} style={({pressed}) => [styles.action, pressed && styles.pressed]}>
            <Text style={[chatType.bodyMd(language === 'zh'), index >= catalog.items.length - 1 ? styles.disabled : styles.primary]}>{t('panelMoveDown')}</Text>
          </Pressable>
          <Pressable accessibilityRole="button" accessibilityLabel={t('panelUnpin')} disabled={busy}
            onPress={() => run(() => unpinPanel(panel.panelId, catalog.listRevision))} style={({pressed}) => [styles.action, pressed && styles.pressed]}>
            <Text style={[chatType.bodyMd(language === 'zh'), styles.error]}>{t('panelUnpin')}</Text>
          </Pressable>
        </View>}
      </View>;
    })}</View>}
    {!!message && <Text accessibilityLiveRegion="polite" style={[chatType.bodyMd(language === 'zh'), styles.error]}>{message}</Text>}
  </View>;
}

const styles = StyleSheet.create({
  root: {gap: 8},
  loading: {paddingVertical: 12, alignItems: 'center'},
  list: {borderRadius: 24, backgroundColor: chatColors.surfaceRaised, overflow: 'hidden'},
  row: {paddingHorizontal: 16, paddingVertical: 10, gap: 4},
  head: {flexDirection: 'row', alignItems: 'center', gap: 8},
  headMain: {flex: 1, minWidth: 0, flexDirection: 'row', alignItems: 'center', gap: 8, minHeight: 40},
  title: {flex: 1, color: chatColors.textPrimary},
  manage: {width: 40, height: 40, alignItems: 'center', justifyContent: 'center'},
  renameRow: {flexDirection: 'row', alignItems: 'center', gap: 8},
  input: {flex: 1, minWidth: 0, color: chatColors.textPrimary, borderBottomWidth: 1, borderBottomColor: chatColors.hairline, paddingVertical: 6},
  actions: {flexDirection: 'row', flexWrap: 'wrap', gap: 4},
  action: {paddingHorizontal: 8, paddingVertical: 8, borderRadius: 8},
  pressed: {opacity: 0.7},
  empty: {color: chatColors.textTertiary, paddingVertical: 8},
  error: {color: chatColors.textError},
  primary: {color: chatColors.textPrimary},
  secondary: {color: chatColors.textSecondary},
  tertiary: {color: chatColors.textTertiary},
  disabled: {color: chatColors.textPlaceholder},
});
