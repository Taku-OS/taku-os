import React, {useCallback, useContext, useLayoutEffect, useRef, useState} from 'react';
import {ActivityIndicator, Image, PixelRatio, Pressable, StyleSheet, Text, View} from 'react-native';
import {GestureDetector, GestureHandlerRootView, usePanGesture} from 'react-native-gesture-handler';
import Animated, {
  cancelAnimation, Easing, ReduceMotion, useAnimatedReaction, useAnimatedStyle, useSharedValue, withDelay, withTiming, type SharedValue,
} from 'react-native-reanimated';
import {scheduleOnRN, scheduleOnUI} from 'react-native-worklets';
import {chatText} from '../chat/chatText';
import {chatColors, chatType} from '../chat/chatTheme';
import {
  MenuDishMotionContext, menuEaseInOut, menuEaseOut, menuEaseStandard, menuMotionStage as stage,
} from '../hooks/useMenuDishReveal';
import type {Language} from '../i18n';
import type {MenuDish} from '../model/menuScan';
import type {MenuDishArtwork} from '../native/agent';
import {Icon} from './Icon';

export function menuDishHeroLayout(width: number, height: number, insetTop: number) {
  const scale = width / 412;
  const panelTop = PixelRatio.roundToNearestPixel(height * 336 / 917);
  const closeTop = Math.max(insetTop, panelTop - 274 * scale);
  const plateTop = Math.min(panelTop, Math.max(insetTop + 44, panelTop - 240 * scale));
  const size = PixelRatio.roundToNearestPixel(Math.max(0, panelTop - plateTop));
  return {
    scale, panelTop, closeTop, pitch: 234.95 * scale,
    plate: {left: (width - size) / 2, top: plateTop, width: size, height: size},
  };
}

type MenuDishHeroLayout = ReturnType<typeof menuDishHeroLayout>;

function MenuDishImage({artwork, dish, width, height, language, onRetry}: {
  artwork?: MenuDishArtwork; dish: MenuDish; width: number; height: number; language: Language; onRetry?: () => void;
}) {
  const [failedUri, setFailedUri] = useState<string>();
  const uri = artwork?.state === 'ready' ? artwork.uri : undefined;
  if (uri && failedUri !== uri) return <Image source={{uri}} accessible={false}
    resizeMode="contain" fadeDuration={0} style={{width, height}} onError={() => setFailedUri(uri)}/>;
  if (!onRetry) return null;

  const ct = chatText(language);
  const textStyle = chatType.bodySmMedium(language === 'zh');
  const unavailable = artwork?.state === 'unavailable' || artwork?.state === 'ready';
  return <View style={[styles.artworkStatus, {width, height}]} pointerEvents="box-none">
    {unavailable ? <Pressable testID={`menu-retry-artwork-${dish.id}`} accessibilityRole="button"
      style={styles.retry} onPress={() => {setFailedUri(undefined); onRetry();}}>
      <Text style={textStyle}>{ct('menuDishArtworkRetry')}</Text>
    </Pressable> : <>
      <ActivityIndicator accessible={false} size="small" color={chatColors.textSecondary}/>
      <Text accessibilityLiveRegion="polite" style={textStyle}>{ct('menuDishArtworkLoading')}</Text>
    </>}
  </View>;
}

const flightEase = Easing.bezierFn(0.24, 0.1, 0.1, 1);
const scaleEase = Easing.bezierFn(0.22, 0.12, 0.08, 1);
const tiltEase = Easing.bezierFn(0.3, 0, 0.2, 1);
const plateReturnEase = Easing.bezierFn(0.55, 0, 0.2, 1);
const shrinkEase = Easing.bezierFn(0.5, 0, 0.4, 1);
const sideExitEase = Easing.bezierFn(0.4, 0, 1, 1);
const releaseDuration = 520;
const releaseStiffness = 0.02;
const flickVelocity = 0.0012;
const commitBand = 0.02;

function lerp(from: number, to: number, progress: number) {
  'worklet';
  return from + (to - from) * progress;
}

function settle(progress: number, overshoot: number) {
  'worklet';
  return overshoot * 45.5625 * progress ** 4 * (1 - progress) ** 2;
}

function plateArc(fromX: number, fromY: number, toX: number, toY: number, progress: number) {
  'worklet';
  const bow = 0.24 * progress * (1 - progress);
  return {
    x: lerp(fromX, toX, progress) + (toY - fromY) * bow,
    y: lerp(fromY, toY, progress) - (toX - fromX) * bow,
  };
}

function rubberBand(position: number, last: number) {
  'worklet';
  const bounded = Math.min(last, Math.max(0, position));
  return bounded + (position - bounded) * 0.35;
}

function releasePlate(position: SharedValue<number>, target: number, velocity: number, reduceMotion: boolean) {
  'worklet';
  const distance = position.get() - target;
  if (reduceMotion || Math.abs(distance) < 0.0001) {
    position.set(target);
    return;
  }
  // The critical damping curve starts at the finger's displacement and velocity.
  const slope = velocity + releaseStiffness * distance;
  position.set(withTiming(target, {
    duration: releaseDuration,
    easing: progress => {
      'worklet';
      if (progress >= 1) return 1;
      const elapsed = releaseDuration * progress;
      return 1 - (1 + slope / distance * elapsed) * Math.exp(-releaseStiffness * elapsed);
    },
    reduceMotion: ReduceMotion.Never,
  }));
}

function CarouselPlate({index, active, first, layout, position, children}: {
  index: number;
  active: boolean;
  first: boolean;
  layout: MenuDishHeroLayout;
  position: SharedValue<number>;
  children: React.ReactNode;
}) {
  const motion = useContext(MenuDishMotionContext);
  const {openingTime, closingTime, marker, phase = 'open', reduceMotion = false} = motion ?? {};
  const {plate, pitch, scale} = layout;
  const centerX = plate.left + plate.width / 2;
  const centerY = plate.top + plate.height / 2;
  const animated = useAnimatedStyle(() => {
    const opening = openingTime?.get() ?? 780;
    const closing = closingTime?.get() ?? -1;
    const sideIn = menuEaseOut(stage(opening, 140, 700));
    const sideOut = sideExitEase(stage(closing, 0, 320));
    const focused = phase === 'closing' ? active : first;
    const spread = reduceMotion || focused ? 1 : 1 + 0.55 * (1 - sideIn) + 0.45 * sideOut;
    const offset = (index - position.get()) * spread;
    const squared = offset * offset;
    const slotX = centerX + pitch * offset;
    const slotY = centerY + 21 * scale * squared;
    const slotScale = 1 / (1 + 0.5 * squared);
    const slotRoll = 16 * offset;
    let x = slotX;
    let y = slotY;
    let size = slotScale;
    let roll = slotRoll;
    let pitchAngle = 0;
    let opacity = reduceMotion ? Number(phase !== 'closing') : stage(opening, 140, 420) * (1 - sideOut);

    if (!reduceMotion && first && opening < 780) {
      opacity = stage(opening, -20, 50);
      if (marker) {
        const travel = plateArc(marker.x, marker.y, slotX, slotY, flightEase(stage(opening, -20, 580)));
        const growth = stage(opening, -20, 700);
        const tilt = stage(opening, -20, 640);
        const markerScale = marker.diameter / Math.max(1, plate.width);
        x = travel.x;
        y = travel.y;
        size = lerp(markerScale, slotScale, scaleEase(growth) + settle(growth, 0.075));
        roll -= 5 * (1 - tiltEase(tilt) - settle(tilt, 0.08));
        pitchAngle = -13 * (1 - tiltEase(stage(opening, -20, 580)));
      }
    }
    if (!reduceMotion && phase === 'closing' && active) {
      opacity = (opening < 780 && first ? opacity : 1) * (1 - stage(closing, marker ? 470 : 0, marker ? 600 : 300));
      if (marker) {
        const travel = plateArc(marker.x, marker.y, x, y, 1 - plateReturnEase(stage(closing, 120, 600)));
        const shrink = stage(closing, 40, 580);
        x = travel.x;
        y = travel.y;
        size *= lerp(1, 0.1, shrinkEase(shrink));
        roll -= 10 * menuEaseInOut(shrink);
      }
    }

    return {
      opacity,
      zIndex: 1000 - Math.round(Math.abs(offset) * 100),
      transform: [
        {translateX: x - centerX}, {translateY: y - centerY}, {perspective: plate.width * 4},
        {rotate: `${roll}deg`}, {rotateX: `${pitchAngle}deg`}, {scale: size},
      ],
    };
  });
  return <Animated.View pointerEvents={active ? 'box-none' : 'none'} accessibilityElementsHidden={!active}
    importantForAccessibility={active ? 'auto' : 'no-hide-descendants'} style={[styles.plate, plate, animated]}>
    {children}
  </Animated.View>;
}

export function MenuDishCarousel({artwork, dishes, selectedIndex, language, layout, onSelect, onDismiss, onRetryArtwork, swipeVelocity}: {
  artwork?: Readonly<Record<string, MenuDishArtwork>>; dishes: readonly MenuDish[]; selectedIndex: number; language: Language;
  layout: MenuDishHeroLayout; onSelect: (id: string) => boolean; onDismiss: () => void; onRetryArtwork?: (dishId: string) => void;
  swipeVelocity: SharedValue<number>;
}) {
  const motion = useContext(MenuDishMotionContext);
  const {openingTime, closingTime, phase = 'open', reduceMotion = false} = motion ?? {};
  const [initialIndex] = useState(selectedIndex);
  const selected = useRef(selectedIndex);
  const interactive = useRef(phase === 'open');
  const dragStart = useSharedValue(selectedIndex);
  const dragging = useSharedValue(false);
  const position = useSharedValue(selectedIndex);
  const committed = useSharedValue(selectedIndex);
  const commitEnabled = useSharedValue(false);
  const lastPositionAt = useSharedValue(0);
  const ct = chatText(language);
  const last = dishes.length - 1;
  const {panelTop, closeTop, pitch, plate: {top, width, height}} = layout;

  const selectDish = useCallback((index: number) => {
    if (index === selected.current) return true;
    const next = dishes[index];
    if (!next || !onSelect(next.id)) return false;
    selected.current = index;
    return true;
  }, [dishes, onSelect]);

  const applyMidpoint = useCallback((index: number) => {
    if (!interactive.current || selectDish(index)) return;
    const accepted = selected.current;
    scheduleOnUI(() => {
      if (committed.get() !== index) return;
      commitEnabled.set(false);
      dragging.set(false);
      committed.set(accepted);
      releasePlate(position, accepted, 0, reduceMotion);
    });
  }, [commitEnabled, committed, dragging, position, reduceMotion, selectDish]);

  useAnimatedReaction(() => position.get(), (next, previous) => {
    const now = performance.now();
    const elapsed = now - lastPositionAt.get();
    lastPositionAt.set(now);
    if (!reduceMotion && previous !== null && elapsed > 0) {
      swipeVelocity.set((next - previous) / elapsed);
      swipeVelocity.set(withDelay(24, withTiming(0, {duration: 105, easing: Easing.out(Easing.cubic)})));
    }
    if (!commitEnabled.get()) return;
    const current = committed.get();
    if (Math.abs(next - current) <= 0.5 + commitBand) return;
    const index = Math.min(last, Math.max(0, Math.round(next)));
    if (index !== current) {
      committed.set(index);
      scheduleOnRN(applyMidpoint, index);
    }
  });

  const pan = usePanGesture({
    enabled: phase === 'open' && last > 0 && pitch > 0,
    activeOffsetX: [-8, 8],
    failOffsetY: [-14, 14],
    maxPointers: 1,
    onActivate: () => {
      'worklet';
      cancelAnimation(position);
      dragging.set(true);
      commitEnabled.set(true);
      dragStart.set(position.get());
    },
    onUpdate: event => {
      'worklet';
      if (dragging.get()) position.set(rubberBand(dragStart.get() - event.translationX / pitch, last));
    },
    onDeactivate: event => {
      'worklet';
      if (!dragging.get()) return;
      dragging.set(false);
      const velocity = event.canceled ? 0 : -event.velocityX / pitch / 1000;
      const current = position.get();
      const target = velocity > flickVelocity ? Math.ceil(current)
        : velocity < -flickVelocity ? Math.floor(current) : Math.round(current);
      releasePlate(position, Math.min(last, Math.max(0, target)), velocity, reduceMotion);
    },
  });

  useLayoutEffect(() => {
    interactive.current = phase === 'open';
    if (phase === 'closing') scheduleOnUI(() => {
      commitEnabled.set(false);
      dragging.set(false);
      cancelAnimation(position);
    });
    return () => { interactive.current = false; swipeVelocity.set(0); };
  }, [commitEnabled, dragging, phase, position, swipeVelocity]);

  useLayoutEffect(() => {
    if (selected.current === selectedIndex) return;
    selected.current = selectedIndex;
    scheduleOnUI(() => {
      commitEnabled.set(false);
      dragging.set(false);
      committed.set(selectedIndex);
      releasePlate(position, selectedIndex, 0, reduceMotion);
    });
  }, [commitEnabled, committed, dragging, position, reduceMotion, selectedIndex]);

  const selectNeighbor = (index: number) => {
    if (!selectDish(index)) return;
    scheduleOnUI(() => {
      commitEnabled.set(false);
      dragging.set(false);
      committed.set(index);
      releasePlate(position, index, 0, reduceMotion);
    });
  };

  const controls = useAnimatedStyle(() => ({
    opacity: reduceMotion ? Number(phase === 'open')
      : menuEaseOut(stage(openingTime?.get() ?? 780, 340, 580)) *
        (1 - menuEaseStandard(stage(closingTime?.get() ?? -1, 0, 160))),
  }));

  return <GestureHandlerRootView pointerEvents="box-none" style={[styles.strip, {height: panelTop}]}>
    <GestureDetector gesture={pan}>
      <Animated.View testID="menu-dish-carousel" accessible={false}
        style={[styles.strip, {height: panelTop}]} pointerEvents={phase === 'open' ? 'auto' : 'none'} collapsable={false}>
        {width > 0 && dishes.map((dish, index) => (
          <CarouselPlate key={dish.id} index={index} first={index === initialIndex} active={index === selectedIndex}
            layout={layout} position={position}>
            {Math.abs(index - selectedIndex) <= 2 && <MenuDishImage artwork={artwork?.[dish.id]} dish={dish}
              width={width} height={height} language={language}
              onRetry={onRetryArtwork && index === selectedIndex ? () => onRetryArtwork(dish.id) : undefined}/>}
          </CarouselPlate>
        ))}
      </Animated.View>
    </GestureDetector>
    <Animated.View pointerEvents="box-none" style={[StyleSheet.absoluteFill, controls]}>
      {([-1, 1] as const).map(direction => {
        const index = selectedIndex + direction;
        const neighbor = dishes[index];
        if (!neighbor) return null;
        const previous = direction < 0;
        return <Pressable key={direction} testID={previous ? 'menu-previous-dish' : 'menu-next-dish'} accessibilityRole="button"
          accessibilityLabel={`${ct(previous ? 'menuPreviousDish' : 'menuNextDish')}, ${neighbor.name}`}
          onPress={() => selectNeighbor(index)} style={[styles.neighborButton, {top: top + height / 2 - 22}, previous ? styles.left : styles.right]}>
          <View style={[styles.control, styles.neighborControl, previous && styles.reverse]}><Icon name="chevron_right" size={16}/></View>
        </Pressable>;
      })}
      <Pressable testID="menu-close-sheet" accessibilityRole="button" accessibilityLabel={ct('menuBack')}
        onPress={onDismiss} style={[styles.close, {top: closeTop}]}>
        <View style={styles.control}><Icon name="close" size={16}/></View>
      </Pressable>
    </Animated.View>
  </GestureHandlerRootView>;
}

const styles = StyleSheet.create({
  strip: {position: 'absolute', left: 0, right: 0, top: 0},
  plate: {position: 'absolute'},
  artworkStatus: {alignItems: 'center', justifyContent: 'center', gap: 8},
  retry: {minHeight: 44, justifyContent: 'center', backgroundColor: chatColors.surfaceRaised, borderRadius: 22, paddingHorizontal: 14},
  neighborButton: {position: 'absolute', width: 44, height: 44, alignItems: 'center', justifyContent: 'center'},
  left: {left: 0},
  right: {right: 0},
  reverse: {transform: [{scaleX: -1}]},
  close: {position: 'absolute', right: 4, width: 44, height: 44, alignItems: 'center', justifyContent: 'center'},
  control: {width: 34, height: 34, borderRadius: 17, alignItems: 'center', justifyContent: 'center', backgroundColor: chatColors.chip},
  neighborControl: {width: 26, height: 26, backgroundColor: chatColors.surfaceGlass},
});
