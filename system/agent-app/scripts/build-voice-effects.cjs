// Bundle the designer's pure renderers with the already-installed TypeScript compiler.
// No package install, network, runtime CDN, audio access or JavaScript/native bridge.
const fs = require('node:fs');
const path = require('node:path');
const ts = require('typescript');
const root = path.resolve(__dirname, '../modules/taku-home');
const source = path.join(root, 'voice-effects');
const modules = {};
for (const [name, file] of Object.entries({
  './light-field': 'effects/voice-light-field/light-field.ts',
  './voice': 'effects/voice-light-field/voice.ts',
  './waveform': 'effects/voice-waveform/waveform.ts',
})) modules[name] = ts.transpileModule(fs.readFileSync(path.join(source, file), 'utf8'), {
  compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020},
}).outputText;
// Expose the supplied layer geometry for the host's static WebGL fallback, without
// modifying the designer's source or inventing a different effect.
modules['./light-field'] += '\nexports.staticLayers = {listen:LISTEN, live:LIVE};';
const masks = {};
for (const file of fs.readdirSync(path.join(source, 'assets/effects/voice-light-field'))) {
  if (!file.endsWith('.svg')) continue;
  const key = file.replace('.svg', '').replace(/-([a-z0-9])/g, (_, c) => c.toUpperCase());
  masks[key] = 'data:image/svg+xml;base64,' + fs.readFileSync(path.join(source, 'assets/effects/voice-light-field', file)).toString('base64');
}
modules['./masks'] = 'exports.LIGHT_MASKS = ' + JSON.stringify(masks);
const loader = `const modules={${Object.entries(modules).map(([name, code]) => `${JSON.stringify(name)}:function(exports,require){${code}\n}`).join(',')}};const cache={};function require(id){if(!cache[id]){cache[id]={};modules[id](cache[id],require);}return cache[id];}`;
const runtime = fs.readFileSync(path.join(source, 'host.js'), 'utf8');
// Fail the build before shipping an HTML page with a broken inline script.
new (require('node:vm').Script)(loader + '\n' + runtime, {filename: 'voice-effects.js'});
const html = `<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:"><style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:transparent}canvas{display:block;width:100%;height:100%}</style><canvas id="effect"></canvas><script>${loader}\n${runtime}</script>`;
const out = path.join(root, 'android/src/main/assets/voice-effects.html');
fs.writeFileSync(out, html);
console.log('Bundled designer voice effects and 10 local masks');
