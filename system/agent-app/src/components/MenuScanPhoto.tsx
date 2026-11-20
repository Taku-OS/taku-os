import React, {useEffect, useMemo, useState} from 'react';
import {Image, Pressable, StyleSheet, Text, View, type LayoutRectangle} from 'react-native';
import Animated, {Easing, FadeIn, FadeInDown, FadeOut, ReduceMotion, useAnimatedStyle,
  useSharedValue, withDelay, withTiming} from 'react-native-reanimated';
import Svg, {Path} from 'react-native-svg';
import {useMenuReducedMotion} from '../hooks/useMenuReducedMotion';
import {formatMenuPrice, type MenuBox, type MenuDish} from '../model/menuScan';
import {Icon} from './Icon';
import type {MenuDishLayer} from './MenuScanSheet';

export type MenuPhotoGeometry = {left: number; top: number; scale: number};

type MarkerBadge = {label: string; added?: boolean};
type BadgePosition = {left: number; top: number; width: number; compact: boolean; side: 'left' | 'right'; index: number};
type Rect = {left: number; top: number; width: number; height: number};

type MenuScanPhotoProps = {
  uri: string;
  width: number;
  height: number;
  textBoxes?: readonly MenuBox[];
  dishes: readonly MenuDish[];
  badges: ReadonlyMap<string, MarkerBadge>;
  islandBottom: number;
  selectedDishId?: string | null;
  dishPhase?: MenuDishLayer['phase'];
  hidden: boolean;
  label: string;
  onLoad: () => void;
  onError: () => void;
  onSelect: (dishId: string) => void;
  onGeometry: (geometry: MenuPhotoGeometry) => void;
};

const AnimatedPressable = Animated.createAnimatedComponent(Pressable);
const markerEntrances = Array.from({length: 9}, (_, index) =>
  FadeIn.duration(120).delay(index * 31).reduceMotion(ReduceMotion.System));
const badgeEntrances = Array.from({length: 6}, (_, index) =>
  FadeInDown.duration(235).delay(180 + index * 72).reduceMotion(ReduceMotion.System));
const badgeExit = FadeOut.duration(135).reduceMotion(ReduceMotion.System);
const markerExit = FadeOut.duration(175).reduceMotion(ReduceMotion.System);
const badgeHeight = 21;
const badgeGap = 6;
const badgeMargin = 8;
const emptyTextBoxes: readonly MenuBox[] = [];

function photoRect({left, top, right, bottom}: MenuBox, geometry: MenuPhotoGeometry): Rect {
  const {scale} = geometry;
  return {left: geometry.left + left * scale, top: geometry.top + top * scale,
    width: (right - left) * scale, height: (bottom - top) * scale};
}

function badgePositions(dishes: readonly MenuDish[], badges: ReadonlyMap<string, MarkerBadge>,
  textBoxes: readonly MenuBox[], geometry: MenuPhotoGeometry, viewport: LayoutRectangle, islandBottom: number) {
  const occupied = textBoxes.map(box => photoRect(box, geometry));
  const positions = new Map<string, BadgePosition>();
  for (const dish of dishes) {
    const badge = badges.get(dish.id);
    if (!badge) continue;
    const row = photoRect(dish.box, geometry);
    if (row.top + row.height < 0 || row.top > viewport.height) continue;
    occupied.push(row);
    const center = row.top + row.height / 2;
    const rowRight = row.left + row.width;
    const preferredWidth = Math.min(119, Math.ceil(25 + [...badge.label].reduce((width, character) => {
      if (/\p{Script=Han}/u.test(character)) return width + 10;
      if (/[MW]/.test(character)) return width + 8;
      if (/[\sijlIftr]/.test(character)) return width + 3.4;
      return width + 5.6;
    }, 0)));
    const place = (width: number, compact: boolean) => {
      let best: (BadgePosition & {distance: number}) | undefined;
      for (const shift of [0, -7, 7, -14, 14, -22, 22, -30, 30, -40, 40]) {
        const top = Math.max(badgeMargin, islandBottom + 2,
          Math.min(viewport.height - badgeHeight - badgeMargin, center + shift - badgeHeight / 2));
        if (top - center > 43 || top + badgeHeight > viewport.height - badgeMargin) continue;
        const blocked = occupied.filter(box => box.top < top + badgeHeight + 2 && box.top + box.height > top - 2);
        for (const side of ['left', 'right'] as const) {
          const anchor = side === 'left' ? row.left : rowRight;
          const candidates = [anchor, side === 'left' ? viewport.width - badgeMargin + badgeGap : badgeMargin - badgeGap,
            ...blocked.map(box => side === 'left' ? box.left : box.left + box.width)];
          for (const edge of candidates) {
            const left = side === 'left' ? edge - width - badgeGap : edge + badgeGap;
            if (left < badgeMargin || left + width > viewport.width - badgeMargin) continue;
            if (blocked.some(box => left - 3 < box.left + box.width && left + width + 3 > box.left)) continue;
            const gap = Math.abs(anchor - edge);
            const distance = gap + Math.abs(top + badgeHeight / 2 - center) * 2;
            if (!best || distance < best.distance) best = {left, top, width, side, compact, distance, index: positions.size};
          }
        }
      }
      return best;
    };
    const position = place(preferredWidth, false) ?? place(Math.max(46, Math.ceil(preferredWidth * 0.77)), false) ?? place(22, true);
    if (position) {
      positions.set(dish.id, position);
      occupied.push({...position, height: badgeHeight});
    }
  }
  return positions;
}

export function MenuScanPhoto({uri, width, height, textBoxes = emptyTextBoxes, dishes, badges, islandBottom, selectedDishId, dishPhase,
  hidden, label, onLoad, onError, onSelect, onGeometry}: MenuScanPhotoProps) {
  const [viewport, setViewport] = useState<LayoutRectangle>({x: 0, y: 0, width: 0, height: 0});
  const reduceMotion = useMenuReducedMotion();
  const focus = useSharedValue(0);
  const focused = Boolean(selectedDishId);
  const closing = dishPhase === 'closing';
  useEffect(() => {
    const show = focused && !closing;
    const fade = withTiming(Number(show), {duration: reduceMotion ? 0 : show ? 270 : 160, easing: Easing.out(Easing.cubic)});
    focus.set(closing && !reduceMotion ? withDelay(215, fade) : fade);
  }, [closing, focus, focused, reduceMotion]);
  const softPhoto = useAnimatedStyle(() => ({opacity: focus.get()}));

  const scale = width > 0 && height > 0 ? Math.max(viewport.width / width, viewport.height / height) : 0;
  const geometry = useMemo(() => ({left: (viewport.width - width * scale) / 2,
    top: (viewport.height - height * scale) / 2, scale}), [height, scale, viewport.height, viewport.width, width]);
  const positions = useMemo(() => badgePositions(dishes, badges, textBoxes, geometry, viewport, islandBottom),
    [dishes, badges, geometry, islandBottom, textBoxes, viewport]);
  useEffect(() => {
    if (scale > 0) onGeometry(geometry);
  }, [geometry, onGeometry, scale]);

  return <View style={styles.viewport} onLayout={event => setViewport(event.nativeEvent.layout)}
    pointerEvents={hidden ? 'none' : 'auto'} accessibilityElementsHidden={hidden}
    importantForAccessibility={hidden ? 'no-hide-descendants' : 'auto'}>
    <Image key={uri} source={{uri}} resizeMode="cover" fadeDuration={0} accessibilityLabel={label}
      onLoad={onLoad} onError={onError} style={StyleSheet.absoluteFill}/>
    {dishes.length > 0 && <Animated.Image source={{uri}} resizeMode="cover" blurRadius={9}
      fadeDuration={0} accessible={false} style={[StyleSheet.absoluteFill, softPhoto]}/>}
    {scale > 0 && dishes.map((dish, index) => {
      const selected = dish.id === selectedDishId;
      if (hidden && (!selected || dishPhase === 'open')) return null;
      const row = photoRect(dish.box, geometry);
      if (row.left + row.width < 0 || row.left > viewport.width || row.top + row.height < 0 || row.top > viewport.height) return null;
      const badge = badges.get(dish.id);
      const dotOnLeft = row.left + row.width + 10 > viewport.width;
      return <AnimatedPressable key={dish.id} testID={'menu-dish-' + dish.id} accessibilityRole="button"
        accessibilityLabel={[dish.name, formatMenuPrice(dish), badge?.label].filter(Boolean).join(', ')} accessibilityState={{selected}}
        hitSlop={{top: 4, right: 10, bottom: 4, left: 6}} onPress={() => onSelect(dish.id)}
        entering={hidden || reduceMotion ? undefined : markerEntrances[Math.min(index, markerEntrances.length - 1)]}
        exiting={hidden ? undefined : markerExit} style={[styles.marker, row]}>
        {selected && <View pointerEvents="none" style={styles.focusCrop}>
          <Image source={{uri}} resizeMode="contain" fadeDuration={0} accessible={false} style={{position: 'absolute',
            left: geometry.left - row.left, top: geometry.top - row.top, width: width * scale, height: height * scale}}/>
        </View>}
        <View pointerEvents="none" style={[styles.markerDot, dotOnLeft && styles.markerDotLeft]}><View style={styles.dotCore}/></View>
      </AnimatedPressable>;
    })}
    {!hidden && dishes.map(dish => {
      const badge = badges.get(dish.id);
      const position = positions.get(dish.id);
      if (!badge || !position) return null;
      const {left, top, width, compact, side, index} = position;
      return <AnimatedPressable key={`${dish.id}:${badge.label}:${Boolean(badge.added)}`} accessible={false}
        testID={badge.added ? 'menu-added-badge-' + dish.id : undefined} onPress={() => onSelect(dish.id)}
        entering={reduceMotion ? undefined : badgeEntrances[Math.min(index, badgeEntrances.length - 1)]} exiting={badgeExit}
        style={[styles.badge, compact && styles.compactBadge, badge.added && styles.addedBadge, {left, top, width}]}>
        {badge.added ? <Icon name="check" color="#24684B" size={12}/> : <Svg width={10} height={10} viewBox="0 0 12 12">
          <Path fill="#FFFFFF" d="M6 0.5 7.65 4.35 11.5 6 7.65 7.65 6 11.5 4.35 7.65 0.5 6 4.35 4.35Z"/>
        </Svg>}
        {!compact && <Text numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.72} allowFontScaling={false}
          style={[styles.badgeLabel, badge.added && styles.addedLabel]}>{badge.label}</Text>}
        {!badge.added && <View style={[styles.badgeDot, side === 'left' ? styles.badgeDotRight : styles.badgeDotLeft]}/>}
      </AnimatedPressable>;
    })}
  </View>;
}

const styles = StyleSheet.create({
  viewport: {...StyleSheet.absoluteFill, overflow: 'hidden'},
  marker: {position: 'absolute', borderRadius: 5, backgroundColor: '#FFFFFF37', borderColor: '#FFFFFF78', borderWidth: StyleSheet.hairlineWidth},
  focusCrop: {...StyleSheet.absoluteFill, borderRadius: 5, overflow: 'hidden'},
  markerDot: {position: 'absolute', right: -9, top: '50%', marginTop: -4, width: 8, height: 8, borderRadius: 4,
    backgroundColor: '#FFFFFF66', alignItems: 'center', justifyContent: 'center'},
  markerDotLeft: {right: undefined, left: -9},
  dotCore: {width: 4, height: 4, borderRadius: 2, backgroundColor: '#FFFFFF'},
  badge: {position: 'absolute', height: badgeHeight, paddingHorizontal: 7, borderRadius: 12, gap: 4,
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center', backgroundColor: '#FA532C'},
  compactBadge: {paddingHorizontal: 0},
  badgeDot: {position: 'absolute', top: '50%', marginTop: -2, width: 4, height: 4, borderRadius: 2, backgroundColor: '#FF623C'},
  badgeDotRight: {right: -5},
  badgeDotLeft: {left: -5},
  badgeLabel: {flex: 1, color: '#FFFFFF', fontSize: 10, fontWeight: '700', textAlign: 'center', includeFontPadding: false},
  addedBadge: {backgroundColor: '#F2FFF6'},
  addedLabel: {color: '#24684B'},
});
