import React, {useState, type ReactNode} from 'react';
import {StyleSheet, View, useWindowDimensions} from 'react-native';
import Svg, {Defs, LinearGradient, Rect, Stop} from 'react-native-svg';
import {chatColors, chatMetrics, themedStyles} from './chatTheme';

/** Figma 300:677 / 300:686: hug the reply, leaving 48dp at the opposite edge. */
export function AssistantBubble({children}: {children: ReactNode}) {
  const {width} = useWindowDimensions();
  return <View style={[styles.bubble, {maxWidth: Math.max(0, width - chatMetrics.side * 2 - chatMetrics.bubbleMinInset)}]}>{children}</View>;
}

/**
 * The Figma GLASS edge (300:666): a thin rim lit from the upper left over the translucent fill.
 * Composer, title-row buttons, menus, dialogs and fields share it; [radius] follows their corners.
 */
export function GlassRim({radius}: {radius: number}) {
  const [size, setSize] = useState({width: 0, height: 0});
  return <View pointerEvents="none" style={StyleSheet.absoluteFill} onLayout={e => setSize(e.nativeEvent.layout)}>
    {size.width > 0 && <Svg width={size.width} height={size.height}>
      <Defs><LinearGradient id="glass-rim" x1="0%" y1="0%" x2="100%" y2="100%">
        <Stop offset="0" stopColor="#FFFFFF" stopOpacity={0.48}/>
        <Stop offset="0.35" stopColor="#FFFFFF" stopOpacity={0.08}/>
        <Stop offset="0.65" stopColor="#FFFFFF" stopOpacity={0.02}/>
        <Stop offset="1" stopColor="#FFFFFF" stopOpacity={0.24}/>
      </LinearGradient></Defs>
      <Rect x={0.5} y={0.5} width={size.width - 1} height={size.height - 1}
        rx={Math.min(radius, size.height / 2, size.width / 2) - 0.5} fill="none" stroke="url(#glass-rim)" strokeWidth={1}/>
    </Svg>}
  </View>;
}

/** The composer's rim: the one-line pill, or Rounded/3xl once it holds several lines. */
export function ComposerGlassRim({multiline}: {multiline: boolean}) {
  return <GlassRim radius={multiline ? 24 : 32}/>;
}

const styles = themedStyles(() => StyleSheet.create({
  bubble: {alignSelf: 'flex-start', backgroundColor: chatColors.assistantBubble, borderRadius: 16,
    borderBottomLeftRadius: 4, padding: 8, gap: 8},
}));
