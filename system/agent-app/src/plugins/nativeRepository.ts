import type {
  Catalog,
  PluginDetails,
  PluginErrorCode,
  PluginOperationResult,
  PluginRepository,
  PluginSource,
  PluginSummary,
  PluginUpdate,
} from './contracts';

type JsonValue = null | boolean | number | string | JsonValue[] | {[key: string]: JsonValue};
type ManagerEnvelope = {ok: true; result?: JsonValue} | {ok: false; error?: {code?: string; message?: string}};
type ManagerRecord = {
  id: string;
  enabled?: boolean;
  state?: string;
  error?: string | null;
  manifest?: Record<string, unknown>;
  registrations?: {
    tools?: {name?: string; id?: string}[];
    skills?: string[];
  };
  generation?: string;
};
type CallPluginManager = (action: 'pluginManager', params: Record<string, unknown>) => Promise<ManagerEnvelope>;

const localSource: PluginSource = {
  id: 'local',
  name: 'God Mode / local',
  url: 'local',
  trust: 'local',
};

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
}

function object(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function summary(record: ManagerRecord): PluginSummary {
  const manifest = object(record.manifest);
  return {
    id: record.id,
    name: typeof manifest.name === 'string' ? manifest.name : record.id,
    author: typeof manifest.author === 'string' ? manifest.author : 'Local',
    version: typeof manifest.version === 'string' ? manifest.version : 'unknown',
    sourceId: localSource.id,
    trust: 'local',
    description: typeof manifest.description === 'string' ? manifest.description : 'Installed God Mode plugin',
    category: typeof manifest.category === 'string' ? manifest.category : 'Local',
    tags: strings(manifest.tags),
    capabilities: strings(manifest.capabilities),
    permissions: strings(manifest.permissions),
    installed: true,
    enabled: record.enabled === true,
    runtimeStatus: typeof record.state === 'string' ? record.state : undefined,
    error: typeof record.error === 'string' ? record.error : undefined,
  };
}

function details(record: ManagerRecord): PluginDetails {
  const manifest = object(record.manifest);
  const registrations = object(record.registrations);
  const tools = Array.isArray(registrations.tools)
    ? registrations.tools.map(item => String(object(item).name ?? object(item).id ?? '')).filter(Boolean)
    : [];
  return {
    ...summary(record),
    manifest,
    tools,
    skills: strings(registrations.skills).length ? strings(registrations.skills) : strings(manifest.skills),
    hooks: strings(manifest.hooks),
    license: typeof manifest.license === 'string' ? manifest.license : '',
    fingerprint: typeof record.generation === 'string' ? record.generation : undefined,
  };
}

function failure<T = never>(error?: {code?: string; message?: string}): PluginOperationResult<T> {
  const raw = error?.code;
  const code: PluginErrorCode = raw === 'UNAVAILABLE' || raw === 'OFFLINE' || raw === 'INVALID_ARGUMENT' || raw === 'NOT_FOUND' ||
    raw === 'CONFLICT' || raw === 'PERMISSION_DENIED' ? raw : raw === 'USAGE' ? 'INVALID_ARGUMENT' : 'OPERATION_FAILED';
  return {ok: false, code, message: error?.message || code};
}

function unpack<T>(response: ManagerEnvelope): PluginOperationResult<T> {
  return response.ok ? {ok: true, value: response.result as T} : failure<T>(response.error);
}

export function createNativePluginRepository(call: CallPluginManager): PluginRepository {
  const invoke = (operation: string, argument?: string, extra: Record<string, unknown> = {}) =>
    call('pluginManager', {...extra, operation, ...(argument === undefined ? {} : {argument})});

  const listRecords = async (): Promise<PluginOperationResult<ManagerRecord[]>> => {
    try {
      const response = await invoke('list');
      if (!response.ok) return failure<ManagerRecord[]>(response.error);
      const value = Array.isArray(response.result) ? response.result as ManagerRecord[] : [];
      return {ok: true, value};
    } catch (error) {
      return failure<ManagerRecord[]>({code: 'UNAVAILABLE', message: String(error)});
    }
  };

  return {
    async listInstalled() {
      const result = await listRecords();
      return result.ok ? {ok: true, value: result.value.map(summary)} : result;
    },
    async listCatalog(): Promise<PluginOperationResult<Catalog>> {
      const result = await listRecords();
      return result.ok ? {ok: true, value: {items: [], offline: false}} : result;
    },
    async listUpdates(): Promise<PluginOperationResult<PluginUpdate[]>> {
      return {ok: true, value: []};
    },
    async getDetails(id) {
      try {
        const response = await invoke('show', id);
        if (!response.ok) return failure<PluginDetails>(response.error);
        return {ok: true, value: details(response.result as ManagerRecord)};
      } catch (error) {
        return failure<PluginDetails>({code: 'UNAVAILABLE', message: String(error)});
      }
    },
    async install(id) {
      try { return unpack(await invoke('install', id)); }
      catch (error) { return failure({code: 'UNAVAILABLE', message: String(error)}); }
    },
    async enable(id) {
      try { return unpack(await invoke('enable', id)); }
      catch (error) { return failure({code: 'UNAVAILABLE', message: String(error)}); }
    },
    async disable(id) {
      try { return unpack(await invoke('disable', id)); }
      catch (error) { return failure({code: 'UNAVAILABLE', message: String(error)}); }
    },
    async reload(id) {
      try { return unpack(await invoke('reload', id)); }
      catch (error) { return failure({code: 'UNAVAILABLE', message: String(error)}); }
    },
    async remove(id) {
      try { return unpack(await invoke('remove', id)); }
      catch (error) { return failure({code: 'UNAVAILABLE', message: String(error)}); }
    },
    async rollback() {
      return {ok: false, code: 'UNAVAILABLE', message: 'Version rollback is not exposed by this manager version'};
    },
    async listSources() {
      return {ok: true, value: [localSource]};
    },
    async addSource(sourceUrl) {
      if (!/^https:\/\//i.test(sourceUrl)) return {ok: false, code: 'INVALID_ARGUMENT', message: 'Only HTTPS indexes are supported'};
      return {ok: false, code: 'UNAVAILABLE', message: 'Remote source sync is not wired yet'};
    },
    async removeSource(id) {
      return id === localSource.id
        ? {ok: false, code: 'PERMISSION_DENIED', message: 'The built-in local source cannot be removed'}
        : {ok: false, code: 'NOT_FOUND', message: 'Unknown source'};
    },
    async syncSources() {
      return {ok: false, code: 'UNAVAILABLE', message: 'Remote source sync is not wired yet'};
    },
    async importLocal() {
      return {ok: false, code: 'UNAVAILABLE', message: 'Native file picker is not wired yet'};
    },
  };
}
