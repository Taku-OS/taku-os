import React, {useLayoutEffect, useMemo, useRef, useState} from 'react';
import {StyleSheet, Text, View, type TextStyle} from 'react-native';
import Animated, {cancelAnimation, Easing, useAnimatedReaction, useAnimatedStyle, useSharedValue, withDelay, withTiming,
  type SharedValue} from 'react-native-reanimated';
import type {menuNumberLayout} from './menuNumberLayout';

const rollEase = Easing.bezier(0.45, 0, 0.18, 1);
const digitAdvance = [0.641, 0.418, 0.603, 0.604, 0.625, 0.599, 0.612, 0.564, 0.623, 0.612];
const absent = '—';

export type MenuNumberMotion = {duration: number; delay: number; carry: number; trail: number};
const priceMotion: MenuNumberMotion = {duration: 320, delay: 0, carry: 30, trail: 0.4};

type DigitPair = {from: string; to: string; direction: number; target: 0 | 1};

function digitWidth(char: string, size: number) {
  return char ? digitAdvance[Number(char)] * size : 0;
}

function DigitStrip({pair, position, trail, copy, strength, pitch, width, style}: {
  pair: DigitPair; position: SharedValue<number>; trail: SharedValue<number>; copy: number;
  strength: number; pitch: number; width: number; style: TextStyle;
}) {
  const moving = useAnimatedStyle(() => {
    const r = position.get();
    const length = trail.get();
    const motion = Math.min(1, Math.abs(length) / 0.16);
    return {
      transform: [{translateY: -(r - length * copy / 3) * pitch * pair.direction}],
      opacity: copy ? 0.34 * motion * (1.25 - copy * 0.25) : 1 - 0.42 * motion * strength,
    };
  });
  const textWidth = width + 4;
  return <Animated.View style={[styles.strip, {width: textWidth, marginLeft: -textWidth / 2}, moving]}>
    <Text numberOfLines={1} style={[style, styles.digitText, {height: pitch}]}>{pair.from}</Text>
    {pair.to !== pair.from && <Text numberOfLines={1} style={[style, styles.digitText, {height: pitch},
      {transform: [{translateY: pair.direction < 0 ? -2 * pitch : 0}]}]}>{pair.to}</Text>}
  </Animated.View>;
}

function DigitColumn({digit, direction, style, duration, delay, strength, reduceMotion}: {
  digit: string; direction: number; style: TextStyle; duration: number; delay: number; strength: number; reduceMotion: boolean;
}) {
  const [pair, setPair] = useState<DigitPair>({from: digit, to: digit, direction: 1, target: 0});
  const activeDigit = pair.target ? pair.to : pair.from;
  if (digit !== activeDigit) {
    if (reduceMotion) setPair({from: digit, to: digit, direction: 1, target: 0});
    else if (digit === (pair.target ? pair.from : pair.to)) setPair({...pair, target: pair.target ? 0 : 1});
    else setPair({from: activeDigit, to: digit, direction, target: 1});
  }

  const position = useSharedValue(0);
  const trail = useSharedValue(0);
  const timestamp = useSharedValue(0);
  const lastPair = useRef<DigitPair>(pair);
  useLayoutEffect(() => {
    const previous = lastPair.current;
    lastPair.current = pair;
    if (pair === previous && !reduceMotion) return;
    cancelAnimation(position);
    if (reduceMotion || pair.from === pair.to) {
      position.set(pair.target);
    } else {
      if (previous.from !== pair.from || previous.to !== pair.to) position.set(0);
      const animation = withTiming(pair.target, {duration, easing: rollEase});
      position.set(delay ? withDelay(delay, animation) : animation);
    }
  }, [delay, duration, pair, position, reduceMotion]);

  useAnimatedReaction(() => position.get(), (current, previous) => {
    const now = performance.now();
    const elapsed = now - timestamp.get();
    timestamp.set(now);
    if (reduceMotion || strength === 0 || previous === null || current === 0 || current === 1) {
      trail.set(0);
      return;
    }
    trail.set((current - previous) * 34 / Math.max(1, elapsed) * strength);
  });

  const size = style.fontSize ?? 16;
  const pitch = style.lineHeight ?? 24;
  const fromWidth = digitWidth(pair.from, size);
  const toWidth = digitWidth(pair.to, size);
  const width = Math.max(fromWidth, toWidth);
  const animatedWidth = useAnimatedStyle(() => {
    const progress = position.get();
    return {width: !fromWidth || !toWidth ? fromWidth + (toWidth - fromWidth) * progress : width};
  });
  const copies = pair.from !== pair.to && strength > 0 && !reduceMotion ? [3, 2, 1, 0] : [0];
  return <Animated.View style={[styles.digit, {height: pitch}, animatedWidth]}>
    {copies.map(copy => <DigitStrip key={copy} pair={pair} position={position} trail={trail} copy={copy}
      strength={strength} pitch={pitch} width={width} style={style}/>)}
  </Animated.View>;
}

export function MenuRollingNumber({value, layout, style, unit, unitStyle = style, direction, motion = priceMotion,
  reduceMotion = false, estimateLabel}: {
  value: string; layout: ReturnType<typeof menuNumberLayout>; style: TextStyle; unit?: string; unitStyle?: TextStyle;
  direction: number; motion?: MenuNumberMotion; reduceMotion?: boolean; estimateLabel?: string;
}) {
  const current = layout.values.get(value);
  const hasNumber = Boolean(current?.size);
  const cellStyle = useMemo(() => ({...style, fontFamily: 'TakuDisplay', letterSpacing: 0}), [style]);
  const label = hasNumber && unit ? value + ' ' + unit : value || absent;

  if (!hasNumber && !layout.columns.length) return <Text style={style}>{label}</Text>;

  return <View accessible accessibilityLabel={label} accessibilityHint={estimateLabel} style={styles.row}>
    <View accessibilityElementsHidden importantForAccessibility="no-hide-descendants" style={styles.row}>
      {layout.columns.map(({key, digit, carry}) => {
        const char = current?.get(key)?.text ?? '';
        return digit ? <DigitColumn key={key} digit={char} direction={direction} style={cellStyle}
          delay={motion.delay + carry * motion.carry} duration={motion.duration} strength={motion.trail}
          reduceMotion={reduceMotion}/> : <Text key={key} style={style}>{char}</Text>;
      })}
      {!hasNumber && <Text style={style}>{value || absent}</Text>}
    </View>
    {hasNumber && unit && <Text accessibilityElementsHidden importantForAccessibility="no" style={unitStyle}>{' ' + unit}</Text>}
  </View>;
}

const styles = StyleSheet.create({
  row: {flexDirection: 'row', alignItems: 'center', flexShrink: 0},
  digit: {overflow: 'hidden', position: 'relative'},
  strip: {position: 'absolute', top: 0, left: '50%'},
  digitText: {textAlign: 'center'},
});
