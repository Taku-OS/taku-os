const fs = require('node:fs');
const path = require('node:path');

/** Keep APK assets authoritative; the web gallery consumes an ignored, generated copy. */
function prepareFeedAssets(appRoot = path.resolve(__dirname, '..')) {
  const source = path.join(appRoot, 'modules/taku-home/android/src/main/assets/feed');
  const destination = path.join(appRoot, 'public/feed-assets');
  if (!fs.statSync(source).isDirectory()) throw new Error('Feed asset source must be a directory');
  // rmSync removes a legacy symlink itself, never its target. Replacing the copy also removes stale assets.
  fs.rmSync(destination, {recursive: true, force: true});
  fs.mkdirSync(path.dirname(destination), {recursive: true});
  fs.cpSync(source, destination, {recursive: true});
}

if (require.main === module) {
  prepareFeedAssets();
  console.info('Feed gallery assets prepared.');
}

module.exports = {prepareFeedAssets};
