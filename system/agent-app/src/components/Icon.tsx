import React from 'react';
import {Platform, Pressable, StyleSheet, View} from 'react-native';
import Svg, {Path, Rect} from 'react-native-svg';
import paths from '../theme/icons.json';
import {colors} from '../theme';

const decorative = Platform.OS === 'web' ? {'aria-hidden': true} : {accessibilityElementsHidden: true};

export type IconName = keyof typeof paths | 'tree' | 'tako' | 'panel';
type VectorPath = {
  d: string; fill: 'currentColor' | 'none'; fillRule: 'evenodd' | 'nonzero';
  stroke?: 'currentColor'; strokeWidth?: number; strokeLinecap?: 'butt' | 'round' | 'square';
  strokeLinejoin?: 'miter' | 'round' | 'bevel'; strokeMiterlimit?: number;
};
export function Icon({name, size = 20, color = colors.ink}: {name: IconName; size?: number; color?: string}) {
  if (name === 'tako') return <Svg width={size} height={size} viewBox="0 0 24 24" {...decorative}>
    <Rect width="24" height="24" rx="6" fill={colors.sage}/><Rect x="8" y="9" width="3" height="6" rx="1" fill={colors.ink}/><Rect x="13" y="9" width="3" height="6" rx="1" fill={colors.ink}/>
  </Svg>;
  if (name === 'tree') return <Svg width={size} height={size} viewBox="0 0 24 24" {...decorative}>
    <Path d="M8 6h4v12h4M12 6h4" fill="none" stroke={color} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"/>
    <Rect x="2" y="3" width="6" height="6" rx="1.4" fill="none" stroke={color} strokeWidth="1.7"/>
    <Rect x="16" y="3" width="6" height="6" rx="1.4" fill="none" stroke={color} strokeWidth="1.7"/>
    <Rect x="16" y="15" width="6" height="6" rx="1.4" fill="none" stroke={color} strokeWidth="1.7"/>
  </Svg>;
  if (name === 'panel') return <Svg width={size} height={size} viewBox="0 0 24 24" {...decorative}>
    <Rect x="3" y="4" width="18" height="16" rx="2.5" fill="none" stroke={color} strokeWidth="1.7"/>
    <Path d="M3 10h18M10 10v10" fill="none" stroke={color} strokeWidth="1.7"/>
  </Svg>;
  const icon = paths[name] as {viewBox: string; paths: VectorPath[]};
  return <Svg width={size} height={size} viewBox={icon.viewBox} {...decorative}>{icon.paths.map((path, i) =>
    <Path key={i} {...path} fill={path.fill === 'currentColor' ? color : 'none'} stroke={path.stroke === 'currentColor' ? color : undefined}/>)}</Svg>;
}

export function IconButton({name, label, onPress, disabled, active, size = 20, color, testID, compact = false}: {
  name: IconName; label: string; onPress: () => void; disabled?: boolean; active?: boolean; size?: number; color?: string; testID?: string; compact?: boolean;
}) {
  return <Pressable testID={testID} accessibilityRole="button" accessibilityLabel={label} accessibilityState={{disabled: Boolean(disabled), selected: Boolean(active)}}
    onPress={onPress} disabled={disabled} style={({pressed}) => [styles.button, compact && styles.compactButton, {opacity: disabled ? 0.35 : 1}, pressed && styles.pressed]}>
    <View style={[styles.face, compact && styles.compactFace, active && styles.active]}><Icon name={name} size={size} color={color}/></View>
  </Pressable>;
}
const styles = StyleSheet.create({
  button: {width: 48, height: 48, alignItems: 'center', justifyContent: 'center', borderRadius: 8},
  face: {width: 32, height: 32, alignItems: 'center', justifyContent: 'center', borderRadius: 6},
  compactButton: {height: 32, paddingTop: 12}, compactFace: {height: 20},
  active: {backgroundColor: colors.control}, pressed: {backgroundColor: colors.subtle},
});
