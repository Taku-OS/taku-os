import React from 'react';
import Svg, {Path} from 'react-native-svg';
import {chatIconPaths} from './chatIconPaths';
import {chatColors} from './chatTheme';

export type ChatIconName = keyof typeof chatIconPaths;

/** A Figma vector at [size] dp; stroked icons keep their Figma stroke width on their own viewport. */
export function ChatIcon({name, size = 24, color = chatColors.icon, rotate = 0}: {name: ChatIconName; size?: number; color?: string; rotate?: number}) {
  const icon = chatIconPaths[name];
  const stroked = icon.stroke > 0;
  return <Svg width={size} height={size} viewBox={`0 0 ${icon.viewport} ${icon.viewport}`} accessibilityElementsHidden importantForAccessibility="no-hide-descendants"
    style={rotate ? {transform: [{rotate: `${rotate}deg`}]} : undefined}>
    <Path d={icon.d} fill={stroked ? 'none' : color} stroke={stroked ? color : undefined} strokeWidth={stroked ? icon.stroke : undefined}
      strokeLinecap="round" strokeLinejoin="round" fillRule="evenodd"/>
  </Svg>;
}
