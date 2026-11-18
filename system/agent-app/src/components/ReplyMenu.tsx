/**
 * Long press on a Taku reply (Figma 0-3 "long press card", applied to the chat): the screen dims
 * under a 24 % black overlay while the list behind blurs (σ 4 dp, by the host's EdgeBlur), the
 * reply lifts onto a Surface/900 card in place, and Surface/900 action pills sit 8 dp below it,
 * aligned right. Copy puts the reply on the clipboard; Select shows the same card as selectable
 * text, all selected, with the system selection handles and toolbar.
 */
import React, {useEffect, useState, type ReactNode} from 'react';
import {Animated, BackHandler, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, ToastAndroid, View} from 'react-native';
import * as Clipboard from 'expo-clipboard';
import {chatColors, chatType} from '../chat/chatTheme';
import {chatText} from '../chat/chatText';
import {containsChinese} from '../theme';

/** A reply's text and where it sits in the window (dp). */
export type ReplyMenuTarget = {id: string; text: string; x: number; y: number; width: number; height: number};

type Props = {
  target: ReplyMenuTarget;
  language: 'en' | 'zh';
  /** The free band between the title row and the composer (window dp). */
  top: number;
  bottom: number;
  screenWidth: number;
  renderMarkdown?: (text: string, active: boolean, itemId: string) => ReactNode;
  onClose: () => void;
};

const PAD = 12, PILL = 40, GAP = 8;

export function ReplyMenu({target, language, top, bottom, screenWidth, renderMarkdown, onClose}: Props) {
  const t = chatText(language);
  const [selecting, setSelecting] = useState(false);
  const [selection, setSelection] = useState({start: 0, end: target.text.length});
  const [shown] = useState(() => new Animated.Value(0));
  useEffect(() => { Animated.timing(shown, {toValue: 1, duration: 220, useNativeDriver: true}).start(); }, [shown]);
  useEffect(() => {
    const back = BackHandler.addEventListener('hardwareBackPress', () => { onClose(); return true; });
    return () => back.remove();
  }, [onClose]);

  // The card keeps the reply where it was, inside the free band; a long reply scrolls inside it.
  const maxHeight = Math.max(120, bottom - top - PILL - GAP * 2);
  const height = Math.min(target.height + PAD * 2, maxHeight);
  const cardTop = Math.min(Math.max(target.y - PAD, top + GAP), bottom - GAP - PILL - GAP - height);
  const left = Math.max(GAP, target.x - PAD), width = Math.min(screenWidth - GAP - left, target.width + PAD * 2);
  const pillsTop = cardTop + height + GAP;

  const copy = (text: string) => {
    void Clipboard.setStringAsync(text).then(() => {
      if (Platform.OS === 'android') ToastAndroid.show(t('copied'), ToastAndroid.SHORT);
    });
    onClose();
  };
  const chinese = containsChinese(target.text);
  const actions = selecting
    ? [{key: 'copy', label: t('copy'), onPress: () => copy(target.text.slice(selection.start, selection.end) || target.text)},
      {key: 'done', label: t('done'), onPress: onClose}]
    : [{key: 'copy', label: t('copy'), onPress: () => copy(target.text)},
      {key: 'select', label: t('select'), onPress: () => setSelecting(true)}];

  return <Animated.View style={[StyleSheet.absoluteFill, {opacity: shown}]}>
    <Pressable testID="reply-menu-overlay" accessibilityRole="button" accessibilityLabel={t('done')} onPress={onClose} style={styles.overlay}/>
    <Animated.View style={[styles.card, {top: cardTop, left, width, maxHeight: height},
      {transform: [{scale: shown.interpolate({inputRange: [0, 1], outputRange: [0.98, 1]})}]}]}>
      {selecting
        ? <TextInput testID="reply-select-text" value={target.text} onChangeText={() => {}} multiline autoFocus showSoftInputOnFocus={false} caretHidden
            selection={selection} onSelectionChange={event => setSelection(event.nativeEvent.selection)} scrollEnabled
            style={[chatType.bodyLg(chinese), styles.replyText, styles.select, {maxHeight: height - PAD * 2}]}/>
        : <ScrollView style={{maxHeight: height - PAD * 2}} showsVerticalScrollIndicator={false}>
            {renderMarkdown ? renderMarkdown(target.text, false, `${target.id}/menu`) : <Text style={[chatType.bodyLg(chinese), styles.replyText]}>{target.text}</Text>}
          </ScrollView>}
    </Animated.View>
    <View style={[styles.pills, {top: pillsTop}]}>
      {actions.map(action => <Pressable key={action.key} testID={`reply-menu-${action.key}`} accessibilityRole="button" accessibilityLabel={action.label}
        onPress={action.onPress} style={({pressed}) => [styles.pill, pressed && styles.pillPressed]}>
        <Text style={[chatType.bodyLg(language === 'zh'), styles.pillText]}>{action.label}</Text>
      </Pressable>)}
    </View>
  </Animated.View>;
}

const styles = StyleSheet.create({
  // Figma 0-3 "Overlay": Content/400 (24 % black); the blur behind is the host's EdgeBlur.
  overlay: {...StyleSheet.absoluteFill, backgroundColor: 'rgba(0,0,0,0.24)'},
  card: {position: 'absolute', backgroundColor: chatColors.surfaceRaised, borderRadius: 24, padding: PAD, overflow: 'hidden'},
  replyText: {color: chatColors.textSecondary},
  select: {padding: 0, margin: 0, textAlignVertical: 'top'},
  // Figma 0-3 action pills ("Pin to top", "Ask Taku"): Surface/900, 40 dp, 16 dp sides, 48 dp shadow.
  pills: {position: 'absolute', right: 16, flexDirection: 'row', gap: GAP},
  pill: {height: PILL, paddingHorizontal: 16, borderRadius: PILL / 2, backgroundColor: chatColors.surfaceRaised, alignItems: 'center', justifyContent: 'center',
    boxShadow: `0px 0px 48px 0px ${chatColors.shadow}`},
  pillPressed: {backgroundColor: chatColors.surfaceRaised},
  pillText: {color: chatColors.icon},
});
