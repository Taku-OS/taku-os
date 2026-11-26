/**
 * A Task's card in TAKU's main stream (Zile's Single Chat wireframe). While the Task waits for the
 * user it shows what the Task asked and, for a plain choice, the choices; choosing one sends that
 * answer to the Task itself. Once the Task works on the answer the card and the Task's capsule
 * both read "In progress", because both are drawn from the same status board, and only "View
 * task" remains. The card never invents a question, an option or a result.
 */
import React, {useEffect, useState} from 'react';
import {Animated, Platform, Pressable, StyleSheet, Text, View} from 'react-native';
import * as Haptics from 'expo-haptics';
import {chatColors, chatMotion, chatType, themedStyles} from './chatTheme';
import {chatText} from './chatText';
import {taskStatusLabel} from './TaskStrip';
import {containsChinese} from '../theme';
import type {LiveTaskStatus, TaskDecisionOption} from '../model/taskStatus';

export interface TaskCardProps {
  language: 'en' | 'zh';
  threadId: string;
  title: string;
  status: LiveTaskStatus;
  /** The pending request the card shows; '' once answered or when there is none to show. */
  askedKey: string;
  /** What the Task asked; '' when its request carries no text the chat can show. */
  prompt: string;
  /** Choices answerable in place; none once answered, or when the request is reviewed on the Task's page. */
  options: readonly TaskDecisionOption[];
  /** The choice the user made here, while the Task works on it. */
  answer: string;
  /** Sends the choice to the Task; resolves false when the gateway did not accept it. */
  onChoose: (option: TaskDecisionOption) => Promise<boolean>;
  onView: () => void;
}

export function TaskCard({language, threadId, title, status, askedKey, prompt, options, answer, onChoose, onView}: TaskCardProps) {
  const t = chatText(language);
  const label = taskStatusLabel(status, language);
  // The choice being delivered (its position), and the request whose last delivery was refused:
  // the note belongs to that request and leaves with it.
  const [sending, setSending] = useState<number | null>(null);
  const [refused, setRefused] = useState<string | null>(null);
  const failed = refused !== null && refused === askedKey;
  const [settle] = useState(() => new Animated.Value(1));
  // The status word and the body change together; a short fade carries the eye across.
  useEffect(() => {
    settle.setValue(0.35);
    Animated.timing(settle, {toValue: 1, duration: chatMotion.colorMs, useNativeDriver: true}).start();
  }, [settle, status]);
  const choose = (option: TaskDecisionOption, index: number) => {
    if (sending !== null) return;
    if (Platform.OS === 'android') void Haptics.performAndroidHapticsAsync(Haptics.AndroidHaptics.Confirm).catch(() => {});
    setSending(index);
    setRefused(null);
    void onChoose(option).then(accepted => { if (!accepted) setRefused(askedKey); }, () => setRefused(askedKey)).finally(() => setSending(null));
  };
  const body = status === 'inProgress' ? t('taskAnswered').replace('{choice}', answer) : prompt || t('taskWaiting');
  return <View testID={`task-card-${threadId}`} style={styles.card}>
    <Animated.View style={[styles.text, {opacity: settle}]}>
      <Text accessibilityLiveRegion="polite" style={[chatType.bodyMd(language === 'zh'), styles.status, {color: label.color}]}>{label.text}</Text>
      <Text accessibilityRole="header" numberOfLines={2} style={[chatType.bodyXlMedium(containsChinese(title)), styles.title]}>{title}</Text>
      <Text numberOfLines={6} style={[chatType.bodyLg(containsChinese(body)), styles.body]}>{body}</Text>
      {failed && <Text accessibilityLiveRegion="polite" style={[chatType.bodyMd(language === 'zh'), styles.failed]}>{t('taskAnswerFailed')}</Text>}
    </Animated.View>
    <View style={styles.actions}>
      {options.map((option, index) => {
        const chosen = sending === index;
        return <Pressable key={index} testID={`task-card-option-${threadId}-${index}`} accessibilityRole="button" accessibilityLabel={option.label}
          accessibilityState={{disabled: sending !== null, busy: chosen}} disabled={sending !== null} onPress={() => choose(option, index)}
          style={({pressed}) => [styles.pill, chosen && styles.pillChosen, sending !== null && !chosen && styles.pillIdle, pressed && styles.pressed]}>
          <Text numberOfLines={1} style={[chatType.bodyMdMedium(containsChinese(option.label)), styles.pillText, chosen && styles.pillTextChosen]}>{option.label}</Text>
        </Pressable>;
      })}
      <Pressable testID={`task-card-view-${threadId}`} accessibilityRole="button" accessibilityLabel={`${t('viewTask')}: ${title}`} onPress={onView}
        style={({pressed}) => [styles.pill, pressed && styles.pressed]}>
        <Text numberOfLines={1} style={[chatType.bodyMdMedium(language === 'zh'), styles.pillText]}>{t('viewTask')}</Text>
      </Pressable>
    </View>
  </View>;
}

const styles = themedStyles(() => StyleSheet.create({
  // Wireframe: a 24 dp rounded card across the stream, one hairline outline, 20 dp padding.
  card: {borderRadius: 24, borderWidth: StyleSheet.hairlineWidth * 2, borderColor: chatColors.cardOutline,
    backgroundColor: chatColors.assistantBubble, paddingHorizontal: 20, paddingTop: 20, paddingBottom: 22, gap: 18},
  text: {gap: 8},
  status: {fontSize: 13, lineHeight: 18},
  title: {color: chatColors.textPrimary, lineHeight: 24},
  body: {color: chatColors.textSecondary},
  failed: {color: chatColors.textError},
  actions: {flexDirection: 'row', flexWrap: 'wrap', gap: 8},
  pill: {height: 38, borderRadius: 19, paddingHorizontal: 14, alignItems: 'center', justifyContent: 'center', maxWidth: '100%',
    backgroundColor: chatColors.fillControl},
  pillChosen: {backgroundColor: chatColors.chosen},
  pillIdle: {opacity: 0.4},
  pillText: {color: chatColors.textPrimary},
  pillTextChosen: {color: chatColors.onChosen},
  pressed: {opacity: 0.7},
}));
