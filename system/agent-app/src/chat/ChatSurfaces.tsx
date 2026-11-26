import React, {useState, type ReactNode} from 'react';
import {Pressable, StyleSheet, Text, View, useWindowDimensions} from 'react-native';
import Svg, {Defs, LinearGradient, Rect, Stop} from 'react-native-svg';
import {chatColors, chatMetrics, chatType, themedStyles} from './chatTheme';
import {chatText, type ChatTextKey} from './chatText';

/** Figma 300:677 / 300:686: hug the reply, leaving 48dp at the opposite edge. */
export function AssistantBubble({children}: {children: ReactNode}) {
  const {width} = useWindowDimensions();
  return <View style={[styles.bubble, {maxWidth: Math.max(0, width - chatMetrics.side * 2 - chatMetrics.bubbleMinInset)}]}>{children}</View>;
}

const quickStarts: readonly (readonly [label: ChatTextKey, prompt: ChatTextKey])[] = [
  ['quickTicket', 'quickTicketPrompt'], ['quickWeek', 'quickWeekPrompt'],
  ['quickMessage', 'quickMessagePrompt'], ['quickResearch', 'quickResearchPrompt'],
];

/**
 * The quick starts under TAKU's greeting in an empty conversation (Zile's wireframe): quiet
 * capsules in the reply's own tone. One hands the composer an opening line to finish.
 */
export function QuickStarts({language, onStart}: {language: 'en' | 'zh'; onStart: (prompt: string) => void}) {
  const t = chatText(language);
  return <View testID="quick-starts" style={styles.quickStarts}>
    {quickStarts.map(([label, prompt]) => <Pressable key={label} testID={`quick-start-${label}`} accessibilityRole="button" accessibilityLabel={t(label)}
      onPress={() => onStart(t(prompt))} style={({pressed}) => [styles.quickStart, pressed && styles.quickStartPressed]}>
      <Text numberOfLines={1} style={[chatType.bodyMdMedium(language === 'zh'), styles.quickStartText]}>{t(label)}</Text>
    </Pressable>)}
  </View>;
}

/** The composer's glass rim, lit from the upper left (Figma 300:666 GLASS effect). */
export function ComposerGlassRim({multiline}: {multiline: boolean}) {
  const [size, setSize] = useState({width: 0, height: 0});
  return <View pointerEvents="none" style={StyleSheet.absoluteFill} onLayout={e => setSize(e.nativeEvent.layout)}>
    {size.width > 0 && <Svg width={size.width} height={size.height}>
      <Defs><LinearGradient id="composer-rim" x1="0%" y1="0%" x2="100%" y2="100%">
        <Stop offset="0" stopColor="#FFFFFF" stopOpacity={0.48}/>
        <Stop offset="0.35" stopColor="#FFFFFF" stopOpacity={0.08}/>
        <Stop offset="0.65" stopColor="#FFFFFF" stopOpacity={0.02}/>
        <Stop offset="1" stopColor="#FFFFFF" stopOpacity={0.24}/>
      </LinearGradient></Defs>
      <Rect x={0.3} y={0.3} width={size.width - 0.6} height={size.height - 0.6}
        rx={multiline ? 24 : 32} fill="none" stroke="url(#composer-rim)" strokeWidth={0.6}/>
    </Svg>}
  </View>;
}

const styles = themedStyles(() => StyleSheet.create({
  bubble: {alignSelf: 'flex-start', backgroundColor: chatColors.assistantBubble, borderRadius: 16,
    borderBottomLeftRadius: 4, padding: 8, gap: 8},
  quickStarts: {flexDirection: 'row', flexWrap: 'wrap', gap: 8},
  quickStart: {height: 38, borderRadius: 19, paddingHorizontal: 14, alignItems: 'center', justifyContent: 'center', backgroundColor: chatColors.assistantBubble},
  quickStartPressed: {opacity: 0.7},
  quickStartText: {color: chatColors.textPrimary, fontSize: 13, lineHeight: 18},
}));
