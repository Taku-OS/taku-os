import React, {useEffect, useState} from 'react';
import {AccessibilityInfo, Animated, AppState, Easing, StyleSheet, View} from 'react-native';
import {chatColors} from '../chat/chatTheme';

/** A native-driven pulse changes only opacity/transform, never the reply's layout. */
export function ReplyLoading({label}: {label: string}) {
  const [phase] = useState(() => new Animated.Value(0));
  const [reduceMotion, setReduceMotion] = useState(false);
  const [foreground, setForeground] = useState(AppState.currentState === 'active');
  useEffect(() => {
    let mounted = true;
    void AccessibilityInfo.isReduceMotionEnabled().then(value => {if (mounted) setReduceMotion(value);}).catch(() => {});
    const motion = AccessibilityInfo.addEventListener('reduceMotionChanged', setReduceMotion);
    const lifecycle = AppState.addEventListener('change', state => setForeground(state === 'active'));
    return () => {mounted = false; motion.remove(); lifecycle.remove();};
  }, []);
  useEffect(() => {
    phase.setValue(0);
    if (reduceMotion || !foreground) return;
    const animation = Animated.loop(Animated.timing(phase, {
      toValue: 1, duration: 1200, easing: Easing.linear, useNativeDriver: true, isInteraction: false,
    }));
    animation.start();
    return () => animation.stop();
  }, [phase, reduceMotion, foreground]);
  return <View testID="reply-loading" accessible accessibilityRole="progressbar" accessibilityLabel={label}
    accessibilityState={{busy: true}} style={styles.row}>
    {[0, 1, 2].map(index => {
      const start = 0.08 + index * 0.14;
      const inputRange = [0, start, start + 0.16, start + 0.32, 1];
      return <Animated.View key={index} style={[styles.dot, {
        opacity: phase.interpolate({inputRange, outputRange: [0.4, 0.4, 1, 0.4, 0.4]}),
        transform: [{translateY: phase.interpolate({inputRange, outputRange: [0, 0, -3, 0, 0]})}],
      }]}/>;
    })}
  </View>;
}

const styles = StyleSheet.create({
  row: {height: '100%', minHeight: 26, flexDirection: 'row', alignItems: 'center', gap: 5},
  dot: {width: 5, height: 5, borderRadius: 2.5, backgroundColor: chatColors.textSecondary},
});
