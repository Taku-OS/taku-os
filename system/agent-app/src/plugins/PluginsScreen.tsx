/**
 * Plugin Market overview (Figma 4-1, node 1482:1512): the Installed strip, then plugins by section
 * (Popular for recommended catalog entries, then each category). Installed rows open a menu
 * (4-1-2): Details, Chat with Taku, Manage, Uninstall; catalog rows install with +.
 */
import React, {useEffect, useRef, useState} from 'react';
import {ActivityIndicator, BackHandler, Modal, Pressable, ScrollView, StyleSheet, Text, TextInput, View, useWindowDimensions} from 'react-native';
import {useRouter} from 'expo-router';
import {useLocales} from 'expo-localization';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
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
import {installedStrip, marketSections, SECTION_PREVIEW} from './marketModel';
import {PluginRow, PluginTile, SectionTitle, usePluginActions} from './PluginUi';
import {usePluginList} from './repository';
import type {PluginSummary} from './contracts';

type Menu = {plugin: PluginSummary; x: number; y: number; width: number; height: number};
const MENU_HEIGHT = 4 * 40 + 3 * 4 + 16 + 9;

export function PluginsScreen() {
  useChatAppearance();
  useChatBackdrop();
  const router = useRouter();
  const language = languageFor(useLocales()[0]?.languageTag), t = chatText(language);
  const insets = useSafeAreaInsets();
  const imeBottom = useImeBottom();
  const {height: screenHeight} = useWindowDimensions();
  const [fonts, fontError] = useTakuFonts();
  const list = usePluginList();
  const actions = usePluginActions({language, onChanged: () => { void list.refresh(); }});
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set());
  const [searching, setSearching] = useState(false);
  const [query, setQuery] = useState('');
  const [menu, setMenu] = useState<Menu | null>(null);
  const [height, setHeight] = useState(0);
  const anchors = useRef<Record<string, React.ComponentRef<typeof View> | null>>({});
  useEffect(() => {
    if (!searching) return;
    const back = BackHandler.addEventListener('hardwareBackPress', () => { setSearching(false); setQuery(''); return true; });
    return () => back.remove();
  }, [searching]);
  if (!fonts && !fontError) return <View style={styles.root}/>;
  const back = () => { if (router.canGoBack()) router.back(); else router.replace({pathname: '/', params: {sidebar: '1'}}); };
  const openDetails = (plugin: PluginSummary) => router.push({pathname: '/plugin', params: {pluginId: plugin.id}});
  const openMenu = (plugin: PluginSummary) => anchors.current[plugin.id]?.measureInWindow((x, y, width, height) => setMenu({plugin, x, y, width, height}));
  const toggle = (key: string) => setExpanded(current => { const next = new Set(current); if (next.has(key)) next.delete(key); else next.add(key); return next; });
  const sections = marketSections(list.installed, list.catalog, query, t('popular'));
  const strip = installedStrip(list.installed);
  const headerBottom = insets.top + 64;
  const row = (plugin: PluginSummary) => <PluginRow key={plugin.id} plugin={plugin} language={language} onPress={() => openDetails(plugin)}
    moreRef={view => { anchors.current[plugin.id] = view; }} onMore={() => openMenu(plugin)}
    onInstall={() => { void actions.install(plugin); }}/>;
  const status = list.loading && !list.installed.length ? <ActivityIndicator color={chatColors.textTertiary} style={styles.status}/>
    : list.error ? <Text style={[chatType.bodyMd(language === 'zh'), styles.error, styles.status]}>{t('pluginsLoadFailed')}</Text>
    : !sections.length ? <Text style={[chatType.bodyMd(language === 'zh'), styles.tertiary, styles.status]}>{query.trim() ? t('noMatchingPlugins') : t('pluginsNone')}</Text> : null;
  const menuBelow = menu ? menu.y - 6 + MENU_HEIGHT <= screenHeight - insets.bottom - 16 : true;
  return <View style={styles.root} onLayout={event => setHeight(event.nativeEvent.layout.height)}>
    <EdgeBlur style={StyleSheet.absoluteFill} topSharp={headerBottom + 8} topFull={0}
      bottomSharp={height > 0 ? height - insets.bottom - 48 : -1} bottomFull={height} sigma={chatBlur.edgeSigma} fade
      uniformSigma={menu ? chatBlur.menuSigma : 0}>
      <ScrollView testID="plugins-overview" keyboardShouldPersistTaps="handled"
        contentContainerStyle={[styles.content, {paddingTop: headerBottom + 8, paddingBottom: insets.bottom + (searching ? 96 : 48)}]}>
        {!searching && list.installed.length > 0 && <View style={styles.section}>
          <SectionTitle title={t('installedCount').replace('{n}', String(list.installed.length))} language={language}
            action={strip.more ? {label: expanded.has('installed') ? t('showLess') : t('seeAll'), onPress: () => toggle('installed')} : undefined}/>
          {expanded.has('installed')
            ? <View style={styles.rows}>{list.installed.map(row)}</View>
            : <View style={styles.strip}>
              {strip.shown.map(plugin => <Pressable key={plugin.id} testID={`plugin-strip-${plugin.id}`} accessibilityRole="button" accessibilityLabel={plugin.name}
                onPress={() => openDetails(plugin)} style={({pressed}) => [styles.stripCell, pressed && styles.pressed]}>
                <PluginTile plugin={plugin} kind="strip" style={styles.fill}/>
              </Pressable>)}
              {strip.more > 0 && <Pressable accessibilityRole="button" accessibilityLabel={t('seeAll')} onPress={() => toggle('installed')}
                style={({pressed}) => [styles.stripCell, styles.moreTile, pressed && styles.pressed]}>
                <Text style={[chatType.bodyLgMedium(), styles.secondary]}>+{strip.more}</Text>
              </Pressable>}
              {Array.from({length: Math.max(0, 7 - strip.shown.length - (strip.more ? 1 : 0))}, (_, index) => <View key={`gap-${index}`} style={styles.stripCell}/>)}
            </View>}
        </View>}
        {sections.map(section => {
          const open = expanded.has(section.key) || searching;
          return <View key={section.key} style={styles.section}>
            <SectionTitle title={section.title} language={language} action={!searching && section.items.length > SECTION_PREVIEW
              ? {label: open ? t('showLess') : t('seeAll'), onPress: () => toggle(section.key)} : undefined}/>
            <View style={styles.rows}>{(open ? section.items : section.items.slice(0, SECTION_PREVIEW)).map(row)}</View>
          </View>;
        })}
        {status}
      </ScrollView>
    </EdgeBlur>
    {!!actions.notice && <Pressable accessibilityRole="button" onPress={actions.clearNotice} style={[styles.notice, {top: headerBottom}]}>
      <Text accessibilityLiveRegion="polite" style={[chatType.bodyMd(language === 'zh'), styles.error]}>{actions.notice}</Text>
    </Pressable>}
    <PageHeader top={insets.top} title={t('pluginsTitle')} left={{icon: 'chevron', label: t('back'), onPress: back, testID: 'plugins-back'}}
      right={searching ? undefined : {icon: 'search', label: t('searchPlugins'), onPress: () => setSearching(true), testID: 'plugins-search'}}/>
    {searching && <View style={[styles.searchBar, {bottom: Math.max(insets.bottom, imeBottom) + 16}]}>
      <View style={styles.searchField}>
        <GlassRim radius={24}/>
        <View style={styles.searchGlyph}><ChromeIcon name="searchField" size={18}/></View>
        <TextInput testID="plugins-search-input" value={query} onChangeText={setQuery} autoFocus placeholder={t('searchPlaceholder')}
          placeholderTextColor={chatColors.placeholder} accessibilityLabel={t('searchPlugins')}
          style={[chatType.bodyLg(containsChinese(query)), styles.primary, styles.searchInput]}/>
      </View>
      <RoundButton icon="close" label={t('closeSearch')} size={48} onPress={() => { setSearching(false); setQuery(''); }}/>
    </View>}
    {menu && <Modal transparent animationType="fade" statusBarTranslucent navigationBarTranslucent onRequestClose={() => setMenu(null)}>
      <Pressable testID="plugin-menu-scrim" style={StyleSheet.absoluteFill} accessibilityLabel={t('done')} onPress={() => setMenu(null)}/>
      {/* Figma 4-1-2: the menu's top meets the row's top, flush with the list's right edge. */}
      <GlassMenu testID="plugin-menu" style={[styles.menu, menuBelow ? {top: menu.y - 6} : {top: menu.y + menu.height + 6 - MENU_HEIGHT}]} items={[
        {key: 'details', label: t('pluginDetails'), icon: 'plugins', onPress: () => { setMenu(null); openDetails(menu.plugin); }},
        {key: 'chat', label: t('chatWithTaku'), icon: 'aiEdit', disabled: menu.plugin.enabled !== true, onPress: () => { setMenu(null); actions.chat(menu.plugin); }},
        {key: 'manage', label: t('managePlugin'), icon: 'settings', onPress: () => { setMenu(null); router.push({pathname: '/plugin-settings', params: {pluginId: menu.plugin.id}}); }},
        {key: 'uninstall', label: t('uninstall'), icon: 'trash', destructive: true, dividerBefore: true, onPress: () => { setMenu(null); actions.uninstall(menu.plugin); }},
      ]}/>
    </Modal>}
    {actions.dialog}
  </View>;
}

const styles = themedStyles(() => StyleSheet.create({
  root: {flex: 1},
  content: {paddingHorizontal: 16, gap: 32},
  section: {gap: 16},
  rows: {gap: 16},
  strip: {flexDirection: 'row', gap: 12},
  stripCell: {flex: 1, aspectRatio: 1},
  fill: {flex: 1},
  moreTile: {alignItems: 'center', justifyContent: 'center', borderRadius: 12, backgroundColor: chatColors.fillControl},
  status: {paddingVertical: 24, textAlign: 'center', alignSelf: 'center'},
  notice: {position: 'absolute', left: 16, right: 16, paddingVertical: 8},
  menu: {position: 'absolute', right: 16},
  searchBar: {position: 'absolute', left: 16, right: 16, flexDirection: 'row', alignItems: 'center', gap: 12},
  searchField: {flex: 1, height: 48, flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 12, paddingVertical: 8,
    borderRadius: 9999, backgroundColor: chatColors.glass},
  searchGlyph: {width: 32, height: 32, alignItems: 'center', justifyContent: 'center'},
  searchInput: {flex: 1, paddingVertical: 0},
  pressed: {opacity: 0.7},
  primary: {color: chatColors.textPrimary},
  secondary: {color: chatColors.textSecondary},
  tertiary: {color: chatColors.textTertiary},
  error: {color: chatColors.textError},
}));
