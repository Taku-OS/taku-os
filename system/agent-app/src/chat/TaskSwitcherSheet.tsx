/**
 * Lightweight frosted switcher sheet for Single Chat (v1): lets the user jump between
 * the continuous personal Main Chat and independent task sessions in one tap.
 */
import React from 'react';
import {Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import {ChatIcon} from './ChatIcon';
import {chatColors, chatMetrics, chatTitle, chatType, themedStyles} from './chatTheme';
import {chatText} from './chatText';
import {shortDate, type TakuHomeThread} from './homeModel';
import {containsChinese} from '../theme';

export interface TaskSwitcherSheetProps {
  language: 'en' | 'zh';
  top: number;
  currentThreadId: string;
  isMainChat: boolean;
  mainThread: TakuHomeThread | null;
  taskThreads: readonly TakuHomeThread[];
  onSelectMain: () => void;
  onSelectTask: (threadId: string) => void;
  onCreateTask: () => void;
  onOpenHome: () => void;
  onClose: () => void;
}

export function TaskSwitcherSheet({
  language,
  top,
  currentThreadId,
  isMainChat,
  mainThread,
  taskThreads,
  onSelectMain,
  onSelectTask,
  onCreateTask,
  onOpenHome,
  onClose,
}: TaskSwitcherSheetProps) {
  const t = chatText(language);
  const zh = language === 'zh';
  const mainPreview = mainThread?.preview || mainThread?.title || t('mainChatSubtitle');

  return (
    <View style={StyleSheet.absoluteFill} pointerEvents="box-none">
      <Pressable
        testID="task-switcher-backdrop"
        accessibilityRole="button"
        accessibilityLabel={t('back')}
        onPress={onClose}
        style={styles.backdrop}
      />
      <View testID="task-switcher-sheet" style={[styles.sheet, {top: top + 6}]}>
        <Pressable
          testID="switcher-main-chat"
          accessibilityRole="button"
          accessibilityState={{selected: isMainChat}}
          onPress={onSelectMain}
          style={({pressed}) => [styles.mainRow, isMainChat && styles.rowSelected, pressed && styles.pressed]}>
          <View style={styles.mainLead}>
            <View style={[styles.dot, isMainChat ? styles.dotActive : styles.dotIdle]} />
            <View style={styles.rowText}>
              <View style={styles.titleLine}>
                <Text numberOfLines={1} style={[chatType.bodyLgMedium(zh), styles.primary]}>
                  {chatTitle}
                </Text>
                <View style={styles.mainPill}>
                  <Text style={[chatType.bodySmMedium(zh), styles.mainPillText]}>{t('mainBadge')}</Text>
                </View>
              </View>
              <Text numberOfLines={1} style={[chatType.bodySm(containsChinese(mainPreview)), styles.tertiary]}>
                {mainPreview}
              </Text>
            </View>
          </View>
          {isMainChat && <ChatIcon name="check" size={16} color={chatColors.textPrimary} />}
        </Pressable>

        <View style={styles.divider} />

        <View style={styles.sectionHead}>
          <Text style={[chatType.bodySmMedium(zh), styles.tertiary]}>
            {taskThreads.length > 0 ? `${t('taskSessions')} · ${taskThreads.length}` : t('taskSessions')}
          </Text>
          <Pressable
            testID="switcher-new-task"
            accessibilityRole="button"
            accessibilityLabel={t('newTask')}
            onPress={onCreateTask}
            hitSlop={8}
            style={({pressed}) => [styles.newTaskChip, pressed && styles.pressed]}>
            <ChatIcon name="plus" size={14} color={chatColors.textPrimary} />
            <Text style={[chatType.bodySmMedium(zh), styles.primary]}>{t('newTask')}</Text>
          </Pressable>
        </View>

        {taskThreads.length === 0 ? (
          <Text style={[chatType.bodyMd(zh), styles.emptyTasks]}>{t('noTasks')}</Text>
        ) : (
          <ScrollView style={styles.taskList} contentContainerStyle={styles.taskListContent} keyboardShouldPersistTaps="handled">
            {taskThreads.slice(0, 8).map(thread => {
              const selected = !isMainChat && thread.id === currentThreadId;
              const title = thread.title || t('taskSession');
              const preview = thread.preview && thread.preview !== thread.title ? thread.preview : '';
              return (
                <Pressable
                  key={thread.id}
                  testID={`switcher-task-${thread.id}`}
                  accessibilityRole="button"
                  accessibilityState={{selected}}
                  onPress={() => onSelectTask(thread.id)}
                  style={({pressed}) => [styles.taskRow, selected && styles.rowSelected, pressed && styles.pressed]}>
                  <View style={styles.rowText}>
                    <View style={styles.taskTitleRow}>
                      <Text numberOfLines={1} style={[chatType.bodyMdMedium(containsChinese(title)), styles.primary, styles.grow]}>
                        {title}
                      </Text>
                      {thread.updatedAt > 0 && (
                        <Text style={[chatType.bodySm(zh), styles.tertiary]}>
                          {shortDate(thread.updatedAt, language)}
                        </Text>
                      )}
                    </View>
                    {!!preview && (
                      <Text numberOfLines={1} style={[chatType.bodySm(containsChinese(preview)), styles.tertiary]}>
                        {preview}
                      </Text>
                    )}
                  </View>
                  {selected ? (
                    <ChatIcon name="check" size={15} color={chatColors.textPrimary} />
                  ) : (
                    <ChatIcon name="chevronRight" size={15} color={chatColors.textPlaceholder} />
                  )}
                </Pressable>
              );
            })}
          </ScrollView>
        )}

        <View style={styles.divider} />

        <Pressable
          testID="switcher-open-home"
          accessibilityRole="button"
          onPress={onOpenHome}
          style={({pressed}) => [styles.footerRow, pressed && styles.pressed]}>
          <Text style={[chatType.bodyMd(zh), styles.secondary, styles.grow]}>{t('allHistory')}</Text>
          <ChatIcon name="chevronRight" size={15} color={chatColors.textPlaceholder} />
        </Pressable>
      </View>
    </View>
  );
}

const styles = themedStyles(() => StyleSheet.create({
  backdrop: {
    ...StyleSheet.absoluteFill,
    backgroundColor: 'rgba(0,0,0,0.28)',
  },
  sheet: {
    position: 'absolute',
    left: chatMetrics.side,
    right: chatMetrics.side,
    borderRadius: 24,
    backgroundColor: chatColors.surfaceRaised,
    padding: 12,
    gap: 8,
    shadowColor: '#000000',
    shadowOpacity: 0.28,
    shadowRadius: 24,
    shadowOffset: {width: 0, height: 12},
    elevation: 10,
  },
  mainRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 12,
    paddingVertical: 12,
    borderRadius: 16,
    gap: 12,
  },
  mainLead: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    minWidth: 0,
  },
  dot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    flexShrink: 0,
  },
  dotActive: {
    backgroundColor: '#34C759',
  },
  dotIdle: {
    backgroundColor: chatColors.textPlaceholder,
  },
  titleLine: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  mainPill: {
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 999,
    backgroundColor: chatColors.fillControl,
  },
  mainPillText: {
    color: chatColors.textSecondary,
  },
  divider: {
    height: StyleSheet.hairlineWidth,
    backgroundColor: chatColors.hairline,
    marginHorizontal: 8,
  },
  sectionHead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 12,
    paddingTop: 2,
  },
  newTaskChip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 999,
    backgroundColor: chatColors.fillControl,
  },
  emptyTasks: {
    color: chatColors.textTertiary,
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
  taskList: {
    maxHeight: 240,
  },
  taskListContent: {
    gap: 2,
  },
  taskRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: 14,
  },
  taskTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  rowSelected: {
    backgroundColor: chatColors.fillControl,
  },
  rowText: {
    flex: 1,
    minWidth: 0,
    gap: 2,
  },
  footerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: 14,
  },
  primary: {color: chatColors.textPrimary},
  secondary: {color: chatColors.textSecondary},
  tertiary: {color: chatColors.textTertiary},
  grow: {flex: 1},
  pressed: {opacity: 0.72},
}));
