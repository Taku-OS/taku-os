import React, {memo, useEffect, useMemo, useState, type ReactNode} from 'react';
import {Animated, Easing, Pressable, StyleSheet, Text, View, useWindowDimensions} from 'react-native';
import type {StyleProp, TextLayoutEvent, ViewStyle} from 'react-native';
import {Icon} from '../components/Icon';
import {containsChinese} from '../theme';
import type {Thinking} from '../model/session';
import {chatColors, chatMetrics, chatMotion, chatType, themedStyles} from './chatTheme';
import {tailRuns, thinkingHeader, thinkingRuns, type ThinkingRun} from './thinkingText';
import {useMotionAllowed} from './useMotionAllowed';

/** The live window shows the last lines of the reasoning; that many characters always fill it. */
const LIVE_LINES = 3, LIVE_CHARACTERS = 600;

/** The device clock, read again each second while [ticking]. */
function useClock(ticking: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!ticking) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [ticking]);
  return now;
}

/** Fades its content in once, when it first shows. Opacity only: the layout is final at once. */
function Reveal({children, style}: {children: ReactNode; style?: StyleProp<ViewStyle>}) {
  const [opacity] = useState(() => new Animated.Value(0));
  useEffect(() => {
    Animated.timing(opacity, {toValue: 1, duration: chatMotion.colorMs, easing: Easing.out(Easing.quad), useNativeDriver: true, isInteraction: false}).start();
  }, [opacity]);
  return <Animated.View style={[style, {opacity}]}>{children}</Animated.View>;
}

/** A slow breath on the live label: the one sign of work when no text has arrived yet. */
function Breathing({moving, children, style}: {moving: boolean; children: ReactNode; style?: StyleProp<ViewStyle>}) {
  const [opacity] = useState(() => new Animated.Value(1));
  useEffect(() => {
    opacity.setValue(1);
    if (!moving) return;
    const step = (toValue: number) => Animated.timing(opacity, {toValue, duration: 900, easing: Easing.inOut(Easing.quad), useNativeDriver: true, isInteraction: false});
    const animation = Animated.loop(Animated.sequence([step(0.5), step(1)]));
    animation.start();
    return () => animation.stop();
  }, [opacity, moving]);
  return <Animated.View style={[style, {opacity}]}>{children}</Animated.View>;
}

function Runs({runs, chinese}: {runs: readonly ThinkingRun[]; chinese: boolean}) {
  const strong = chatType.bodyMdMedium(chinese).fontFamily;
  return <>{runs.map((run, index) => run.strong ? <Text key={index} style={{fontFamily: strong}}>{run.text}</Text> : run.text)}</>;
}

/**
 * The model's reasoning above its reply. While it is written: a quiet "Thinking…" line and the last
 * lines of the text, in a window that never grows past [LIVE_LINES]. Once the reply begins: one row,
 * "Thought for 21s", that opens to show the whole text again.
 */
export const ThinkingBlock = memo(function ThinkingBlock({thinking, language, expanded, onToggle}: {
  thinking: Thinking; language: 'en' | 'zh'; expanded: boolean; onToggle: () => void;
}) {
  const {width, fontScale} = useWindowDimensions();
  const moving = useMotionAllowed();
  const now = useClock(thinking.active && thinking.startedAtMs !== undefined);
  const header = thinkingHeader(thinking, language, now);
  const open = expanded && header.expandable;
  const chinese = language === 'zh' || containsChinese(thinking.text);
  const runs = useMemo(() => thinkingRuns(thinking.text), [thinking.text]);
  // The row that follows live reasoning fades in; one met already finished (history) is simply there.
  const [seenLive, setSeenLive] = useState(thinking.active);
  if (thinking.active && !seenLive) setSeenLive(true);
  // One measured line makes the window a whole number of lines at any font scale.
  const [measuredLine, setMeasuredLine] = useState(0);
  const lineHeight = measuredLine || (chatType.bodyMd(chinese).lineHeight ?? 20) * fontScale;
  const measure = (event: TextLayoutEvent) => {
    const height = event.nativeEvent.lines.at(-1)?.height ?? 0;
    if (height > 0 && Math.abs(height - measuredLine) > 0.5) setMeasuredLine(height);
  };
  const quiet = [chatType.bodyMd(chinese), styles.quiet];
  const row = <>
    <Text style={quiet}>{thinking.active ? `${header.label}…` : header.label}</Text>
    {!!header.elapsed && <Text style={[quiet, styles.elapsed]}>{header.elapsed}</Text>}
    {header.expandable && <Icon name={open ? 'chevron_down' : 'chevron_right'} size={14} color={chatColors.textTertiary}/>}
  </>;
  const label = header.elapsed ? `${header.label} ${header.elapsed}` : header.label;
  return <View testID={`thinking-${thinking.id}`} style={[styles.block, {maxWidth: Math.max(0, width - chatMetrics.side * 2 - chatMetrics.bubbleMinInset)}]}>
    {header.expandable
      ? <Pressable testID={`thinking-toggle-${thinking.id}`} accessibilityRole="button" accessibilityLabel={label} accessibilityHint={header.hint(open)}
        accessibilityState={{expanded: open}} onPress={onToggle} hitSlop={toggleSlop} style={({pressed}) => [styles.header, pressed && styles.pressed]}>
        {seenLive ? <Reveal style={styles.row}>{row}</Reveal> : <View style={styles.row}>{row}</View>}
      </Pressable>
      : <View accessible accessibilityRole="text" accessibilityLabel={label} accessibilityState={{busy: thinking.active}} style={styles.header}>
        {thinking.active ? <Breathing moving={moving} style={styles.row}>{row}</Breathing> : <View style={styles.row}>{row}</View>}
      </View>}
    {/* Read by a screen reader once finished, from the row above; the moving window is not announced. */}
    {thinking.active && runs.length > 0 && <View testID={`thinking-live-${thinking.id}`} accessibilityElementsHidden importantForAccessibility="no-hide-descendants"
      style={[styles.thought, styles.window, {maxHeight: LIVE_LINES * lineHeight}]}>
      <Text onTextLayout={measure} style={quiet}><Runs runs={tailRuns(runs, LIVE_CHARACTERS)} chinese={chinese}/></Text>
    </View>}
    {open && <Reveal style={styles.thought}>
      <Text testID={`thinking-text-${thinking.id}`} selectable style={quiet}><Runs runs={runs} chinese={chinese}/></Text>
    </Reveal>}
  </View>;
});

const toggleSlop = {top: 8, bottom: 8, left: 8, right: 24};

const styles = themedStyles(() => StyleSheet.create({
  block: {alignSelf: 'flex-start', gap: 4},
  header: {alignSelf: 'flex-start', borderRadius: 8}, pressed: {opacity: 0.6},
  row: {minHeight: 28, flexDirection: 'row', alignItems: 'center', gap: 6},
  quiet: {color: chatColors.textTertiary}, elapsed: {fontVariant: ['tabular-nums']},
  // A hairline at the left sets the text apart as the model's aside, not part of its reply.
  thought: {borderLeftWidth: 1.5, borderLeftColor: chatColors.hairline, paddingLeft: 10},
  // Anchored to its end: text taller than the window shows its last lines, never its first.
  window: {overflow: 'hidden', justifyContent: 'flex-end'},
}));
