const fs = require('node:fs');
const path = require('node:path');

// Run with Node alone, before installing or executing dependency code.
function checkDependencyLock(lock) {
  if (lock.lockfileVersion !== 3 || !lock.packages?.['']) {
    throw new Error('Expected a v3 npm lockfile with a root package');
  }
  let count = 0;
  for (const [location, entry] of Object.entries(lock.packages)) {
    if (location === '') continue;
    let url;
    try { url = new URL(entry.resolved); } catch { /* Report below. */ }
    if (entry.link || !url || url.origin !== 'https://registry.npmjs.org' ||
        url.username || url.password || url.search || url.hash || !url.pathname.endsWith('.tgz')) {
      throw new Error(`${location}: expected an HTTPS npm registry tarball`);
    }
    if (!/^sha512-[A-Za-z0-9+/]{86}==$/.test(entry.integrity ?? '')) {
      throw new Error(`${location}: missing or invalid SHA-512 integrity`);
    }
    count++;
  }
  if (!count) throw new Error('Dependency lockfile is empty');
  return count;
}

if (require.main === module) {
  const lock = JSON.parse(fs.readFileSync(path.join(__dirname, '../package-lock.json'), 'utf8'));
  console.log(`Verified registry URLs and SHA-512 pins for ${checkDependencyLock(lock)} dependencies.`);
}
module.exports = {checkDependencyLock};
