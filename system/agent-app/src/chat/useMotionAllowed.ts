import {useEffect, useState} from 'react';
import {AccessibilityInfo, AppState} from 'react-native';

/** Looping motion runs only in front and only when the user has not asked the system to reduce it. */
export function useMotionAllowed(): boolean {
  const [reduceMotion, setReduceMotion] = useState(false);
  const [foreground, setForeground] = useState(AppState.currentState === 'active');
  useEffect(() => {
    let mounted = true;
    void AccessibilityInfo.isReduceMotionEnabled().then(value => {if (mounted) setReduceMotion(value);}).catch(() => {});
    const motion = AccessibilityInfo.addEventListener('reduceMotionChanged', setReduceMotion);
    const lifecycle = AppState.addEventListener('change', state => setForeground(state === 'active'));
    return () => {mounted = false; motion.remove(); lifecycle.remove();};
  }, []);
  return foreground && !reduceMotion;
}
