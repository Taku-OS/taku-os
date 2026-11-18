const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {test} = require('node:test');
const {prepareFeedAssets} = require('./prepare-feed-assets.cjs');

test('web assets replace the legacy symlink without modifying APK sources and refresh stale files', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'taku-feed-assets-'));
  t.after(() => fs.rmSync(root, {recursive: true, force: true}));
  const source = path.join(root, 'modules/taku-home/android/src/main/assets/feed');
  const destination = path.join(root, 'public/feed-assets');
  fs.mkdirSync(path.join(source, 'icons'), {recursive: true});
  fs.writeFileSync(path.join(source, 'icons/sun.svg'), '<svg/>');
  fs.mkdirSync(path.dirname(destination), {recursive: true});
  fs.symlinkSync(source, destination, 'dir');

  prepareFeedAssets(root);
  assert.equal(fs.lstatSync(destination).isSymbolicLink(), false);
  assert.equal(fs.readFileSync(path.join(source, 'icons/sun.svg'), 'utf8'), '<svg/>');
  assert.equal(fs.readFileSync(path.join(destination, 'icons/sun.svg'), 'utf8'), '<svg/>');

  fs.rmSync(path.join(source, 'icons/sun.svg'));
  fs.writeFileSync(path.join(source, 'sample-feed.json'), '{"cards":[]}');
  prepareFeedAssets(root);
  assert.equal(fs.existsSync(path.join(destination, 'icons/sun.svg')), false);
  assert.equal(fs.readFileSync(path.join(destination, 'sample-feed.json'), 'utf8'), '{"cards":[]}');
});
