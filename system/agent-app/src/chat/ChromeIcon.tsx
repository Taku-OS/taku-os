import React, {useMemo} from 'react';
import {View} from 'react-native';
import {SvgXml} from 'react-native-svg';
import chatIcons from './assets/chat/icons.json';
import voiceIcons from './assets/voice/icons.json';
import {chatColors} from './chatTheme';

const sources = {...voiceIcons, ...chatIcons};
export type ChromeIconName = keyof typeof sources;

/** The artwork's own size where it is smaller than its Figma 24 dp frame (Burger: 16 × 8 centred). */
const artwork: Partial<Record<ChromeIconName, {width: number; height: number}>> = {burger: {width: 16, height: 8}};

/**
 * A Figma icon painted in the chat palette's icon colour. Color=Light (white) and Color=Dark
 * (Main/Foreground #1B1B1B) are the same geometry; other paints (the red Trash) are kept.
 */
export function ChromeIcon({name, size = 24, color = chatColors.icon}: {name: ChromeIconName; size?: number; color?: string}) {
  const xml = useMemo(() => sources[name].replace(/"(white|#1B1B1B)"/g, '"currentColor"'), [name]);
  const own = artwork[name];
  const scale = size / 24;
  return <View style={{width: size, height: size, alignItems: 'center', justifyContent: 'center'}} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
    <SvgXml xml={xml} color={color} width={own ? own.width * scale : size} height={own ? own.height * scale : size}/>
  </View>;
}
