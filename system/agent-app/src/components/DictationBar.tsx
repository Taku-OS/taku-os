/**
 * Figma 1-5 "voice text", inside the chat's composer pill while dictating: the running
 * transcript fading out toward the waveform, the "Voice waveform · Gap 2" strip and the
 * Main/Foreground stop button (a 12 dp white rounded square). The same geometry as the home's
 * Compose composer (modules/taku-home …/ui/assistant/Composer.kt, DictationText/DictationWave).
 */
import React, {useRef} from 'react';
import {ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import Svg, {Defs, LinearGradient, Rect, Stop} from 'react-native-svg';
import {chatColors, chatType} from '../chat/chatTheme';
import {containsChinese} from '../theme';
import {barHeights} from '../chat/dictationText';

type Props = {
  transcript: string;
  placeholder: string;
  levels: number[];
  finishing: boolean;
  stopLabel: string;
  onStop: () => void;
};

const DOTS = 22, BARS = 6, UNIT = 3, PITCH = 5, FADE = 90;
export function DictationBar({transcript, placeholder, levels, finishing, stopLabel, onStop}: Props) {
  const scroll = useRef<React.ComponentRef<typeof ScrollView>>(null);
  const heights = barHeights(levels);
  const empty = !transcript;
  return <>
    <View style={styles.textBox}>
      {/* One line; the newest words stay in view while the oldest scroll off the left. */}
      <ScrollView ref={scroll} horizontal scrollEnabled={false} showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.textContent} onContentSizeChange={() => scroll.current?.scrollToEnd({animated: false})}>
        <Text testID="dictation-transcript" numberOfLines={1} accessibilityLiveRegion="polite"
          style={[chatType.bodyLg(containsChinese(transcript || placeholder)), empty ? styles.placeholder : styles.transcript]}>
          {transcript || placeholder}
        </Text>
      </ScrollView>
      <Svg pointerEvents="none" width={FADE} height="100%" style={styles.fade}>
        <Defs>
          <LinearGradient id="dictation-fade" x1="0" y1="0" x2="1" y2="0">
            <Stop offset="0" stopColor="#242424" stopOpacity={0}/>
            <Stop offset="1" stopColor="#242424" stopOpacity={1}/>
          </LinearGradient>
        </Defs>
        <Rect x="0" y="0" width={FADE} height="100%" fill="url(#dictation-fade)"/>
      </Svg>
    </View>
    <View testID="dictation-waveform" style={styles.wave} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
      {Array.from({length: DOTS}, (_, i) => <View key={`d${i}`} style={styles.dot}/>)}
      {heights.map((height, i) => <View key={`b${i}`} style={[styles.bar, {height, opacity: i === BARS - 1 ? 0.38 : 0.55}]}/>)}
    </View>
    <Pressable testID="dictation-stop" accessibilityRole="button" accessibilityLabel={stopLabel} accessibilityState={{busy: finishing}}
      disabled={finishing} onPress={onStop} style={({pressed}) => [styles.stop, pressed && {opacity: 0.8}]}>
      {finishing ? <ActivityIndicator size="small" color={chatColors.iconOnInverse}/> : <View style={styles.stopGlyph}/>}
    </Pressable>
  </>;
}

const styles = StyleSheet.create({
  textBox: {flex: 1, minWidth: 0, height: 40, justifyContent: 'center', overflow: 'hidden'},
  textContent: {alignItems: 'center', flexGrow: 1, paddingRight: 32},
  transcript: {color: chatColors.textSecondary},
  placeholder: {color: chatColors.placeholder},
  fade: {position: 'absolute', right: 0, top: 0, bottom: 0},
  wave: {height: 32, flexDirection: 'row', alignItems: 'center', gap: PITCH - UNIT, flexShrink: 0},
  dot: {width: UNIT, height: UNIT, borderRadius: UNIT / 2, backgroundColor: chatColors.icon, opacity: 0.2},
  bar: {width: UNIT, borderRadius: UNIT / 2, backgroundColor: chatColors.icon},
  stop: {width: 40, height: 40, borderRadius: 20, backgroundColor: chatColors.inverse, alignItems: 'center', justifyContent: 'center', flexShrink: 0},
  stopGlyph: {width: 12, height: 12, borderRadius: 4, backgroundColor: chatColors.iconOnInverse},
});
