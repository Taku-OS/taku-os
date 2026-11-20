import {useCallback, useState} from 'react';
import {useFocusEffect, useRouter} from 'expo-router';
import {Button, Card, confirm, Copy, Details, Field, manage, Status, useManagementData, useOperation} from './controls';

type Plugin = {
  id: string; name: string; installed?: boolean; enabled?: boolean; localVersion?: string;
  interface?: {displayName?: string; shortDescription?: string};
};
type Market = {name: string; path?: string; plugins: Plugin[]};
type PluginState = {marketplaces: Market[]; marketplaceLoadErrors?: unknown[]};
const loadPlugins = () => manage<PluginState>('plugin/list');

export function CodexPluginsPanel() {
  const {data, setData, busy, error, run, refresh} = useManagementData(loadPlugins);
  const [reloadFailed, setReloadFailed] = useState(false);
  useFocusEffect(useCallback(() => {
    if (busy) return;
    let open = true;
    const poll = async () => {
      try {
        const next = await loadPlugins();
        if (open) setData(next);
      } catch {
        // The last successful catalog stays visible until the next refresh.
      }
      if (open) timer = setTimeout(() => void poll(), 2000);
    };
    let timer = setTimeout(() => void poll(), 2000);
    return () => { open = false; clearTimeout(timer); };
  }, [busy, setData]));
  const change = (operation: string, options: Record<string, unknown>) => void run(async () => {
    const result = await manage<{mcpReloaded: boolean}>(operation, options);
    setReloadFailed(!result.mcpReloaded);
    await refresh();
  });
  const rows = data?.marketplaces.flatMap(market => market.plugins.map(plugin => ({market, plugin}))) ?? [];
  return <>
    <Status busy={busy} error={error}/>
    <Card>
      <Copy muted>Install plugins only from sources you trust. Plugin MCP services run with system-runtime privileges.</Copy>
      <Button label="Refresh Codex plugins" disabled={busy} onPress={() => void run(refresh)}/>
      {reloadFailed && <>
        <Copy>The plugin setting changed. MCP reload failed.</Copy>
        <Button label="Retry MCP reload" disabled={busy} onPress={() => void run(async () => {
          await manage('config/mcpServer/reload');
          setReloadFailed(false);
        })}/>
      </>}
      {data?.marketplaceLoadErrors?.length ? <Copy muted>Some Codex plugin sources could not be read.</Copy> : null}
    </Card>
    {data && rows.length === 0 && <Copy>No Codex plugins are available.</Copy>}
    {rows.map(({market, plugin}) => <Card key={`${market.path ?? market.name}/${plugin.id}`} title={plugin.interface?.displayName || plugin.name}>
      {plugin.interface?.shortDescription && <Copy>{plugin.interface.shortDescription}</Copy>}
      <Copy muted>{`${market.name} · ${plugin.installed ? plugin.enabled ? 'Enabled' : 'Disabled' : 'Available to install'}${plugin.localVersion ? ` · ${plugin.localVersion}` : ''}`}</Copy>
      {plugin.installed ? <>
        <Button label={plugin.enabled ? 'Disable plugin' : 'Enable plugin'} disabled={busy} onPress={() =>
          change('plugin/set-enabled', {pluginId: plugin.id, enabled: !plugin.enabled})}/>
        <Button label="Uninstall plugin" disabled={busy} onPress={() => confirm('Uninstall Codex plugin?',
          'This plugin’s skills and tools will be unavailable after uninstalling.', () => change('plugin/uninstall', {pluginId: plugin.id}), true)}/>
      </> : market.path && <Button label="Install plugin" disabled={busy} onPress={() => confirm('Install Codex plugin?',
        'Confirm that you trust this source. Plugin MCP services run with system-runtime privileges.', () =>
          change('plugin/install', {pluginName: plugin.name, marketplacePath: market.path}))}/>}
    </Card>)}
  </>;
}

type McpTool = {title?: string; description?: string; inputSchema?: object};
type Server = {name: string; authStatus?: string; tools?: Record<string, McpTool>};
type ToolSelection = {server: string; tool: string; definition: McpTool};
type ToolResult = {isError?: boolean};
type Connector = {id: string; name: string; description?: string; isAccessible?: boolean; isEnabled?: boolean};
type Page<T> = {data: T[]; nextCursor?: string | null};
const loadServers = () => manage<Page<Server>>('mcpServerStatus/list');

export function McpPanel({threadId}: {threadId?: string}) {
  const {data, setData, busy, error, run, refresh} = useManagementData(loadServers);
  const [connectors, setConnectors] = useState<Page<Connector>>();
  const [filter, setFilter] = useState('');
  const [expandedServer, setExpandedServer] = useState<string>();
  const [selected, setSelected] = useState<ToolSelection>();
  const query = filter.trim().toLowerCase();
  if (selected && threadId) return <McpToolPanel threadId={threadId} selection={selected} close={() => setSelected(undefined)}/>;
  return <>
    <Status busy={busy} error={error}/>
    <Card>
      <Button label="Refresh MCP status" disabled={busy} onPress={() => void run(refresh)}/>
      <Button label="Reload MCP configuration" disabled={busy} onPress={() => void run(async () => {
        await manage('config/mcpServer/reload');
        await refresh();
      })}/>
      <Button label={connectors ? 'Refresh connector catalog' : 'Read connector catalog'} disabled={busy} onPress={() => void run(async () =>
        setConnectors(await manage<Page<Connector>>('app/list')))}/>
      <Field label="Filter services and tools" value={filter} onChangeText={setFilter}/>
      {!threadId && <Copy muted>Open a conversation to call an MCP tool.</Copy>}
    </Card>
    {data && data.data.length === 0 && <Copy>No connected MCP services.</Copy>}
    {data?.data.map(server => {
      const tools = Object.entries(server.tools ?? {}).sort(([a], [b]) => a.localeCompare(b));
      const matchesServer = !query || server.name.toLowerCase().includes(query);
      const matches = matchesServer ? tools : tools.filter(([name, tool]) =>
        `${name} ${tool.title ?? ''} ${tool.description ?? ''}`.toLowerCase().includes(query));
      if (!matchesServer && matches.length === 0) return null;
      return <Card key={server.name} title={server.name === 'codex_apps' ? 'Connected Apps' : server.name}>
        <Copy muted>{`${tools.length} tools${server.authStatus ? ` · ${server.authStatus}` : ''}`}</Copy>
        {matches.length > 0 && <Button label={expandedServer === server.name ? 'Hide tools' : `Show ${server.name} tools`}
          onPress={() => setExpandedServer(expandedServer === server.name ? undefined : server.name)}/>}
        {expandedServer === server.name && matches.map(([name, tool]) => <Card key={name} title={tool.title || name}>
          {tool.description && <Copy muted>{tool.description}</Copy>}
          {threadId && <Button label={`Configure ${tool.title || name}`} disabled={busy}
            onPress={() => setSelected({server: server.name, tool: name, definition: tool})}/>}
        </Card>)}
      </Card>;
    })}
    {data?.nextCursor && <Button label="Load more MCP services" disabled={busy} onPress={() => void run(async () => {
      const next = await manage<Page<Server>>('mcpServerStatus/list', {cursor: data.nextCursor});
      setData({...next, data: [...data.data, ...next.data]});
    })}/>}
    {connectors && <Card title="Connector catalog">
      {connectors.data.length === 0 && <Copy>No connectors are available.</Copy>}
      {connectors.data.map(connector => <Card key={connector.id} title={connector.name || connector.id}>
        {connector.description && <Copy>{connector.description}</Copy>}
        {connector.isAccessible !== undefined && <Copy muted>{connector.isAccessible ? 'Available to this account' : 'Unavailable to this account'}</Copy>}
        {connector.isEnabled !== undefined && <Copy muted>{connector.isEnabled ? 'Enabled' : 'Disabled'}</Copy>}
      </Card>)}
      {connectors.nextCursor && <Button label="Load more connectors" disabled={busy} onPress={() => void run(async () => {
        const next = await manage<Page<Connector>>('app/list', {cursor: connectors.nextCursor});
        setConnectors({...next, data: [...connectors.data, ...next.data]});
      })}/>}
    </Card>}
  </>;
}

function McpToolPanel({threadId, selection: {server, tool, definition}, close}: {
  threadId: string; selection: ToolSelection; close: () => void;
}) {
  const {busy, error, run} = useOperation();
  const router = useRouter();
  const [args, setArgs] = useState('{}');
  const [result, setResult] = useState<ToolResult>();
  const invoke = () => void run(async () => {
    setResult(undefined);
    let value: unknown;
    try { value = JSON.parse(args); } catch { throw new Error('Enter valid JSON for the tool arguments.'); }
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Tool arguments must be a JSON object.');
    setResult(await manage<ToolResult>('mcpServer/tool/call', {threadId, server, tool, arguments: value}));
  });
  return <>
    <Status busy={busy} error={error === 'MCP_TOOL_UNAVAILABLE' ? 'This tool is unavailable in the selected conversation.' : error}/>
    <Card title={definition.title || tool}>
      <Copy muted>{`${server} / ${tool}`}</Copy>
      {definition.description && <Copy>{definition.description}</Copy>}
      <Details label="argument schema" value={definition.inputSchema}/>
      <Field label="Tool arguments (JSON object)" value={args} onChangeText={setArgs} editable={!busy} multiline autoCorrect={false}/>
      <Button label="Call tool" disabled={busy} onPress={invoke}/>
      {busy && <Button label="Open pending requests" onPress={() => router.push({pathname: '/assistant-management', params: {section: 'requests', threadId}})}/>}
      <Button label="Back to MCP tools" disabled={busy} onPress={close}/>
    </Card>
    {result && <Card title={result.isError ? 'Tool reported an error' : 'Tool result'}>
      <Details label="tool result" value={result}/>
    </Card>}
  </>;
}
