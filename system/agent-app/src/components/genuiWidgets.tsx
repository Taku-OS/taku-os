import React, {createContext, useContext, type ReactNode} from 'react';
import {ScrollView, StyleSheet, View} from 'react-native';
import * as Linking from 'expo-linking';
import {useBoundProp} from '@json-render/react-native';
import {radii, spacing} from '../feed/feedTokens';
import {FeedCornerTag, FeedIcon, FeedInset, FeedItem, FeedMediaFrame, FeedPlaceholder, FeedPressable, FeedProgress, FeedText, FeedValue} from '../feed/feedVisual';
import type {Language} from '../i18n';
import type {UiConfig, UiItem, UiValue} from '../model/genui';
import {compareUiMoney, formatUiValue} from './genuiFormat';
import {MediaContent} from './genuiMedia';
import {AudioCapture} from './genuiCapture';
import {ProgressiveImage, useItemRegions, useRegionSkeleton, useRegionWaiting} from './genuiAssembly';

export const CardLanguage = createContext<Language>('en');
export const CardMatter = createContext('');
export const safeUrl = (url: unknown): string => typeof url === 'string' && /^https?:\/\/\S+$/.test(url) ? url : '';
export function openSafeUrl(url: unknown) {
  const target = safeUrl(url);
  if (target) void Linking.openURL(target).catch(() => {});
}
type Content = {title?: string; text?: string; value?: UiValue; config?: UiConfig};
function Caption({title, text, children}: Content & {children?: ReactNode}) {
  return <View style={styles.caption}>{title && <FeedText text={title} variant="body-md-medium"/>}{children}{text && <FeedText text={text} variant="body-sm" tone="secondary"/>}</View>;
}
export function Metric({title, text, value, config, items = [], expanded, binding, children}: Content & {items?: UiItem[]; expanded?: boolean; binding?: string; children?: ReactNode}) {
  const language = useContext(CardLanguage), size = config?.size;
  const [open, setOpen] = useBoundProp<boolean>(expanded ?? false, binding);
  const full = size === 'full_size', compact = size === 'small_size', collapsible = !!size && !full && (React.Children.count(children) > 0 || compact && items.length > 0);
  const valueSize = full ? '2xl' : size === 'medium_size' ? 'xl' : compact ? 'lg' : config?.emphasis === 'hero' ? '2xl' : 'lg';
  const supporting = !!items.length && <View style={styles.supporting}>{items.map(item => <FeedText key={item.id} text={`${item.label} ${formatUiValue(item.value, language)}`} variant="body-sm" tone="secondary"/>)}</View>;
  const primary = <View style={{gap: compact ? spacing[4] : spacing[8]}}>
    {title && <FeedText text={title} variant={compact ? 'body-sm' : 'body-md'} tone="secondary"/>}
    <View style={styles.metricRow}>
      {!full && config?.icon && <FeedIcon name={config.icon} size={compact ? 'medium' : 'large'}/>}
      <View style={styles.metricValue}><FeedValue text={formatUiValue(value, language)} size={valueSize} selectable/></View>
      {compact && text && <FeedText text={text} variant="body-sm" tone="secondary" flex={1}/>}
      {full && config?.icon && <View style={styles.metricIcon}><FeedIcon name={config.icon} size="large"/></View>}
      {collapsible && <FeedIcon name="chevron_down" tone="secondary"/>}
    </View>
    {!compact && text && <FeedText text={text} variant={full ? 'body-md-medium' : 'body-sm'} tone="secondary"/>}
    {!compact && supporting}
  </View>;
  return <View style={styles.stack}>
    {collapsible ? <FeedPressable label={[title, text, language === 'zh' ? open ? '收起详情' : '展开详情' : open ? 'Collapse details' : 'Expand details'].filter(Boolean).join(' · ')} expanded={!!open} onPress={() => setOpen(!open)}>{primary}</FeedPressable> : primary}
    {(!collapsible || open) && <>{compact && supporting}{children}</>}
  </View>;
}
export function TimeDisplay(props: Content & {items?: UiItem[]}) {
  const language = useContext(CardLanguage), itemsWaiting = useRegionWaiting('items'), items = props.items ?? [];
  return <View style={styles.stack}>{props.config?.emphasis === 'inline'
    ? <FeedText text={[props.title, formatUiValue(props.value, language), props.value?.timeZone].filter(Boolean).join(' · ')} variant="body-sm" tone="secondary"/>
    : <Caption title={props.title} text={props.text}><FeedValue text={formatUiValue(props.value, language)} selectable/>{props.value?.type === 'instant' && <FeedText text={props.value.timeZone ?? ''} variant="body-sm" tone="secondary"/>}</Caption>}
    {(items.length > 0 || itemsWaiting) && <Collection items={items} config={props.config}/>}
  </View>;
}
export function Collection({title, text, items, config, url, role = 'collection'}: Content & {items: UiItem[]; url?: string; role?: 'collection' | 'timeline' | 'sources'}) {
  const horizontal = config?.layout === 'horizontal', tiled = horizontal || config?.layout === 'grid';
  const titleWaiting = useRegionSkeleton('title', title) && title !== undefined, textWaiting = useRegionSkeleton('text', text) && text !== undefined, itemsWaiting = useRegionWaiting('items');
  const money = items.filter(item => item.value.type === 'money' && item.value.value !== null);
  const comparable = money.length > 1 && money.every(item => item.value.currency === money[0].value.currency);
  const lowest = config?.layout === 'table' && comparable ? money.reduce<UiValue | undefined>((best, item) => !best || compareUiMoney(item.value, best) === -1 ? item.value : best, undefined) : undefined;
  const body = <View style={[styles.collection, horizontal ? styles.horizontal : config?.layout === 'grid' && styles.wrap]}>
    {items.map((item, index) => <CollectionRow key={item.id} item={item} index={index} config={config} role={role} tiled={tiled} best={!!lowest && compareUiMoney(item.value, lowest) === 0}/>)}
    {itemsWaiting && [0, 1, 2].slice(items.length).map(index => <CollectionRow key={`pending-${index}`} index={index} config={config} role={role} tiled={tiled}/>)}
  </View>;
  return <View style={styles.stack}>
    {(!!title || titleWaiting) && (title ? <FeedText text={title} variant="body-md-medium"/> : <FeedPlaceholder kind="text"/>)}
    {horizontal ? <ScrollView horizontal showsHorizontalScrollIndicator={false}>{body}</ScrollView> : body}
    {text && <FeedText text={text} variant="body-sm" tone="secondary"/>}{textWaiting && <FeedPlaceholder kind="text"/>}
    {!!safeUrl(url) && <FeedPressable label={url!} role="link" onPress={() => openSafeUrl(url)}><SourceLabel url={url!}/></FeedPressable>}
  </View>;
}
function SourceLabel({url}: {url: string}) {
  const language = useContext(CardLanguage);
  return <FeedText text={`${language === 'zh' ? '打开来源' : 'Open source'} · ${url.replace(/^https?:\/\//, '').split('/')[0]}`} variant="body-sm" tone="link"/>;
}
function CollectionRow({item, index, config, role, tiled, best}: {item?: UiItem; index: number; config?: UiConfig; role: 'collection' | 'timeline' | 'sources'; tiled: boolean; best?: boolean}) {
  const language = useContext(CardLanguage), pending = useItemRegions(item), sized = !!config?.size, image = safeUrl(item?.image), target = safeUrl(item?.url);
  const narrative = item?.value.type === 'text' || item?.value.type === 'instant', valueKnown = item && item.value.value !== null && item.value.value !== '';
  const labelWaiting = !item || pending.includes('label'), detailWaiting = !item || pending.includes('detail');
  const label = item?.label ? <FeedText text={item.label} variant={sized ? 'body-sm' : 'body-md-medium'}/> : labelWaiting ? <FeedPlaceholder kind="text"/> : null;
  const detail = <>{item?.detail && item.detail !== item.value.value && <FeedText text={item.detail} variant="body-sm" tone="secondary"/>}{detailWaiting && <FeedPlaceholder kind="text"/>}</>;
  const scalar = <>{valueKnown && <FeedText text={formatUiValue(item.value, language)} variant="body-sm"/>}{pending.includes('value') && <FeedPlaceholder kind="value"/>}</>;
  const leading = image ? <ProgressiveImage src={image} width={42} height={42} label={item?.label}/> : pending.includes('image') ? <FeedPlaceholder kind="media"/> : !sized ? <FeedInset radius={radii.full} fill="content-100"><View style={styles.ordinal}>
    {!item ? <FeedPlaceholder kind="text"/> : role === 'timeline' ? <FeedText text="•"/> : item.icon ? <FeedIcon name={item.icon}/> : <FeedText text={String(index + 1).padStart(2, '0')} variant="body-sm"/>}
  </View></FeedInset> : undefined;
  const body = <FeedItem layout={tiled ? 'tile' : 'row'} leading={leading} corner={best ? <FeedCornerTag tone="quiet" label={language === 'zh' ? '最低' : 'Lowest'}/> : undefined}>
    <View style={styles.itemBody}>{label}{sized && item?.icon && <FeedIcon name={item.icon} size="medium"/>}
      {narrative && scalar}{detail}{!narrative && scalar}
    </View>
  </FeedItem>;
  return <View style={tiled ? {width: sized ? 100 : 176} : undefined}>{target ? <FeedPressable label={item?.label ?? target} role="link" onPress={() => openSafeUrl(target)}>{body}</FeedPressable> : body}</View>;
}
export function RouteView({title, text, value, items, image, url, config}: Content & {items: UiItem[]; image?: string; url?: string}) {
  const src = safeUrl(image), language = useContext(CardLanguage);
  return <Caption title={title} text={text}>{src && <ProgressiveImage src={src} height={170} label={title}/>}
    {value && value.value !== null && <FeedText text={formatUiValue(value, language)} variant="body-sm"/>}
    <Collection items={items} role="timeline" config={config}/>{!!safeUrl(url) && <FeedPressable label={url!} role="link" onPress={() => openSafeUrl(url)}><FeedText text={url!} variant="body-sm" tone="link"/></FeedPressable>}
  </Caption>;
}
export function MediaSurface({title, text, value, image, url, config}: Content & {image?: string; url?: string}) {
  const language = useContext(CardLanguage), matterId = useContext(CardMatter), imageWaiting = useRegionWaiting('image'), urlWaiting = useRegionWaiting('url');
  const waitingForSource = !image && !url && (imageWaiting || urlWaiting);
  return <Caption title={title} text={text}>
    {config?.captureSource ? matterId ? <AudioCapture matterId={matterId} config={config} language={language}/> :
      <FeedText text={language === 'zh' ? '音频控制需要已连接的事项。' : 'Audio controls require a connected matter.'} variant="body-sm"/> :
      !waitingForSource && <MediaContent mediaType={config?.mediaType ?? 'image'} config={config} image={image} url={url} language={language} title={title}/>}
    {value && value.value !== null && <FeedText text={formatUiValue(value, language)} variant="body-sm"/>}
  </Caption>;
}
export function Overlay({title, text, value, image, config, children}: Content & {image?: string; children?: ReactNode}) {
  const src = safeUrl(image);
  return <FeedMediaFrame kind="image"><View style={styles.overlay}>
    {src && <View style={StyleSheet.absoluteFill}><ProgressiveImage src={src} height="100%" fit="cover" label={title}/></View>}
    <FeedInset fill="surface-900"><View style={styles.caption}><Caption title={title} text={text}/>{value && value.value !== null && <Metric value={value} config={config}/>}{children}</View></FeedInset>
  </View></FeedMediaFrame>;
}
export function Progress({title, text, value, config}: Content) {
  const language = useContext(CardLanguage), min = config?.min ?? 0, max = config?.max ?? 1;
  const n = value?.type === 'number' && typeof value.value === 'number' ? value.value : null;
  const fraction = n === null ? null : Math.min(1, Math.max(0, (n - min) / (max - min)));
  return <Caption title={title} text={text}><FeedProgress value={fraction} valueText={formatUiValue(value, language)}/></Caption>;
}
const styles = StyleSheet.create({
  caption: {gap: spacing[6], minWidth: 0}, stack: {gap: spacing[12], minWidth: 0}, supporting: {flexDirection: 'row', flexWrap: 'wrap', gap: spacing[12]},
  metricRow: {flexDirection: 'row', alignItems: 'center', gap: spacing[12]}, metricIcon: {marginLeft: 'auto'},
  metricValue: {minWidth: 0, maxWidth: '100%', flexShrink: 1},
  collection: {gap: spacing[12]}, horizontal: {flexDirection: 'row'}, wrap: {flexDirection: 'row', flexWrap: 'wrap'}, itemBody: {minWidth: 0, flex: 1, gap: spacing[4]},
  ordinal: {width: 30, height: 30, alignItems: 'center', justifyContent: 'center'}, overlay: {minHeight: 140, padding: spacing[16], justifyContent: 'flex-end', gap: spacing[12]},
});
