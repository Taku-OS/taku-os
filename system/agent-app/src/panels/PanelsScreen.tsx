/**
 * Panel management (Figma 3-1, node 1482:1429): every pinned Panel from the Assistant's directory as a
 * live tile, in Home order. Tap opens the detail page (3-2-1); long press lifts the tile over the
 * Content/800 overlay with Rename / Edit with Taku / Delete (3-1-2). The panels themselves live on
 * Home; this page manages them through the same directory.
 */
import React, {useRef, useState} from 'react';
import {ActivityIndicator, BackHandler, FlatList, Modal, Pressable, StyleSheet, Text, TextInput, View, useWindowDimensions} from 'react-native';
import {useRouter} from 'expo-router';
import {useLocales} from 'expo-localization';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import * as Haptics from 'expo-haptics';
import {EdgeBlur} from '../native/EdgeBlur';
import {GlassRim} from '../chat/ChatSurfaces';
import {ChromeIcon} from '../chat/ChromeIcon';
import {GlassMenu, PageHeader, RoundButton} from '../chat/ChatOverlays';
import {chatBlur, chatColors, chatType, themedStyles, useChatAppearance} from '../chat/chatTheme';
import {chatText} from '../chat/chatText';
import {useChatBackdrop} from '../chat/useChatBackdrop';
import {useImeBottom} from '../chat/useImeBottom';
import {languageFor} from '../i18n';
import {containsChinese} from '../theme';
import {useTakuFonts} from '../theme/useTakuFonts';
import {usePanelCatalog} from './usePanelCatalog';
import {usePanelActions} from './panelActions';
import {panelErrorText} from './panelErrors';
import {PanelPreview} from './PanelPreview';
import type {PanelRecord} from '../model/panel';

const TILE_HEIGHT = 324;
const GAP = 16;

type Lifted = {panel: PanelRecord; x: number; y: number; width: number};

export function PanelsScreen() {
  useChatAppearance();
  useChatBackdrop();
  const router = useRouter();
  const language = languageFor(useLocales()[0]?.languageTag), t = chatText(language);
  const insets = useSafeAreaInsets();
  const {height: screenHeight, width: screenWidth} = useWindowDimensions();
  // Two columns of equal tiles, an odd last one included (Figma 3-1: 182 dp on a 412 dp screen).
  const tileWidth = Math.floor((screenWidth - 16 * 2 - GAP) / 2);
  const [fonts, fontError] = useTakuFonts();
  const imeBottom = useImeBottom();
  const catalog = usePanelCatalog(true);
  const actions = usePanelActions({language, onChanged: () => { void catalog.refresh(); }});
  const [lifted, setLifted] = useState<Lifted | null>(null);
  const [searching, setSearching] = useState(false);
  const [query, setQuery] = useState('');
  const [height, setHeight] = useState(0);
  const tiles = useRef<Record<string, React.ComponentRef<typeof View> | null>>({});
  const needle = query.trim().toLocaleLowerCase();
  const items = needle ? catalog.items.filter(panel => panel.title.toLocaleLowerCase().includes(needle)) : catalog.items;
  React.useEffect(() => {
    if (!searching) return;
    const back = BackHandler.addEventListener('hardwareBackPress', () => { setSearching(false); setQuery(''); return true; });
    return () => back.remove();
  }, [searching]);
  // The menu button returns to the chat with its sidebar open, where Panels was chosen.
  const openSidebar = () => router.replace({pathname: '/', params: {sidebar: '1'}});
  const lift = (panel: PanelRecord) => {
    const tile = tiles.current[panel.panelId];
    if (!tile) return;
    void Haptics.performAndroidHapticsAsync(Haptics.AndroidHaptics.Long_Press).catch(() => {});
    tile.measureInWindow((x, y, width) => setLifted({panel, x, y, width}));
  };
  if (!fonts && !fontError) return <View style={styles.root}/>;
  const headerBottom = insets.top + 64;
  const listError = catalog.error ? panelErrorText(catalog.error, language) : '';
  return <View style={styles.root} onLayout={event => setHeight(event.nativeEvent.layout.height)}>
    <EdgeBlur style={StyleSheet.absoluteFill} topSharp={headerBottom + 8} topFull={0}
      bottomSharp={height > 0 ? height - insets.bottom - 64 : -1} bottomFull={height} sigma={chatBlur.edgeSigma} fade
      uniformSigma={lifted ? chatBlur.menuSigma : 0}>
      <FlatList testID="panels-grid" data={items} numColumns={2} keyExtractor={panel => panel.panelId} removeClippedSubviews={false}
        keyboardShouldPersistTaps="handled" columnWrapperStyle={styles.columns}
        contentContainerStyle={[styles.grid, {paddingTop: headerBottom, paddingBottom: insets.bottom + (searching ? 96 : 64)}]}
        onRefresh={() => { void catalog.refresh(); }} refreshing={false}
        ListEmptyComponent={catalog.loading ? <View style={styles.status}><ActivityIndicator color={chatColors.textTertiary}/></View>
          : <Text style={[chatType.bodyMd(language === 'zh'), styles.tertiary, styles.status]}>
            {listError || (needle ? t('noMatchingPanels') : t('panelsEmpty'))}</Text>}
        renderItem={({item: panel}) => <View style={{width: tileWidth}}>
          <Pressable ref={view => { tiles.current[panel.panelId] = view; }} testID={`panel-tile-${panel.panelId}`} accessibilityRole="button"
            accessibilityLabel={panel.title || t('panelPreview')} accessibilityHint={t('panelManage')}
            accessibilityActions={[{name: 'longpress', label: t('panelManage')}]} onAccessibilityAction={() => lift(panel)}
            onPress={() => router.push({pathname: '/panel', params: {panelId: panel.panelId}})} onLongPress={() => lift(panel)}
            style={({pressed}) => [styles.tile, pressed && styles.pressed]}>
            <PanelPreview panel={panel} language={language} height={TILE_HEIGHT}/>
            <Text numberOfLines={1} style={[chatType.bodyLg(containsChinese(panel.title)), styles.primary]}>{panel.title || t('panelPreview')}</Text>
          </Pressable>
        </View>}/>
    </EdgeBlur>
    {!!listError && !!catalog.items.length && <Text accessibilityLiveRegion="polite" style={[chatType.bodyMd(language === 'zh'), styles.error, styles.banner, {top: headerBottom}]}>{listError}</Text>}
    {!!actions.notice && <Pressable accessibilityRole="button" onPress={actions.clearNotice} style={[styles.banner, {top: headerBottom}]}>
      <Text accessibilityLiveRegion="polite" style={[chatType.bodyMd(language === 'zh'), styles.error]}>{actions.notice}</Text>
    </Pressable>}
    <PageHeader top={insets.top} title={t('panels')} left={{icon: 'burger', label: t('openMenu'), onPress: openSidebar, testID: 'panels-menu'}}
      right={searching ? undefined : {icon: 'search', label: t('searchPanels'), onPress: () => setSearching(true), testID: 'panels-search'}}/>
    {searching && <View style={[styles.searchBar, {bottom: Math.max(insets.bottom, imeBottom) + 16}]}>
      <View style={styles.searchField}>
        <GlassRim radius={24}/>
        <View style={styles.searchGlyph}><ChromeIcon name="searchField" size={18}/></View>
        <TextInput testID="panels-search-input" value={query} onChangeText={setQuery} autoFocus placeholder={t('searchPlaceholder')}
          placeholderTextColor={chatColors.placeholder} accessibilityLabel={t('searchPanels')}
          style={[chatType.bodyLg(containsChinese(query)), styles.primary, styles.searchInput]}/>
      </View>
      <RoundButton icon="close" label={t('closeSearch')} size={48} onPress={() => { setSearching(false); setQuery(''); }}/>
    </View>}
    {lifted && <LiftedPanel lifted={lifted} language={language} screenHeight={screenHeight} onClose={() => setLifted(null)}
      onRename={() => { setLifted(null); actions.rename(lifted.panel); }}
      onEdit={() => { setLifted(null); actions.edit(lifted.panel); }}
      onDelete={() => { setLifted(null); actions.remove(lifted.panel, catalog.listRevision); }}/>}
    {actions.dialogs}
  </View>;
}

/** Figma 3-1-2: the pressed tile above a Content/800 overlay, its menu 8 dp below (above when it would not fit). */
function LiftedPanel({lifted, language, screenHeight, onClose, onRename, onEdit, onDelete}: {
  lifted: Lifted; language: 'en' | 'zh'; screenHeight: number; onClose: () => void; onRename: () => void; onEdit: () => void; onDelete: () => void;
}) {
  const t = chatText(language);
  const menuHeight = 3 * 40 + 2 * 4 + 16 + 9;
  const tileHeight = TILE_HEIGHT + 8 + 24;
  const below = lifted.y + tileHeight + 8 + menuHeight <= screenHeight - 24;
  return <Modal transparent animationType="fade" statusBarTranslucent navigationBarTranslucent onRequestClose={onClose}>
    <Pressable testID="panel-menu-scrim" style={styles.scrim} accessibilityLabel={t('done')} onPress={onClose}/>
    <View style={[styles.lifted, {left: lifted.x, top: lifted.y, width: lifted.width, height: tileHeight}]} pointerEvents="box-none">
      <View pointerEvents="none" style={styles.tile}>
        <PanelPreview panel={lifted.panel} language={language} height={TILE_HEIGHT}/>
        <Text numberOfLines={1} style={[chatType.bodyLg(containsChinese(lifted.panel.title)), styles.primary]}>{lifted.panel.title || t('panelPreview')}</Text>
      </View>
      <GlassMenu testID="panel-menu" style={[styles.menu, below ? {top: tileHeight + 8} : {bottom: tileHeight + 8}]} items={[
        {key: 'rename', label: t('panelRename'), icon: 'edit', onPress: onRename},
        {key: 'edit', label: t('editWithTaku'), icon: 'aiEdit', onPress: onEdit},
        {key: 'delete', label: t('panelDelete'), icon: 'trash', destructive: true, dividerBefore: true, onPress: onDelete},
      ]}/>
    </View>
  </Modal>;
}

const styles = themedStyles(() => StyleSheet.create({
  root: {flex: 1},
  grid: {paddingHorizontal: 16, gap: GAP},
  columns: {gap: GAP},
  tile: {gap: 8},
  pressed: {opacity: 0.85},
  status: {paddingVertical: 24, alignItems: 'center', textAlign: 'center'},
  banner: {position: 'absolute', left: 16, right: 16, paddingVertical: 8},
  scrim: {...StyleSheet.absoluteFill, backgroundColor: chatColors.scrim},
  lifted: {position: 'absolute'},
  menu: {position: 'absolute', left: 0},
  searchBar: {position: 'absolute', left: 16, right: 16, flexDirection: 'row', alignItems: 'center', gap: 12},
  searchField: {flex: 1, height: 48, flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 12, paddingVertical: 8,
    borderRadius: 9999, backgroundColor: chatColors.glass},
  searchGlyph: {width: 32, height: 32, alignItems: 'center', justifyContent: 'center'},
  searchInput: {flex: 1, paddingVertical: 0},
  primary: {color: chatColors.textPrimary},
  tertiary: {color: chatColors.textTertiary},
  error: {color: chatColors.textError},
}));
