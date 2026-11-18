import React, {useState} from 'react';
import {Pressable, StyleSheet, View} from 'react-native';
import type {Language} from '../i18n';
import {sourceNotice} from '../model/sourceNotice';
import {spacing} from '../feed/feedTokens';
import {FeedStatus, FeedText} from '../feed/feedVisual';

type Json = Record<string, unknown>;
const record = (value: unknown): Json => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Json : {};

/**
 * Freshness and source failures accompany the retained facts, without starting any reads. The
 * line says what it means for the user and what to do (issue #32 C7); the source, read time and
 * error code stay one tap away under "Details". Nothing shows while every source is fine.
 */
export function MatterSourceStatus({meta, language}: {meta: Json; language: Language}) {
  const zh = language === 'zh';
  const [open, setOpen] = useState(false);
  // Staleness is evaluated against the wall clock whenever the retained facts render.
  // eslint-disable-next-line react-hooks/purity
  const now = Date.now();
  const notices = Object.entries(record(meta.sources)).map(([id, raw]) => ({id, notice: sourceNotice(record(raw), language, now)}))
    .filter((entry): entry is {id: string; notice: NonNullable<ReturnType<typeof sourceNotice>>} => entry.notice !== null);
  if (!notices.length) return null;
  return <View style={styles.sources}>
    {notices.map(({id, notice}) => <FeedStatus key={id} state={notice.alert ? 'error' : 'info'} text={notice.message}>
      {open ? <FeedText text={notice.details} variant="body-sm" tone="muted" selectable/> : null}
    </FeedStatus>)}
    <Pressable accessibilityRole="button" accessibilityState={{expanded: open}} onPress={() => setOpen(!open)} hitSlop={8} style={styles.toggle}>
      <FeedText text={open ? zh ? '收起详情' : 'Hide details' : zh ? '详情' : 'Details'} variant="body-sm" tone="muted"/>
    </Pressable>
  </View>;
}

const styles = StyleSheet.create({
  sources: {gap: spacing[4]},
  toggle: {alignSelf: 'flex-start'},
});
