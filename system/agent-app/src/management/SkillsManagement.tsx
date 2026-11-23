import {useCallback, useEffect, useRef, useState} from 'react';
import {StyleSheet, View} from 'react-native';
import {holdSecureScreen} from '../native/agent';
import {SecretField, type SecretFieldHandle} from '../native/SecretField';
import {colors} from '../theme';
import {Button, Card, confirm, Copy, Details, Field, manage, Status, useManagementData} from './controls';

type Provenance = {repo?: string; commit?: string; bodySha256?: string};
type Skill = Provenance & {
  name: string; displayName?: string; description?: string; path?: string; sourceId?: string;
  nativeRecognized?: boolean; enabled?: boolean; rollbackAvailable?: boolean; instructions?: string;
  provenance?: Provenance; builtinUpdate?: object;
};
type Source = {id: string; title: string; repo: string; path?: string; private?: boolean};
type Library = {skills: Skill[]; selected: Skill[]; sources: Source[]; catalogFrozenForActiveTurn?: boolean};
type Catalog = {source: Source; skills: Skill[]};
type SkillUpdate = {updateAvailable?: boolean; latest?: string};
const initialPageSize = 40;

type Display = {kind: 'installed'} | {kind: 'catalog'; catalog: Catalog} | {kind: 'detail'; skill: Skill; catalog?: Catalog} | {kind: 'source'};

export function SkillsPanel({threadId}: {threadId?: string}) {
  const load = useCallback(() => manage<Library>('skills/read', threadId ? {threadId} : {}), [threadId]);
  const {data, refresh, run, busy, error} = useManagementData(load);
  const [display, setDisplay] = useState<Display>({kind: 'installed'});
  const [query, setQuery] = useState('');
  const [limit, setLimit] = useState(initialPageSize);
  const [update, setUpdate] = useState<SkillUpdate>();
  const [repo, setRepo] = useState('');
  const [folder, setFolder] = useState('');
  const [privateRepo, setPrivateRepo] = useState(false);
  const [configured, setConfigured] = useState<boolean>();
  const [enteringToken, setEnteringToken] = useState(false);
  const tokenField = useRef<SecretFieldHandle>(null);
  const frozen = data?.catalogFrozenForActiveTurn === true;
  const validRepo = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repo.trim());
  // The token field must not reach screenshots or the recents preview.
  useEffect(() => (enteringToken ? holdSecureScreen() : undefined), [enteringToken]);

  const closeToken = () => setEnteringToken(false);
  // The native field holds the token and hands it to the gateway itself; it stays open until accepted.
  const saveToken = () => {
    const entry = tokenField.current;
    if (!entry) return;
    void run(async () => {
      const saved = await entry.submit<{configured: boolean}>('manageAssistant', {operation: 'skills/credentials/set', repo: repo.trim()});
      setConfigured(saved.configured);
      closeToken();
    });
  };
  const navigate = (next: Display) => {
    setDisplay(next);
    closeToken();
    setQuery('');
    setLimit(initialPageSize);
    setUpdate(undefined);
  };
  const library = () => navigate({kind: 'installed'});
  const showSource = (source?: Source) => {
    setRepo(source?.repo ?? '');
    setFolder(source?.path ?? '');
    setPrivateRepo(source?.private ?? false);
    setConfigured(undefined);
    navigate({kind: 'source'});
  };
  const openCatalog = (sourceId: string) => void run(async () => {
    navigate({kind: 'catalog', catalog: await manage<Catalog>('skills/community/catalog', {sourceId})});
  });
  const openSkill = (skill: Skill, catalog?: Catalog) => {
    if (!catalog) { navigate({kind: 'detail', skill}); return; }
    const sourceId = skill.sourceId ?? catalog.source.id;
    void run(async () => {
      const detail = await manage<Skill>('skills/community/detail', {sourceId, name: skill.name, commit: skill.commit});
      navigate({kind: 'detail', skill: {...skill, ...detail, sourceId}, catalog});
    });
  };
  const change = (operation: string, params: Record<string, unknown>, after?: () => void) => void run(async () => {
    await manage(operation, params);
    after?.();
    await refresh();
  });
  const visible = display.kind === 'installed' ? data?.skills : display.kind === 'catalog' ? display.catalog.skills : undefined;
  const needle = query.trim().toLowerCase();
  const matching = visible?.filter(row => !needle || `${row.name} ${row.displayName ?? ''} ${row.description ?? ''}`.toLowerCase().includes(needle));

  return <>
    <Status busy={busy} error={error}/>
    {frozen && <Copy muted>A task is running. The current skill catalog is fixed until the task finishes.</Copy>}
    {display.kind === 'installed' && <Card title="Installed skills">
      <Button label="Refresh Skills" disabled={busy} onPress={() => void run(refresh)}/>
    </Card>}
    {display.kind === 'catalog' && <Card title={display.catalog.source.title}>
      <Copy muted>{display.catalog.source.repo}</Copy>
      <Button label="Installed skills" disabled={busy} onPress={library}/>
      <Button label="Edit source settings" disabled={busy || frozen} onPress={() => showSource(display.catalog.source)}/>
      <Button label="Refresh source" disabled={busy || frozen} onPress={() => openCatalog(display.catalog.source.id)}/>
    </Card>}
    {matching && <Card>
      <Field label="Search skills" value={query} onChangeText={value => { setQuery(value); setLimit(initialPageSize); }}/>
      {matching.slice(0, limit).map(skill => <Button key={skill.path ?? skill.name}
        label={`${skill.displayName || skill.name}${skill.path ? skill.enabled ? ' · Enabled' : ' · Disabled' : ''}`}
        disabled={busy || (frozen && display.kind === 'catalog')} onPress={() => openSkill(skill, display.kind === 'catalog' ? display.catalog : undefined)}/>)}
      {matching.length === 0 && <Copy>No matching skills.</Copy>}
      {matching.length > limit && <Button label="Show more skills" onPress={() => setLimit(limit + initialPageSize)}/>}
    </Card>}
    {display.kind === 'installed' && data && <Card title="Community sources">
      {data.sources.map(source => <Button key={source.id} label={source.title || source.repo} disabled={busy || frozen} onPress={() => openCatalog(source.id)}/>)}
      <Button label="Add or edit repository" disabled={busy || frozen} onPress={() => showSource()}/>
    </Card>}
    {display.kind === 'detail' && (() => {
      const skill = data?.skills.find(row => row.path && row.path === display.skill.path) ?? display.skill;
      const origin = skill.provenance ?? skill;
      const path = skill.path;
      const removable = skill.name !== 'taku-app-development' && !!skill.provenance?.repo?.match(/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/);
      const selected = data?.selected.some(row => row.path === path);
      const disabled = busy || frozen;
      return <Card title={skill.displayName || skill.name}>
        {skill.description && <Copy>{skill.description}</Copy>}
        <Copy muted>{`Source: ${origin.repo ?? display.catalog?.source.repo ?? 'Runtime skill catalog'}`}</Copy>
        {origin.commit && <Copy muted>{`Commit: ${origin.commit}`}</Copy>}
        {(origin.bodySha256 ?? skill.bodySha256) && <Copy muted>{`SHA-256: ${origin.bodySha256 ?? skill.bodySha256}`}</Copy>}
        {skill.builtinUpdate && <Details label="built-in update status" value={skill.builtinUpdate}/>}
        <Details label="skill instructions" value={skill.instructions}/>
        {path ? <>
          <Copy muted>{skill.nativeRecognized ? 'Recognized by runtime' : 'Not yet recognized by runtime'}</Copy>
          {skill.nativeRecognized && <Button label={skill.enabled ? 'Disable skill' : 'Enable skill'} disabled={disabled}
            onPress={() => change('skills/set-enabled', {path, enabled: !skill.enabled})}/>}
          {skill.nativeRecognized && skill.enabled && threadId && <Button label={selected ? 'Stop loading in this conversation' : 'Use in this conversation'} disabled={disabled}
            onPress={() => change('skills/select', {threadId, path})}/>}
          {removable && <>
            <Button label="Check for updates" disabled={disabled} onPress={() => void run(async () => setUpdate(await manage<SkillUpdate>('skills/community/check-update', {path})))}/>
            {update && <Copy muted>{update.updateAvailable ? `New source commit: ${update.latest}` : 'Already at the current source commit.'}</Copy>}
            {update?.updateAvailable && update.latest && <Button label="Update skill" disabled={disabled}
              onPress={() => change('skills/community/upgrade', {path, commit: update.latest}, () => setUpdate(undefined))}/>}
            {skill.rollbackAvailable && <Button label="Roll back skill" disabled={disabled} onPress={() => change('skills/community/rollback', {path}, () => setUpdate(undefined))}/>}
            <Button label="Uninstall skill" disabled={disabled} onPress={() => confirm('Uninstall skill?', 'Remove its files and conversation selections. Existing conversation records remain available.', () => change('skills/community/uninstall', {path}, library), true)}/>
          </>}
        </> : <Button label="Install skill" disabled={disabled || !skill.commit || !skill.sourceId || !skill.instructions} onPress={() =>
          change('skills/community/install', {sourceId: skill.sourceId, name: skill.name, commit: skill.commit}, library)}/>}
        <Button label={display.catalog ? 'Back to source' : 'Back to installed skills'} disabled={busy} onPress={() =>
          display.catalog ? navigate({kind: 'catalog', catalog: display.catalog}) : library()}/>
      </Card>;
    })()}
    {display.kind === 'source' && <Card title="Skill repository settings">
      <Field label="GitHub repository (owner/repo)" value={repo} onChangeText={value => { setRepo(value); setConfigured(undefined); closeToken(); }} editable={!busy}/>
      <Field label="Relative skill directory" value={folder} onChangeText={setFolder} editable={!busy}/>
      <Button label={privateRepo ? 'Private repository · Change to public' : 'Public repository · Change to private'} disabled={busy} onPress={() => setPrivateRepo(!privateRepo)}/>
      {privateRepo && <>
        {configured !== undefined && <Copy muted>{configured ? 'Repository token configured.' : 'No repository token configured.'}</Copy>}
        <Button label="Check token status" disabled={!validRepo || busy} onPress={() => void run(async () =>
          setConfigured((await manage<{configured: boolean}>('skills/credentials/status', {repo: repo.trim()})).configured))}/>
        {enteringToken ? <>
          <Copy muted>{`GitHub read-only token for ${repo.trim()}`}</Copy>
          <View style={styles.token}>
            <SecretField ref={tokenField} placeholder="GitHub token" textColor={colors.ink} placeholderColor={colors.secondary}
              autoFocus onSubmitEditing={saveToken} style={styles.tokenInput}/>
          </View>
          <Button label="Save token" disabled={busy} onPress={saveToken}/>
          <Button label="Cancel token entry" disabled={busy} onPress={closeToken}/>
        </> : <Button label={configured ? 'Replace GitHub token' : 'Enter GitHub token'} disabled={!validRepo || busy} onPress={() => setEnteringToken(true)}/>}
        <Button label="Clear saved token" disabled={!validRepo || busy} onPress={() => confirm('Clear repository token?', 'Browsing this private repository needs a token.', () =>
          change('skills/credentials/remove', {repo: repo.trim()}, () => setConfigured(false)), true)}/>
      </>}
      <Button label="Save source" disabled={!validRepo || busy || frozen} onPress={() =>
        change('skills/community/add', {repo: repo.trim(), path: folder.trim(), private: privateRepo}, library)}/>
      <Button label="Back to installed skills" disabled={busy} onPress={library}/>
    </Card>}
  </>;
}

const styles = StyleSheet.create({
  token: {minHeight: 48, paddingHorizontal: 10, borderWidth: 1, borderColor: colors.border, borderRadius: 8, justifyContent: 'center'},
  tokenInput: {height: 46},
});
