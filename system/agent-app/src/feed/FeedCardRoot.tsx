/** `TakuFeedCard` root: renders one FeedCard body (`taku.feed-card/1`) for the native Home shell. */
import React, {Component, useCallback, useEffect, useMemo, useState, type ReactNode} from 'react';
import {StyleSheet, View} from 'react-native';
import {JSONUIProvider, Renderer, createStateStore, type Spec} from '@json-render/react-native';
import {validateFeedSpec} from './feedCatalog';
import {FeedActionAvailability, FeedActionError, feedRegistry} from './feedRegistry';
import {hasUnavailableActions, isActionUnavailable, UNAVAILABLE_ACTION_MESSAGE, UNAVAILABLE_ACTION_NOTICE, type ActionAvailability} from './actionAvailability';
import {FeedStatus, FeedText} from './feedVisual';
import {dispatchAction, getCard, onCardChanged, reportCardHeight, type DispatchResult} from './native';

export type FeedCard = {
  schema: 'taku.feed-card/1'; id: string; summary: string;
  source: {id: string; app: string; package?: string; icon?: string};
  layout?: {span?: 'full' | 'half'; surface?: 'card' | 'control'};
  ui: {catalog: string; spec: Spec; state?: Record<string, unknown>};
  actions?: Record<string, {label?: string; intent: {type: string}}>;
  actionAvailability?: ActionAvailability;
  [key: string]: unknown;
};

export function parseFeedCard(json: string | null): FeedCard | null {
  if (!json) return null;
  try {
    const card = JSON.parse(json);
    return card?.schema === 'taku.feed-card/1' && card.ui?.spec ? card as FeedCard : null;
  } catch { return null; }
}

function Summary({text}: {text: string}) {
  return <FeedText text={text} tone="secondary"/>;
}

/** A spec that validated can still throw while rendering; fall back to the card summary, never blank. */
class CardBoundary extends Component<{fallback: string; children: ReactNode}, {failed: boolean}> {
  state = {failed: false};
  static getDerivedStateFromError() { return {failed: true}; }
  componentDidCatch(error: unknown) { console.warn('[feed] card render failed; showing summary', error); }
  render() { return this.state.failed ? <Summary text={this.props.fallback}/> : this.props.children; }
}

/** Pure card body: shared by the native root and the web gallery. */
export function FeedCardView({card, onDispatch, onHeight}: {
  card: FeedCard;
  onDispatch: (actionId: string, values: Record<string, unknown>) => Promise<DispatchResult>;
  onHeight?: (height: number) => void;
}) {
  const stateKey = JSON.stringify(card.ui.state ?? {});
  // A republished card with new initial state starts from that state.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const store = useMemo(() => createStateStore(JSON.parse(stateKey)), [card.id, stateKey]);
  const [actionError, setActionError] = useState<string | null>(null);
  const availability = card.actionAvailability ?? EMPTY_AVAILABILITY;
  const issues = useMemo(() => card.ui.catalog === 'taku-feed-1'
    ? validateFeedSpec(card.ui.spec, card.ui.state ?? {}, new Set(Object.keys(card.actions ?? {})))
    : [{element: '(card)', message: `unsupported catalog ${card.ui.catalog}`}], [card]);
  useEffect(() => { if (issues.length) console.warn(`[feed] ${card.id} spec rejected`, issues); }, [card.id, issues]);
  const handlers = useMemo(() => ({
    dispatch: async (params: Record<string, unknown>) => {
      const actionId = String(params.actionId ?? '');
      if (isActionUnavailable(actionId, availability)) {
        setActionError(UNAVAILABLE_ACTION_MESSAGE);
        throw new Error(UNAVAILABLE_ACTION_MESSAGE);
      }
      const select = Array.isArray(params.select) ? params.select.filter((p): p is string => typeof p === 'string') : [];
      const values = Object.fromEntries(select.map(path => [path, store.get(path) ?? null]));
      const result = await onDispatch(actionId, values);
      if (!result.ok) throw new Error(result.message);
    },
  }), [availability, onDispatch, store]);
  const half = card.layout?.span === 'half';
  return <View collapsable={false} style={[styles.root, half && styles.half]} onLayout={e => onHeight?.(e.nativeEvent.layout.height)}>
    {hasUnavailableActions(availability) ? <View style={styles.notice}><FeedStatus text={UNAVAILABLE_ACTION_NOTICE} state="info"/></View> : null}
    <FeedActionAvailability.Provider value={availability}>
      <FeedActionError.Provider value={setActionError}>
        {issues.length ? <Summary text={card.summary}/> : <CardBoundary key={card.id} fallback={card.summary}>
          <JSONUIProvider registry={feedRegistry} store={store} handlers={handlers}>
            <Renderer spec={card.ui.spec} registry={feedRegistry} includeStandard={false}/>
          </JSONUIProvider>
        </CardBoundary>}
      </FeedActionError.Provider>
    </FeedActionAvailability.Provider>
    {actionError ? <View style={styles.error}><FeedStatus text={actionError} state="error"/></View> : null}
  </View>;
}

const EMPTY_AVAILABILITY: ActionAvailability = {};

/** Registered with AppRegistry as `TakuFeedCard`; initial props `{cardId}`. */
export default function FeedCardRoot({cardId}: {cardId: string}) {
  const [version, setVersion] = useState(0);
  // version is the change signal from onCardChanged; the card itself is read from native.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const card = useMemo(() => parseFeedCard(getCard(cardId)), [cardId, version]);
  useEffect(() => {
    const subscription = onCardChanged(cardId, () => setVersion(v => v + 1));
    return () => subscription.remove();
  }, [cardId]);
  const onDispatch = useCallback((actionId: string, values: Record<string, unknown>) => dispatchAction(cardId, actionId, values), [cardId]);
  const onHeight = useCallback((height: number) => reportCardHeight(cardId, Math.ceil(height)), [cardId]);
  if (!card) return <View style={styles.root} onLayout={e => onHeight(e.nativeEvent.layout.height)}/>;
  return <FeedCardView card={card} onDispatch={onDispatch} onHeight={onHeight}/>;
}

const styles = StyleSheet.create({
  root: {backgroundColor: 'transparent', alignSelf: 'stretch'},
  half: {aspectRatio: 1},
  notice: {marginBottom: 8},
  error: {marginTop: 8},
});
