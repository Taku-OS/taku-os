/**
 * Panel detail (Figma 3-2-1, node 1482:1473): the live panel at full width with Open panel (Home, at
 * this panel) and Edit with Taku. The More button opens Rename / Delete (3-2-2); Delete confirms
 * first (3-3) and removes the panel from Home and from the Panels list.
 */
import React, {useCallback, useEffect, useState} from 'react';
import {AppState, Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import {useLocalSearchParams, useRouter} from 'expo-router';
import {useLocales} from 'expo-localization';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import {GlassMenu, PageHeader, PillButton} from '../chat/ChatOverlays';
import {chatColors, chatType, themedStyles, useChatAppearance} from '../chat/chatTheme';
import {chatText} from '../chat/chatText';
import {useChatBackdrop} from '../chat/useChatBackdrop';
import {languageFor} from '../i18n';
import {useTakuFonts} from '../theme/useTakuFonts';
import {getPanel, openPanel, requirePanelContract} from './client';
import {onPanelsChanged} from './host';
import {usePanelActions} from './panelActions';
import {panelErrorCode, panelErrorText} from './panelErrors';
import {PANEL_WIDTH, PanelCardView, usePanelCard} from './PanelPreview';
import type {PanelDetail} from '../model/panel';

type Load = {kind: 'loading'} | {kind: 'failed'; code: string} | {kind: 'ready'; detail: PanelDetail};

export function PanelDetailScreen() {
  useChatAppearance();
  useChatBackdrop();
  const router = useRouter();
  const {panelId = ''} = useLocalSearchParams<{panelId?: string}>();
  const language = languageFor(useLocales()[0]?.languageTag), t = chatText(language);
  const insets = useSafeAreaInsets();
  const [fonts, fontError] = useTakuFonts();
  const [load, setLoad] = useState<Load>({kind: 'loading'});
  const [menu, setMenu] = useState(false);
  const [opening, setOpening] = useState(false);
  const [notice, setNotice] = useState('');
  const read = useCallback(async () => {
    if (!panelId) { setLoad({kind: 'failed', code: 'PANEL_NOT_FOUND'}); return; }
    try { await requirePanelContract(); setLoad({kind: 'ready', detail: await getPanel(panelId)}); }
    catch (problem) { setLoad({kind: 'failed', code: panelErrorCode(problem)}); }
  }, [panelId]);
  // Reads the panel from the Assistant's directory (external system) and follows its changes.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void read();
    const directory = onPanelsChanged(() => { void read(); });
    const lifecycle = AppState.addEventListener('change', status => { if (status === 'active') void read(); });
    return () => { directory.remove(); lifecycle.remove(); };
  }, [read]);
  const back = () => { if (router.canGoBack()) router.back(); else router.replace('/panels'); };
  const actions = usePanelActions({language, onChanged: () => { void read(); }, onDeleted: () => router.replace('/panels')});
  const detail = load.kind === 'ready' ? load.detail : null;
  const card = usePanelCard(detail?.panel ?? null);
  if (!fonts && !fontError) return <View style={styles.root}/>;
  const title = detail?.panel.title || t('panelPreview');
  const headerBottom = insets.top + 64;
  const actionsBottom = insets.bottom + 16;
  const open = () => {
    if (!detail || opening) return;
    setOpening(true); setNotice('');
    void openPanel(detail.panel.panelId).catch(problem => setNotice(panelErrorText(panelErrorCode(problem), language, 'panelOpenFailed')))
      .finally(() => setOpening(false));
  };
  const message = notice || actions.notice || (load.kind === 'failed' ? panelErrorText(load.code, language, 'panelUnavailable') : '');
  return <View style={styles.root}>
    {detail && <View style={[styles.frame, {top: headerBottom, bottom: actionsBottom + 40 + 24}]}>
      <ScrollView testID="panel-detail" contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
        <PanelCardView panel={detail.panel} card={card} language={language} active/>
      </ScrollView>
    </View>}
    {!!message && <Text accessibilityLiveRegion="polite" style={[chatType.bodyMd(language === 'zh'), styles.error, styles.notice, {top: headerBottom}]}>{message}</Text>}
    <PageHeader top={insets.top} title={detail ? title : ''} left={{icon: 'chevron', label: t('back'), onPress: back, testID: 'panel-back'}}
      right={detail ? {icon: 'more', label: t('moreActions'), onPress: () => setMenu(true), testID: 'panel-more'} : undefined}/>
    {detail && <View style={[styles.actions, {bottom: actionsBottom}]}>
      <PillButton testID="panel-open" label={t('openPanel')} busy={opening} disabled={!detail.capabilities.canOpen} onPress={open} style={styles.grow}/>
      <PillButton testID="panel-edit" label={t('editWithTaku')} variant="primary" icon="aiEdit"
        disabled={!detail.capabilities.canContinue || card.state !== 'ready'} onPress={() => actions.edit(detail.panel)} style={styles.grow}/>
    </View>}
    {menu && detail && <>
      <Pressable testID="panel-detail-menu-scrim" style={StyleSheet.absoluteFill} accessibilityLabel={t('done')} onPress={() => setMenu(false)}/>
      <GlassMenu testID="panel-detail-menu" style={[styles.menu, {top: insets.top + 12}]} items={[
        {key: 'rename', label: t('panelRename'), icon: 'edit', onPress: () => { setMenu(false); actions.rename(detail.panel); }},
        {key: 'delete', label: t('panelDelete'), icon: 'trash', destructive: true, disabled: !detail.capabilities.canUnpin,
          onPress: () => { setMenu(false); actions.remove(detail.panel, detail.listRevision); }},
      ]}/>
    </>}
    {actions.dialogs}
  </View>;
}

const styles = themedStyles(() => StyleSheet.create({
  root: {flex: 1},
  // Figma 1482:1505: the 314 dp panel with 32 dp corners, scrolling inside its own frame.
  frame: {position: 'absolute', alignSelf: 'center', width: PANEL_WIDTH, borderRadius: 32, overflow: 'hidden'},
  scroll: {paddingBottom: 8},
  notice: {position: 'absolute', left: 16, right: 16, paddingVertical: 8},
  actions: {position: 'absolute', left: 16, right: 16, flexDirection: 'row', gap: 16},
  menu: {position: 'absolute', right: 16},
  error: {color: chatColors.textError},
  grow: {flex: 1},
}));
