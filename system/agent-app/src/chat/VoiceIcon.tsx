import React from 'react';
import {SvgXml} from 'react-native-svg';
import icons from './assets/voice/icons.json';

/** Original Figma 297:433 / 300:661 vectors, never runtime URLs. */
export function VoiceIcon({name, size = 20}: {name: keyof typeof icons; size?: number}) {
  return <SvgXml xml={icons[name]} width={size} height={size} accessibilityElementsHidden/>;
}
