const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {test} = require('node:test');
const {collectSources, checkSources, inspectExport} = require('./check-android-audit-boundary.cjs');

const flat = (sources, sourceRoot = '') => ({version: 3, sources, sourceRoot, mappings: ''});
const indexed = (map) => ({version: 3, sections: [{offset: {line: 0, column: 0}, map}]});

test('visits nested indexed maps, including deeply nested forbidden packages', () => {
  const sources = collectSources(indexed(indexed(flat(['/node_modules/picomatch/lib/parse.js']))));
  assert.equal(sources.length, 1);
  assert.throws(() => checkSources(sources), /Tooling-only/);
});

test('rejects all waived packages with source roots, nested node_modules, or Windows paths', () => {
  for (const source of ['/node_modules/picomatch/index.js', '/node_modules/a/node_modules/micromatch/index.js',
    'C:\\project\\node_modules\\tinyglobby\\dist\\index.cjs']) {
    assert.throws(() => checkSources(collectSources(flat([source]))), /Tooling-only/);
  }
  assert.throws(() => checkSources(collectSources(flat(['index.js'], '/node_modules/picomatch'))), /Tooling-only/);
});

test('allows unrelated modules without confusing similar names', () => {
  assert.equal(checkSources(collectSources(flat(['/src/app/index.tsx', '/node_modules/picomatch-helper/index.js']))), 2);
});

test('fails on absent, unsupported, empty, or externally referenced maps', () => {
  for (const map of [null, {version: 2, sources: []}, {version: 3},
    {version: 3, sections: []}, {version: 3, sections: [{url: 'elsewhere.map'}]},
    {version: 3, sources: [42], mappings: ''}]) {
    assert.throws(() => collectSources(map));
  }
  assert.throws(() => checkSources([]), /no modules/);
});

function exportFixture(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'taku-boundary-test-'));
  t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
  const bundleDir = path.join(directory, '_expo/static/js/android');
  fs.mkdirSync(bundleDir, {recursive: true});
  fs.writeFileSync(path.join(directory, 'metadata.json'), JSON.stringify({bundler: 'metro', fileMetadata: {
    android: {bundle: '_expo/static/js/android/index.js', assets: []},
  }}));
  fs.writeFileSync(path.join(bundleDir, 'index.js'), 'application bundle');
  fs.writeFileSync(path.join(bundleDir, 'index.js.map'), JSON.stringify(indexed(flat(['/src/app/index.tsx']))));
  return {directory, bundleDir};
}

test('checks every Android chunk, not only the metadata entry bundle', (t) => {
  const {directory, bundleDir} = exportFixture(t);
  assert.equal(inspectExport(directory).sourceCount, 1);
  fs.writeFileSync(path.join(bundleDir, 'lazy.js'), 'lazy bundle');
  assert.throws(() => inspectExport(directory), /ENOENT/);
  fs.writeFileSync(path.join(bundleDir, 'lazy.js.map'), JSON.stringify(flat(['/node_modules/picomatch/index.js'])));
  assert.throws(() => inspectExport(directory), /Tooling-only/);
});

test('blocks DOM bundles and extensionless executable Android assets', (t) => {
  const {directory} = exportFixture(t);
  for (const asset of [{path: 'www.bundle/123.js', ext: 'js'}, {path: 'assets/hash', ext: 'html'},
    {path: 'www.bundle/123', ext: 'bin'}, {path: 'assets/hash', ext: 'JS'}]) {
    fs.writeFileSync(path.join(directory, 'metadata.json'), JSON.stringify({bundler: 'metro', fileMetadata: {
      android: {bundle: '_expo/static/js/android/index.js', assets: [asset]},
    }}));
    assert.throws(() => inspectExport(directory), /executable or DOM/);
  }
});

test('blocks nested or unlisted executable bundles outside the checked layout', (t) => {
  const {directory, bundleDir} = exportFixture(t);
  fs.mkdirSync(path.join(bundleDir, 'chunks'));
  fs.writeFileSync(path.join(bundleDir, 'chunks/lazy.js'), 'uninspected chunk');
  assert.throws(() => inspectExport(directory), /outside/);
});

test('requires a real app entry screen and the expected Android export layout', (t) => {
  const {directory, bundleDir} = exportFixture(t);
  fs.writeFileSync(path.join(bundleDir, 'index.js.map'), JSON.stringify(flat(['/unrelated.js'])));
  assert.throws(() => inspectExport(directory), /entry screen/);
  fs.writeFileSync(path.join(directory, 'metadata.json'), JSON.stringify({bundler: 'metro', fileMetadata: {
    android: {bundle: '../../somewhere.js'},
  }}));
  assert.throws(() => inspectExport(directory), /production Android/);
});
