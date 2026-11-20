import React, {memo, useEffect, useState} from 'react';
import {StyleSheet, View} from 'react-native';
import Animated, {
  cancelAnimation, Easing, ReduceMotion, useAnimatedStyle, useSharedValue, withRepeat, withTiming, type SharedValue,
} from 'react-native-reanimated';
import Svg, {Defs, LinearGradient, Rect, Stop} from 'react-native-svg';
import {useMenuReducedMotion} from '../hooks/useMenuReducedMotion';

type Phase = 'hidden' | 'scanning' | 'recommendations' | 'active' | 'detail';
type Size = {width: number; height: number};
type Bounds = Size & {left: number; top: number};
type Edge = 'left' | 'top' | 'right' | 'bottom';
type GradientStop = [offset: string, color: string, opacity: number];

const phaseStrength = {hidden: 0, scanning: 1, recommendations: 0.12, active: 0.81, detail: 0.94};
const phaseDuration = {hidden: 260, scanning: 355, recommendations: 620, active: 400, detail: 390};
const glowEasing = Easing.bezier(0.24, 0.76, 0.29, 1);
const coreLayers = [
  {width: 18, opacity: 0.03},
  {width: 12, opacity: 0.035},
  {width: 7, opacity: 0.045},
  {width: 3.5, opacity: 0.065},
  {width: 1.25, opacity: 0.095},
];
const tintLayers = [
  {width: 48, opacity: 0.035},
  {width: 36, opacity: 0.055},
  {width: 27, opacity: 0.085},
  {width: 19, opacity: 0.13},
  {width: 12, opacity: 0.18},
  {width: 7, opacity: 0.21},
  {width: 3, opacity: 0.21},
];
const stops: Record<Edge, GradientStop[]> = {
  left: [
    ['0%', '#E2EDFF', 0.03], ['7%', '#D1F4FF', 0.75], ['24%', '#BFFAFF', 0.92],
    ['47%', '#AAF5FF', 0.76], ['65%', '#DCFAFF', 0.53], ['81%', '#DBF6FF', 0.40],
    ['92%', '#C4E8FF', 0.73], ['100%', '#C4E6FF', 0.04],
  ],
  top: [
    ['0%', '#D1EBFF', 0.04], ['10%', '#C4EDFF', 0.68], ['35%', '#EAF7FF', 0.58],
    ['57%', '#FDF1FF', 0.53], ['76%', '#FFDEFC', 0.80], ['90%', '#FFC6F8', 0.98], ['100%', '#FFD3F9', 0.04],
  ],
  right: [
    ['0%', '#F8C4FE', 0.04], ['7%', '#FFBDF6', 0.92], ['22%', '#FFD5F7', 0.77],
    ['40%', '#FFF1F7', 0.54], ['57%', '#F1DAFF', 0.53], ['74%', '#FFC7F4', 0.90],
    ['89%', '#F8E0E9', 0.66], ['100%', '#E1FBD6', 0.04],
  ],
  bottom: [
    ['0%', '#D7EEFF', 0.04], ['11%', '#CBEAFF', 0.64], ['25%', '#FFC8F9', 0.81],
    ['42%', '#F0E6FF', 0.65], ['57%', '#DCFFD9', 0.77], ['76%', '#B7F1CC', 0.90],
    ['90%', '#E2F8CE', 0.84], ['100%', '#E4F9D1', 0.04],
  ],
};
const edges = ['left', 'top', 'right', 'bottom'] as const;

function EdgeTint({edge, viewport, clip, radius, turn, pulse, angle}: {
  edge: Edge;
  viewport: Size;
  clip: Bounds;
  radius: number;
  turn: SharedValue<number>;
  pulse: SharedValue<number>;
  angle: number;
}) {
  const vertical = edge === 'left' || edge === 'right';
  const gradientId = `menu-scan-${edge}`;
  const brightness = useAnimatedStyle(() => ({
    opacity: 0.92 + 0.08 * pulse.get() * Math.cos(turn.get() * 2 * Math.PI - angle),
  }));

  return <Animated.View style={[styles.edge, clip, brightness]}>
    <Svg width={clip.width} height={clip.height} viewBox={`${clip.left} ${clip.top} ${clip.width} ${clip.height}`}>
      <Defs>
        <LinearGradient id={gradientId} x1="0%" y1="0%" x2={vertical ? '0%' : '100%'} y2={vertical ? '100%' : '0%'}>
          {stops[edge].map(([offset, color, opacity]) => <Stop key={offset} offset={offset} stopColor={color} stopOpacity={opacity}/>)}
        </LinearGradient>
      </Defs>
      {tintLayers.map(layer => <Rect key={layer.width} x={0.75} y={0.75} width={viewport.width - 1.5}
        height={viewport.height - 1.5} rx={radius} fill="none" stroke={`url(#${gradientId})`}
        strokeWidth={layer.width} strokeOpacity={layer.opacity}/>)}
    </Svg>
  </Animated.View>;
}

const ScanSweep = memo(function ScanSweep({viewport, radius, progress, strength}: {
  viewport: Size; radius: number; progress: SharedValue<number>; strength: SharedValue<number>;
}) {
  const {width, height} = viewport;
  const bandHeight = Math.min(166, height * 0.23);
  const motion = useAnimatedStyle(() => ({
    opacity: strength.get(),
    transform: [{translateY: progress.get() * (height + bandHeight) - bandHeight}],
  }));

  return <View style={[styles.sweepClip, {borderRadius: radius}]}>
    <Animated.View style={motion}>
      <Svg width={width} height={bandHeight}>
        <Defs>
          <LinearGradient id="menu-sweep-tints" x1="0%" y1="0%" x2="20%" y2="100%">
            <Stop offset="0%" stopColor="#B6F8FF" stopOpacity={0}/>
            <Stop offset="28%" stopColor="#B6F8FF" stopOpacity={0.035}/>
            <Stop offset="61%" stopColor="#D9F8FF" stopOpacity={0.115}/>
            <Stop offset="72%" stopColor="#F1E6FC" stopOpacity={0.165}/>
            <Stop offset="82%" stopColor="#FCD0FF" stopOpacity={0.10}/>
            <Stop offset="100%" stopColor="#FCD0FF" stopOpacity={0}/>
          </LinearGradient>
        </Defs>
        <Rect width={width} height={bandHeight} fill="url(#menu-sweep-tints)"/>
      </Svg>
    </Animated.View>
  </View>;
});

const PerimeterLight = memo(function PerimeterLight({phase, viewport}: {phase: Phase; viewport: Size}) {
  const reduced = useMenuReducedMotion();
  const strength = useSharedValue(0);
  const turn = useSharedValue(0);
  const pulse = useSharedValue(0);
  const sweepProgress = useSharedValue(0);
  const sweepStrength = useSharedValue(0);
  const scanning = phase === 'scanning';

  useEffect(() => {
    const target = phaseStrength[phase];
    strength.set(reduced ? target : withTiming(target, {
      duration: phaseDuration[phase], easing: glowEasing, reduceMotion: ReduceMotion.Never,
    }));
  }, [phase, reduced, strength]);

  useEffect(() => {
    if (reduced) {
      cancelAnimation(turn);
      cancelAnimation(sweepProgress);
      pulse.set(0);
      sweepStrength.set(0);
      return;
    }
    pulse.set(withTiming(scanning ? 1 : 0, {duration: 340, reduceMotion: ReduceMotion.Never}, finished => {
      if (finished && !scanning) cancelAnimation(turn);
    }));
    sweepStrength.set(withTiming(scanning ? 1 : 0, {
      duration: scanning ? 310 : 270, easing: glowEasing, reduceMotion: ReduceMotion.Never,
    }, finished => {
      if (finished && !scanning) cancelAnimation(sweepProgress);
    }));
    if (scanning) {
      turn.set(0);
      turn.set(withRepeat(withTiming(1, {duration: 2200, easing: Easing.linear, reduceMotion: ReduceMotion.Never}),
        -1, false, undefined, ReduceMotion.Never));
      sweepProgress.set(0);
      sweepProgress.set(withRepeat(withTiming(1, {duration: 1650, easing: Easing.linear, reduceMotion: ReduceMotion.Never}),
        -1, false, undefined, ReduceMotion.Never));
    }
  }, [pulse, reduced, scanning, sweepProgress, sweepStrength, turn]);
  useEffect(() => () => {
    cancelAnimation(turn);
    cancelAnimation(sweepProgress);
  }, [sweepProgress, turn]);

  const glow = useAnimatedStyle(() => ({opacity: strength.get()}));
  const {width, height} = viewport;
  const radius = Math.min(42, width * 0.1, height * 0.1);
  const edgeDepth = radius + 25;
  const sideWidth = Math.min(edgeDepth, width / 2);
  const capHeight = Math.min(edgeDepth, height / 2);
  const clips: Record<Edge, Bounds> = {
    left: {left: 0, top: 0, width: sideWidth, height},
    top: {left: 0, top: 0, width, height: capHeight},
    right: {left: width - sideWidth, top: 0, width: sideWidth, height},
    bottom: {left: 0, top: height - capHeight, width, height: capHeight},
  };

  return <>
    {!reduced && <ScanSweep viewport={viewport} radius={radius} progress={sweepProgress} strength={sweepStrength}/>}
    <Animated.View style={[StyleSheet.absoluteFill, glow]}>
      {coreLayers.map(layer => <View key={layer.width} style={[styles.core, {
        borderRadius: radius, borderWidth: layer.width, opacity: layer.opacity,
      }]}/>)}
      {edges.map((edge, index) => <EdgeTint key={edge} edge={edge}
        viewport={viewport} clip={clips[edge]} radius={radius} turn={turn} pulse={pulse} angle={index * Math.PI / 2}/>)}
    </Animated.View>
  </>;
});

export function MenuScanPerimeter({phase}: {phase: Phase}) {
  const [viewport, setViewport] = useState<Size | null>(null);

  return <View pointerEvents="none" accessible={false} accessibilityElementsHidden importantForAccessibility="no-hide-descendants"
    collapsable={false} style={StyleSheet.absoluteFill} onLayout={({nativeEvent: {layout: {width, height}}}) => {
      setViewport(previous => width <= 1.5 || height <= 1.5 ? null
        : previous?.width === width && previous.height === height ? previous : {width, height});
    }}>
    {viewport && <PerimeterLight phase={phase} viewport={viewport}/>}
  </View>;
}

const styles = StyleSheet.create({
  core: {...StyleSheet.absoluteFill, borderColor: '#FFFFFF'},
  edge: {position: 'absolute', overflow: 'hidden'},
  sweepClip: {...StyleSheet.absoluteFill, overflow: 'hidden'},
});
