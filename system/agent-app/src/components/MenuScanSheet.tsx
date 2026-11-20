import React, {useCallback, useLayoutEffect, useMemo, useRef, useState, type ReactNode} from 'react';
import {Pressable, ScrollView, StyleSheet, View, type LayoutRectangle, type ScrollViewInstance} from 'react-native';
import Animated, {
  cancelAnimation, Easing, FadeIn, FadeInDown, FadeOut, FadeOutDown, ReduceMotion,
  useAnimatedStyle, useSharedValue, withTiming,
} from 'react-native-reanimated';
import {scheduleOnRN} from 'react-native-worklets';
import {
  MenuDishMotionContext, menuEaseInOut, menuEaseOut, menuEaseStandard, menuMotionStage as stage, useMenuDishReveal,
} from '../hooks/useMenuDishReveal';
import {useMenuReducedMotion} from '../hooks/useMenuReducedMotion';
import {menuDishHeroLayout} from './MenuDishCarousel';

export type MenuSheetAnchor = {left: number; top: number; width: number; height: number};
export type MenuDishLayer = {
  phase: 'opening' | 'open' | 'closing';
  anchor?: MenuSheetAnchor;
  dishId?: string;
};

type DishContent = {
  anchor?: MenuSheetAnchor;
  dishId?: string;
  hero?: (layout: ReturnType<typeof menuDishHeroLayout>) => ReactNode;
  dishActions?: ReactNode;
  children: ReactNode;
};

type SheetInsets = {top: number; bottom: number};
const noInsets: SheetInsets = {top: 0, bottom: 0};
const sheetPadding = 16;
const dimIn = FadeIn.duration(260).reduceMotion(ReduceMotion.System);
const dimOut = FadeOut.duration(240).reduceMotion(ReduceMotion.System);
const sheetIn = FadeInDown.duration(260).easing(Easing.bezier(0.22, 0.69, 0.26, 1))
  .withInitialValues({opacity: 0.38, transform: [{translateY: 20}]}).reduceMotion(ReduceMotion.System);
const sheetOut = FadeOutDown.duration(335).easing(Easing.inOut(Easing.cubic))
  .withTargetValues({transform: [{translateY: 20}]}).reduceMotion(ReduceMotion.System);
const riseEase = Easing.bezierFn(0.3, 0, 0.1, 1);
const closeBottomEase = Easing.bezierFn(0.5, 0, 0.3, 1);
const drawDuration = 780;
const returnDuration = 600;

function DishActions({children}: {children: ReactNode}) {
  const reveal = useMenuDishReveal('lower');
  return <Animated.View style={reveal}>{children}</Animated.View>;
}

function DishSheet({visible, anchor, dishId, hero, dishActions, children,
  insets, onDismiss, onExit, onLayerChange}: DishContent & {
  visible: boolean;
  insets: SheetInsets;
  onDismiss: () => void;
  onExit: () => void;
  onLayerChange?: (layer: MenuDishLayer | undefined) => void;
}) {
  const [viewport, setViewport] = useState<LayoutRectangle>();
  const [bodyHeight, setBodyHeight] = useState<number>();
  const [actionHeight, setActionHeight] = useState(0);
  const scroll = useRef<ScrollViewInstance>(null);
  const reduceMotion = useMenuReducedMotion();
  const [opening, setOpening] = useState({visible, completed: false});
  const openingTime = useSharedValue(reduceMotion ? drawDuration : 0);
  const closingTime = useSharedValue(-1);
  const panelHeight = useSharedValue(0);
  const ready = Boolean(viewport && viewport.width > 0 && viewport.height > 0);
  if (opening.visible !== visible) setOpening({visible, completed: false});
  const opened = reduceMotion || (opening.visible === visible && opening.completed);
  const phase: MenuDishLayer['phase'] = visible ? opened ? 'open' : 'opening' : 'closing';

  const viewportWidth = viewport?.width ?? 0;
  const viewportHeight = viewport?.height ?? 0;
  const layout = menuDishHeroLayout(viewportWidth, viewportHeight, insets.top);
  const {panelTop, scale} = layout;
  const panelMargin = 8 * scale;
  const panelWidth = Math.max(0, viewportWidth - panelMargin * 2);
  const maxPanelHeight = Math.max(0, viewportHeight - panelTop - insets.bottom - panelMargin);
  const targetHeight = bodyHeight === undefined || !actionHeight
    ? maxPanelHeight : Math.min(maxPanelHeight, bodyHeight + actionHeight);
  const marker = useMemo(() => {
    if (!anchor || !ready || anchor.left + anchor.width < 0 || anchor.left > viewportWidth
      || anchor.top + anchor.height < 0 || anchor.top > viewportHeight) return undefined;
    const right = anchor.left + anchor.width;
    return {
      x: Math.min(viewportWidth - 4, Math.max(4, right + 10 > viewportWidth ? anchor.left - 5 : right + 5)),
      y: Math.min(viewportHeight - 4, Math.max(4, anchor.top + anchor.height / 2)),
      diameter: 8,
    };
  }, [anchor, ready, viewportHeight, viewportWidth]);
  const motion = useMemo(() => ({openingTime, closingTime, marker, phase, scale, reduceMotion}),
    [openingTime, closingTime, marker, phase, scale, reduceMotion]);

  useLayoutEffect(() => {
    onLayerChange?.({phase, anchor, dishId});
  }, [anchor, dishId, onLayerChange, phase]);

  useLayoutEffect(() => {
    scroll.current?.scrollTo({y: 0, animated: false});
  }, [dishId]);

  useLayoutEffect(() => {
    if (!visible) {
      cancelAnimation(panelHeight);
    } else if (!opened || reduceMotion) {
      panelHeight.set(targetHeight);
    } else {
      panelHeight.set(withTiming(targetHeight, {duration: 250, easing: Easing.bezier(0.22, 1, 0.36, 1)}));
    }
  }, [opened, panelHeight, reduceMotion, targetHeight, visible]);

  useLayoutEffect(() => {
    if (!ready && visible && !reduceMotion) return;
    let active = true;
    const finish = () => {
      if (!active) return;
      if (visible) setOpening({visible, completed: true});
      else onExit();
    };
    const time = visible ? openingTime : closingTime;
    const duration = visible ? drawDuration : returnDuration;
    if (visible) closingTime.set(-1);
    time.set(0);
    time.set(withTiming(duration, {
      duration, easing: Easing.linear,
      reduceMotion: reduceMotion ? ReduceMotion.Always : ReduceMotion.Never,
    }, finished => {
      if (finished) scheduleOnRN(finish);
    }));
    return () => {
      active = false;
      cancelAnimation(time);
    };
  }, [closingTime, onExit, openingTime, ready, reduceMotion, visible]);

  const backdrop = useAnimatedStyle(() => ({
    opacity: reduceMotion ? Number(phase !== 'closing')
      : menuEaseInOut(stage(openingTime.get(), -40, 640)) * (1 - menuEaseInOut(stage(closingTime.get(), 60, 600))),
  }));
  const ripple = useAnimatedStyle(() => {
    const time = openingTime.get();
    const age = stage(time, 0, 360);
    return {
      opacity: 0.55 * menuEaseOut(stage(age, 0, 0.08)) * (1 - age) ** 1.6 * Number(closingTime.get() < 0),
      transform: [{scale: 1 + 23 * (1 - (1 - age) ** 2.2)}],
    };
  });
  const panelRise = useAnimatedStyle(() => {
    const age = stage(openingTime.get(), 120, 720);
    const rise = reduceMotion ? 1 : riseEase(age) + 0.025 * 45.5625 * age ** 4 * (1 - age) ** 2;
    return {height: panelHeight.get(), transform: [{translateY: 56 * scale * (1 - rise)}, {scale: 0.92 + 0.08 * rise}]};
  });
  const clip = useAnimatedStyle(() => {
    const exit = closingTime.get();
    const bottom = closeBottomEase(stage(exit, 30, 470));
    const top = menuEaseInOut(stage(exit, 0, 300));
    const sides = menuEaseInOut(stage(exit, 80, 470));
    const inset = Math.max(0, panelWidth / 2 - 56 * scale) * sides;
    const liftTop = -20 * scale * top;
    const height = panelHeight.get();
    const liftBottom = Math.max(0, height - 4 * scale) * bottom;
    const radius = (24 - 12 * bottom) * scale;
    return {
      left: inset,
      right: inset,
      top: liftTop,
      bottom: liftBottom,
      borderRadius: Math.max(0, Math.min(radius, (height - liftTop - liftBottom) / 2, panelWidth / 2 - inset)),
    };
  });
  const restingContent = useAnimatedStyle(() => {
    const exit = closingTime.get();
    return {
      height: panelHeight.get(),
      left: -Math.max(0, panelWidth / 2 - 56 * scale) * menuEaseInOut(stage(exit, 80, 470)),
      top: 20 * scale * menuEaseInOut(stage(exit, 0, 300)),
    };
  });
  const surface = useAnimatedStyle(() => {
    const enter = openingTime.get();
    const exit = closingTime.get();
    return {
      backgroundColor: `rgba(255, 255, 255, ${0.92 - 0.3 * menuEaseInOut(stage(exit, 40, 380))})`,
      opacity: reduceMotion ? Number(phase !== 'closing')
        : menuEaseOut(stage(enter, 120, 230)) * (1 - menuEaseStandard(stage(exit, 180, 440))),
    };
  });

  const interactive = phase === 'open';
  const ringSize = 10 * scale;
  return <MenuDishMotionContext value={motion}>
    <View style={styles.dishLayer} onLayout={event => setViewport(event.nativeEvent.layout)}
      accessibilityViewIsModal accessibilityElementsHidden={!interactive}
      importantForAccessibility={interactive ? 'auto' : 'no-hide-descendants'}>
      <Animated.View style={[styles.dishCanvas, backdrop]} pointerEvents="none"/>
      <Pressable accessible={false} style={StyleSheet.absoluteFill} onPress={visible ? onDismiss : undefined}/>
      {marker && !reduceMotion && <Animated.View pointerEvents="none" style={[styles.ripple,
        {left: marker.x - ringSize / 2, top: marker.y - ringSize / 2, width: ringSize, height: ringSize, borderRadius: ringSize / 2}, ripple]}/>}
      <View pointerEvents={interactive ? 'box-none' : 'none'} style={[styles.dishContent, {paddingBottom: insets.bottom}]}>
        <View pointerEvents="box-none" style={{height: panelTop}}/>
        <Animated.View style={[styles.dishPanel, {marginHorizontal: panelMargin, marginBottom: panelMargin}, panelRise]} pointerEvents="box-none">
          <Animated.View style={[styles.panelClip, clip]}>
            <Animated.View pointerEvents="none" style={[StyleSheet.absoluteFill, surface]}/>
            <Animated.View style={[styles.panelContent, {width: panelWidth}, restingContent]}>
              <ScrollView testID="menu-dish-body" ref={scroll} style={styles.dishBody} nestedScrollEnabled directionalLockEnabled
                onContentSizeChange={(_, height) => setBodyHeight(height)}>
                {children}
              </ScrollView>
              <View onLayout={event => setActionHeight(event.nativeEvent.layout.height)}>
                <DishActions>{dishActions}</DishActions>
              </View>
            </Animated.View>
          </Animated.View>
        </Animated.View>
        {ready && hero?.(layout)}
      </View>
    </View>
  </MenuDishMotionContext>;
}

export function MenuScanSheet({visible, surfaceKey, anchor, dishId, hero, dishActions,
  insets = noInsets, onDismiss, onDishLayerChange, children}: DishContent & {
  visible: boolean;
  surfaceKey: 'dish' | 'order' | 'staff';
  insets?: SheetInsets;
  onDismiss: () => void;
  onDishLayerChange?: (layer: MenuDishLayer | undefined) => void;
}) {
  const showingDish = visible && surfaceKey === 'dish';
  const liveDish = useMemo<DishContent | undefined>(() => showingDish
    ? {anchor, dishId, hero, dishActions, children} : undefined,
  [anchor, children, dishActions, dishId, hero, showingDish]);
  const [lastDish, setLastDish] = useState(liveDish);
  if (liveDish && liveDish !== lastDish) setLastDish(liveDish);
  const finishDish = useCallback(() => setLastDish(undefined), []);
  const dish = liveDish ?? lastDish;
  const dishPresent = Boolean(dish);
  useLayoutEffect(() => {
    if (!dishPresent) onDishLayerChange?.(undefined);
  }, [dishPresent, onDishLayerChange]);
  const showOrder = visible && surfaceKey !== 'dish';
  const showStaff = surfaceKey === 'staff';

  return <View style={styles.layer} pointerEvents={showOrder || dishPresent ? 'box-none' : 'none'} collapsable={false}>
    {showOrder && <>
      <Animated.View entering={dimIn} exiting={dimOut} style={styles.dim}>
        <Pressable accessible={false} style={StyleSheet.absoluteFill} onPress={onDismiss}/>
      </Animated.View>
      <Animated.View key={surfaceKey} entering={sheetIn} exiting={sheetOut} accessibilityViewIsModal
        style={[styles.sheet, showStaff && styles.fullHeight, {
          paddingTop: sheetPadding + (showStaff ? insets.top : 0),
          paddingBottom: sheetPadding + insets.bottom,
        }]}>
        {children}
      </Animated.View>
    </>}
    {dish && <DishSheet {...dish} visible={showingDish} insets={insets} onDismiss={onDismiss}
      onExit={finishDish} onLayerChange={onDishLayerChange}/>}
  </View>;
}

const styles = StyleSheet.create({
  layer: {...StyleSheet.absoluteFill, justifyContent: 'flex-end'},
  dishLayer: {...StyleSheet.absoluteFill},
  dim: {...StyleSheet.absoluteFill, backgroundColor: '#00000064'},
  dishCanvas: {...StyleSheet.absoluteFill, backgroundColor: '#E3E3E3DE'},
  ripple: {position: 'absolute', borderWidth: StyleSheet.hairlineWidth, borderColor: '#FFFFFF'},
  dishContent: {flex: 1},
  dishPanel: {flexShrink: 0},
  panelClip: {position: 'absolute', overflow: 'hidden'},
  panelContent: {position: 'absolute'},
  dishBody: {flex: 1},
  sheet: {backgroundColor: '#FFFFFF', borderTopLeftRadius: 24, borderTopRightRadius: 24, paddingHorizontal: sheetPadding, gap: 12, maxHeight: '94%'},
  fullHeight: {flex: 1, maxHeight: '100%', borderTopLeftRadius: 0, borderTopRightRadius: 0},
});
