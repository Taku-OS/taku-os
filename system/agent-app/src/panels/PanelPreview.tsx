/**
 * A pinned Panel drawn from its current matter, as the desktop draws it: the same GenUiCard over the
 * native snapshot. `PanelPreview` scales the panel-width card into a tile (Figma 3-1); the detail page
 * (3-2-1) shows it at full width.
 */
import React, {useEffect, useState} from 'react';
import {ActivityIndicator, StyleSheet, Text, View, type StyleProp, type ViewStyle} from 'react-native';
import {GenUiCard} from '../components/GenUiCard';
import {chatColors, chatType, themedStyles} from '../chat/chatTheme';
import {callAgent} from '../native/agent';
import {record} from '../model/session';
import {applyMatterSnapshot, ensureCard, parseMatterPresentation} from '../model/genuiStore';
import type {CardPresentation} from '../model/genui';
import type {PanelRecord} from '../model/panel';

/** The detail page's panel width (Figma 3-2-1, node 1482:1505); tiles scale it down. */
export const PANEL_WIDTH = 314;

type Card = {state: 'loading' | 'missing' | 'failed'} | {state: 'ready'; presentation: CardPresentation};

/** Reads the panel's current matter once per source version and seeds the card store with it. */
export function usePanelCard(panel: Pick<PanelRecord, 'matterId' | 'originThreadId' | 'sourceVersion'> | null): Card {
  const [card, setCard] = useState<{key: string; card: Card} | null>(null);
  const key = panel ? `${panel.matterId}/${panel.sourceVersion}` : '';
  useEffect(() => {
    if (!panel) return;
    let current = true;
    void callAgent<{snapshot: unknown}>('readMatter', {matterId: panel.matterId}).then(result => {
      const raw = record(record(result).snapshot);
      const presentation = parseMatterPresentation(raw.presentation, panel.originThreadId);
      if (presentation) {
        ensureCard(panel.originThreadId, presentation);
        applyMatterSnapshot(raw);
      }
      if (current) setCard({key, card: presentation ? {state: 'ready', presentation} : {state: 'missing'}});
    }, () => { if (current) setCard({key, card: {state: 'failed'}}); });
    return () => { current = false; };
    // The key covers the matter and its source version; the rest of the record is not read here.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  return card?.key === key ? card.card : {state: 'loading'};
}

/** The live panel, or its loading / unavailable state, at [width]. */
export function PanelCardView({panel, card, language, active, preload, width = PANEL_WIDTH, bare}: {
  panel: Pick<PanelRecord, 'originThreadId'>; card: Card; language: 'en' | 'zh'; active: boolean; preload?: boolean; width?: number;
  /** Inside a tile, which already draws the placeholder surface. */
  bare?: boolean;
}) {
  if (card.state === 'ready') return <View style={{width}}>
    <GenUiCard threadId={panel.originThreadId} presentation={card.presentation} language={language} active={active} preload={preload} surface="panel"/>
  </View>;
  return <View style={[styles.placeholder, bare && styles.bare, {width}]}>
    {card.state === 'loading' ? <ActivityIndicator color={chatColors.textTertiary}/>
      : <Text style={[chatType.bodyMd(language === 'zh'), styles.tertiary]}>{language === 'zh' ? '面板暂时无法加载' : 'Panel unavailable'}</Text>}
  </View>;
}

/** Figma 3-1 tile art: the panel scaled to the tile's width, clipped to its height, 24 dp corners. */
export function PanelPreview({panel, language, height, style}: {panel: PanelRecord; language: 'en' | 'zh'; height: number; style?: StyleProp<ViewStyle>}) {
  const card = usePanelCard(panel);
  const [width, setWidth] = useState(0);
  const scale = width > 0 ? width / PANEL_WIDTH : 0;
  return <View style={[styles.tile, {height}, style]} pointerEvents="none" accessibilityElementsHidden importantForAccessibility="no-hide-descendants"
    onLayout={event => setWidth(event.nativeEvent.layout.width)}>
    {scale > 0 && <View style={{width: PANEL_WIDTH, transform: [{scale}], transformOrigin: 'top left'}}>
      <PanelCardView panel={panel} card={card} language={language} active preload bare/>
    </View>}
  </View>;
}

const styles = themedStyles(() => StyleSheet.create({
  tile: {borderRadius: 24, overflow: 'hidden', backgroundColor: chatColors.fillControl},
  placeholder: {minHeight: 240, alignItems: 'center', justifyContent: 'center', borderRadius: 32, backgroundColor: chatColors.fillControl, padding: 16},
  bare: {backgroundColor: 'transparent'},
  tertiary: {color: chatColors.textTertiary},
}));
