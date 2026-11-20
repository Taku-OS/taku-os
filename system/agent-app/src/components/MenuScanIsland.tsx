import React, {useEffect, useRef, useState} from 'react';
import {Pressable, ScrollView, StyleSheet, Text, useWindowDimensions, View} from 'react-native';
import Animated, {
  cancelAnimation, Easing, FadeInDown, FadeOutUp, interpolate, interpolateColor, ReduceMotion,
  useAnimatedProps, useAnimatedReaction, useAnimatedStyle, useSharedValue,
  withDelay, withRepeat, withSequence, withTiming, type SharedValue,
} from 'react-native-reanimated';
import Svg, {Path} from 'react-native-svg';
import {scheduleOnRN} from 'react-native-worklets';
import {useMenuReducedMotion} from '../hooks/useMenuReducedMotion';
import type {IslandAnchor} from '../native/IslandAnchorView';

type IslandAction = {id: string; label: string; onPress: () => void};
type Phase = 'hidden' | 'idle' | 'scanning' | 'expanded' | 'compact';

type MenuScanIslandProps = {
  phase: Phase;
  insetTop: number;
  insetBottom: number;
  cutoutAnchor?: IslandAnchor;
  scanningLabel: string;
  headline: string;
  actions: readonly IslandAction[];
  detailLines?: readonly string[];
  onExpand?: () => void;
  onCollapse?: () => void;
  collapseLabel?: string;
  onPanelBottom?: (bottom: number) => void;
  orderCount?: number;
  orderLabel?: string;
  onOpenOrder?: () => void;
  dishPhase?: 'opening' | 'open' | 'closing';
  dishSwipeVelocity: SharedValue<number>;
};

const AnimatedPath = Animated.createAnimatedComponent(Path);
const pillColor = '#000000';
const panelColor = '#0D0D0D';
const panelInset = 8;
const panelRadius = 34;
const pillWidth = 120;
const pillHeight = 32;
const lensSize = 24;
const lobeHeight = 32;
const eyeWidth = 6;
const eyeHeight = 12;
const eyeGap = 4;
const centerToDots = 36;
const centerToLabel = 20;
const privacyIndicatorInset = 56;
const controlHeight = 44;
const actionStagger = 46;
const headlineIn = 190;
const actionIn = 155;
const narrowDuration = 140;
const panelDuration = 335;
const firstOrderDelay = 250;
const expandEasing = Easing.bezier(0.24, 0.76, 0.29, 1);
const countIn = FadeInDown.duration(175).reduceMotion(ReduceMotion.Never);
const countOut = FadeOutUp.duration(135).reduceMotion(ReduceMotion.Never);

function timeTo(value: number, duration: number, delay = 0) {
  const motion = withTiming(value, {duration, easing: expandEasing, reduceMotion: ReduceMotion.Never});
  return delay ? withDelay(delay, motion, ReduceMotion.Never) : motion;
}

function circlePath(cx: number, cy: number, radius: number) {
  'worklet';
  return `M${cx - radius},${cy} a${radius},${radius} 0 1 1 ${2 * radius},0 a${radius},${radius} 0 1 1 ${-2 * radius},0 Z`;
}

function pillPath(left: number, top: number, width: number, height: number) {
  'worklet';
  const radius = Math.min(width, height) / 2;
  const horizontal = width - 2 * radius;
  const vertical = height - 2 * radius;
  return `M${left + radius},${top} h${horizontal} a${radius},${radius} 0 0 1 ${radius},${radius}
    v${vertical} a${radius},${radius} 0 0 1 ${-radius},${radius} h${-horizontal}
    a${radius},${radius} 0 0 1 ${-radius},${-radius} v${-vertical} a${radius},${radius} 0 0 1 ${radius},${-radius} Z`;
}

function neckPath(left: number, right: number, y: number, depth: number) {
  'worklet';
  const bend = (right - left) * 0.58;
  const top = y - depth;
  const bottom = y + depth;
  return `M${left},${top} C${left + bend},${y - 3} ${right - bend},${y - 3} ${right},${top} V${bottom} C${right - bend},${y + 3} ${left + bend},${y + 3} ${left},${bottom} Z`;
}

function WorkingDot({column, row, sweep}: {column: number; row: number; sweep: SharedValue<number>}) {
  const animated = useAnimatedStyle(() => {
    const phase = (column + 3 - sweep.get()) % 3;
    const peak = 1 - Math.abs(phase - 1.5) / 1.5;
    const opacity = (row === 1 ? 0.48 : 0.19) + peak * (row === 1 ? 0.5 : 0.77);
    return {opacity};
  });
  return <Animated.View style={[styles.dot, animated, {left: column * 5, top: row * 5}]}/>;
}

function IslandActionButton({action, index, reveal, interactive, reduced}: {
  action: IslandAction; index: number; reveal: SharedValue<number>; interactive: boolean; reduced: boolean;
}) {
  const pressed = useSharedValue(0);
  const start = 55 + index * actionStagger;
  const entrance = useAnimatedStyle(() => {
    const progress = Math.min(1, Math.max(0, (reveal.get() - start) / actionIn));
    return {opacity: progress, transform: [{translateY: 6 * (1 - progress)}, {scale: 1 - 0.045 * pressed.get()}]};
  });
  return <Animated.View style={[styles.actionItem, entrance]}>
    <Pressable testID={'menu-scan-island-' + action.id} accessibilityRole="button" disabled={!interactive}
      onPress={action.onPress} onPressIn={() => pressed.set(reduced ? 0 : timeTo(1, 75))}
      onPressOut={() => pressed.set(reduced ? 0 : timeTo(0, 145))}
      style={({pressed}) => [styles.actionTarget, {opacity: pressed ? 0.76 : 1}]}>
      <View style={styles.actionFace}>
        <Text style={styles.actionText}>{action.label}</Text>
      </View>
    </Pressable>
  </Animated.View>;
}

export function MenuScanIsland({phase, insetTop, insetBottom, cutoutAnchor, scanningLabel, headline, actions, detailLines, onExpand,
  onCollapse, collapseLabel, onPanelBottom, orderCount = 0, orderLabel, onOpenOrder, dishPhase, dishSwipeVelocity}: MenuScanIslandProps) {
  const window = useWindowDimensions();
  const [frame, setFrame] = useState<{width: number; height: number}>();
  const {width, height} = frame ?? window;
  const reduced = useMenuReducedMotion();
  const detailGaze = useSharedValue(0.6);
  const eyeOpening = useSharedValue(1);
  useEffect(() => {
    if (reduced || !dishPhase) {
      detailGaze.set(reduced ? 0.6 : timeTo(0.6, 220));
      eyeOpening.set(reduced ? 1 : timeTo(1, 220));
    } else if (dishPhase === 'opening') {
      detailGaze.set(withSequence(timeTo(3.2, 460), timeTo(2.9, 300)));
      eyeOpening.set(withSequence(timeTo(1.16, 120), timeTo(1.06, 640)));
    } else if (dishPhase === 'closing') {
      detailGaze.set(withSequence(timeTo(0.4, 60), timeTo(2.6, 320)));
      eyeOpening.set(timeTo(1.04, 380));
    } else {
      detailGaze.set(timeTo(1.8, 420));
      eyeOpening.set(timeTo(1, 420));
    }
  }, [detailGaze, dishPhase, eyeOpening, reduced]);
  const hasOrder = orderCount > 0;
  const centerX = cutoutAnchor?.x ?? width / 2;
  const centerY = cutoutAnchor?.y ?? Math.max(27, insetTop + lobeHeight / 2);
  const pillTop = centerY - pillHeight / 2;
  const compactWidth = Math.min(pillWidth, width);
  const compactLeft = centerX - compactWidth / 2;
  const compactLeftLobe = compactLeft + pillHeight / 2;
  const scanning = phase === 'scanning';
  const expanded = phase === 'expanded';
  const showingOrder = Boolean(hasOrder && orderLabel && onOpenOrder && (expanded || phase === 'compact'));
  const revealDuration = Math.max(headlineIn, actions.length ? 55 + (actions.length - 1) * actionStagger + actionIn : 0);
  const labelLeft = centerX + centerToLabel;
  const labelMaxWidth = Math.max(lobeHeight, width - labelLeft - privacyIndicatorInset);
  const [scanSize, setScanSize] = useState({width: lobeHeight, height: lobeHeight});
  const scanWidth = Math.min(labelMaxWidth, scanSize.width);
  const scanTop = Math.max(0, centerY - scanSize.height / 2);
  const [orderContentWidth, setOrderContentWidth] = useState(80);
  const [orderHeight, setOrderHeight] = useState(lobeHeight);
  const orderWidth = Math.max(lobeHeight, Math.min(labelMaxWidth, orderContentWidth));
  const orderTargetHeight = Math.max(controlHeight, orderHeight);
  const orderTargetTop = Math.max(0, centerY - orderTargetHeight / 2);
  const orderTop = orderTargetTop + (orderTargetHeight - orderHeight) / 2;
  const organicTop = Math.min(centerY - lobeHeight / 2, scanTop, orderTop);
  const organicHeight = Math.max(centerY + lobeHeight / 2, scanTop + scanSize.height, orderTop + orderHeight) - organicTop;
  const controlsTop = Math.max(insetTop, centerY + pillHeight / 2);
  const controlsHeight = orderTargetHeight;
  const collapseLeft = panelInset + 6;
  const collapseTop = controlsTop + (controlsHeight - controlHeight) / 2;
  const bodyTop = controlsTop - pillTop + controlsHeight;
  const panelWidth = width - panelInset * 2;
  const [contentHeight, setContentHeight] = useState(80);
  const bodyHeight = Math.min(contentHeight, Math.max(controlHeight, height - insetBottom - controlsTop - controlsHeight - panelInset));
  const panelHeight = bodyTop + bodyHeight;
  const [actionsVisible, setActionsVisible] = useState(expanded);
  const [orderVisible, setOrderVisible] = useState(false);
  const previousPhase = useRef(phase);
  const shape = useSharedValue(scanning ? -1 : Number(expanded));
  const visibility = useSharedValue(Number(phase !== 'hidden'));
  const reveal = useSharedValue(expanded ? revealDuration : 0);
  const sweep = useSharedValue(0);
  const orderReveal = useSharedValue(Number(reduced && showingOrder));
  const orderEntrancePlayed = useSharedValue(reduced && showingOrder);

  useEffect(() => {
    onPanelBottom?.(pillTop + panelHeight);
  }, [onPanelBottom, panelHeight, pillTop]);

  useEffect(() => {
    const previous = previousPhase.current;
    previousPhase.current = phase;
    if (reduced) {
      shape.set(scanning ? -1 : Number(expanded));
      visibility.set(Number(phase !== 'hidden'));
      return;
    }
    const fromScan = previous === 'scanning';
    const closingPanel = previous === 'expanded';
    const closeDelay = closingPanel && phase !== 'hidden' ? 95 : 0;
    if (expanded && fromScan) {
      shape.set(withSequence(ReduceMotion.Never, timeTo(0, narrowDuration - 26), timeTo(1, panelDuration, 26)));
    } else {
      const duration = scanning ? 260 : expanded ? panelDuration : fromScan ? narrowDuration : 270;
      shape.set(timeTo(scanning ? -1 : Number(expanded), duration, closeDelay));
    }
    visibility.set(timeTo(Number(phase !== 'hidden'), phase === 'hidden' ? 120 : 170));
  }, [expanded, phase, reduced, scanning, shape, visibility]);

  useEffect(() => {
    if (reduced) {
      reveal.set(expanded ? revealDuration : 0);
      return;
    }
    const animation = withTiming(expanded ? revealDuration : 0, {
      duration: expanded ? revealDuration : 155, easing: Easing.linear, reduceMotion: ReduceMotion.Never,
    });
    reveal.set(expanded ? withDelay(230, animation, ReduceMotion.Never) : animation);
  }, [expanded, reduced, reveal, revealDuration]);

  useEffect(() => {
    if (reduced || !scanning) return;
    sweep.set(0);
    sweep.set(withRepeat(withTiming(3, {duration: 1050, easing: Easing.linear, reduceMotion: ReduceMotion.Never}),
      -1, false, undefined, ReduceMotion.Never));
    return () => cancelAnimation(sweep);
  }, [reduced, scanning, sweep]);

  useEffect(() => {
    if (!hasOrder) orderEntrancePlayed.set(false);
    const target = Number(showingOrder);
    if (reduced) {
      orderReveal.set(target);
      if (showingOrder) orderEntrancePlayed.set(true);
      return;
    }
    const motion = withTiming(target, {
      duration: showingOrder ? 240 : 130, easing: expandEasing, reduceMotion: ReduceMotion.Never,
    }, finished => {
      if (finished && showingOrder) orderEntrancePlayed.set(true);
    });
    const delay = showingOrder && !orderEntrancePlayed.get() ? firstOrderDelay : 0;
    orderReveal.set(delay ? withDelay(delay, motion, ReduceMotion.Never) : motion);
  }, [hasOrder, orderEntrancePlayed, orderReveal, reduced, showingOrder]);

  useAnimatedReaction(() => shape.get() > 0.97 && reveal.get() >= revealDuration - 8, (visible, previous) => {
    if (visible !== previous) scheduleOnRN(setActionsVisible, visible);
  });
  useAnimatedReaction(() => orderReveal.get() > 0.85, (visible, previous) => {
    if (visible !== previous) scheduleOnRN(setOrderVisible, visible);
  });
  const interactive = expanded && (reduced || actionsVisible);
  const orderInteractive = showingOrder && (reduced || orderVisible);

  const presence = useAnimatedStyle(() => ({opacity: visibility.get()}));
  const shell = useAnimatedStyle(() => {
    const panel = Math.max(0, shape.get());
    return {
      top: pillTop,
      left: interpolate(panel, [0, 1], [compactLeft, panelInset]),
      width: interpolate(panel, [0, 1], [compactWidth, panelWidth]),
      height: interpolate(panel, [0, 1], [pillHeight, panelHeight]),
      borderRadius: interpolate(panel, [0, 1], [pillHeight / 2, panelRadius]),
      backgroundColor: interpolateColor(panel, [0, 1], [pillColor, panelColor]),
      opacity: Math.min(1, panel * 9),
    };
  });
  const organic = useAnimatedProps(() => {
    const current = shape.get();
    const panel = Math.max(0, current);
    const scan = Math.max(0, -current);
    const order = orderReveal.get() * (1 - panel) * (1 - scan);
    const extension = Math.max(scan, order);
    const y = centerY - organicTop;
    const centerWidth = interpolate(extension, [0, 1], [compactWidth, 24]);
    const centerHeight = interpolate(extension, [0, 1], [pillHeight, 24]);
    const leftCenter = interpolate(extension, [0, 1], [compactLeftLobe, centerX - centerToDots]);
    const leftRadius = interpolate(extension, [0, 1], [pillHeight / 2, lobeHeight / 2]);
    const rightStart = interpolate(extension, [0, 1], [centerX + compactWidth / 2 - pillHeight, labelLeft]);
    const rightTop = interpolate(extension, [0, 1], [pillTop, scan > 0 ? scanTop : orderTop]) - organicTop;
    const rightHeight = interpolate(extension, [0, 1], [pillHeight, scan > 0 ? scanSize.height : orderHeight]);
    const rightWidth = interpolate(extension, [0, 1], [pillHeight, scan > 0 ? scanWidth : orderWidth]);
    const neckDepth = 10 * extension;
    return {
      opacity: 1 - Math.min(1, panel * 9),
      d: [circlePath(leftCenter, y, leftRadius), pillPath(centerX - centerWidth / 2, y - centerHeight / 2, centerWidth, centerHeight),
        neckPath(leftCenter + leftRadius * 0.58, centerX - 7, y, neckDepth),
        neckPath(centerX + 7, rightStart + 9, y, neckDepth),
        pillPath(rightStart, rightTop, rightWidth, rightHeight)].join(' '),
    };
  });
  const workingDots = useAnimatedStyle(() => {
    const scan = Math.max(0, -shape.get());
    return {
      left: interpolate(scan, [0, 1], [compactLeftLobe, centerX - centerToDots]) - 6.5,
      opacity: Math.max(0, (scan - 0.18) / 0.82),
    };
  });
  const eyes = useAnimatedStyle(() => {
    const current = shape.get();
    const panel = Math.max(0, current);
    const scan = Math.max(0, -current);
    const order = orderReveal.get();
    const left = interpolate(order, [0, 1], [compactLeftLobe + eyeGap, centerX - centerToDots]);
    return {
      left: interpolate(panel, [0, 1], [left, collapseLeft + controlHeight / 2]) - eyeWidth - eyeGap / 2,
      top: interpolate(panel, [0, 1], [centerY, collapseTop + controlHeight / 2]) - eyeHeight / 2,
      opacity: 1 - Math.min(1, scan * 3.5),
      transform: [{translateX: !reduced && dishPhase ? -2.6 * Math.tanh(dishSwipeVelocity.get() / 0.0035) : 0},
        {translateY: detailGaze.get() * (1 - panel)}, {scaleY: eyeOpening.get()}],
    };
  });
  const lens = useAnimatedStyle(() => ({opacity: 1 - Math.min(1, Math.abs(shape.get()) * 3.5 + orderReveal.get())}));
  const scanningText = useAnimatedStyle(() => ({opacity: Math.max(0, (-shape.get() - 0.85) / 0.15)}));
  const headlineEntrance = useAnimatedStyle(() => {
    const progress = Math.min(1, reveal.get() / headlineIn);
    return {opacity: progress, transform: [{translateY: 6 * (1 - progress)}]};
  });
  const orderEntrance = useAnimatedStyle(() => {
    const panel = Math.max(0, shape.get());
    const progress = orderReveal.get();
    return {
      left: interpolate(panel, [0, 1], [labelLeft, width - panelInset - 14 - orderWidth]),
      top: interpolate(panel, [0, 1], [orderTargetTop, controlsTop]),
      opacity: Math.max(0, (progress - 0.85) / 0.15),
    };
  });
  const orderFace = useAnimatedStyle(() => ({
    backgroundColor: interpolateColor(Math.max(0, shape.get()), [0, 1], ['#00000000', '#262626']),
  }));

  return <Animated.View pointerEvents="box-none" style={[StyleSheet.absoluteFill, presence]} onLayout={({nativeEvent: {layout}}) => {
    setFrame(previous => previous?.width === layout.width && previous.height === layout.height ? previous
      : {width: layout.width, height: layout.height});
  }}>
    <Animated.View pointerEvents="none" importantForAccessibility="no-hide-descendants"
      style={[styles.absolute, {left: 0, top: organicTop}]}>
      <Svg width={width} height={organicHeight}>
        <AnimatedPath animatedProps={organic} fill={pillColor}/>
      </Svg>
    </Animated.View>
    <Animated.View testID="menu-scan-island" pointerEvents={interactive ? 'box-none' : 'none'} style={[styles.shell, shell]}>
      <ScrollView pointerEvents={interactive ? 'auto' : 'none'}
        accessibilityElementsHidden={!interactive} importantForAccessibility={interactive ? 'auto' : 'no-hide-descendants'}
        onContentSizeChange={(_, measuredHeight) => setContentHeight(measuredHeight)}
        contentContainerStyle={styles.content} style={[styles.panelContent, {top: bodyTop, width: panelWidth, height: bodyHeight}]}>
        <Animated.Text accessibilityRole="header" style={[styles.headline, headlineEntrance]}>{headline}</Animated.Text>
        {actions.length > 0 && <View style={styles.actions}>
          {actions.map((action, index) => <IslandActionButton key={action.id} action={action} index={index}
            reveal={reveal} interactive={interactive} reduced={reduced}/>)}
        </View>}
        {detailLines?.map((line, index) => <Animated.Text key={index} style={[styles.detail, headlineEntrance]}>
          {line}
        </Animated.Text>)}
      </ScrollView>
    </Animated.View>
    <Animated.View pointerEvents="none" style={[styles.lens, {left: centerX - lensSize / 2, top: centerY - lensSize / 2}, lens]}/>
    <Animated.View pointerEvents="none" style={[styles.eyes, eyes]}>
      <View style={styles.eye}/><View style={styles.eye}/>
    </Animated.View>
    {interactive && onCollapse && <Pressable testID="menu-scan-island-collapse" accessibilityRole="button"
      accessibilityLabel={collapseLabel ?? headline} onPress={onCollapse}
      style={({pressed}) => [styles.collapse, {left: collapseLeft, top: collapseTop}, pressed && styles.orderPressed]}/>}
    <Animated.View pointerEvents="none" accessible={false} importantForAccessibility="no-hide-descendants"
      style={[styles.absolute, {left: 0, right: 0, top: centerY - lobeHeight / 2, height: lobeHeight}]}>
      <Animated.View style={[styles.dots, workingDots]}>
        {Array.from({length: 9}, (_, index) => <WorkingDot key={index} column={index % 3} row={Math.floor(index / 3)} sweep={sweep}/>)}
      </Animated.View>
    </Animated.View>
    <Animated.View pointerEvents="none" importantForAccessibility="no-hide-descendants"
      style={[styles.scanCopy, {left: labelLeft, top: scanTop, maxWidth: labelMaxWidth}, scanningText]}
      onLayout={({nativeEvent: {layout}}) => {
        setScanSize(previous => previous.width === layout.width && previous.height === layout.height ? previous
          : {width: layout.width, height: layout.height});
      }}>
      <Text style={[styles.smallText, styles.scanText]}>{scanningLabel}</Text>
    </Animated.View>
    {scanning && <View testID="menu-scan-working-island" accessible pointerEvents="none" importantForAccessibility="yes"
      accessibilityLabel={scanningLabel} accessibilityLiveRegion="polite"
      style={[styles.absolute, {left: centerX - centerToDots - lobeHeight / 2, top: organicTop,
        width: centerToDots + lobeHeight / 2 + centerToLabel + scanWidth, height: organicHeight}]}/>}
    {phase === 'compact' && onExpand && <Pressable testID="menu-scan-island-expand" accessibilityRole="button"
      accessibilityLabel={headline} onPress={onExpand}
      style={[styles.absolute, {left: showingOrder ? centerX - centerToDots - lobeHeight / 2 : compactLeft,
        top: Math.max(0, centerY - controlHeight / 2), width: showingOrder ? centerToDots + lobeHeight / 2 + centerToLabel : compactWidth,
        height: controlHeight}]}/>}
    {hasOrder && orderLabel && <View pointerEvents="none" accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants" style={styles.measureOrder}>
      <View style={styles.orderRow} onLayout={({nativeEvent: {layout}}) => {
        setOrderContentWidth(Math.ceil(layout.width) + 1);
      }}>
        <Text numberOfLines={1} style={styles.smallText}>{orderLabel}</Text>
        <Text numberOfLines={1} style={[styles.smallText, styles.orderCount]}>{orderCount}</Text>
      </View>
    </View>}
    {!!orderLabel && <Animated.View pointerEvents={orderInteractive ? 'box-none' : 'none'}
      accessibilityElementsHidden={!orderInteractive} importantForAccessibility={orderInteractive ? 'auto' : 'no-hide-descendants'}
      style={[styles.order, {width: orderWidth}, orderEntrance]}>
      <Pressable testID="menu-view-order" accessibilityRole="button" accessibilityLabel={`${orderLabel} ${orderCount}`}
        onPress={onOpenOrder} disabled={!orderInteractive} style={styles.orderButton}>
        {({pressed}) => <Animated.View pointerEvents="none"
          onLayout={({nativeEvent: {layout}}) => setOrderHeight(layout.height)}
          style={[styles.orderRow, styles.orderFace, {borderRadius: Math.min(orderHeight, orderWidth) / 2}, orderFace,
            pressed && styles.orderFacePressed]}>
          <Text numberOfLines={1} adjustsFontSizeToFit style={[styles.smallText, styles.orderLabel]}>{orderLabel}</Text>
          <View style={styles.orderDigits}>
            <Text accessible={false} style={[styles.smallText, styles.orderCount, styles.measure]}>{orderCount}</Text>
            <Animated.View key={orderCount} entering={reduced ? undefined : countIn} exiting={reduced ? undefined : countOut}
              style={StyleSheet.absoluteFill}>
              <Text numberOfLines={1} style={[styles.smallText, styles.orderCount]}>{orderCount}</Text>
            </Animated.View>
          </View>
        </Animated.View>}
      </Pressable>
    </Animated.View>}
  </Animated.View>;
}

const styles = StyleSheet.create({
  absolute: {position: 'absolute'},
  shell: {position: 'absolute', overflow: 'hidden'},
  lens: {position: 'absolute', width: lensSize, height: lensSize, borderRadius: lensSize / 2, backgroundColor: '#FFFFFF14'},
  eyes: {position: 'absolute', flexDirection: 'row', gap: eyeGap},
  eye: {width: eyeWidth, height: eyeHeight, borderRadius: eyeWidth / 2, backgroundColor: '#F5F5F5'},
  collapse: {position: 'absolute', width: controlHeight, height: controlHeight, borderRadius: controlHeight / 2},
  dots: {position: 'absolute', top: (lobeHeight - 13) / 2, width: 13, height: 13},
  dot: {position: 'absolute', width: 3, height: 3, borderRadius: 1.5, backgroundColor: '#F5F5F5'},
  scanCopy: {position: 'absolute', minHeight: lobeHeight, minWidth: lobeHeight, justifyContent: 'center', paddingHorizontal: 9, paddingVertical: 4},
  scanText: {textAlign: 'center'},
  smallText: {color: '#F5F5F5', fontSize: 11, includeFontPadding: false},
  panelContent: {position: 'absolute', left: 0},
  content: {paddingBottom: 5, paddingHorizontal: 16, gap: 6},
  headline: {color: '#F5F5F5', fontSize: 16, includeFontPadding: false},
  actions: {flexDirection: 'row', flexWrap: 'wrap', gap: 7},
  actionItem: {maxWidth: '100%'},
  actionTarget: {minHeight: 44, justifyContent: 'center'},
  actionFace: {minHeight: 36, justifyContent: 'center', borderRadius: 18, paddingHorizontal: 11, paddingVertical: 6, backgroundColor: '#202020'},
  actionText: {color: '#F5F5F5', fontSize: 13, includeFontPadding: false},
  detail: {color: '#D1D1D1', fontSize: 13},
  order: {position: 'absolute'},
  orderButton: {minHeight: controlHeight, justifyContent: 'center'},
  orderRow: {flexDirection: 'row', gap: 4, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 8},
  orderFace: {minHeight: lobeHeight, paddingVertical: 4},
  orderFacePressed: {opacity: 0.72},
  orderPressed: {backgroundColor: '#FFFFFF22'},
  orderLabel: {flexShrink: 1},
  orderDigits: {overflow: 'hidden', flexShrink: 0},
  orderCount: {fontVariant: ['tabular-nums']},
  measure: {opacity: 0},
  measureOrder: {position: 'absolute', left: 0, right: 0, top: 0, alignItems: 'flex-start', opacity: 0},
});
