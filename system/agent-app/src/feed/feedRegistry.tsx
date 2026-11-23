/** Pixel renderers for `taku-feed-1`. Every size, radius, color and type style comes from the Figma feed spec. */
import React, {createContext, useContext, useEffect, useMemo, useRef, useState, type ReactNode} from 'react';
import {ActivityIndicator, Animated, PanResponder, Platform, Pressable, ScrollView, StyleSheet, Text, View,
  type ScrollViewInstance, type StyleProp, type TextStyle, type ViewStyle} from 'react-native';
import Svg, {Defs, LinearGradient, Path, Rect, Stop} from 'react-native-svg';
import * as Haptics from 'expo-haptics';
import {defineRegistry, useBoundProp, useRepeatScope, useStateStore, type ComponentRenderProps} from '@json-render/react-native';
import {ICONS, feedCatalog, type IconName} from './feedCatalog';
import {accent, containsCjk, content, fills, frosted, main, radii, surface, textStyle, tones, type Fill, type TextVariant, type Tone} from './feedTokens';
import {levelAt} from './value';
import {FeedButton, FeedCornerTag, FeedHeader, FeedImage, FeedInset, FeedItem, FeedProgress, FeedSelection, FeedTags, FeedText, FeedValue} from './feedVisual';
import {hasUnavailableBinding, type ActionAvailability} from './actionAvailability';

/** Reports a failed dispatch so the card can say so instead of pretending it worked. */
export const FeedActionError = createContext<(message: string | null) => void>(() => {});
export const FeedActionAvailability = createContext<ActionAvailability>({});
const FeedControlDisabled = createContext(false);

function ActionControl({children}: {children: (disabled: boolean) => ReactNode}) {
  return children(useContext(FeedControlDisabled));
}

/** FeedText with a style override, for mention chips, quote meta and word-wrapped message runs. */
function T({text, variant = 'body-md', tone = 'primary', style, lines}: {text: string; variant?: TextVariant; tone?: Tone; style?: StyleProp<TextStyle>; lines?: number}) {
  return <Text numberOfLines={lines} style={[textStyle(variant, containsCjk(text)), {color: tones[tone]}, style]}>{text}</Text>;
}


const iconRef = (name: IconName) => `asset:feed/${ICONS[name]}`;
function Glyph({name, size = 16}: {name: IconName; size?: number}) {
  return <FeedImage src={iconRef(name)} width={size} height={size}/>;
}
function Asset({src, width, height, radius, fit, label, style}: {src: string; width: number | `${number}%`; height?: number | `${number}%`; radius?: number; fit?: 'cover' | 'contain'; label?: string; style?: StyleProp<ViewStyle>}) {
  return style ? <View style={[{width, height}, style]}><FeedImage src={src} width="100%" height="100%" radius={radius} fit={fit} label={label}/></View>
    : <FeedImage src={src} width={width} height={height} radius={radius} fit={fit} label={label}/>;
}

const alignMap = {start: 'flex-start', center: 'center', end: 'flex-end', stretch: 'stretch'} as const;
const justifyMap = {start: 'flex-start', center: 'center', end: 'flex-end', between: 'space-between'} as const;
type BoxProps = {gap?: number; padding?: number; paddingX?: number; paddingY?: number; align?: keyof typeof alignMap; justify?: keyof typeof justifyMap;
  flex?: number; height?: number; fill?: Fill; radius?: number; wrap?: boolean; outline?: 'white'};
const boxStyle = (p: BoxProps, row: boolean): ViewStyle => ({
  flexDirection: row ? 'row' : 'column', gap: p.gap, padding: p.padding, paddingHorizontal: p.paddingX, paddingVertical: p.paddingY,
  alignItems: p.align ? alignMap[p.align] : row ? 'center' : 'stretch', justifyContent: p.justify ? justifyMap[p.justify] : undefined,
  flex: p.flex, minWidth: p.flex != null ? 0 : undefined, minHeight: p.flex != null ? 0 : undefined, height: p.height,
  backgroundColor: p.fill ? fills[p.fill] : undefined, borderRadius: p.radius, overflow: p.radius ? 'hidden' : undefined, flexWrap: p.wrap ? 'wrap' : undefined,
  borderWidth: p.outline ? 1 : undefined, borderColor: p.outline ? main.surface : undefined,
});

/** Vertical level track (3-2 temperature): drag anywhere on it; the value is sent on release. */
function LevelTrack({value, min, max, label, binding, emit, disabled}: {value: number; min: number; max: number; label: string; binding?: string; emit: (event: string) => void; disabled: boolean}) {
  const [bound, setBound] = useBoundProp<number>(value, binding);
  const [drag, setDrag] = useState<number | null>(null);
  const [height, setHeight] = useState(0);
  const shown = drag ?? (binding ? bound : value) ?? min;
  const share = max > min ? Math.min(1, Math.max(0, (shown - min) / (max - min))) : 0;
  const commit = (next: number) => { if (disabled) return; setDrag(null); if (binding) setBound(next); emit('change'); };
  return <View accessible accessibilityRole="adjustable" accessibilityLabel={label} accessibilityValue={{min, max, now: shown}} accessibilityState={{disabled}}
    accessibilityActions={disabled ? undefined : [{name: 'increment'}, {name: 'decrement'}]}
    onAccessibilityAction={event => commit(Math.min(max, Math.max(min, shown + (event.nativeEvent.actionName === 'increment' ? 1 : -1))))}
    onLayout={event => setHeight(event.nativeEvent.layout.height)} style={[styles.level, disabled && styles.disabled]}
    onStartShouldSetResponder={() => !disabled} onMoveShouldSetResponder={() => !disabled}
    // The drag belongs to the track: the page and the Home pager must not take it over.
    onResponderTerminationRequest={() => false}
    onResponderGrant={event => { if (!disabled) setDrag(levelAt(event.nativeEvent.locationY, height, min, max)); }}
    onResponderMove={event => { if (!disabled) setDrag(levelAt(event.nativeEvent.locationY, height, min, max)); }}
    onResponderRelease={event => commit(levelAt(event.nativeEvent.locationY, height, min, max))}
    onResponderTerminate={() => setDrag(null)}>
    <View pointerEvents="none" style={[styles.levelFill, {height: `${Math.max(12, share * 100)}%`}]}><View style={styles.levelHandle}/></View>
  </View>;
}

function Toggle({on}: {on: boolean}) {
  return <Svg width={40} height={24} viewBox="0 0 40 24">
    <Path d="M0 12C0 5.37 5.37 0 12 0H28C34.63 0 40 5.37 40 12C40 18.63 34.63 24 28 24H12C5.37 24 0 18.63 0 12Z" fill={on ? main.foreground : '#000'} fillOpacity={on ? 1 : 0.16}/>
    <Path d={`M${on ? 20 : 4} 12a8 8 0 1 0 16 0a8 8 0 1 0 -16 0`} fill="#FFFFFF"/>
  </Svg>;
}

type Endpoint = {code: string; time: string; date: string};
function FlightEnd({end}: {end: Endpoint}) {
  return <View style={styles.flightEnd}>
    <T text={end.code} variant="body-lg" tone="tertiary"/>
    <T text={end.time} variant="heading-2xl" tone="secondary"/>
    <T text={end.date} variant="body-sm" tone="muted" lines={1}/>
  </View>;
}

const MENTION = /(@[\p{L}\p{N}_.-]+)/u;
/** Lay out mention chips beside text, not as Android inline views with a different baseline. */
function MentionText({text}: {text: string}) {
  return <View accessible accessibilityLabel={text}>
    {text.split(/\r?\n/).map((line, lineIndex) => MENTION.test(line)
      ? <View key={lineIndex} style={styles.messageLine}>
        {line.split(MENTION).flatMap((part, partIndex) => partIndex % 2 === 1
          ? [<View key={partIndex} style={styles.mention}>
            <T text={part} variant="body-md-medium" style={{color: accent.mentionInk}}/>
          </View>]
          : (part.match(/\S+\s*|\s+/g) ?? []).map((word, wordIndex) =>
            <T key={`${partIndex}-${wordIndex}`} text={word} tone="secondary" style={styles.messageWord}/>))}
      </View>
      : <T key={lineIndex} text={line || '\u00a0'} tone="secondary"/>)}
  </View>;
}

function DispatchButton({label, variant, size, icon, flex, emit, disabled}: {label: string; variant?: 'primary' | 'secondary'; size?: 'lg' | 'md'; icon?: 'sparkle'; flex?: number; emit: (event: string) => unknown; disabled: boolean}) {
  const [sending, setSending] = useState(false);
  const report = useContext(FeedActionError);
  const press = async () => {
    if (sending || disabled) return;
    setSending(true); report(null);
    try {await emit('press');}
    catch (error) { report(error instanceof Error ? error.message : String(error)); }
    finally { setSending(false); }
  };
  return <FeedButton label={label} variant={variant} size={size} icon={icon} flex={flex} busy={sending} disabled={disabled} onPress={() => { void press(); }}/>;
}

function Segmented({options, value, binding, emit, disabled}: {options: {id: string; label: string}[]; value?: string | null; binding?: string; emit: (event: string) => void; disabled: boolean}) {
  const [bound, setBound] = useBoundProp<string | null | undefined>(value, binding);
  const [local, setLocal] = useState(value ?? options[0]?.id);
  const selected = binding ? bound : local;
  return <View accessibilityRole="radiogroup" style={styles.segmented}>
    {options.map(option => {
      const active = option.id === selected;
      return <Pressable key={option.id} accessibilityRole="radio" accessibilityState={{selected: active, disabled}} accessibilityLabel={option.label} disabled={disabled}
        onPress={() => { if (disabled) return; if (binding) setBound(option.id); else setLocal(option.id); emit('change'); }}
        style={[styles.segment, active && {backgroundColor: surface[800]}, disabled && styles.disabled]}>
        <FeedText text={option.label} lines={1}/>
      </Pressable>;
    })}
  </View>;
}

function Checklist({items, checked, binding, fade, emit, disabled}: {items: {id: string; label: string}[]; checked?: string[] | null; binding?: string; fade?: boolean; emit: (event: string) => void; disabled: boolean}) {
  const [bound, setBound] = useBoundProp<string[] | null | undefined>(checked, binding);
  const [local, setLocal] = useState<string[]>(checked ?? []);
  const done = (binding ? bound : local) ?? [];
  const toggle = (id: string) => {
    if (disabled) return;
    const next = done.includes(id) ? done.filter(item => item !== id) : [...done, id];
    if (binding) setBound(next); else setLocal(next);
    emit('change');
  };
  return <View style={styles.checklist}>
    {items.map(item => {
      const on = done.includes(item.id);
      return <FeedSelection key={item.id} label={item.label} kind="checkbox" checked={on} disabled={disabled} compact onPress={() => toggle(item.id)}>
        <FeedText text={item.label} tone={on ? 'tertiary' : 'primary'} lines={1} flex={1} strike={on}/>
      </FeedSelection>;
    })}
    {fade !== false ? <Svg pointerEvents="none" width="100%" height={24} style={styles.fade}>
      <Defs><LinearGradient id="fade" x1="0" y1="0" x2="0" y2="1"><Stop offset="0" stopColor="#fff" stopOpacity={0}/><Stop offset="1" stopColor="#fff" stopOpacity={0.24}/></LinearGradient></Defs>
      <Rect width="100%" height="100%" fill="url(#fade)"/>
    </Svg> : null}
  </View>;
}

const toneColor = {green: accent.green, brand: accent.brand, foreground: main.foreground} as const;

/** 180° gauge drawn from value: centerline 140×70, stroke 12, round caps. */
function ArcGauge({value, tone = 'brand', children}: {value: number; tone?: keyof typeof toneColor; children?: ReactNode}) {
  const r = 70, cx = 76, cy = 76, v = Math.max(0, Math.min(1, value));
  const angle = Math.PI * (1 - v);
  const end = {x: cx + r * Math.cos(angle), y: cy - r * Math.sin(angle)};
  return <View style={styles.gauge}>
    <Svg width={152} height={82} style={styles.gaugeArc}>
      <Path d={`M ${cx - r} ${cy} A ${r} ${r} 0 0 1 ${cx + r} ${cy}`} stroke={content[200]} strokeWidth={12} strokeLinecap="round" fill="none"/>
      {v > 0 ? <Path d={`M ${cx - r} ${cy} A ${r} ${r} 0 0 1 ${end.x.toFixed(2)} ${end.y.toFixed(2)}`} stroke={toneColor[tone]} strokeWidth={12} strokeLinecap="round" fill="none"/> : null}
    </Svg>
    <View style={styles.gaugeCenter}>{children}</View>
  </View>;
}

type Post = {author: string; avatar: string; image: string; likes?: string};
/** One Instagram post tile; back posts are the same tile at 0.9 scale. */
function PostTile({post, s, front}: {post: Post; s: number; front: boolean}) {
  const label = (text: string) => <Text style={[textStyle('body-md'), {fontSize: 14 * s, lineHeight: 14 * s * 1.42, letterSpacing: -0.14 * s, color: content[800]}]}>{text}</Text>;
  return <View style={[styles.post, front ? styles.postFront : styles.postBack, {width: 200 * s, height: front ? 267 : 240.3, padding: 4 * s, gap: 8 * s,
    borderRadius: front ? 12 : 8, borderWidth: s}]}>
    <View style={[styles.rowCenter, {gap: 4 * s}]}>
      <Asset src={post.avatar} width={24 * s} height={24 * s} radius={12 * s}/>{label(post.author)}
    </View>
    <Asset src={post.image} width="100%" height={192 * s} radius={4 * s}/>
    <View style={[styles.rowCenter, {gap: 12 * s, overflow: 'hidden'}]}>
      <View style={[styles.rowCenter, {gap: 4 * s}]}><Asset src={iconRef('heart')} width={16 * s} height={16 * s}/>{post.likes ? label(post.likes) : null}</View>
      <Asset src={iconRef('comment')} width={16 * s} height={16 * s}/>
      <Asset src={iconRef('share')} width={16 * s} height={16 * s}/>
    </View>
  </View>;
}

function MediaStack({posts, emit, disabled}: {posts: Post[]; emit: (event: string) => void; disabled: boolean}) {
  const [front, left, right] = posts;
  const back = (post: Post | undefined, side: -1 | 1) => post ? <View pointerEvents="none" style={[styles.stackSlot, {transform: [{translateX: side * 60.15}, {rotate: `${side * 8}deg`}]}]}>
    <PostTile post={post} s={0.9} front={false}/>
  </View> : null;
  return <Pressable accessibilityRole="button" accessibilityLabel={`${posts.length} posts, ${front.author} first`} accessibilityState={{disabled}} disabled={disabled}
    onPress={() => emit('press')} style={[styles.stack, disabled && styles.disabled]}>
    {back(left, -1)}{back(right, 1)}
    <View style={styles.stackSlot}><PostTile post={front} s={1} front/></View>
  </Pressable>;
}

/** Slide to confirm: thumb follows the finger; reaching the end fires `complete` once, then resets. */
function SlideToAct({label, icon = 'unlock', emit, disabled}: {label: string; icon?: IconName; emit: (event: string) => unknown; disabled: boolean}) {
  const [width, setWidth] = useState(0);
  const [busy, setBusy] = useState(false);
  const report = useContext(FeedActionError);
  const [x] = useState(() => new Animated.Value(0));
  const max = Math.max(0, width - 56);
  // Gesture callbacks outlive renders; they read the latest values from this box.
  const state = useRef({max, busy, emit, report, disabled});
  useEffect(() => { state.current = {max, busy, emit, report, disabled}; });
  const reset = () => Animated.spring(x, {toValue: 0, useNativeDriver: Platform.OS !== 'web', bounciness: 4}).start();
  // The responder's callbacks read state.current only while a gesture runs, never during render.
  // eslint-disable-next-line react-hooks/refs
  const responder = useMemo(() => PanResponder.create({
    onStartShouldSetPanResponder: () => !state.current.busy && !state.current.disabled,
    onMoveShouldSetPanResponder: (_e, g) => !state.current.busy && !state.current.disabled && Math.abs(g.dx) > Math.abs(g.dy),
    onPanResponderTerminationRequest: () => false,
    onPanResponderMove: (_e, g) => { if (!state.current.disabled) x.setValue(Math.max(0, Math.min(state.current.max, g.dx))); },
    onPanResponderRelease: (_e, g) => {
      const {max: end} = state.current;
      if (state.current.disabled) { reset(); return; }
      if (end > 0 && g.dx >= end * 0.9) {
        setBusy(true);
        Animated.timing(x, {toValue: end, duration: 120, useNativeDriver: Platform.OS !== 'web'}).start();
        void (Platform.OS === 'android' ? Haptics.performAndroidHapticsAsync(Haptics.AndroidHaptics.Confirm) : Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success)).catch(() => {});
        state.current.report(null);
        void Promise.resolve(state.current.emit('complete'))
          .catch(error => state.current.report(error instanceof Error ? error.message : String(error)))
          .finally(() => { setBusy(false); reset(); });
      } else reset();
    },
    onPanResponderTerminate: () => reset(),
  // x is a stable Animated.Value; everything else is read through state.current.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }), []);
  const labelOpacity = max ? x.interpolate({inputRange: [0, max * 0.6], outputRange: [1, 0], extrapolate: 'clamp'}) : 1;
  return <View accessible accessibilityRole="adjustable" accessibilityLabel={label} accessibilityState={{busy, disabled}}
    accessibilityActions={disabled ? undefined : [{name: 'activate', label}]}
    onAccessibilityAction={() => {if (!busy && !disabled) {setBusy(true); void Promise.resolve(emit('complete'))
      .catch(error => report(error instanceof Error ? error.message : String(error))).finally(() => setBusy(false));}}}
    onLayout={e => setWidth(e.nativeEvent.layout.width)} style={[styles.slide, disabled && styles.disabled]}>
    <Animated.View pointerEvents="none" style={[styles.slideLabel, {opacity: labelOpacity}]}>
      <FeedText text={label} variant="body-lg" tone="tertiary" lines={1}/>
    </Animated.View>
    <Animated.View pointerEvents="none" style={[styles.chevrons, {opacity: labelOpacity}]}><Asset src={iconRef('chevrons')} width={35.22} height={11.5}/></Animated.View>
    <Animated.View {...responder.panHandlers} style={[styles.thumb, {transform: [{translateX: x}]}]}>
      {busy ? <ActivityIndicator size="small" color={main.foreground}/> : <Glyph name={icon} size={24}/>}
    </Animated.View>
  </View>;
}

/** Horizontal pages; height follows the current page so the card resizes as you swipe. */
function Pager({index, binding, emit, children, disabled}: {index?: number | null; binding?: string; emit: (event: string) => void; children?: ReactNode; disabled: boolean}) {
  const pages = React.Children.toArray(children);
  const [bound, setBound] = useBoundProp<number | null | undefined>(index, binding);
  const [local, setLocal] = useState(index ?? 0);
  const current = Math.max(0, Math.min(pages.length - 1, (binding ? bound : local) ?? 0));
  const [width, setWidth] = useState(0);
  const [heights, setHeights] = useState<number[]>([]);
  const scroll = useRef<ScrollViewInstance>(null);
  useEffect(() => { if (width) scroll.current?.scrollTo({x: current * width, animated: true}); }, [current, width]);
  const settle = (offset: number) => {
    if (disabled) return;
    if (!width) return;
    const next = Math.round(offset / width);
    if (next === current) return;
    if (binding) setBound(next); else setLocal(next);
    emit('change');
  };
  return <View onLayout={e => setWidth(e.nativeEvent.layout.width)} style={{height: heights[current]}}>
    {width ? <ScrollView ref={scroll} horizontal pagingEnabled scrollEnabled={!disabled} accessibilityState={{disabled}} showsHorizontalScrollIndicator={false} contentOffset={{x: current * width, y: 0}}
      onMomentumScrollEnd={e => settle(e.nativeEvent.contentOffset.x)}
      onScrollEndDrag={Platform.OS === 'web' ? e => settle(e.nativeEvent.contentOffset.x) : undefined}
      scrollEventThrottle={16} contentContainerStyle={styles.pagerContent}>
      {pages.map((page, i) => <View key={i} style={{width}} onLayout={e => {
        const h = e.nativeEvent.layout.height;
        setHeights(prev => prev[i] === h ? prev : Object.assign([...prev], {[i]: h}));
      }}>{page}</View>)}
    </ScrollView> : <View>{pages[0]}</View>}
  </View>;
}

function Dots({count, index, variant = 'dot'}: {count: number; index?: number | null; variant?: 'dot' | 'pill'}) {
  return <View accessible accessibilityLabel={`Page ${(index ?? 0) + 1} of ${count}`} style={styles.dots}>
    {Array.from({length: count}, (_, i) => <View key={i} style={[styles.dot, i === (index ?? 0) && {backgroundColor: main.foreground, width: variant === 'pill' ? 12 : 6}]}/>)}
  </View>;
}

const {registry: baseRegistry} = defineRegistry(feedCatalog, {components: {
  Column: ({props, children}) => <View style={boxStyle(props, false)}>{children}</View>,
  Row: ({props, children}) => <View style={boxStyle(props, true)}>{children}</View>,
  Spacer: ({props}) => <View style={props.flex != null ? {flex: props.flex} : {width: props.size ?? 8, height: props.size ?? 8}}/>,
  Text: ({props}) => <FeedText {...props}/>,
  Value: ({props}) => <FeedValue text={props.text} size={props.size} tone={props.tone}/>,
  Icon: ({props}) => {
    const src = props.src ?? (props.name ? iconRef(props.name) : undefined);
    return src ? <Asset src={src} width={props.size ?? 16} height={props.size ?? 16} fit="contain" label={props.label}/> : null;
  },
  AppHeader: ({props}) => <FeedHeader {...props}/>,
  CornerTag: ({props}) => <FeedCornerTag {...props}/>,
  Inset: ({props, children}) => <FeedInset fill={props.fill} radius={props.radius}>
    {props.tag ? <FeedCornerTag {...props.tag}/> : null}
    {children}
  </FeedInset>,
  MessageQuote: ({props}) => <View style={styles.quote}>
    <Asset src={props.avatar} width={32} height={32} radius={radii.md}/>
    <View style={styles.quoteBody}>
      <View style={styles.quoteMeta}>
        <View style={styles.quoteAuthor}>
          <T text={props.author} variant="body-lg-medium" lines={1} style={styles.quoteMetaText}/>
          {props.time ? <T text={props.time} tone="tertiary" lines={1} style={styles.quoteMetaText}/> : null}
        </View>
        {props.channel ? <T text={props.channel} tone="tertiary" lines={1} style={styles.quoteMetaText}/> : null}
      </View>
      <MentionText text={props.text}/>
    </View>
  </View>,
  Button: ({props, emit}) => <ActionControl>{disabled => <DispatchButton {...props} emit={emit} disabled={disabled}/>}</ActionControl>,
  IconButton: ({props, emit}) => {
    const glass = props.variant === 'glass';
    const size = props.size ?? (glass ? 32 : 40);
    return <ActionControl>{disabled => <Pressable accessibilityRole="button" accessibilityLabel={props.label} accessibilityState={{disabled}} disabled={disabled}
      onPress={() => emit('press')} hitSlop={glass ? 8 : 0}
      style={({pressed}) => [styles.iconButton, glass && styles.iconButtonGlass, {width: size, height: size, borderRadius: size / 2}, pressed && styles.pressed, disabled && styles.disabled]}>
      <Glyph name={props.icon} size={glass ? 18 : 16}/>
    </Pressable>}</ActionControl>;
  },
  Tags: ({props}) => <FeedTags items={props.items}/>,
  Segmented: ({props, bindings, emit}) => <ActionControl>{disabled => <Segmented options={props.options} value={props.value} binding={bindings?.value} emit={emit} disabled={disabled}/>}</ActionControl>,
  ProductImage: ({props}) => <View style={[styles.product, {flex: props.flex ?? 1}]}>
    {props.shadow !== false ? <Asset src="asset:feed/starbucks/shadow-ellipse.svg" width="47.13%" height="19.54%" style={styles.productShadow}/> : null}
    <Asset src={props.src} width="100%" height="100%" fit="contain" label={props.alt} style={StyleSheet.absoluteFill}/>
  </View>,
  Image: ({props}) => <View style={{width: props.width ?? '100%', height: props.aspectRatio ? undefined : props.height ?? 120, aspectRatio: props.aspectRatio, flex: props.flex}}>
    <FeedImage src={props.src} label={props.alt} width="100%" height="100%" radius={props.radius} fit={props.fit}/></View>,
  Avatar: ({props}) => {
    const size = props.size ?? 32;
    return <Asset src={props.src} width={size} height={size} radius={props.shape === 'rounded' ? size * 0.1875 : size / 2}/>;
  },
  Checklist: ({props, bindings, emit}) => <ActionControl>{disabled => <Checklist items={props.items} checked={props.checked} binding={bindings?.checked} fade={props.fade} emit={emit} disabled={disabled}/>}</ActionControl>,
  ProgressBar: ({props}) => <FeedProgress value={props.value} tone={props.tone}/>,
  ArcGauge: ({props, children}) => <ArcGauge value={props.value} tone={props.tone}>{children}</ArcGauge>,
  MapRoute: ({props, children}) => {
    const {art} = props;
    return <View style={[styles.map, {height: props.height ?? 267}]}>
      <View pointerEvents="none" style={[styles.mapArt, {width: art.width, height: art.height, marginLeft: -art.width / 2 + (art.offsetX ?? 0), marginTop: -art.height / 2 + (art.offsetY ?? 0)}]}>
        {props.layers.map((layer, i) => <Asset key={i} src={layer.src} width={layer.width} height={layer.height} style={{position: 'absolute', left: layer.x, top: layer.y}}/>)}
      </View>
      {props.callouts.map((callout, i) => <View key={i} style={[styles.callout, {left: callout.x, top: callout.y}]}>
        {callout.eta ? <View style={styles.eta}>
          <FeedText text={callout.eta} variant="body-sm-medium" tone="inverseSoft"/>
          {callout.etaUnit ? <FeedText text={callout.etaUnit} variant="body-sm" tone="inverseMuted"/> : null}
        </View> : null}
        <View style={styles.calloutText}>
          <FeedText text={callout.title} variant="body-sm-medium" tone="secondary" lines={1}/>
          {callout.subtitle ? <FeedText text={callout.subtitle} variant="body-sm" tone="tertiary" lines={1}/> : null}
        </View>
      </View>)}
      {children ? <View style={styles.mapOverlay}>{children}</View> : null}
    </View>;
  },
  OptionRow: ({props, emit}) => <ActionControl>{disabled => <FeedItem disabled={disabled} onPress={() => emit('press')} leading={<Asset src={props.image} width={48} height={48} fit="contain"/>}
    trailing={<View style={styles.optionPrice}>{props.price ? <FeedText text={props.price} variant="body-xl-medium"/> : null}{props.subprice ? <FeedText text={props.subprice} tone="tertiary"/> : null}</View>}
    corner={props.tag ? <FeedCornerTag tone="solid" {...props.tag}/> : undefined}>
      <View>
        <View style={[styles.rowCenter, {gap: 8}]}>
          <FeedText text={props.title} variant="body-xl-medium" lines={1}/>
          {props.badgeText ? <View style={[styles.rowCenter, {gap: 2}]}>{props.badgeIcon ? <Glyph name={props.badgeIcon}/> : null}<FeedText text={props.badgeText} variant="body-xl-medium"/></View> : null}
        </View>
        <View style={[styles.rowCenter, {gap: 4}]}>
          {props.meta.map((item, i) => <React.Fragment key={i}>{i ? <View style={styles.metaDot}/> : null}<FeedText text={item} tone="tertiary" lines={1}/></React.Fragment>)}
        </View>
      </View>
  </FeedItem>}</ActionControl>,
  MediaStack: ({props, emit}) => <ActionControl>{disabled => <MediaStack posts={props.posts} emit={emit} disabled={disabled}/>}</ActionControl>,
  SlideToAct: ({props, emit}) => <ActionControl>{disabled => <SlideToAct label={props.label} icon={props.icon} emit={emit} disabled={disabled}/>}</ActionControl>,
  Pager: ({props, bindings, emit, children}) => <ActionControl>{disabled => <Pager index={props.index} binding={bindings?.index} emit={emit} disabled={disabled}>{children}</Pager>}</ActionControl>,
  Dots: ({props}) => <Dots count={props.count} index={props.index} variant={props.variant}/>,
  Cover: ({props, children}) => {
    const {src, sheen, ...box} = props;
    return <View style={[boxStyle(box, false), styles.cover]}>
      <Asset src={src} width="100%" height="100%" style={StyleSheet.absoluteFill}/>
      {sheen ? <Svg pointerEvents="none" width="100%" height="100%" style={StyleSheet.absoluteFill}>
        <Defs><LinearGradient id="sheen" x1="0" y1="0" x2="0" y2="1"><Stop offset="0" stopColor="#fff" stopOpacity={0.16}/><Stop offset="1" stopColor="#fff" stopOpacity={0}/></LinearGradient></Defs>
        <Rect width="100%" height="100%" fill="url(#sheen)"/>
      </Svg> : null}
      {children}
    </View>;
  },
  Chip: ({props}) => <View style={styles.statusChip}>
    {props.dot ? <View style={[styles.statusDot, {backgroundColor: props.dot === 'green' ? '#34C759' : accent.brand}]}/> : null}
    <T text={props.label} tone="secondary" lines={1}/>
  </View>,
  Switch: ({props, emit}) => <ActionControl>{disabled => <Pressable accessibilityRole="switch" accessibilityLabel={props.label} accessibilityState={{checked: props.on, disabled}} disabled={disabled}
    onPress={() => emit('press')} hitSlop={8} style={({pressed}) => [styles.switchBox, pressed && styles.pressed, disabled && styles.disabled]}>
    <Toggle on={props.on}/>
  </Pressable>}</ActionControl>,
  LevelTrack: ({props, bindings, emit}) => <ActionControl>{disabled => <LevelTrack value={props.value} min={props.min} max={props.max} label={props.label} binding={bindings?.value} emit={emit} disabled={disabled}/>}</ActionControl>,
  Timeline: ({props}) => <View>
    {props.items.map((item, i) => {
      const first = i === 0, last = i === props.items.length - 1;
      return <View key={`${item.marker}-${i}`} style={styles.timelineRow}>
        <View style={styles.rail}>
          <View style={[styles.railLine, first && styles.railHidden]}/>
          <View style={styles.marker}><T text={item.marker} variant="body-sm-medium" tone="muted"/></View>
          <View style={[styles.railLine, last && styles.railHidden]}/>
        </View>
        <View style={[styles.timelineItem, !last && {paddingBottom: 8}]}>
          {item.image ? <Asset src={item.image} width={82} height={82} radius={radii.base}/> : null}
          <View style={styles.timelineText}>
            <View style={styles.timelineTop}>
              <View style={styles.timelineBadge}><T text={item.badge} variant="body-sm" tone="tertiary"/></View>
              {item.sources ? <Asset src={item.sources} width={32} height={16} fit="contain"/> : null}
            </View>
            <View style={{gap: 2}}>
              <T text={item.title} variant="body-lg-medium" tone="secondary" lines={1}/>
              {item.note ? <T text={item.note} tone="tertiary" lines={1}/> : null}
            </View>
          </View>
        </View>
      </View>;
    })}
  </View>,
  FlightRoute: ({props}) => <View style={styles.flightRoute}>
    <FlightEnd end={props.from}/>
    <View style={styles.flightArc}>
      <T text={props.number} tone="tertiary"/>
      <Svg width={110} height={18} viewBox="-2 -2 110 18">
        <Defs><LinearGradient id="arc" x1="0" y1="0" x2="1" y2="0"><Stop offset="0" stopColor="#000" stopOpacity={0.48}/><Stop offset="1" stopColor="#000" stopOpacity={0.08}/></LinearGradient></Defs>
        <Path d="M0.75 12.75Q53 -11.5 105.4 12.75" stroke="url(#arc)" strokeWidth={1.5} strokeLinecap="round" strokeDasharray="2.6 2.4" fill="none"/>
        <Path d="M-1.25 13a2 2 0 1 0 4 0a2 2 0 1 0 -4 0" fill="rgba(0,0,0,0.48)"/>
        <Path d="M103.4 13a2 2 0 1 0 4 0a2 2 0 1 0 -4 0" fill="rgba(0,0,0,0.08)"/>
      </Svg>
      <T text={props.duration} variant="body-sm" tone="muted"/>
    </View>
    <FlightEnd end={props.to}/>
  </View>,
  Link: ({props, emit}) => <ActionControl>{disabled => <Pressable accessibilityRole="button" accessibilityState={{disabled}} disabled={disabled}
    onPress={() => emit('press')} hitSlop={8} style={({pressed}) => [styles.link, pressed && styles.pressed, disabled && styles.disabled]}>
    <T text={props.label} tone="secondary"/>
  </Pressable>}</ActionControl>,
}});

/** The catalog adapter passes only resolved props; inspect event bindings before that boundary. */
function withActionAvailability(Control: React.ComponentType<ComponentRenderProps>) {
  return function AvailableControl(props: ComponentRenderProps) {
    const availability = useContext(FeedActionAvailability);
    const {state} = useStateStore();
    const repeat = useRepeatScope();
    const disabled = hasUnavailableBinding(props.element.on, availability, {stateModel: state,
      repeatItem: repeat?.item, repeatIndex: repeat?.index, repeatBasePath: repeat?.basePath});
    return <FeedControlDisabled.Provider value={disabled}><Control {...props}/></FeedControlDisabled.Provider>;
  };
}

const feedRegistry = {...baseRegistry};
for (const name of ['Button', 'IconButton', 'Segmented', 'Checklist', 'OptionRow', 'MediaStack', 'SlideToAct', 'Pager', 'Switch', 'LevelTrack', 'Link']) {
  feedRegistry[name] = withActionAvailability(baseRegistry[name]);
}
export {feedRegistry};

const styles = StyleSheet.create({
  pressed: {opacity: 0.6},
  disabled: {opacity: 0.4},
  iconButtonGlass: {backgroundColor: surface[800]},
  cover: {overflow: 'hidden'},
  statusChip: {flexDirection: 'row', alignItems: 'center', gap: 4, backgroundColor: surface[800], borderRadius: 16, paddingHorizontal: 12, paddingVertical: 6},
  statusDot: {width: 6, height: 6, borderRadius: 3},
  switchBox: {width: 40, height: 40, alignItems: 'center', justifyContent: 'center'},
  level: {width: 40, alignSelf: 'stretch', borderRadius: 20, backgroundColor: content[200], overflow: 'hidden', justifyContent: 'flex-end'},
  levelFill: {width: 40, backgroundColor: main.foreground, borderBottomLeftRadius: 20, borderBottomRightRadius: 20, borderTopLeftRadius: 4, borderTopRightRadius: 4, alignItems: 'center'},
  levelHandle: {width: 12, height: 4, borderRadius: 2, backgroundColor: main.surface, marginTop: 4},
  timelineRow: {flexDirection: 'row', gap: 8},
  rail: {width: 18, alignItems: 'center'},
  railLine: {flex: 1, width: 1, backgroundColor: content[200]},
  railHidden: {backgroundColor: 'transparent'},
  marker: {width: 18, height: 18, borderRadius: 9, backgroundColor: main.canvas, alignItems: 'center', justifyContent: 'center'},
  timelineItem: {flex: 1, minWidth: 0, flexDirection: 'row', gap: 4, backgroundColor: content[100], borderRadius: radii.lg, padding: 4},
  timelineText: {flex: 1, minWidth: 0, gap: 8, padding: 4, justifyContent: 'center'},
  timelineTop: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between'},
  timelineBadge: {backgroundColor: content[50], borderRadius: radii.base, paddingHorizontal: 4, paddingVertical: 2},
  flightRoute: {flexDirection: 'row', alignItems: 'center', padding: 8},
  flightEnd: {flex: 1, alignItems: 'center', gap: 4},
  flightArc: {flex: 1, alignItems: 'center', gap: 2},
  link: {alignSelf: 'center', paddingHorizontal: 8, paddingVertical: 2},
  rowCenter: {flexDirection: 'row', alignItems: 'center'},
  quote: {flexDirection: 'row', alignItems: 'flex-start', gap: 11, paddingTop: 8, paddingHorizontal: 8, paddingBottom: 12},
  quoteBody: {flex: 1, minWidth: 0, gap: 4},
  quoteAuthor: {flexDirection: 'row', alignItems: 'center', gap: 8, flexShrink: 1},
  quoteMeta: {minHeight: 16, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between'},
  quoteMetaText: {lineHeight: 16},
  messageLine: {flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center'},
  messageWord: {maxWidth: '100%'},
  mention: {backgroundColor: accent.mentionBg, borderRadius: radii.base, paddingHorizontal: 4, paddingVertical: 2, marginRight: 2, minHeight: 24, justifyContent: 'center'},
  iconButton: {backgroundColor: main.foreground, alignItems: 'center', justifyContent: 'center'},
  segmented: {flexDirection: 'row', padding: 4, borderRadius: radii.full, backgroundColor: content[200], alignSelf: 'stretch'},
  segment: {flex: 1, minWidth: 0, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 2, paddingVertical: 4, borderRadius: radii.full},
  product: {aspectRatio: 1, minWidth: 0, borderRadius: radii.xl, backgroundColor: content[100], overflow: 'hidden'},
  productShadow: {position: 'absolute', left: '33.33%', top: '75.86%'},
  // Content height by default (Panel cards size to their content); inside a bounded card
  // (square half Feed card) it shrinks and clips under the fade. `flex: 1` gave it a zero
  // basis, so content-sized cards showed no items at all (issue #35 item 3).
  checklist: {flexGrow: 0, flexShrink: 1, flexBasis: 'auto', minHeight: 0, gap: 12, overflow: 'hidden'},
  fade: {position: 'absolute', left: 0, right: 0, bottom: 0},
  gauge: {flex: 1, minHeight: 0, overflow: 'hidden', alignItems: 'center'},
  gaugeArc: {position: 'absolute', top: 0},
  gaugeCenter: {position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, alignItems: 'center', justifyContent: 'center', gap: 2, transform: [{translateY: 11}]},
  map: {width: '100%', borderRadius: radii.xl, backgroundColor: main.surface, overflow: 'hidden'},
  mapArt: {position: 'absolute', left: '50%', top: '50%', backgroundColor: content[200], overflow: 'hidden'},
  callout: {position: 'absolute', flexDirection: 'row', alignItems: 'stretch', borderRadius: radii.base, backgroundColor: frosted.strong, overflow: 'hidden'},
  eta: {width: 36, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 4, paddingVertical: 2, backgroundColor: main.foreground},
  calloutText: {justifyContent: 'center', paddingHorizontal: 4, paddingVertical: 2},
  mapOverlay: {position: 'absolute', left: 4, right: 4, bottom: 4},
  optionPrice: {alignItems: 'flex-end', justifyContent: 'center'},
  metaDot: {width: 2, height: 2, borderRadius: 1, backgroundColor: content[700]},
  stack: {height: 267, width: '100%', alignItems: 'center', justifyContent: 'center'},
  stackSlot: {position: 'absolute', alignItems: 'center', justifyContent: 'center'},
  post: {alignItems: 'stretch', borderColor: main.surface, overflow: 'hidden'},
  postFront: {backgroundColor: main.surface, boxShadow: '0px 4px 24px rgba(0,0,0,0.08)', overflow: 'visible'},
  postBack: {backgroundColor: content[100]},
  slide: {height: 56, justifyContent: 'center'},
  slideLabel: {position: 'absolute', left: 0, right: 0, top: 16, alignItems: 'center'},
  chevrons: {position: 'absolute', right: 35.53, top: 22.25, width: 35.22, height: 11.5},
  thumb: {width: 56, height: 56, borderRadius: 28, backgroundColor: surface[900], alignItems: 'center', justifyContent: 'center'},
  pagerContent: {alignItems: 'flex-start'},
  dots: {flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8, alignSelf: 'center'},
  dot: {width: 6, height: 6, borderRadius: 3, backgroundColor: content[200]},
});
