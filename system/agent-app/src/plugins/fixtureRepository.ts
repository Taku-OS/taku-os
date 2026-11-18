import type {Catalog, PluginDetails, PluginOperationResult, PluginRepository, PluginSource, PluginUpdate} from './contracts';

const success = <T>(value: T): PluginOperationResult<T> => ({ok: true, value});
const failure = (code: 'NOT_FOUND' | 'CONFLICT' | 'INVALID_ARGUMENT', message: string): PluginOperationResult<never> => ({ok: false, code, message});

export function createFixtureRepository(seed: {installed?: PluginDetails[]} = {}): PluginRepository {
  const source: PluginSource = {id: 'community', name: 'Taku-OS/taku-os-plugins', url: 'https://github.com/Taku-OS/taku-os-plugins', trust: 'community'};
  let sources = [source];
  const catalog: PluginDetails[] = [{id: 'example', name: 'Example tools', author: 'Community', version: '2.0.0', sourceId: source.id, trust: 'community', description: 'Example fixture only', category: 'Productivity', tags: ['Tools'], capabilities: ['MCP'], permissions: ['Network'], recommended: true, installed: false, manifest: {name: 'example', version: '2.0.0'}, tools: ['example'], skills: [], hooks: [], license: 'MIT'}];
  const installed = new Map<string, PluginDetails>((seed.installed || []).map(item => [item.id, {...item}]));
  const previous = new Map<string, PluginDetails>();
  const find = (id: string) => installed.get(id) || catalog.find(item => item.id === id);
  const mutate = async (id: string, action: (item: PluginDetails) => void): Promise<PluginOperationResult> => {
    const item = installed.get(id); if (!item) return failure('NOT_FOUND', id);
    action(item); return success(undefined);
  };
  return {
    async listInstalled() { return success([...installed.values()].map(item => ({...item}))); },
    async listCatalog(): Promise<PluginOperationResult<Catalog>> { return success({items: catalog.map(item => ({...item, ...(installed.get(item.id) || {})})), offline: false}); },
    async listUpdates(): Promise<PluginOperationResult<PluginUpdate[]>> { return success(catalog.flatMap(item => { const current = installed.get(item.id); return current && current.version !== item.version ? [{pluginId: item.id, name: item.name, installedVersion: current.version, candidateVersion: item.version, sourceChanged: current.sourceId !== item.sourceId, fingerprintChanged: current.fingerprint !== item.fingerprint}] : []; })); },
    async getDetails(id) { const item = find(id); return item ? success({...item}) : failure('NOT_FOUND', id); },
    async install(id, version) { const item = catalog.find(entry => entry.id === id); if (!item) return failure('NOT_FOUND', id); if (version && version !== item.version) return failure('INVALID_ARGUMENT', version); const current = installed.get(id); if (current) previous.set(id, {...current}); installed.set(id, {...item, installed: true, enabled: true, runtimeStatus: 'running', updatedAt: new Date().toISOString()}); return success(undefined); },
    async enable(id) { return mutate(id, item => {item.enabled = true; item.runtimeStatus = 'running';}); },
    async disable(id) { return mutate(id, item => {item.enabled = false; item.runtimeStatus = 'stopped';}); },
    async reload(id) { return mutate(id, item => {item.updatedAt = new Date().toISOString();}); },
    async remove(id) { if (!installed.delete(id)) return failure('NOT_FOUND', id); previous.delete(id); return success(undefined); },
    async rollback(id) { const old = previous.get(id); if (!old) return failure('NOT_FOUND', id); const current = installed.get(id); if (!current) return failure('NOT_FOUND', id); installed.set(id, old); previous.set(id, current); return success(undefined); },
    async listSources() { return success(sources.map(item => ({...item}))); },
    async addSource(url) { try { const parsed = new URL(url); if (parsed.protocol !== 'https:') return failure('INVALID_ARGUMENT', url); } catch { return failure('INVALID_ARGUMENT', url); } if (sources.some(item => item.url === url)) return failure('CONFLICT', url); sources = [...sources, {id: url, name: url, url, trust: 'community'}]; return success(undefined); },
    async removeSource(id) { if (!sources.some(item => item.id === id)) return failure('NOT_FOUND', id); sources = sources.filter(item => item.id !== id); return success(undefined); },
    async syncSources() { return success(undefined); },
    async importLocal() { return failure('INVALID_ARGUMENT', 'Fixture has no file picker'); },
  };
}
