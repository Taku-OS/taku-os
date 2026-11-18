const assert = require('node:assert/strict');
const {createHash} = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {test} = require('node:test');
const {applyPatch} = require('./patch-enriched-markdown.cjs');
const sha = text => createHash('sha256').update(text).digest('hex');

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'taku-markdown-patch-'));
  t.after(() => fs.rmSync(root, {recursive: true, force: true}));
  fs.writeFileSync(path.join(root, 'package.json'), JSON.stringify({name: 'test-native-module', version: '1.1.1'}));
  fs.writeFileSync(path.join(root, 'native.kt'), 'original\n');
  const manifest = path.join(root, 'manifest.json');
  const patch = path.join(root, 'module.patch');
  fs.writeFileSync(manifest, JSON.stringify({package: 'test-native-module', version: '1.1.1', files: [
    {path: 'native.kt', before: sha('original\n'), after: sha('patched\n')},
    {path: 'new.kt', before: null, after: sha('new\n')},
  ]}));
  fs.writeFileSync(patch, '--- a/native.kt\n+++ b/native.kt\n@@ -1 +1 @@\n-original\n+patched\n--- /dev/null\n+++ b/new.kt\n@@ -0,0 +1 @@\n+new\n');
  return {root, apply: (checkOnly = false) => applyPatch(root, manifest, patch, checkOnly)};
}

test('native patch installs both edited and added sources, then is idempotent', t => {
  const {root, apply} = fixture(t);
  assert.throws(() => apply(true), /patch is missing/);
  assert.equal(apply(), 'applied');
  assert.equal(fs.readFileSync(path.join(root, 'new.kt'), 'utf8'), 'new\n');
  assert.equal(apply(), 'already applied');
  assert.equal(apply(true), 'already applied');
});
test('native patch refuses upstream drift before any file is changed', t => {
  const {root, apply} = fixture(t);
  fs.writeFileSync(path.join(root, 'native.kt'), 'upstream change\n');
  assert.throws(apply, /source changed/);
  assert.equal(fs.existsSync(path.join(root, 'new.kt')), false);
});
test('native patch refuses a different installed version', t => {
  const {root, apply} = fixture(t);
  fs.writeFileSync(path.join(root, 'package.json'), JSON.stringify({name: 'test-native-module', version: '1.2.0'}));
  assert.throws(apply, /requires.*1.1.1/);
  assert.equal(fs.readFileSync(path.join(root, 'native.kt'), 'utf8'), 'original\n');
});
test('native patch refuses mixed sources instead of silently accepting a partial install', t => {
  const {root, apply} = fixture(t);
  fs.writeFileSync(path.join(root, 'native.kt'), 'patched\n');
  assert.throws(apply, /partially applied/);
  assert.equal(fs.existsSync(path.join(root, 'new.kt')), false);
});
