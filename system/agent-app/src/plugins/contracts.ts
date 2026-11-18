export type PluginTrust = 'community' | 'verified' | 'local';
export type PluginErrorCode = 'UNAVAILABLE' | 'OFFLINE' | 'INVALID_ARGUMENT' | 'NOT_FOUND' | 'CONFLICT' | 'PERMISSION_DENIED' | 'OPERATION_FAILED';
export type PluginOperationResult<T = void> = {ok: true; value: T} | {ok: false; code: PluginErrorCode; message: string};
export type PluginSummary = {
  id: string; name: string; author: string; version: string; sourceId: string; trust: PluginTrust;
  description: string; category: string; tags: string[]; capabilities: string[]; permissions: string[]; recommended?: boolean;
  installed: boolean; enabled?: boolean; runtimeStatus?: string; error?: string; updatedAt?: string;
};
export type PluginDetails = PluginSummary & {manifest: Record<string, unknown>; tools: string[]; skills: string[]; hooks: string[]; license: string; fingerprint?: string};
export type PluginUpdate = {pluginId: string; name: string; installedVersion: string; candidateVersion: string; sourceChanged: boolean; fingerprintChanged: boolean};
export type PluginSource = {id: string; name: string; url: string; trust: PluginTrust; lastSync?: string; error?: string};
export type Catalog = {items: PluginSummary[]; offline: boolean};

export interface PluginRepository {
  listInstalled(): Promise<PluginOperationResult<PluginSummary[]>>;
  listCatalog(): Promise<PluginOperationResult<Catalog>>;
  listUpdates(): Promise<PluginOperationResult<PluginUpdate[]>>;
  getDetails(id: string): Promise<PluginOperationResult<PluginDetails>>;
  install(id: string, version?: string): Promise<PluginOperationResult>;
  enable(id: string): Promise<PluginOperationResult>;
  disable(id: string): Promise<PluginOperationResult>;
  reload(id: string): Promise<PluginOperationResult>;
  remove(id: string): Promise<PluginOperationResult>;
  rollback(id: string): Promise<PluginOperationResult>;
  listSources(): Promise<PluginOperationResult<PluginSource[]>>;
  addSource(url: string): Promise<PluginOperationResult>;
  removeSource(id: string): Promise<PluginOperationResult>;
  syncSources(): Promise<PluginOperationResult>;
  importLocal(): Promise<PluginOperationResult>;
}

export type PluginNativeBridge = PluginRepository;

export const unavailableRepository: PluginRepository = {
  listInstalled: unavailable, listCatalog: unavailable, listUpdates: unavailable, getDetails: unavailable,
  install: unavailable, enable: unavailable, disable: unavailable, reload: unavailable, remove: unavailable,
  rollback: unavailable, listSources: unavailable, addSource: unavailable, removeSource: unavailable,
  syncSources: unavailable, importLocal: unavailable,
};

async function unavailable(): Promise<{ok: false; code: 'UNAVAILABLE'; message: string}> {
  return {ok: false, code: 'UNAVAILABLE', message: 'Plugin manager bridge is not connected'};
}

export function pluginErrorKey(code: string): 'unavailable' | 'offlineError' | 'error' {
  return code === 'UNAVAILABLE' ? 'unavailable' : code === 'OFFLINE' ? 'offlineError' : 'error';
}

export function catalogResults(items: PluginSummary[], query: string, category: string): PluginSummary[] {
  const needle = query.trim().toLocaleLowerCase();
  return items.filter(item => (category === 'all' || item.category === category || item.tags.includes(category)) &&
    [item.name, item.author, item.description, item.category, ...item.tags, ...item.capabilities].some(value => value.toLocaleLowerCase().includes(needle)))
    .slice().sort((left, right) => left.name.localeCompare(right.name) || left.id.localeCompare(right.id));
}
