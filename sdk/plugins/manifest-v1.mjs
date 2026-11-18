const required = ['schemaVersion', 'id', 'name', 'version', 'minHostVersion', 'targets', 'capabilities', 'permissions', 'ui', 'settings', 'hooks', 'runtime'];
const fields = [...required, 'type', 'description', 'author', 'category', 'license', 'entrypoint', 'mcp', 'skills', 'healthcheck', 'runtimePack', 'roles', 'checks'];
const version = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/;
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);

export function validateManifest(manifest) {
  const errors = [];
  const fail = (path, message) => errors.push({path, message});
  if (!object(manifest)) return [{path: '$', message: 'must be an object'}];
  for (const key of required) if (!Object.hasOwn(manifest, key)) fail(`$.${key}`, 'required');
  for (const key of Object.keys(manifest)) if (!fields.includes(key)) fail(`$.${key}`, 'unknown field');
  if (manifest.schemaVersion !== 1) fail('$.schemaVersion', 'must equal 1');
  if (typeof manifest.id !== 'string' || !/^[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*)+$/.test(manifest.id)) fail('$.id', 'must be a reverse-domain identifier');
  if (typeof manifest.name !== 'string' || !/\S/.test(manifest.name)) fail('$.name', 'must be nonempty');
  for (const key of ['description', 'author', 'category', 'license']) if (Object.hasOwn(manifest, key) && (typeof manifest[key] !== 'string' || !/\S/.test(manifest[key]))) fail(`$.${key}`, 'must be nonempty when present');
  for (const key of ['version', 'minHostVersion']) if (typeof manifest[key] !== 'string' || !version.test(manifest[key])) fail(`$.${key}`, 'must be a three-part numeric version');
  if (Object.hasOwn(manifest, 'type') && !['hook', 'system-app', 'root-daemon', 'agent', 'tool-provider'].includes(manifest.type)) fail('$.type', 'unsupported type');
  if (!Object.hasOwn(manifest, 'type') && (!Array.isArray(manifest.roles) || !manifest.roles.length)) fail('$.roles', 'type or nonempty roles required');
  if (Object.hasOwn(manifest, 'roles') && (!Array.isArray(manifest.roles) || manifest.roles.some(role => !['tool-provider', 'check-provider', 'hook-provider', 'root-service', 'ui-provider'].includes(role)) || new Set(manifest.roles).size !== manifest.roles.length)) fail('$.roles', 'must contain unique supported roles');
  const checkProvider = Array.isArray(manifest.roles) && manifest.roles.includes('check-provider');
  const provider = manifest.type === 'tool-provider' || Array.isArray(manifest.roles) && manifest.roles.some(role => ['tool-provider', 'check-provider'].includes(role));
  if (provider && Object.hasOwn(manifest, 'type') && manifest.type !== 'tool-provider') fail('$.type', 'provider role conflicts with legacy type');
  if (checkProvider && !Array.isArray(manifest.checks)) fail('$.checks', 'check-provider requires checks');
  if (!checkProvider && Object.hasOwn(manifest, 'checks')) fail('$.checks', 'requires check-provider role');
  if (Array.isArray(manifest.checks)) {
    const ids = new Set();
    for (const check of manifest.checks) {
      if (!object(check) || Object.keys(check).sort().join(',') !== 'description,id,kinds,targets,title' || typeof check.id !== 'string' || !/\S/.test(check.id) || ids.has(check.id) || typeof check.title !== 'string' || !/\S/.test(check.title) || typeof check.description !== 'string' || !['kinds', 'targets'].every(key => Array.isArray(check[key]) && check[key].every(item => typeof item === 'string' && /\S/.test(item)) && new Set(check[key]).size === check[key].length)) fail('$.checks', 'invalid or duplicate check');
      if (object(check)) ids.add(check.id);
    }
  }
  if (!['host', 'target-process', 'root-daemon', 'agent'].includes(manifest.runtime)) fail('$.runtime', 'unsupported runtime');
  if (provider) {
    if (Object.hasOwn(manifest, 'entrypoint')) fail('$.entrypoint', 'not supported for tool-provider');
    const mcp = manifest.mcp;
    if (!object(mcp) || Object.keys(mcp).some(key => !['command', 'args', 'env'].includes(key)) || typeof mcp.command !== 'string' || !/\S/.test(mcp.command) || !Array.isArray(mcp.args) || mcp.args.some(item => typeof item !== 'string') || !object(mcp.env) || Object.values(mcp.env).some(item => typeof item !== 'string')) fail('$.mcp', 'requires command, args and env');
    if (!Array.isArray(manifest.skills) || manifest.skills.some(item => typeof item !== 'string' || !/^[A-Za-z0-9_-]+(\/[A-Za-z0-9_-]+)*\/SKILL\.md$/.test(item)) || new Set(manifest.skills).size !== manifest.skills.length) fail('$.skills', 'must contain unique relative SKILL.md paths');
    if (!object(manifest.healthcheck) || Object.keys(manifest.healthcheck).some(key => key !== 'timeoutMs') || !Number.isInteger(manifest.healthcheck.timeoutMs) || manifest.healthcheck.timeoutMs < 100 || manifest.healthcheck.timeoutMs > 30000) fail('$.healthcheck', 'requires timeoutMs between 100 and 30000');
    if (Object.hasOwn(manifest, 'runtimePack') && (!object(manifest.runtimePack) || Object.keys(manifest.runtimePack).sort().join(',') !== 'id,platform,root,version' || !['id', 'platform', 'root', 'version'].every(key => typeof manifest.runtimePack[key] === 'string' && /\S/.test(manifest.runtimePack[key])))) fail('$.runtimePack', 'requires id, platform, root and version');
  } else {
  for (const key of ['mcp', 'skills', 'healthcheck', 'runtimePack']) if (Object.hasOwn(manifest, key)) fail(`$.${key}`, 'only supported for tool-provider');
  if (!object(manifest.entrypoint)) fail('$.entrypoint', 'must be an object');
  else {
    for (const key of Object.keys(manifest.entrypoint)) if (!['dex', 'class'].includes(key)) fail(`$.entrypoint.${key}`, 'unknown field');
    if (typeof manifest.entrypoint.dex !== 'string' || !/^[A-Za-z0-9_-]+(\/[A-Za-z0-9_-]+)*\.(dex|jar|apk)$/.test(manifest.entrypoint.dex)) fail('$.entrypoint.dex', 'must be a relative dex, jar or apk path');
    if (typeof manifest.entrypoint.class !== 'string' || !/^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)+$/.test(manifest.entrypoint.class)) fail('$.entrypoint.class', 'must be a qualified class name');
  }
  }
  for (const key of ['targets', 'capabilities', 'permissions', 'ui', 'settings', 'hooks']) {
    const value = manifest[key];
    if (!Array.isArray(value)) fail(`$.${key}`, 'must be an array');
    else if (value.some(item => typeof item !== 'string' || !/\S/.test(item)) || new Set(value).size !== value.length) fail(`$.${key}`, 'must contain unique nonempty strings');
  }
  return errors;
}
