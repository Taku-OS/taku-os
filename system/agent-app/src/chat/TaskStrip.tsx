/**
 * The row of Task capsules under TAKU's title (Zile's Single Chat wireframe): one capsule for each
 * Task at work, its name and its status, newest first, scrolling sideways. A capsule opens its
 * Task. It shows only what the status board read from the native threads; with no Task at work
 * there is no row.
 */
import React, {useEffect, useState} from 'react';
import {Animated, Pressable, ScrollView, StyleSheet, Text} from 'react-native';
import {chatColors, chatMetrics, chatMotion, chatType, themedStyles} from './chatTheme';
import {chatText} from './chatText';
import {containsChinese} from '../theme';
import type {LiveTaskStatus} from '../model/taskStatus';

export interface TaskCapsule {
  id: string;
  title: string;
  status: LiveTaskStatus;
}

/** The status word and its colour, shared by the capsules, the drawer and the cards. */
export function taskStatusLabel(status: LiveTaskStatus, language: 'en' | 'zh'): {text: string; color: string} {
  const t = chatText(language);
  return status === 'needsYou' ? {text: t('needsYou'), color: chatColors.statusAttention}
    : {text: t('inProgress'), color: chatColors.statusProgress};
}

export function TaskStrip({tasks, language, top, onOpen}: {
  tasks: readonly TaskCapsule[];
  language: 'en' | 'zh';
  /** The title row's bottom edge (window dp). */
  top: number;
  onOpen: (id: string) => void;
}) {
  const t = chatText(language);
  const [shown] = useState(() => new Animated.Value(0));
  useEffect(() => {
    Animated.timing(shown, {toValue: 1, duration: chatMotion.colorMs, useNativeDriver: true}).start();
  }, [shown]);
  return <Animated.View style={[styles.strip, {top, opacity: shown}]}>
    <ScrollView testID="task-strip" horizontal showsHorizontalScrollIndicator={false} keyboardShouldPersistTaps="handled"
      accessibilityLabel={t('activeTasks')} contentContainerStyle={styles.content}>
      {tasks.map(task => {
        const status = taskStatusLabel(task.status, language);
        return <Pressable key={task.id} testID={`task-capsule-${task.id}`} accessibilityRole="button"
          accessibilityLabel={`${task.title}, ${status.text}`} onPress={() => onOpen(task.id)}
          style={({pressed}) => [styles.capsule, pressed && styles.pressed]}>
          <Text numberOfLines={1} style={[chatType.bodyMdMedium(containsChinese(task.title)), styles.label, styles.title]}>{task.title}</Text>
          <Text numberOfLines={1} style={[chatType.bodyMd(language === 'zh'), styles.label, {color: status.color}]}>{status.text}</Text>
        </Pressable>;
      })}
    </ScrollView>
  </Animated.View>;
}

const styles = themedStyles(() => StyleSheet.create({
  strip: {position: 'absolute', left: 0, right: 0, height: chatMetrics.strip},
  // Wireframe: 14 dp from the edge, 6 dp between capsules; the last one may run off the edge.
  content: {paddingHorizontal: 14, gap: 6, alignItems: 'flex-start'},
  capsule: {height: 38, borderRadius: 19, paddingHorizontal: 16, flexDirection: 'row', alignItems: 'center', gap: 8,
    backgroundColor: chatColors.assistantBubble},
  label: {fontSize: 13, lineHeight: 18},
  title: {color: chatColors.textPrimary, maxWidth: 220},
  pressed: {opacity: 0.7},
}));
