import {useEffect, useState} from 'react';
import {AccessibilityInfo} from 'react-native';
import {useReducedMotion} from 'react-native-reanimated';

export function useMenuReducedMotion() {
  const [reduced, setReduced] = useState(useReducedMotion());

  useEffect(() => {
    let awaitingInitialValue = true;
    const listener = AccessibilityInfo.addEventListener('reduceMotionChanged', value => {
      awaitingInitialValue = false;
      setReduced(value);
    });
    void AccessibilityInfo.isReduceMotionEnabled().then(value => {
      if (awaitingInitialValue) setReduced(value);
    }).catch(() => {});
    return () => {
      awaitingInitialValue = false;
      listener.remove();
    };
  }, []);

  return reduced;
}
