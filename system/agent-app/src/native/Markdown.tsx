import React, {memo, useState} from 'react';
import {Linking, StyleSheet, View} from 'react-native';
import {EnrichedMarkdownText, type MarkdownStyle} from 'react-native-enriched-markdown';
import {colors as baseColors, containsChinese} from '../theme';
import {chatColors, chatMetrics} from '../chat/chatTheme';
import {advanceMarkdown} from './MarkdownStream';

const colors = {...baseColors, ink: chatColors.textSecondary, muted: chatColors.textTertiary,
  border: chatColors.hairline, subtle: chatColors.fillControl, control: '#303030', page: '#181818', surface: '#222222'};

// Replies that are not covered by the 2-1 frame keep these: one embedded font from the first
// Latin character through CJK.
const body = {fontFamily: 'TakuChinese', fontSize: 16, lineHeight: 26, color: colors.ink, marginTop: 0, marginBottom: 12};
const heading = {...body, fontFamily: 'TakuChineseMedium', fontWeight: 'normal'};
// A clearer local boundary separates scrollable Markdown from the reply card.
const blockBorder = chatColors.hairline;
/**
 * Figma 2-1 reply text: Body/Regular/lg (16 / 1.5, Content/800) with replies 12 dp apart. Latin
 * text uses Hanken Grotesk like the frame; text with Chinese keeps the embedded Noto Sans SC.
 */
function replyStyle(chinese: boolean): MarkdownStyle {
  const reply = {...body, fontFamily: chinese ? 'TakuChinese' : 'TakuBody', lineHeight: 24, color: chatColors.textSecondary, marginBottom: chatMetrics.replyGap};
  const medium = chinese ? 'TakuChineseMedium' : 'TakuBodyMedium';
  return {
    paragraph: reply,
    h1: {...heading, fontSize: 24, lineHeight: 32, marginTop: 16},
    h2: {...heading, fontSize: 20, lineHeight: 29, marginTop: 12},
    h3: {...heading, fontSize: 18, lineHeight: 27, marginTop: 12},
    h4: heading, h5: heading, h6: heading,
    list: {...reply, marginLeft: 24, gapWidth: 8, bulletColor: chatColors.textSecondary, markerColor: chatColors.textSecondary},
    strong: {fontFamily: medium, fontWeight: 'normal', color: chatColors.textSecondary},
    link: {fontFamily: reply.fontFamily, color: chatColors.textSecondary, underline: true},
    blockquote: {...body, borderColor: colors.muted, borderWidth: 3, gapWidth: 8, padding: 10,
      borderRadius: 8, backgroundColor: colors.subtle},
    code: {fontFamily: 'TakuCode', fontSize: 14, color: colors.ink, backgroundColor: colors.control},
    codeBlock: {...body, fontFamily: 'TakuCode', fontSize: 13, lineHeight: 21, backgroundColor: colors.page,
      borderColor: blockBorder, borderWidth: 1, borderRadius: 12, padding: 12,
      // The native default palette targets dark surfaces; use readable colors on our light card.
      syntaxColors: {keyword: '#FF7B72', string: '#A5D6FF', number: '#79C0FF', constant: '#79C0FF',
        comment: '#A0A8B4', function: '#D2A8FF', type: '#FFA657', property: '#79C0FF', tag: '#7EE787',
        attribute: '#79C0FF', operator: colors.ink, punctuation: colors.ink, variable: colors.ink, embedded: colors.ink}},
    table: {...body, fontSize: 14, lineHeight: 22, headerFontFamily: 'TakuChineseMedium', headerBackgroundColor: colors.control,
      headerTextColor: colors.ink, rowEvenBackgroundColor: colors.surface, rowOddBackgroundColor: colors.page,
      borderColor: blockBorder, borderWidth: 1, borderRadius: 8, cellPaddingHorizontal: 12, cellPaddingVertical: 8},
    thematicBreak: {color: colors.border, height: 1, marginTop: 12, marginBottom: 12},
  };
}
const latinStyle = replyStyle(false), chineseStyle = replyStyle(true);
const streamingConfig = {tableMode: 'progressive', codeBlockMode: 'progressive'} as const;
const selectionMenuConfig = {copyAsMarkdown: {enabled: false}};
const flags = {latexMath: false};
const openLink = ({url}: {url: string}) => {
  if (/^https?:\/\//i.test(url)) void Linking.openURL(url).catch(() => {});
};

const MarkdownChunk = memo(function MarkdownChunk({text, active, trailing, chinese}: {text: string; active: boolean; trailing: boolean; chinese: boolean}) {
  return <EnrichedMarkdownText markdown={text} markdownStyle={chinese ? chineseStyle : latinStyle} flavor="github" selectable={false}
    allowFontScaling allowTrailingMargin={trailing} streamingAnimation={active} streamingConfig={streamingConfig}
    textBreakStrategy="simple" md4cFlags={flags} enableTaskListItemToggle={false}
    selectionMenuConfig={active ? selectionMenuConfig : undefined} onLinkPress={openLink} containerStyle={styles.width}/>;
});

/** One instance per app-server item. The upstream subscription coalesces deltas at 40ms. */
export const AgentMarkdown = memo(function AgentMarkdown({text, active}: {text: string; active: boolean}) {
  const [stream, setStream] = useState(() => advanceMarkdown(undefined, text, active));
  // Reconcile before committing children, so completion flushes in the same frame.
  if (stream.raw !== text || stream.active !== active) setStream(advanceMarkdown(stream, text, active));
  const chunks = stream.tail.text ? [...stream.frozen, stream.tail] : stream.frozen;
  // One font for the whole reply, so its chunks never switch faces mid-answer.
  const chinese = containsChinese(text);
  return <View style={styles.width}>{chunks.map((chunk, index) =>
    <MarkdownChunk key={chunk.id} text={chunk.text} active={stream.active && chunk === stream.tail}
      trailing={index < chunks.length - 1} chinese={chinese}/>)}</View>;
});

const styles = StyleSheet.create({width: {width: '100%'}});
