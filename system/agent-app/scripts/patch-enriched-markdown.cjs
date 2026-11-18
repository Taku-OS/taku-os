const {createHash} = require('node:crypto');
const {execFileSync} = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const digest = file => fs.existsSync(file) ? createHash('sha256').update(fs.readFileSync(file)).digest('hex') : null;

/** Fail before writing if the pinned upstream sources or patch have drifted. */
function applyPatch(packageRoot, manifestPath, patchPath, checkOnly = false) {
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const installed = JSON.parse(fs.readFileSync(path.join(packageRoot, 'package.json'), 'utf8'));
  if (installed.name !== manifest.package || installed.version !== manifest.version) {
    throw new Error(`Markdown native patch requires ${manifest.package}@${manifest.version}; found ${installed.name}@${installed.version}`);
  }
  const states = manifest.files.map(file => {
    if (path.isAbsolute(file.path) || file.path.split('/').includes('..')) throw new Error(`Invalid patch path: ${file.path}`);
    const actual = digest(path.join(packageRoot, file.path));
    if (actual === file.after) return 'patched';
    if (actual === file.before) return 'original';
    throw new Error(`Markdown native source changed: ${file.path}. Review the patch; do not apply it to a different upstream version.`);
  });
  if (states.every(state => state === 'patched')) return 'already applied';
  if (states.some(state => state === 'patched')) throw new Error('Markdown native patch is partially applied; reinstall with npm ci.');
  if (checkOnly) throw new Error('Markdown native patch is missing; run npm run patch:markdown.');
  execFileSync('patch', ['-f', '-F', '0', '--dry-run', '-p1', '-i', patchPath], {cwd: packageRoot, stdio: 'pipe'});
  execFileSync('patch', ['-f', '-F', '0', '-p1', '-i', patchPath], {cwd: packageRoot, stdio: 'pipe'});
  for (const file of manifest.files) {
    if (digest(path.join(packageRoot, file.path)) !== file.after) throw new Error(`Markdown patch verification failed: ${file.path}`);
  }
  return 'applied';
}

if (require.main === module) {
  const appRoot = path.resolve(__dirname, '..');
  const prefix = path.join(appRoot, 'patches', 'react-native-enriched-markdown+1.1.1');
  const result = applyPatch(path.join(appRoot, 'node_modules', 'react-native-enriched-markdown'), `${prefix}.json`, `${prefix}.patch`, process.argv.includes('--check'));
  console.log(`Markdown native patch: ${result}`);
}
module.exports = {applyPatch};
