import {createContext, useContext} from 'react';
import {Easing, useAnimatedStyle, type SharedValue} from 'react-native-reanimated';

type MenuDishMotion = {
  openingTime: SharedValue<number>;
  closingTime: SharedValue<number>;
  marker?: {x: number; y: number; diameter: number};
  phase: 'opening' | 'open' | 'closing';
  scale: number;
  reduceMotion: boolean;
};

export const MenuDishMotionContext = createContext<MenuDishMotion | undefined>(undefined);
export const menuEaseOut = Easing.bezierFn(0.22, 1, 0.36, 1);
export const menuEaseInOut = Easing.bezierFn(0.65, 0, 0.35, 1);
export const menuEaseStandard = Easing.bezierFn(0.4, 0, 0.2, 1);

export function menuMotionStage(time: number, start: number, end: number) {
  'worklet';
  return Math.min(1, Math.max(0, (time - start) / (end - start)));
}

const groupStart = {title: 120, nutrition: 160, social: 210, lower: 260};

export function useMenuDishReveal(group: keyof typeof groupStart) {
  const motion = useContext(MenuDishMotionContext);
  const {openingTime, closingTime, scale = 1, reduceMotion = false} = motion ?? {};
  const start = groupStart[group];
  return useAnimatedStyle(() => {
    const shown = !openingTime || reduceMotion ? 1 : menuEaseOut(menuMotionStage(openingTime.get(), start, start + 260));
    const exiting = closingTime ? menuEaseStandard(menuMotionStage(closingTime.get(), 0, 140)) : 0;
    const opacity = shown * (1 - exiting);
    return {opacity, transform: [{translateY: reduceMotion ? 0 : (1 - opacity) * 8 * scale}]};
  });
}
