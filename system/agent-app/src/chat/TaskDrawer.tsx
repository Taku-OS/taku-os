/**
 * TAKU's side drawer (Zile's Single Chat wireframe). It slides in from the left over four fifths
 * of the screen and dims the conversation beside it: TAKU, Panels and Plugins first, then every
 * Task with its status. "New task" rests under the Task list, at the foot of the drawer above the
 * personal space row, on purpose (Ricky): TAKU starts Tasks itself, and starting one by hand stays
 * possible but out of the way. It is pinned there so that a long list never buries it. The drawer
 * holds no chat state: the host passes the threads, the status board and the actions, and the two
 * counts come from the same native directories their pages read.
 */
import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {Animated, BackHandler, Easing, FlatList, Keyboard, PanResponder, Pressable, StyleSheet, Text, TextInput, View} from 'react-native';
import Constants from 'expo-constants';
import {ChatIcon, type ChatIconName} from './ChatIcon';
import {chatColors, chatMotion, chatTitle, chatType, themedStyles} from './chatTheme';
import {chatText} from './chatText';
import {matchingChats, shortDate, type TakuHomeThread} from './homeModel';
import {taskStatusLabel} from './TaskStrip';
import {containsChinese} from '../theme';
import {callAgent, gatewayLoad} from '../native/agent';
import {usePanelCatalog} from '../panels/usePanelCatalog';
import {createNativePluginRepository} from '../plugins/nativeRepository';
import type {TaskBoard, TaskStatus} from '../model/taskStatus';

/** The drawer's share of the screen width, and its width limit on a wide window (dp). */
const SHARE = 0.8, MAX_WIDTH = 420;
/** Closing is quicker than opening; a drag past a third of the drawer, or a flick, closes it. */
const CLOSE_MS = 240, DRAG_CLOSE = 0.33, FLICK = 0.6;

export interface TaskDrawerProps {
  language: 'en' | 'zh';
  windowWidth: number;
  /** The status bar's height, and what covers the bottom edge (gesture bar or keyboard), in dp. */
  top: number;
  bottom: number;
  tasks: readonly TakuHomeThread[];
  states: TaskBoard['states'];
  historyError?: string;
  onSelectMain: () => void;
  onSelectTask: (threadId: string) => void;
  onCreateTask: () => void;
  onOpenPanels: () => void;
  onOpenPlugins: () => void;
  onOpenSettings: () => void;
  /** The drawer has left the screen; the host unmounts it. */
  onClosed: () => void;
}

/** Plugins that are installed, switched on and running without an error. */
function useConnectedPlugins(): number {
  const [count, setCount] = useState(0);
  useEffect(() => {
    let current = true;
    // A count nobody waits on: it is read once the drawer's own lists have left the gateway room.
    void gatewayLoad.quiet().then(() => createNativePluginRepository((action, params) => callAgent(action, params)).listInstalled()).then(result => {
      if (current && result.ok) setCount(result.value.filter(plugin => plugin.enabled && !plugin.error).length);
    });
    return () => { current = false; };
  }, []);
  return count;
}

export function TaskDrawer({language, windowWidth, top, bottom, tasks, states, historyError = '', onSelectMain, onSelectTask,
  onCreateTask, onOpenPanels, onOpenPlugins, onOpenSettings, onClosed}: TaskDrawerProps) {
  const t = chatText(language), zh = language === 'zh';
  const width = Math.min(Math.round(windowWidth * SHARE), MAX_WIDTH);
  const [progress] = useState(() => new Animated.Value(0));
  const [drag] = useState(() => new Animated.Value(0));
  // Once it is leaving, the drawer takes no further touches: one tap opens one destination.
  const [leaving, setLeaving] = useState(false);
  const [searching, setSearching] = useState(false);
  const [query, setQuery] = useState('');
  const panels = usePanelCatalog(true).items.length;
  const plugins = useConnectedPlugins();

  useEffect(() => {
    Animated.timing(progress, {toValue: 1, duration: chatMotion.drawerMs, easing: Easing.bezier(...chatMotion.drawerEase), useNativeDriver: true}).start();
  }, [progress]);

  const dismiss = useCallback(() => {
    setLeaving(true);
    Keyboard.dismiss();
    Animated.timing(progress, {toValue: 0, duration: CLOSE_MS, easing: Easing.bezier(...chatMotion.drawerEase), useNativeDriver: true})
      .start(() => onClosed());
  }, [onClosed, progress]);
  /** A destination was chosen: it opens at once while the drawer leaves over it. */
  const leave = (open: () => void) => { open(); dismiss(); };
  const closeSearch = useCallback(() => { setSearching(false); setQuery(''); Keyboard.dismiss(); }, []);

  useEffect(() => {
    const back = BackHandler.addEventListener('hardwareBackPress', () => { if (searching) closeSearch(); else dismiss(); return true; });
    return () => back.remove();
  }, [closeSearch, dismiss, searching]);

  // A sideways drag follows the finger; the Task list keeps vertical drags.
  const pan = useMemo(() => PanResponder.create({
    onMoveShouldSetPanResponderCapture: (_, gesture) => gesture.dx < -12 && Math.abs(gesture.dx) > Math.abs(gesture.dy) * 1.5,
    onPanResponderMove: (_, gesture) => drag.setValue(Math.min(0, gesture.dx)),
    onPanResponderRelease: (_, gesture) => {
      if (gesture.dx < -width * DRAG_CLOSE || gesture.vx < -FLICK) {
        setLeaving(true);
        Keyboard.dismiss();
        Animated.timing(drag, {toValue: -width, duration: 180, easing: Easing.out(Easing.cubic), useNativeDriver: true}).start(() => onClosed());
      } else Animated.timing(drag, {toValue: 0, duration: 200, easing: Easing.out(Easing.cubic), useNativeDriver: true}).start();
    },
    onPanResponderTerminate: () => { Animated.timing(drag, {toValue: 0, duration: 200, useNativeDriver: true}).start(); },
  }), [drag, onClosed, width]);

  const shown = useMemo(() => searching ? matchingChats(tasks, query) : [...tasks], [query, searching, tasks]);
  const slide = Animated.add(progress.interpolate({inputRange: [0, 1], outputRange: [-width, 0]}), drag);
  const dim = Animated.multiply(progress, drag.interpolate({inputRange: [-width, 0], outputRange: [0, 1], extrapolate: 'clamp'}));
  const device = Constants.deviceName?.trim() ?? '';

  const menu = <View>
    <NavRow testID="drawer-main" icon="menu" label={chatTitle} onPress={() => leave(onSelectMain)}/>
    <NavRow testID="drawer-panels" icon="panels" label={t('panels')} meta={panels > 0 ? String(panels) : ''} onPress={() => leave(onOpenPanels)}/>
    <NavRow testID="drawer-plugins" icon="plug" label={t('plugins')} onPress={() => leave(onOpenPlugins)}
      meta={plugins > 0 ? t('connectedCount').replace('{count}', String(plugins)) : ''}/>
    <Text accessibilityRole="header" style={[chatType.bodyMd(zh), styles.section]}>{t('tasks')}</Text>
  </View>;
  const empty = <Text style={[chatType.bodyMd(zh), styles.empty]}>{historyError || t(searching ? 'noMatchingTasks' : 'noTasks')}</Text>;

  return <View style={StyleSheet.absoluteFill} pointerEvents="box-none">
    <Animated.View style={[StyleSheet.absoluteFill, {opacity: dim}]}>
      <Pressable testID="drawer-scrim" accessibilityRole="button" accessibilityLabel={t('closeMenu')} disabled={leaving} onPress={dismiss} style={styles.scrim}/>
    </Animated.View>
    <Animated.View testID="task-drawer" accessibilityViewIsModal pointerEvents={leaving ? 'none' : 'auto'} {...pan.panHandlers}
      style={[styles.panel, {width, top, bottom, transform: [{translateX: slide}]}]}>
      <View style={styles.head}>
        {searching
          ? <TextInput testID="drawer-search-input" value={query} onChangeText={setQuery} autoFocus placeholder={t('searchTasks')} placeholderTextColor={chatColors.placeholder}
              accessibilityLabel={t('searchTasks')} returnKeyType="search" style={[chatType.bodyLg(zh || containsChinese(query)), styles.searchInput]}/>
          : <Text accessibilityRole="header" style={[chatType.headingXl(), styles.brand]}>{chatTitle}</Text>}
        {!searching && <HeadButton testID="drawer-search" icon="search24" label={t('searchTasks')} onPress={() => setSearching(true)}/>}
        <HeadButton testID="drawer-close" icon="close" label={t(searching ? 'back' : 'closeMenu')} onPress={searching ? closeSearch : dismiss}/>
      </View>
      <FlatList testID="drawer-tasks" data={shown} keyExtractor={thread => thread.id} style={styles.list} contentContainerStyle={styles.listContent}
        keyboardShouldPersistTaps="handled" showsVerticalScrollIndicator={false} extraData={states}
        ListHeaderComponent={searching ? undefined : menu} ListEmptyComponent={empty}
        renderItem={({item: thread, index}) => <TaskRow thread={thread} status={states[thread.id]?.status} language={language} divided={index > 0}
          onOpen={() => leave(() => onSelectTask(thread.id))}/>}/>
      <View style={styles.foot}>
      {!searching && <Pressable testID="drawer-new-task" accessibilityRole="button" accessibilityLabel={t('newTask')} onPress={() => leave(onCreateTask)}
        style={({pressed}) => [styles.newTask, pressed && styles.pressed]}>
        <ChatIcon name="plus" size={16} color={chatColors.textTertiary}/>
        <Text style={[chatType.bodyMd(zh), styles.newTaskText]}>{t('newTask')}</Text>
      </Pressable>}
      <Pressable testID="drawer-space" accessibilityRole="button" accessibilityLabel={`${t('personalSpace')}, ${t('settings')}`} onPress={() => leave(onOpenSettings)}
        style={({pressed}) => [styles.space, pressed && styles.pressed]}>
        <Text numberOfLines={1} style={[chatType.bodyMd(containsChinese(device)), styles.spaceText, styles.grow]}>{device}</Text>
        <Text numberOfLines={1} style={[chatType.bodyMd(zh), styles.spaceText]}>{t('personalSpace')}</Text>
        <ChatIcon name="chevronRight" size={14} color={chatColors.textPlaceholder}/>
      </Pressable>
      </View>
    </Animated.View>
  </View>;
}

function HeadButton({icon, label, testID, onPress}: {icon: ChatIconName; label: string; testID: string; onPress: () => void}) {
  return <Pressable testID={testID} accessibilityRole="button" accessibilityLabel={label} onPress={onPress} hitSlop={6}
    style={({pressed}) => [styles.headButton, pressed && styles.pressedFill]}>
    <ChatIcon name={icon} size={20} color={chatColors.icon}/>
  </Pressable>;
}

function NavRow({icon, label, meta = '', testID, onPress}: {icon: ChatIconName; label: string; meta?: string; testID: string; onPress: () => void}) {
  return <Pressable testID={testID} accessibilityRole="button" accessibilityLabel={meta ? `${label}, ${meta}` : label} onPress={onPress}
    style={({pressed}) => [styles.nav, pressed && styles.pressedFill]}>
    <ChatIcon name={icon} size={20} color={chatColors.icon}/>
    <Text numberOfLines={1} style={[chatType.bodyLgMedium(containsChinese(label)), styles.navLabel, styles.grow]}>{label}</Text>
    {!!meta && <Text numberOfLines={1} style={[chatType.bodyMd(containsChinese(meta)), styles.meta]}>{meta}</Text>}
  </Pressable>;
}

function TaskRow({thread, status, language, divided, onOpen}: {
  thread: TakuHomeThread; status: TaskStatus | undefined; language: 'en' | 'zh'; divided: boolean; onOpen: () => void;
}) {
  const t = chatText(language);
  const title = thread.title || t('task');
  // A Task whose status has not been read says when it last changed, never a guessed status.
  const note = status === 'needsYou' || status === 'inProgress' ? taskStatusLabel(status, language)
    : {color: chatColors.textTertiary, text: status === 'done' ? t('taskDone') : status === 'failed' ? t('taskFailed')
      : thread.updatedAt > 0 ? shortDate(thread.updatedAt, language) : ''};
  return <Pressable testID={`drawer-task-${thread.id}`} accessibilityRole="button" accessibilityLabel={note.text ? `${title}, ${note.text}` : title} onPress={onOpen}
    style={({pressed}) => [styles.task, divided && styles.divided, pressed && styles.pressed]}>
    <Text numberOfLines={1} style={[chatType.bodyLgMedium(containsChinese(title)), styles.navLabel]}>{title}</Text>
    {!!note.text && <Text numberOfLines={1} style={[chatType.bodyMd(language === 'zh'), styles.note, {color: note.color}]}>{note.text}</Text>}
  </Pressable>;
}

const styles = themedStyles(() => StyleSheet.create({
  scrim: {flex: 1, backgroundColor: chatColors.scrim},
  // Wireframe: an opaque sheet from the status bar to the gesture bar, rounded on its free side.
  panel: {position: 'absolute', left: 0, backgroundColor: chatColors.backdropFallback, borderTopRightRadius: 28, borderBottomRightRadius: 28,
    overflow: 'hidden', elevation: 12},
  head: {height: 88, flexDirection: 'row', alignItems: 'center', paddingLeft: 16, paddingRight: 8, gap: 4},
  brand: {flex: 1, color: chatColors.textPrimary},
  headButton: {width: 44, height: 44, borderRadius: 22, alignItems: 'center', justifyContent: 'center'},
  searchInput: {flex: 1, height: 44, paddingHorizontal: 0, paddingVertical: 0, color: chatColors.textPrimary},
  list: {flex: 1},
  listContent: {paddingHorizontal: 16, paddingTop: 8, paddingBottom: 16},
  // The icon sits 32 dp from the sheet's edge and the count 30 dp from the other, as drawn.
  nav: {height: 50, borderRadius: 14, flexDirection: 'row', alignItems: 'center', paddingLeft: 16, paddingRight: 14, gap: 16},
  navLabel: {color: chatColors.textPrimary},
  meta: {color: chatColors.textTertiary},
  section: {color: chatColors.textTertiary, fontSize: 13, lineHeight: 18, paddingLeft: 12, paddingTop: 28, paddingBottom: 6},
  task: {paddingHorizontal: 12, paddingVertical: 13, gap: 3},
  note: {fontSize: 13, lineHeight: 18},
  divided: {borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: chatColors.hairline},
  empty: {color: chatColors.textTertiary, paddingHorizontal: 12, paddingVertical: 14},
  // The foot stays put while the Task list scrolls under its hairline.
  foot: {borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: chatColors.hairline, paddingTop: 4},
  newTask: {height: 48, flexDirection: 'row', alignItems: 'center', gap: 8, paddingLeft: 28, paddingRight: 22},
  newTaskText: {color: chatColors.textTertiary},
  space: {height: 48, flexDirection: 'row', alignItems: 'center', gap: 6, paddingLeft: 28, paddingRight: 22},
  spaceText: {color: chatColors.textTertiary, fontSize: 13, lineHeight: 18},
  grow: {flex: 1},
  pressed: {opacity: 0.6},
  pressedFill: {backgroundColor: chatColors.fillControl},
}));
