import React, {memo, useState} from 'react';
import {Linking, StyleSheet, View} from 'react-native';
import {EnrichedMarkdownText, type MarkdownStyle} from 'react-native-enriched-markdown';
import {colors as baseColors, containsChinese} from '../theme';
import {chatColors, chatMetrics, getChatAppearance, themed, themedStyles} from '../chat/chatTheme';
import {advanceMarkdown} from './MarkdownStream';
import {isWebUrl} from './MarkdownLinks';

/** Code, table and quote surfaces for the active chat background (Dark keeps its earlier values). */
const colors = themed(() => ({...baseColors, ink: chatColors.textSecondary, muted: chatColors.textTertiary,
  border: chatColors.hairline, subtle: chatColors.fillControl,
  ...(getChatAppearance() === 'dark'
    ? {control: '#303030', page: '#181818', surface: '#222222'}
    : {control: 'rgba(0,0,0,0.06)', page: '#F4F4F5', surface: '#FFFFFF'})}));

/** Readable syntax colours on each background (GitHub dark / light). */
const syntax = themed(() => getChatAppearance() === 'dark'
  ? {keyword: '#FF7B72', string: '#A5D6FF', number: '#79C0FF', constant: '#79C0FF', comment: '#A0A8B4',
    function: '#D2A8FF', type: '#FFA657', property: '#79C0FF', tag: '#7EE787', attribute: '#79C0FF'}
  : {keyword: '#CF222E', string: '#0A3069', number: '#0550AE', constant: '#0550AE', comment: '#6E7781',
    function: '#8250DF', type: '#953800', property: '#0550AE', tag: '#116329', attribute: '#0550AE'});
/**
 * Figma 2-1 reply text: Body/Regular/lg (16 / 1.5, Content/800) with replies 12 dp apart. Latin
 * text uses Hanken Grotesk like the frame; text with Chinese keeps the embedded Noto Sans SC.
 */
function replyStyle(chinese: boolean): MarkdownStyle {
  // Replies that are not covered by the 2-1 frame keep these: one embedded font from the first
  // Latin character through CJK.
  const body = {fontFamily: 'TakuChinese', fontSize: 16, lineHeight: 26, color: colors.ink, marginTop: 0, marginBottom: 12};
  const heading = {...body, fontFamily: 'TakuChineseMedium', fontWeight: 'normal'};
  // A clearer local boundary separates scrollable Markdown from the reply card.
  const blockBorder = chatColors.hairline;
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
      syntaxColors: {...syntax, operator: colors.ink, punctuation: colors.ink, variable: colors.ink, embedded: colors.ink}},
    table: {...body, fontSize: 14, lineHeight: 22, headerFontFamily: 'TakuChineseMedium', headerBackgroundColor: colors.control,
      headerTextColor: colors.ink, rowEvenBackgroundColor: colors.surface, rowOddBackgroundColor: colors.page,
      borderColor: blockBorder, borderWidth: 1, borderRadius: 8, cellPaddingHorizontal: 12, cellPaddingVertical: 8},
    thematicBreak: {color: colors.border, height: 1, marginTop: 12, marginBottom: 12},
  };
}
const markdownStyles = themed(() => ({latin: replyStyle(false), chinese: replyStyle(true)}));
const streamingConfig = {tableMode: 'progressive', codeBlockMode: 'progressive'} as const;
const selectionMenuConfig = {copyAsMarkdown: {enabled: false}};
const flags = {latexMath: false};
const openLink = ({url}: {url: string}) => {
  if (isWebUrl(url)) void Linking.openURL(url.trim()).catch(() => {});
};

const MarkdownChunk = memo(function MarkdownChunk({text, active, trailing, chinese}: {text: string; active: boolean; trailing: boolean; chinese: boolean}) {
  return <EnrichedMarkdownText markdown={text} markdownStyle={chinese ? markdownStyles.chinese : markdownStyles.latin} flavor="github" selectable={false}
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

const styles = themedStyles(() => StyleSheet.create({width: {width: '100%'}}));
