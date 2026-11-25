/**
 * Editing a pinned Panel in chat (Figma 4-1, node 1482:2586): the attached panel as a card in the
 * composer, and Taku's opening question with quick suggestions. The panel itself travels natively:
 * the next send is a Panel update whose turn carries the panel's current content.
 */
import React, {useEffect, useState} from 'react';
import {Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import {AssistantBubble} from './ChatSurfaces';
import {ChromeIcon} from './ChromeIcon';
import {chatColors, chatType, themedStyles} from './chatTheme';
import {chatText} from './chatText';
import {getPanel} from '../panels/client';
import {containsChinese} from '../theme';

type Language = 'en' | 'zh';

/** The attached panel's current title from the Panel directory ('' until it is read). */
export function usePanelTitle(panelId: string | null | undefined): string {
  const [title, setTitle] = useState<{panelId: string; title: string} | null>(null);
  useEffect(() => {
    if (!panelId) return;
    let current = true;
    void getPanel(panelId).then(detail => { if (current) setTitle({panelId, title: detail.panel.title}); }, () => {});
    return () => { current = false; };
  }, [panelId]);
  return title && title.panelId === panelId ? title.title : '';
}

/** Figma 1482:2637: 96 dp Surface/200 card, 20 dp corners; panel glyph, a Content/600 close disc, the title. */
export function PanelContextChip({title, language, disabled, onRemove}: {title: string; language: Language; disabled: boolean; onRemove?: () => void}) {
  const t = chatText(language);
  const name = title || t('panelPreview');
  return <View testID="panel-context-chip" accessible accessibilityLabel={`${t('panelPreview')}: ${name}`} style={styles.chip}>
    <View style={styles.chipHead}>
      <ChromeIcon name="panelsSmall" size={18}/>
      {onRemove && <Pressable testID="panel-context-remove" accessibilityRole="button" accessibilityLabel={t('removePanelContext')}
        accessibilityState={{disabled}} disabled={disabled} onPress={onRemove} hitSlop={10} style={({pressed}) => [styles.close, pressed && styles.pressed]}>
        <ChromeIcon name="closeSmall" size={8} color="#FFFFFF"/>
      </Pressable>}
    </View>
    <Text numberOfLines={2} style={[chatType.bodyMd(containsChinese(name)), styles.title]}>{name}</Text>
  </View>;
}

/** 4-1: "What would you like to change?" and suggestion pills that start the request in the composer. */
export function PanelEditPrompt({language, onSuggest}: {language: Language; onSuggest: (text: string) => void}) {
  const t = chatText(language);
  const suggestions = [t('editSuggestCompact'), t('editSuggestBackground'), t('editSuggestLayout')];
  return <View testID="panel-edit-prompt" style={styles.prompt}>
    <AssistantBubble><Text style={[chatType.bodyLg(language === 'zh'), styles.reply]}>{t('editPanelGreeting')}</Text></AssistantBubble>
    <ScrollView horizontal showsHorizontalScrollIndicator={false} keyboardShouldPersistTaps="handled" contentContainerStyle={styles.suggestions}
      style={styles.suggestionRow}>
      {suggestions.map(text => <Pressable key={text} accessibilityRole="button" onPress={() => onSuggest(text)}
        style={({pressed}) => [styles.suggestion, pressed && styles.pressed]}>
        <Text style={[chatType.bodyLgMedium(language === 'zh'), styles.reply]}>{text}</Text>
      </Pressable>)}
    </ScrollView>
  </View>;
}

const styles = themedStyles(() => StyleSheet.create({
  chip: {width: 96, padding: 8, gap: 22, borderRadius: 20, backgroundColor: chatColors.fillControl},
  chipHead: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', height: 18},
  close: {width: 18, height: 18, borderRadius: 9, alignItems: 'center', justifyContent: 'center', backgroundColor: 'rgba(0,0,0,0.48)'},
  title: {color: chatColors.textSecondary},
  prompt: {gap: 10},
  // The pills run to the screen edge, past the 16 dp list padding (Figma 4-1).
  suggestionRow: {marginRight: -16},
  suggestions: {gap: 8, paddingRight: 16},
  suggestion: {paddingHorizontal: 12, paddingVertical: 8, borderRadius: 9999, backgroundColor: chatColors.fillControl,
    borderWidth: 1, borderColor: chatColors.reactionFill},
  reply: {color: chatColors.textSecondary},
  pressed: {opacity: 0.7},
}));
