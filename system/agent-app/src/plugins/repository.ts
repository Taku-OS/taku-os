/**
 * The app's one view of installed plugins: the native plugin manager behind the `pluginManager`
 * gateway action, the same source agents use. Screens read it through these hooks.
 */
import {useCallback, useEffect, useState} from 'react';
import {callAgent} from '../native/agent';
import {createNativePluginRepository} from './nativeRepository';
import type {PluginDetails, PluginRepository, PluginSummary} from './contracts';

let shared: PluginRepository | null = null;
export const pluginRepository = (): PluginRepository => shared ??= createNativePluginRepository((action, params) => callAgent(action, params));

export type PluginList = {installed: PluginSummary[]; catalog: PluginSummary[]; loading: boolean; error: string; refresh: () => Promise<void>};

/** Installed plugins and the catalog (empty until a remote source is wired), reloaded on [refresh]. */
export function usePluginList(repository: PluginRepository = pluginRepository()): PluginList {
  const [state, setState] = useState({installed: [] as PluginSummary[], catalog: [] as PluginSummary[], loading: true, error: ''});
  const refresh = useCallback(async () => {
    setState(current => ({...current, loading: true}));
    const [installed, catalog] = await Promise.all([repository.listInstalled(), repository.listCatalog()]);
    setState({installed: installed.ok ? installed.value : [], catalog: catalog.ok ? catalog.value.items : [], loading: false,
      error: installed.ok ? '' : installed.code});
  }, [repository]);
  // Loads from the native plugin manager (external system) on mount.
  // eslint-disable-next-line react-hooks/set-state-in-effect
  useEffect(() => { void refresh(); }, [refresh]);
  return {...state, refresh};
}

/** One plugin's manifest-backed details (tools, skills, license), reloaded on [refresh]. */
export function usePluginDetails(id: string, repository: PluginRepository = pluginRepository()) {
  const [state, setState] = useState<{id: string; details: PluginDetails | null; error: string; loading: boolean}>({id, details: null, error: '', loading: true});
  const refresh = useCallback(async () => {
    if (!id) { setState({id, details: null, error: 'NOT_FOUND', loading: false}); return; }
    const result = await repository.getDetails(id);
    setState({id, details: result.ok ? result.value : null, error: result.ok ? '' : result.code, loading: false});
  }, [id, repository]);
  // Loads from the native plugin manager (external system) when the plugin changes.
  // eslint-disable-next-line react-hooks/set-state-in-effect
  useEffect(() => { void refresh(); }, [refresh]);
  return {...(state.id === id ? state : {id, details: null, error: '', loading: true}), refresh};
}
