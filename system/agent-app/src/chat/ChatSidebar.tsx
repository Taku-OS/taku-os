/**
 * The chat sidebar (Figma 2-1, node 1482:2397) and its search view (2-2, node 1482:2476). It replaces
 * Taku Home: Panels and Plugins entry points, the conversation history (newest first), Settings and
 * New Chat. It holds no chat state; the host passes the threads and the actions.
 */
import React, {useEffect, useMemo, useState} from 'react';
import {Animated, BackHandler, Easing, FlatList, Pressable, StyleSheet, Text, TextInput, useWindowDimensions, View} from 'react-native';
import {EdgeBlur} from '../native/EdgeBlur';
import {GlassRim} from './ChatSurfaces';
import {ChromeIcon, type ChromeIconName} from './ChromeIcon';
import {RoundButton} from './ChatOverlays';
import {chatBlur, chatColors, chatMetrics, chatType, themedStyles} from './chatTheme';
import {chatText} from './chatText';
import {matchingChats, recentTime, type TakuHomeThread} from './homeModel';
import {taskStatusLabel} from './TaskStrip';
import {partitionThreads} from '../model/singleChat';
import type {TaskBoard, TaskStatus} from '../model/taskStatus';
import {containsChinese} from '../theme';

type Language = 'en' | 'zh';

export type ChatSidebarActions = {
  openThread: (threadId: string) => void;
  openMainChat?: () => void;
  newChat: () => void;
  openSettings: () => void;
  openPanels: () => void;
  openPlugins: () => void;
  close: () => void;
};

export function ChatSidebar({language, threads, threadId, primaryThreadId = '', isMainChat = false, taskStates, historyError = '', insets, imeBottom, actions}: {
  language: Language; threads: readonly TakuHomeThread[]; threadId: string; primaryThreadId?: string; isMainChat?: boolean;
  taskStates?: TaskBoard['states']; historyError?: string;
  insets: {top: number; bottom: number}; imeBottom: number; actions: ChatSidebarActions;
}) {
  const t = chatText(language);
  const {width} = useWindowDimensions();
  const [searching, setSearching] = useState(false);
  const [query, setQuery] = useState('');
  const [height, setHeight] = useState(0);
  // Times are read when the sidebar opens; it is short-lived.
  const [now] = useState(() => Date.now());
  const [slide] = useState(() => new Animated.Value(-width));
  useEffect(() => {
    Animated.timing(slide, {toValue: 0, duration: 220, easing: Easing.out(Easing.cubic), useNativeDriver: true}).start();
  }, [slide]);
  const close = () => Animated.timing(slide, {toValue: -width, duration: 180, easing: Easing.in(Easing.cubic), useNativeDriver: true})
    .start(() => actions.close());
  const leaveSearch = () => { setSearching(false); setQuery(''); };
  useEffect(() => {
    if (!searching) return;
    const back = BackHandler.addEventListener('hardwareBackPress', () => { leaveSearch(); return true; });
    return () => back.remove();
  }, [searching]);
  const singleChat = Boolean(actions.openMainChat);
  const listThreads = useMemo(() => singleChat ? partitionThreads(threads, primaryThreadId).taskThreads : threads, [singleChat, threads, primaryThreadId]);
  const chats = useMemo(() => matchingChats(listThreads, query), [listThreads, query]);
  const headerBottom = insets.top + chatMetrics.header;
  const top = searching ? insets.top + 16 : headerBottom;
  const bottomSpace = searching ? imeBottom + 16 + 48 : insets.bottom + 16 + 40;
  const empty = <Text style={[chatType.bodyMd(language === 'zh'), styles.tertiary, styles.empty]}>
    {historyError || (query.trim() ? t(singleChat ? 'noMatchingTasks' : 'noResults') : t(singleChat ? 'noTasks' : 'noChats'))}
  </Text>;
  const navigation = <View style={styles.section}>
    {actions.openMainChat && <NavRow icon="burger" label={t('sidebarTitle')} testID="sidebar-main" current={isMainChat} onPress={actions.openMainChat}/>}
    <NavRow icon="panels" label={t('panels')} testID="sidebar-panels" onPress={actions.openPanels}/>
    <NavRow icon="plugins" label={t('attachPlugins')} testID="sidebar-plugins" onPress={actions.openPlugins}/>
  </View>;
  return <Animated.View style={[styles.root, {transform: [{translateX: slide}]}]} onLayout={event => setHeight(event.nativeEvent.layout.height)}>
    <EdgeBlur style={StyleSheet.absoluteFill} topSharp={top + 8} topFull={0}
      bottomSharp={height > 0 ? height - bottomSpace - 24 : -1} bottomFull={height} sigma={chatBlur.edgeSigma} fade>
      <FlatList testID={searching ? 'sidebar-search-results' : 'chat-sidebar'} data={searching ? chats : listThreads} keyExtractor={thread => thread.id}
        removeClippedSubviews={false} keyboardShouldPersistTaps="handled" keyboardDismissMode="on-drag" extraData={taskStates}
        contentContainerStyle={[styles.list, {paddingTop: top, paddingBottom: bottomSpace + 24}]}
        ListHeaderComponent={<View style={styles.listHeader}>
          {!searching && navigation}
          <Text accessibilityRole="header" style={[chatType.bodyMd(language === 'zh'), styles.tertiary, styles.sectionLabel]}>{t(singleChat ? 'tasks' : 'recents')}</Text>
        </View>}
        ListEmptyComponent={empty}
        renderItem={({item}) => searching
          ? <SearchRow thread={item} language={language} time={item.updatedAt > 0 ? recentTime(item.updatedAt, now, language) : ''} onPress={() => actions.openThread(item.id)}/>
          : <ChatRow thread={item} language={language} current={item.id === threadId} status={taskStates?.[item.id]?.status} onPress={() => actions.openThread(item.id)}/>}/>
    </EdgeBlur>
    {!searching && <View style={[styles.header, {paddingTop: insets.top, height: headerBottom}]} pointerEvents="box-none">
      <Text accessibilityRole="header" style={[chatType.headingXl(), styles.primary]}>{t('sidebarTitle')}</Text>
      <View style={styles.headerActions}>
        <RoundButton icon="search" label={t('search')} testID="sidebar-search" onPress={() => setSearching(true)}/>
        <RoundButton icon="close" label={t('closeSidebar')} testID="sidebar-close" onPress={close}/>
      </View>
    </View>}
    {searching ? <View style={[styles.searchBar, {bottom: imeBottom + 16}]}>
      <View style={styles.searchField}>
        <GlassRim radius={24}/>
        <View style={styles.searchGlyph}><ChromeIcon name="searchField" size={18}/></View>
        <TextInput testID="sidebar-search-input" value={query} onChangeText={setQuery} autoFocus placeholder={t('searchPlaceholder')}
          placeholderTextColor={chatColors.placeholder} accessibilityLabel={t(singleChat ? 'searchTasks' : 'searchChats')} returnKeyType="search"
          style={[chatType.bodyLg(containsChinese(query)), styles.primary, styles.searchInput]}/>
      </View>
      <RoundButton icon="close" label={t('closeSearch')} size={48} testID="sidebar-search-close" onPress={leaveSearch}/>
    </View> : <View style={[styles.bottom, {bottom: insets.bottom + 16}]}>
      <RoundButton icon="settings" label={t('settings')} testID="sidebar-settings" onPress={actions.openSettings}/>
      <Pressable testID="sidebar-new-chat" accessibilityRole="button" accessibilityLabel={t(singleChat ? 'newTask' : 'newChat')} onPress={actions.newChat}
        style={({pressed}) => [styles.newChat, pressed && styles.pressedOpacity]}>
        <ChromeIcon name="newChat" color={chatColors.pillText}/>
        <Text style={[chatType.bodyLgMedium(language === 'zh'), {color: chatColors.pillText}]}>{t(singleChat ? 'newTask' : 'newChatPill')}</Text>
      </Pressable>
    </View>}
  </Animated.View>;
}

/** 2-1 entry rows: 48 dp, 24 dp icon, Body/lg. */
function NavRow({icon, label, onPress, testID, current = false}: {icon: ChromeIconName; label: string; onPress: () => void; testID: string; current?: boolean}) {
  return <Pressable testID={testID} accessibilityRole="button" accessibilityState={{selected: current}} onPress={onPress}
    style={({pressed}) => [styles.row, current && styles.current, pressed && styles.pressed]}>
    <ChromeIcon name={icon}/>
    <Text numberOfLines={1} style={[chatType.bodyLg(containsChinese(label)), styles.primary]}>{label}</Text>
  </Pressable>;
}

/** 2-1 history rows: 48 dp Body/lg titles; the open conversation sits on Surface/100. */
function ChatRow({thread, language, current, status, onPress}: {thread: TakuHomeThread; language: Language; current: boolean; status?: TaskStatus; onPress: () => void}) {
  const title = thread.title || thread.preview || chatText(language)('newChat');
  const badge = status === 'needsYou' || status === 'inProgress' ? taskStatusLabel(status, language) : null;
  return <Pressable accessibilityRole="button" accessibilityState={{selected: current}} accessibilityLabel={badge ? `${title}, ${badge.text}` : title} onPress={onPress}
    style={({pressed}) => [styles.row, current && styles.current, pressed && styles.pressed]}>
    <Text numberOfLines={1} style={[chatType.bodyLg(containsChinese(title)), styles.primary, styles.grow]}>{title}</Text>
    {badge && <Text numberOfLines={1} style={[chatType.bodySm(language === 'zh'), {color: badge.color}]}>{badge.text}</Text>}
  </Pressable>;
}

/** 2-2 results: Body/Medium/lg title with its time, one Body/md preview line. */
function SearchRow({thread, language, time, onPress}: {thread: TakuHomeThread; language: Language; time: string; onPress: () => void}) {
  const title = thread.title || chatText(language)('newChat');
  return <Pressable accessibilityRole="button" onPress={onPress} style={({pressed}) => [styles.result, pressed && styles.pressed]}>
    <View style={styles.resultHead}>
      <Text numberOfLines={1} style={[chatType.bodyLgMedium(containsChinese(title)), styles.primary, styles.grow]}>{title}</Text>
      {!!time && <Text style={[chatType.bodyMd(language === 'zh'), styles.tertiary]}>{time}</Text>}
    </View>
    {!!thread.preview && thread.preview !== thread.title &&
      <Text numberOfLines={1} style={[chatType.bodyMd(containsChinese(thread.preview)), styles.tertiary]}>{thread.preview}</Text>}
  </Pressable>;
}

const styles = themedStyles(() => StyleSheet.create({
  root: {flex: 1},
  list: {paddingHorizontal: 12, gap: 8},
  listHeader: {gap: 8},
  section: {paddingBottom: 16},
  sectionLabel: {paddingHorizontal: 12},
  header: {position: 'absolute', left: 0, right: 0, top: 0, flexDirection: 'row', alignItems: 'center',
    justifyContent: 'space-between', paddingHorizontal: chatMetrics.side},
  headerActions: {flexDirection: 'row', gap: 16},
  row: {flexDirection: 'row', alignItems: 'center', gap: 8, height: 48, paddingHorizontal: 12, borderRadius: 9999},
  current: {backgroundColor: chatColors.selection},
  result: {paddingHorizontal: 12, paddingVertical: 12, borderRadius: 8, gap: 4},
  resultHead: {flexDirection: 'row', alignItems: 'center', gap: 12},
  empty: {paddingHorizontal: 12, paddingVertical: 8},
  bottom: {position: 'absolute', left: chatMetrics.side, right: chatMetrics.side, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between'},
  newChat: {height: 40, flexDirection: 'row', alignItems: 'center', gap: 8, paddingHorizontal: 12, borderRadius: 9999, backgroundColor: chatColors.pill},
  searchBar: {position: 'absolute', left: chatMetrics.side, right: chatMetrics.side, flexDirection: 'row', alignItems: 'center', gap: 12},
  searchField: {flex: 1, height: 48, flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 12, paddingVertical: 8,
    borderRadius: 9999, backgroundColor: chatColors.glass},
  searchGlyph: {width: 32, height: 32, alignItems: 'center', justifyContent: 'center'},
  searchInput: {flex: 1, paddingVertical: 0},
  pressed: {backgroundColor: chatColors.fillControl},
  pressedOpacity: {opacity: 0.7},
  primary: {color: chatColors.textPrimary},
  tertiary: {color: chatColors.textTertiary},
  grow: {flex: 1},
}));
