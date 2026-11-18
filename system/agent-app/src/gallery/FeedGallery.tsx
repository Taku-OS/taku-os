/** Web preview of the Taku Home feed cards, inside stand-ins for the native glass shell. */
import React, {useEffect, useState} from 'react';
import {ImageBackground, StyleSheet, Text, View} from 'react-native';
import {FeedCardView, type FeedCard} from '../feed/FeedCardRoot';
import {setFeedAssetBase} from '../feed/assets';
import {FeedStatus, FeedSurface, FeedText} from '../feed/feedVisual';
import {shell} from '../feed/feedTokens';
import type {DispatchResult} from '../feed/native';

/** Dev-only: public/feed-assets is generated from the bundled APK assets by prepare:feed-assets. */
export const FEED_ASSETS = '/feed-assets';

function useSampleFeed(assetBase: string): {cards: FeedCard[]; error: string} {
  const [state, setState] = useState<{cards: FeedCard[]; error: string}>({cards: [], error: ''});
  useEffect(() => {
    const base = new URL(FEED_ASSETS, assetBase).href;
    setFeedAssetBase(base);
    let live = true;
    fetch(`${base}/sample-feed.json`).then(r => r.json()).then(json => { if (live) setState({cards: json.cards ?? [], error: ''}); })
      .catch(error => { if (live) setState({cards: [], error: String(error)}); });
    return () => { live = false; };
  }, [assetBase]);
  return state;
}

/** The surface the native Compose shell draws around each card body. */
export function FeedFrame({card, onHeight, onDispatch}: {card: FeedCard; onHeight?: (height: number) => void; onDispatch: (card: FeedCard, actionId: string, values: Record<string, unknown>) => Promise<DispatchResult>}) {
  const control = card.layout?.surface === 'control', half = card.layout?.span === 'half';
  return <View onLayout={e => onHeight?.(e.nativeEvent.layout.height)} style={{width: half ? shell.widths.half + 24 : 380}}>
    <FeedSurface kind={control ? 'control' : 'card'}>
      <FeedCardView card={card} onDispatch={(actionId, values) => onDispatch(card, actionId, values)}/>
    </FeedSurface>
  </View>;
}

async function localDispatch(card: FeedCard, actionId: string, values: Record<string, unknown>): Promise<DispatchResult> {
  await new Promise(resolve => setTimeout(resolve, 450));
  const action = card.actions?.[actionId];
  console.info('[feed gallery] dispatch', card.id, actionId, values, action);
  return action ? {ok: true, result: {effect: 'gallery', actionId}} : {ok: false, code: 'UNKNOWN_ACTION', message: `No action ${actionId}`};
}

/** `/components?feed=<cardId>`: one frame at the origin for headless screenshots. */
export function FeedShot({assetBase, cardId}: {assetBase: string; cardId: string}) {
  const {cards, error} = useSampleFeed(assetBase);
  const [height, setHeight] = useState(0);
  const card = cards.find(item => item.id === cardId);
  return <ImageBackground source={{uri: new URL(`${FEED_ASSETS}/home/wallpaper.jpg`, assetBase).href}} resizeMode="cover" style={styles.shot}>
    {card ? <FeedFrame card={card} onHeight={setHeight} onDispatch={localDispatch}/> : <FeedText text={error || 'Loading…'}/>}
    <Text testID="feed-shot-height" style={styles.hidden}>{`feed-height:${Math.round(height)}`}</Text>
  </ImageBackground>;
}

export function FeedGallery({assetBase}: {assetBase: string}) {
  const {cards, error} = useSampleFeed(assetBase);
  const [last, setLast] = useState('');
  const dispatch = async (card: FeedCard, actionId: string, values: Record<string, unknown>) => {
    const result = await localDispatch(card, actionId, values);
    const action = card.actions?.[actionId] as {intent?: Record<string, unknown>} | undefined;
    setLast(`${card.id} → ${actionId} ${JSON.stringify(values)} ${JSON.stringify(action?.intent ?? {})}`);
    return result;
  };
  const rows: FeedCard[][] = [];
  for (const card of cards) {
    const previous = rows[rows.length - 1];
    if (card.layout?.span === 'half' && previous?.length === 1 && previous[0].layout?.span === 'half') previous.push(card);
    else rows.push([card]);
  }
  return <View style={styles.section}>
    <FeedText accessibilityRole="header" text="Home feed cards" variant="body-xl-medium"/>
    <FeedText text="taku-feed-1 bodies from sample-feed.json, inside a flat stand-in for the native glass shell. Actions update this local fixture." tone="tertiary"/>
    {error ? <FeedStatus state="error" text={error}/> : null}
    <ImageBackground source={{uri: new URL(`${FEED_ASSETS}/home/wallpaper.jpg`, assetBase).href}} resizeMode="cover" style={styles.wall} imageStyle={styles.wallImage}>
      {rows.map(row => <View key={row[0].id} style={styles.row}>
        {row.map(card => <FeedFrame key={card.id} card={card} onDispatch={dispatch}/>)}
      </View>)}
    </ImageBackground>
    {last ? <FeedText selectable text={`Local action: ${last}`} tone="tertiary"/> : null}
  </View>;
}

const styles = StyleSheet.create({
  section: {marginTop: 34, gap: 12},
  wall: {alignSelf: 'flex-start', padding: 16, gap: 16, borderRadius: 24, overflow: 'hidden', backgroundColor: '#e3e3e3'},
  wallImage: {borderRadius: 24},
  row: {flexDirection: 'row', gap: 16},
  shot: {flex: 1, alignItems: 'flex-start', backgroundColor: '#e3e3e3'},
  hidden: {position: 'absolute', left: -9999, top: 0},
});
