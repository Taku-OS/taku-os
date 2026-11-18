const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const process = require('node:process');
const {spawnSync} = require('node:child_process');
const {appRoot, checkException, readException, sha256} = require('./audit-dependencies.cjs');

function collectSources(map) {
  if (!map || map.version !== 3) throw new Error('Missing or unsupported Android source map.');
  if (Array.isArray(map.sections)) {
    if ('sources' in map || !map.sections.length) throw new Error('Invalid indexed source map.');
    return map.sections.flatMap((section) => {
      if (section.url || !section.map) throw new Error('External source-map sections cannot be verified.');
      return collectSources(section.map);
    });
  }
  if (!Array.isArray(map.sources) || map.sources.some((source) => typeof source !== 'string') ||
      typeof map.mappings !== 'string' || (map.sourceRoot && typeof map.sourceRoot !== 'string')) {
    throw new Error('Incomplete Android source map.');
  }
  return map.sources.map((source) => `${map.sourceRoot ?? ''}/${source}`.replaceAll('\\', '/'));
}

function checkSources(sources) {
  if (!sources.length) throw new Error('Android source map contains no modules.');
  const blocked = sources.filter((source) => /(?:^|\/)node_modules\/(?:picomatch|micromatch|tinyglobby)(?:\/|$)/.test(source));
  if (blocked.length) throw new Error(`Tooling-only audit exception violated: ${blocked.join(', ')}`);
  return sources.length;
}

function inspectExport(directory) {
  const metadata = JSON.parse(fs.readFileSync(path.join(directory, 'metadata.json'), 'utf8'));
  const entry = metadata.fileMetadata?.android?.bundle;
  if (metadata.bundler !== 'metro' || typeof entry !== 'string' ||
      !/^_expo\/static\/js\/android\/[^/]+\.js$/.test(entry)) {
    throw new Error('Expected a production Android JavaScript export.');
  }
  const bundleDir = path.join(directory, '_expo/static/js/android');
  const assets = metadata.fileMetadata.android.assets;
  if (!Array.isArray(assets) || assets.some((asset) => typeof asset?.ext !== 'string' ||
      typeof asset.path !== 'string' || /(?:^|\/)www\.bundle(?:\/|$)/.test(asset.path) ||
      /^(?:js|mjs|cjs|html?|xhtml|hbc|bundle)$/i.test(asset.ext))) {
    throw new Error('Unsupported executable or DOM Android asset; audit its bundle boundary before enabling it.');
  }
  function checkLayout(folder) {
    for (const entry of fs.readdirSync(folder, {withFileTypes: true})) {
      const file = path.join(folder, entry.name);
      if (entry.isSymbolicLink()) throw new Error('Linked export files cannot be verified.');
      if (entry.isDirectory()) checkLayout(file);
      else if (/\.(?:js|mjs|cjs|html?|xhtml|hbc|bundle)$/i.test(entry.name) &&
          !(folder === bundleDir && entry.name.endsWith('.js'))) {
        throw new Error('Unsupported executable export outside the checked Android bundle layout.');
      }
    }
  }
  checkLayout(directory);
  const bundles = fs.readdirSync(bundleDir).filter((file) => file.endsWith('.js'));
  if (!bundles.includes(path.basename(entry))) throw new Error('Android entry bundle is missing.');
  const sources = [];
  const results = bundles.map((file) => {
    const bundleBytes = fs.readFileSync(path.join(bundleDir, file));
    const mapBytes = fs.readFileSync(path.join(bundleDir, `${file}.map`));
    const collected = collectSources(JSON.parse(mapBytes));
    const sourceCount = checkSources(collected);
    sources.push(...collected);
    return {file, sourceCount, bundleSha256: sha256(bundleBytes), sourceMapSha256: sha256(mapBytes)};
  });
  if (!sources.some((source) => /(?:^|\/)src\/app\/index\.tsx$/.test(source))) {
    throw new Error('Android export does not contain the application entry screen.');
  }
  return {platform: 'android', sourceCount: sources.length, bundles: results, excludedPackages: ['picomatch', 'micromatch', 'tinyglobby']};
}

function runBoundaryCheck() {
  fs.rmSync(path.join(appRoot, 'out/security/android-audit-boundary.json'), {force: true});
  const lockBytes = fs.readFileSync(path.join(appRoot, 'package-lock.json'));
  checkException(readException(), lockBytes);
  // A fresh, owned directory prevents stale or manually supplied maps passing CI.
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'taku-android-audit-'));
  try {
    const result = spawnSync(process.execPath, [path.join(appRoot, 'node_modules/expo/bin/cli'),
      'export', '--platform', 'android', '--source-maps', '--no-bytecode', '--max-workers', '2', '--output-dir', directory],
    {cwd: appRoot, stdio: 'inherit', timeout: 600000, env: {...process.env, CI: '1', EXPO_OFFLINE: '1', EXPO_NO_TELEMETRY: '1'}});
    if (result.error || result.signal || result.status !== 0) throw new Error('Fresh Android export failed.');
    const evidence = {...inspectExport(directory), checkedAt: new Date().toISOString(), lockfileSha256: sha256(lockBytes)};
    const output = path.join(appRoot, 'out/security');
    fs.mkdirSync(output, {recursive: true});
    fs.writeFileSync(path.join(output, 'android-audit-boundary.json'), JSON.stringify(evidence, null, 2) + '\n');
    console.log(`Verified ${evidence.sourceCount} Android sources; waived tooling packages are absent.`);
  } finally {
    fs.rmSync(directory, {recursive: true, force: true});
  }
}

if (require.main === module) {
  try { runBoundaryCheck(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = {collectSources, checkSources, inspectExport};
