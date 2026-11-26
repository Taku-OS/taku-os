import React from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {colors} from '../theme';
import type {PluginSummary} from './contracts';

const pluginTints = [colors.sage, colors.peach, colors.lavender, colors.mist, colors.rose, colors.sand];

/** A stable pastel for a plugin id; plugins carry no artwork of their own. */
export function tintFor(value: string): string {
  let hash = 0;
  for (const character of value) hash = (hash * 31 + character.charCodeAt(0)) >>> 0;
  return pluginTints[hash % pluginTints.length];
}

/**
 * The Plugin Market's plugin mark: the name's initial on the plugin's tint with a light cutout.
 * Proportions are the market's 54 dp tile (16 dp corners, 44 dp cutout); small marks scale them.
 */
export function PluginMark({plugin, size = 54}: {plugin: Pick<PluginSummary, 'id' | 'name'>; size?: number}) {
  const cutout = size >= 54 ? 44 : Math.round(size * 0.82);
  const offset = size >= 54 ? -17 : -Math.round(size * 0.31);
  return <View accessibilityElementsHidden importantForAccessibility="no-hide-descendants"
    style={[styles.mark, {width: size, height: size, borderRadius: Math.round(size * 0.3), backgroundColor: tintFor(plugin.id)}]}>
    <View style={[styles.cutout, {width: cutout, height: cutout, borderRadius: cutout / 2, right: offset, bottom: offset}]}/>
    <Text allowFontScaling={false} style={[styles.letter, {fontSize: Math.round(size * 0.43), lineHeight: Math.round(size * 0.5)}]}>
      {plugin.name.trim().slice(0, 1).toUpperCase()}
    </Text>
  </View>;
}

const styles = StyleSheet.create({
  mark: {alignItems: 'center', justifyContent: 'center', overflow: 'hidden'},
  cutout: {position: 'absolute', backgroundColor: 'rgba(255,255,255,0.38)'},
  letter: {fontFamily: 'TakuDisplay', color: colors.ink, includeFontPadding: false},
});
