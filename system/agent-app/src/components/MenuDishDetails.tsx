import React, {useLayoutEffect, useMemo, useRef, useState, type ReactNode} from 'react';
import {Image, Platform, Pressable, ScrollView, StyleSheet, Text, View, type ScrollViewInstance} from 'react-native';
import Animated, {cancelAnimation, Easing, useAnimatedStyle, useSharedValue, withDelay, withTiming} from 'react-native-reanimated';
import {scheduleOnRN} from 'react-native-worklets';
import {chatText} from '../chat/chatText';
import {chatColors, chatType} from '../chat/chatTheme';
import {useMenuDishReveal} from '../hooks/useMenuDishReveal';
import {useMenuReducedMotion} from '../hooks/useMenuReducedMotion';
import type {Language} from '../i18n';
import {menuNutritionValue} from '../model/menuNutrition';
import {formatMenuPrice, type MenuDish} from '../model/menuScan';
import type {MenuVideo, MenuVideoSearchResult} from '../native/agent';
import {Icon} from './Icon';
import {MenuRollingNumber, type MenuNumberMotion} from './MenuRollingNumber';
import {menuNumberLayout} from './menuNumberLayout';

const nutrients = [
  ['calories', 'menuCalories', 'menuKcal'],
  ['carbs', 'menuCarbs', 'menuGrams'],
  ['fat', 'menuFat', 'menuGrams'],
  ['protein', 'menuProtein', 'menuGrams'],
] as const;

const platforms: Record<MenuVideo['platform'], string> = {
  tiktok: 'TikTok', instagram: 'Instagram', youtube: 'YouTube', x: 'X', facebook: 'Facebook',
};
const detailInset = 12;
const nutrientMotion: readonly MenuNumberMotion[] = [
  {duration: 360, delay: 0, carry: 40, trail: 1},
  {duration: 320, delay: 90, carry: 30, trail: 0.4},
  {duration: 320, delay: 90, carry: 30, trail: 0.4},
  {duration: 320, delay: 110, carry: 30, trail: 0.4},
];
const swapCurves = {in: Easing.bezier(0.22, 1, 0.36, 1), out: Easing.bezier(0.4, 0, 0.2, 1)};
type SwapMotion = {delay: number; inMs: number; outMs: number; inX: number; outX: number; blur?: number};
const titleSwap = {delay: 80, inMs: 230, outMs: 110, inX: 14, outX: 10};
const tagSwap = {delay: 110, inMs: 240, outMs: 110, inX: 8, outX: 6};
const lowerSwap = {delay: 120, inMs: 260, outMs: 130, inX: 6, outX: 4};
const postSwap = {delay: 90, inMs: 260, outMs: 230, inX: 28, outX: 20, blur: 1.4};

function DissolveLayer({initial, current, version, children, direction, motion, reduceMotion, onDone}: {
  initial: boolean; current: boolean; version: number; children: ReactNode; direction: number;
  motion: SwapMotion; reduceMotion: boolean; onDone: (version: number) => void;
}) {
  const opacity = useSharedValue(initial ? 1 : 0);
  const translate = useSharedValue(initial ? 0 : direction * motion.inX);
  const blur = motion.blur ?? 0;
  const soften = blur > 0 && (Platform.OS === 'web' || (Platform.OS === 'android' && Number(Platform.Version) >= 31));
  useLayoutEffect(() => {
    cancelAnimation(opacity);
    cancelAnimation(translate);
    const target = current ? 1 : 0;
    if (reduceMotion) {
      opacity.set(target);
      translate.set(0);
      if (!current) onDone(version);
      return;
    }
    const easing = current ? swapCurves.in : swapCurves.out;
    const duration = current ? motion.inMs : motion.outMs;
    const delay = current ? motion.delay : 0;
    const animation = withTiming(target, {duration, easing}, finished => {
      if (finished && !current) scheduleOnRN(onDone, version);
    });
    const movement = withTiming(current ? 0 : -direction * motion.outX, {duration, easing});
    opacity.set(delay ? withDelay(delay, animation) : animation);
    translate.set(delay ? withDelay(delay, movement) : movement);
  }, [current, direction, motion, onDone, opacity, reduceMotion, translate, version]);
  const faded = useAnimatedStyle(() => {
    const alpha = opacity.get();
    return {opacity: alpha, transform: [{translateX: translate.get()}],
      ...(soften ? {filter: [{blur: (1 - alpha) * blur}]} : {})};
  });
  return <Animated.View pointerEvents={current ? 'auto' : 'none'} accessibilityElementsHidden={!current}
    importantForAccessibility={current ? 'auto' : 'no-hide-descendants'}
    style={[current ? styles.activeLayer : styles.previousLayer, faded]}>
    {children}
  </Animated.View>;
}

type DissolveContent = {key: string; children: ReactNode};

function DishDissolve({contentKey, children, direction, motion, reduceMotion}: {
  contentKey: string; children: ReactNode; direction: number; motion: SwapMotion; reduceMotion: boolean;
}) {
  const [content, setContent] = useState<{current: DissolveContent; previous?: DissolveContent; version: number}>({
    current: {key: contentKey, children}, version: 0,
  });
  const remove = React.useCallback((version: number) => {
    setContent(state => state.version === version && state.previous ? {...state, previous: undefined} : state);
  }, []);
  if (content.current.key !== contentKey) {
    setContent({current: {key: contentKey, children}, previous: reduceMotion ? undefined : content.current, version: content.version + 1});
  } else if (content.current.children !== children) {
    setContent({...content, current: {key: contentKey, children}});
  }
  const layers = content.previous && !reduceMotion ? [content.previous, content.current] : [content.current];
  return <View style={styles.dissolve}>
    {layers.map(layer => <DissolveLayer key={layer.key} initial={content.version === 0} version={content.version}
      current={layer === content.current} direction={direction} motion={motion} reduceMotion={reduceMotion} onDone={remove}>
      {layer.children}
    </DissolveLayer>)}
  </View>;
}

function Nutrition({dish, dishes, language, direction, reduceMotion}: {
  dish: MenuDish; dishes: readonly MenuDish[]; language: Language; direction: number; reduceMotion: boolean;
}) {
  const ct = chatText(language);
  const chinese = language === 'zh';
  const {nutrition} = dish;
  const reveal = useMenuDishReveal('nutrition');
  const layouts = useMemo(() => nutrients.map(([key]) => menuNumberLayout(dishes.map(item => menuNutritionValue(item.nutrition, key)))), [dishes]);
  const labelStyle = [chatType.bodyMdMedium(chinese), styles.nutrientLabel];
  const numberStyle = {...chatType.headingMd(chinese), ...styles.nutrientValue};
  const unitStyle = {...chatType.bodyLg(chinese), ...styles.nutrientValue};
  const estimateLabel = nutrition?.kind === 'estimated'
    ? ct(nutrition.scope === 'piece' ? 'menuNutritionEstimatePerPiece' : 'menuNutritionEstimate') : undefined;

  return <Animated.View style={[styles.nutrition, reveal]} testID="menu-nutrition">
    {nutrients.map(([key, label, unit], index) => <React.Fragment key={key}>
      {index > 0 && <View style={styles.nutrientDivider}/>}
      <View style={styles.nutrient}>
        <Text style={labelStyle}>{ct(label)}</Text>
        <MenuRollingNumber value={menuNutritionValue(nutrition, key)} layout={layouts[index]}
          style={numberStyle} unitStyle={unitStyle} unit={ct(unit)} direction={direction}
          motion={nutrientMotion[index]} estimateLabel={estimateLabel} reduceMotion={reduceMotion}/>
      </View>
    </React.Fragment>)}
  </Animated.View>;
}

function CreatorPost({post, language, open}: {post: MenuVideo; language: Language; open: (url: string) => void}) {
  const [failedThumbnail, setFailedThumbnail] = useState<string>();
  const ct = chatText(language);
  const chinese = language === 'zh';
  const platform = platforms[post.platform];
  const thumbnail = post.thumbnailUrl && post.thumbnailUrl !== failedThumbnail ? post.thumbnailUrl : undefined;
  const isVideo = post.mediaType === undefined || post.mediaType === 'video';
  const playbackUrl = isVideo ? post.playbackUrl : undefined;
  const targetUrl = playbackUrl ?? post.viewerUrl ?? post.url;
  const hint = targetUrl === post.url ? 'menuOpenOriginalPost' : playbackUrl ? 'menuPlayVideo' : 'menuViewPost';
  return <View style={styles.creatorCard}>
    <Pressable testID={`menu-${post.platform}-source`} accessibilityRole="link"
      accessibilityLabel={[platform, post.author, post.title].filter(Boolean).join(', ')}
      accessibilityHint={ct(hint)}
      onPress={() => open(targetUrl)} style={[styles.creatorFace, thumbnail && styles.creatorWithThumbnail]}>
      {thumbnail && <Image source={{uri: thumbnail}} onError={() => setFailedThumbnail(thumbnail)}
        style={StyleSheet.absoluteFill} resizeMode="cover" fadeDuration={150}/>}
      <View style={styles.platformPill}><Text style={chatType.bodySmMedium(chinese)}>{platform}</Text></View>
      <View style={styles.creatorAction}>
        {isVideo ? <View style={styles.playTriangle}/> : <Icon name="arrow_up_right" size={18} color={chatColors.iconOnInverse}/>}
      </View>
      <View style={styles.creatorCaption}>
        <Text style={[chatType.bodySmMedium(chinese), styles.inverse]} numberOfLines={1}>{post.author}</Text>
        {post.title && <Text style={[chatType.bodySm(chinese), styles.inverse]} numberOfLines={2}>{post.title}</Text>}
      </View>
    </Pressable>
    {targetUrl !== post.url && <Text accessibilityRole="link" onPress={() => open(post.url)}
      style={[chatType.bodySmMedium(chinese), styles.source, styles.sourceLink]}>{ct('menuOriginalPost')}</Text>}
  </View>;
}

function CreatorPosts({dishId, hasVenue, videoSearch, language, open, direction, reduceMotion}: {
  dishId: string; hasVenue: boolean; videoSearch?: MenuVideoSearchResult; language: Language;
  open: (url: string) => void; direction: number; reduceMotion: boolean;
}) {
  const ct = chatText(language);
  const chinese = language === 'zh';
  const reveal = useMenuDishReveal('social');
  const posts = hasVenue ? videoSearch?.videos?.filter(post => post.sameBrand === true) ?? [] : [];
  const state = videoSearch?.state ?? 'queued';
  const searching = hasVenue && (state === 'queued' || state === 'searching');
  const scroll = useRef<ScrollViewInstance>(null);
  useLayoutEffect(() => { scroll.current?.scrollTo({x: 0, animated: false}); }, [dishId]);

  if (!searching && posts.length === 0) return null;

  return <Animated.View style={[styles.socialSection, reveal]} testID="menu-creator-section">
    {posts.length > 0 && <>
      <Text accessibilityRole="header" style={[chatType.bodyLgMedium(chinese), styles.sectionInset]}>{ct('menuPostFound')}</Text>
      <ScrollView ref={scroll} testID="menu-creator-videos" horizontal showsHorizontalScrollIndicator={false} nestedScrollEnabled
        directionalLockEnabled contentContainerStyle={styles.creatorStrip}>
        {posts.map((post, index) => <CreatorPostSlot key={index} post={post} index={index}
          direction={direction} language={language} open={open} reduceMotion={reduceMotion}/>)}
      </ScrollView>
    </>}
    {searching && <Text testID="menu-video-search-status" accessibilityLiveRegion="polite"
      style={[chatType.bodyMd(chinese), styles.sectionInset, styles.secondary]}>{ct('menuPostSearching')}</Text>}
  </Animated.View>;
}

function CreatorPostSlot({post, index, direction, language, open, reduceMotion}: {
  post: MenuVideo; index: number; direction: number; language: Language; open: (url: string) => void; reduceMotion: boolean;
}) {
  const motion = useMemo(() => ({...postSwap, delay: postSwap.delay + Math.min(index, 2) * 40}), [index]);
  return <DishDissolve contentKey={post.url} direction={direction} motion={motion} reduceMotion={reduceMotion}>
    <CreatorPost key={post.url} post={post} language={language} open={open}/>
  </DishDissolve>;
}

export function MenuDishDetails({dish, dishes: sessionDishes, badge, hasVenue, videoSearch, language, open}: {
  dish: MenuDish; dishes?: readonly MenuDish[]; badge?: string; hasVenue: boolean; videoSearch?: MenuVideoSearchResult; language: Language;
  open: (url: string) => void;
}) {
  const ct = chatText(language);
  const chinese = language === 'zh';
  const {evidence, description} = dish;
  const dishes = useMemo(() => sessionDishes ?? [dish], [dish, sessionDishes]);
  const index = dishes.findIndex(item => item.id === dish.id);
  const [lastSelection, setLastSelection] = useState({index, direction: 1});
  const direction = lastSelection.index === index ? lastSelection.direction : Math.sign(index - lastSelection.index);
  if (lastSelection.index !== index) setLastSelection({index, direction});
  const priceLayout = useMemo(() => menuNumberLayout(dishes.map(formatMenuPrice)), [dishes]);
  const reduceMotion = useMenuReducedMotion();
  const titleReveal = useMenuDishReveal('title');
  const lowerReveal = useMenuDishReveal('lower');
  const ingredients = evidence?.ingredients?.[language]?.trim() || description?.trim();
  const taste = evidence?.facts[language]?.trim();

  return <View style={styles.details}>
    <View style={[styles.section, styles.sectionInset]}>
      <Animated.View style={[styles.section, titleReveal]}>
        <View style={styles.titleRow}>
          <View style={styles.dishName}>
            <DishDissolve contentKey={dish.id} direction={direction} motion={titleSwap} reduceMotion={reduceMotion}>
              <Text accessibilityRole="header" accessibilityLiveRegion="polite" numberOfLines={1}
                style={[chatType.headingLg(chinese), styles.dishTitle]}>{dish.name.toLocaleLowerCase(language)}</Text>
            </DishDissolve>
          </View>
          <MenuRollingNumber value={formatMenuPrice(dish)} layout={priceLayout} style={chatType.headingLg(chinese)}
            direction={direction} reduceMotion={reduceMotion}/>
        </View>
        {(badge || Boolean(evidence?.tasteTags?.length)) && <View style={styles.tagSlot}>
          <DishDissolve contentKey={dish.id} direction={direction} motion={tagSwap} reduceMotion={reduceMotion}>
            <View style={styles.tags}>
              {badge && <View style={[styles.tag, styles.badge]}><Icon name="sparkle" size={12} color={chatColors.iconOnInverse}/>
                <Text style={[chatType.bodySm(chinese), styles.inverse]}>{badge}</Text>
              </View>}
              {evidence?.tasteTags?.map(tag => <View key={tag.en} style={[styles.tag, styles.taste]}>
                <Text style={[chatType.bodySm(chinese), styles.secondary]}>{tag[language]}</Text>
              </View>)}
            </View>
          </DishDissolve>
        </View>}
      </Animated.View>
      {dishes.some(item => item.nutrition) && <Nutrition dish={dish} dishes={dishes} language={language}
        direction={direction} reduceMotion={reduceMotion}/>}
    </View>
    <CreatorPosts dishId={dish.id} hasVenue={hasVenue} videoSearch={videoSearch} language={language}
      open={open} direction={direction} reduceMotion={reduceMotion}/>
    {(ingredients || taste) && <Animated.View style={[styles.lower, styles.sectionInset, lowerReveal]}>
      {ingredients && <View style={styles.description}>
        <Text accessibilityRole="header" style={chatType.bodyMdMedium(chinese)}>{ct('menuIngredients')}</Text>
        <DishDissolve contentKey={dish.id} direction={direction} motion={lowerSwap} reduceMotion={reduceMotion}>
          <Text style={[chatType.bodyMd(chinese), styles.secondary]}>{ingredients}</Text>
        </DishDissolve>
      </View>}
      {taste && <DishDissolve contentKey={dish.id} direction={direction}
        motion={lowerSwap} reduceMotion={reduceMotion}>
        <View style={styles.description}>
          <Text accessibilityRole="header" style={chatType.bodyMdMedium(chinese)}>{ct('menuWhatToExpect')}</Text>
          <Text style={[chatType.bodyMd(chinese), styles.secondary]}>{taste}</Text>
          {evidence?.menuUrl && <Text accessibilityRole="link" onPress={() => open(evidence.menuUrl)}
            style={[chatType.bodySmMedium(chinese), styles.source, styles.sourceLink]}>{ct('menuOfficial')}</Text>}
        </View>
      </DishDissolve>}
    </Animated.View>}
  </View>;
}

const styles = StyleSheet.create({
  details: {gap: 16, paddingTop: 12, paddingBottom: 12},
  dissolve: {position: 'relative'},
  activeLayer: {position: 'relative'},
  previousLayer: {position: 'absolute', top: 0, left: 0, right: 0},
  section: {gap: 8},
  socialSection: {gap: 4},
  sectionInset: {marginHorizontal: detailInset},
  titleRow: {flexDirection: 'row', alignItems: 'flex-start', gap: 10},
  dishName: {flex: 1, minWidth: 0},
  dishTitle: {textTransform: 'capitalize'},
  tagSlot: {minHeight: 20},
  tags: {flexDirection: 'row', flexWrap: 'wrap', gap: 4},
  tag: {borderRadius: 10, paddingHorizontal: 4, minHeight: 20, justifyContent: 'center'},
  badge: {flexDirection: 'row', alignItems: 'center', gap: 4, backgroundColor: '#E94E17'},
  taste: {backgroundColor: chatColors.fillControl},
  nutrition: {flexDirection: 'row', backgroundColor: chatColors.bubble, padding: 12, borderRadius: 8},
  nutrient: {flex: 1, alignItems: 'center', gap: 4},
  nutrientDivider: {width: 1, backgroundColor: chatColors.hairline},
  nutrientLabel: {color: chatColors.textSecondary, lineHeight: 20},
  nutrientValue: {color: chatColors.textTertiary, lineHeight: 24},
  lower: {gap: 16},
  description: {gap: 4},
  source: {textDecorationLine: 'underline'},
  sourceLink: {paddingVertical: 4, alignSelf: 'flex-start'},
  secondary: {color: chatColors.textSecondary},
  inverse: {color: chatColors.textOnInverse},
  creatorStrip: {paddingHorizontal: detailInset, gap: 8},
  creatorCard: {width: 150, gap: 6},
  creatorFace: {height: 200, borderRadius: 12, overflow: 'hidden', backgroundColor: '#343434', alignItems: 'center', justifyContent: 'center'},
  creatorWithThumbnail: {backgroundColor: chatColors.fillControl},
  platformPill: {position: 'absolute', top: 7, left: 7, paddingHorizontal: 7, paddingVertical: 4, borderRadius: 12, backgroundColor: chatColors.chip},
  creatorAction: {width: 35, height: 35, borderRadius: 18, alignItems: 'center', justifyContent: 'center', backgroundColor: '#000000A0'},
  playTriangle: {width: 0, height: 0, marginLeft: 3, borderTopWidth: 5, borderBottomWidth: 5, borderLeftWidth: 8,
    borderTopColor: 'transparent', borderBottomColor: 'transparent', borderLeftColor: chatColors.iconOnInverse},
  creatorCaption: {position: 'absolute', left: 0, right: 0, bottom: 0, gap: 2, padding: 8, backgroundColor: '#000000B8'},
});
