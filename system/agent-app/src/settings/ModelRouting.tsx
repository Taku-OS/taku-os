import React, {useCallback, useRef, useState} from 'react';
import {ActivityIndicator, Pressable, StyleSheet, TextInput, View} from 'react-native';
import {callAgent} from '../native/agent';
import type {RoutingText, RoutingTextKey} from '../i18n/routing';
import {colors} from '../theme';
import {Action, Copy, type AccountPalette} from './AccountSettings';

export type RoutingGroup = {id: string; name?: string; members: string[]};
type Props = {
  palette: AccountPalette; t: RoutingText;
  groups: RoutingGroup[];
  /** Scene id to `group/<id>`. */
  bindings: Record<string, string>;
  /** The display name of a scene this build has a feature for; undefined for an added scene. */
  sceneName: (scene: string) => string | undefined;
  onChanged: () => void;
};

const danger = '#CC2A4A';
const groupIdPattern = /^[A-Za-z0-9_-]{1,64}$/;
const sceneIdPattern = /^[A-Za-z0-9_.-]{1,128}$/;
const errorKeys: Record<string, RoutingTextKey> = {
  INVALID_GROUP: 'errorGroup',
  INVALID_MEMBERS: 'errorMembers',
  GROUP_EXISTS: 'errorExists',
  GROUP_NOT_FOUND: 'errorMissing',
  INVALID_SCENE: 'errorScene',
  SCENE_NOT_FOUND: 'errorScene',
  ASSISTANT_MODE_NOT_MAGPIE: 'errorAssistantMode',
};

type GroupEditor = {create: boolean; id: string; name: string; members: string};
type SceneEditor = {scene: string; typed: boolean};

/**
 * Model groups typed by hand and scene bindings by ID, for routing that the feature list does
 * not cover: a group whose member model IDs are entered directly, and a binding for any scene.
 */
export function ModelRouting({palette, t, groups, bindings, sceneName, onChanged}: Props) {
  const [group, setGroup] = useState<GroupEditor | null>(null);
  const [scene, setScene] = useState<SceneEditor | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const running = useRef(false);

  const change = useCallback(async (params: Record<string, unknown>, done: () => void) => {
    if (running.current) return;
    running.current = true;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await callAgent('manageSystemAi', params);
      done();
      setNotice(t('saved'));
      onChanged();
    } catch (problem) {
      const code = problem instanceof Error ? problem.message : String(problem);
      setError(t(errorKeys[code] ?? 'errorGeneric'));
    } finally {
      running.current = false;
      setBusy(false);
    }
  }, [onChanged, t]);

  const saveGroup = () => {
    if (!group) return;
    const id = group.id.trim(), name = group.name.trim();
    const members = group.members.split('\n').map(line => line.trim()).filter(Boolean);
    if (!groupIdPattern.test(id) || !name) { setError(t('errorGroup')); return; }
    if (!members.length) { setError(t('errorMembers')); return; }
    void change({operation: 'saveGroup', id, name, members, create: group.create}, () => setGroup(null));
  };
  const openScene = (next: SceneEditor) => {
    setError('');
    if (!groups.length) { setError(t('addGroupFirst')); return; }
    setGroup(null);
    setScene(next);
  };
  const bind = (groupId: string) => {
    if (!scene) return;
    const id = scene.scene.trim();
    if (!sceneIdPattern.test(id)) { setError(t('errorScene')); return; }
    void change({operation: 'bindScene', scene: id, group: groupId}, () => setScene(null));
  };
  const field = (label: string, value: string, onChangeText: (value: string) => void, options: {editable?: boolean; multiline?: boolean} = {}) =>
    <TextInput accessibilityLabel={label} placeholder={label} placeholderTextColor={palette.sub} value={value} onChangeText={onChangeText}
      editable={options.editable !== false && !busy} multiline={options.multiline} autoCapitalize="none" autoCorrect={false} spellCheck={false}
      style={[styles.input, options.multiline && styles.multiline, {color: palette.ink, borderColor: palette.line, opacity: options.editable === false ? 0.6 : 1}]}/>;

  const scenes = Object.keys(bindings).sort();
  return <View style={styles.block}>
    <Copy color={palette.ink} size={16} medium>{t('groupsTitle')}</Copy>
    <Copy color={palette.sub}>{t('groupsHint')}</Copy>
    {groups.map(item => <Pressable key={item.id} accessibilityRole="button" disabled={busy}
      onPress={() => { setError(''); setScene(null); setGroup({create: false, id: item.id, name: item.name ?? '', members: (item.members ?? []).join('\n')}); }}
      style={({pressed}) => [styles.row, {borderColor: palette.line, opacity: pressed ? 0.65 : 1}]}>
      <Copy color={palette.ink} size={15} medium>{item.name?.trim() || item.id}</Copy>
      <Copy color={palette.sub} size={13}>{`group/${item.id} · ${item.members?.length ? item.members.join(' → ') : t('noMembers')}`}</Copy>
    </Pressable>)}
    {group ? <View style={[styles.row, {borderColor: palette.line}]}>
      <Copy color={palette.ink} size={15} medium>{t(group.create ? 'addGroup' : 'editGroup')}</Copy>
      {field(t('groupId'), group.id, id => setGroup({...group, id}), {editable: group.create})}
      {field(t('groupName'), group.name, name => setGroup({...group, name}))}
      {field(t('groupMembers'), group.members, members => setGroup({...group, members}), {multiline: true})}
      <View style={styles.pair}>
        <View style={styles.half}><Action palette={palette} label={t('cancel')} disabled={busy} onPress={() => { setGroup(null); setError(''); }}/></View>
        <View style={styles.half}><Action palette={palette} primary label={t('save')} disabled={busy} onPress={saveGroup}/></View>
      </View>
    </View> : <Action palette={palette} label={t('addGroup')} disabled={busy}
      onPress={() => { setError(''); setScene(null); setGroup({create: true, id: '', name: '', members: ''}); }}/>}

    <View style={[styles.divider, {backgroundColor: palette.line}]}/>
    <Copy color={palette.ink} size={16} medium>{t('scenesTitle')}</Copy>
    <Copy color={palette.sub}>{t('scenesHint')}</Copy>
    {scenes.map(id => <View key={id} style={[styles.row, {borderColor: palette.line}]}>
      <Pressable accessibilityRole="button" disabled={busy} onPress={() => openScene({scene: id, typed: false})} style={({pressed}) => ({opacity: pressed ? 0.65 : 1, gap: 4})}>
        <Copy color={palette.ink} size={15} medium>{sceneName(id) ?? id}</Copy>
        <Copy color={palette.sub} size={13}>{`${sceneName(id) ? `${id} · ` : ''}${bindings[id] || t('unbound')}`}</Copy>
      </Pressable>
      {sceneName(id) === undefined ? <Action palette={palette} destructive label={t('removeScene')} disabled={busy}
        onPress={() => void change({operation: 'unbindScene', scene: id}, () => setScene(null))}/> : null}
    </View>)}
    {scene ? <View style={[styles.row, {borderColor: palette.line}]}>
      {scene.typed ? field(t('sceneId'), scene.scene, value => setScene({...scene, scene: value})) : null}
      <Copy color={palette.ink} size={15} medium>{t('chooseGroup', {scene: sceneName(scene.scene.trim()) ?? (scene.scene.trim() || '…')})}</Copy>
      {groups.map(item => <Action key={item.id} palette={palette} disabled={busy}
        primary={bindings[scene.scene.trim()] === `group/${item.id}`}
        label={`${item.name?.trim() || item.id} · group/${item.id}`} onPress={() => bind(item.id)}/>)}
      <Action palette={palette} label={t('cancel')} disabled={busy} onPress={() => { setScene(null); setError(''); }}/>
    </View> : <Action palette={palette} label={t('addScene')} disabled={busy} onPress={() => openScene({scene: '', typed: true})}/>}

    {busy ? <ActivityIndicator color={colors.brand}/> : null}
    {error ? <View accessibilityLiveRegion="polite"><Copy color={danger}>{error}</Copy></View> : null}
    {notice && !error ? <View accessibilityLiveRegion="polite"><Copy color={palette.sub}>{notice}</Copy></View> : null}
  </View>;
}

const styles = StyleSheet.create({
  block: {gap: 10},
  row: {borderWidth: StyleSheet.hairlineWidth, borderRadius: 12, padding: 12, gap: 8},
  pair: {flexDirection: 'row', gap: 10},
  half: {flex: 1},
  divider: {height: StyleSheet.hairlineWidth, marginVertical: 8},
  input: {minHeight: 48, borderWidth: 1, borderRadius: 12, paddingHorizontal: 12, paddingVertical: 10, fontFamily: 'TakuChinese', fontSize: 16},
  multiline: {minHeight: 96, textAlignVertical: 'top'},
});
