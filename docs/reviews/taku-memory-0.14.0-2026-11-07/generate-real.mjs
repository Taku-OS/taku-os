// 把记忆写的描述交给首页工程自己的生成代码（src/feed/feedGeneration.ts 的 prepareFeedGeneration：同一份 instructions、解析和校验），
// 模型换成本机 Codex（taku-sim 的 llm-server，不给任何工具）。在首页工程 system/agent-app 目录里运行：node <本文件> describe-asks.json <输出目录> [并发数] [模型] [effort]
import {spawn} from 'node:child_process';
import {readFileSync, writeFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import readline from 'node:readline';
import {pathToFileURL} from 'node:url';
const {prepareFeedGeneration} = await import(pathToFileURL(process.cwd() + '/src/feed/feedGeneration.ts').href);
const [asksFile, outDir, par = '3', model = 'gpt-6-luna', effort = 'low'] = process.argv.slice(2);
const asks = JSON.parse(readFileSync(asksFile, 'utf8'));
const SIM = process.env.TAKU_SIM;   // taku-sim 目录：scripts/llm-server.mjs 用它自己 .codex-home 里已有的登录
if (!SIM) throw new Error("set TAKU_SIM to a taku-sim checkout with a signed-in Codex");

function server() {
  const p = spawn('node', ['scripts/llm-server.mjs'], {cwd: SIM, stdio: ['pipe', 'pipe', 'inherit']});
  const waiting = new Map(); let ready, failed;
  const up = new Promise((res, rej) => { ready = res; failed = rej; });
  readline.createInterface({input: p.stdout}).on('line', line => {
    let m; try { m = JSON.parse(line); } catch { return; }
    if (m.ready) return ready();
    if (m.fatal) return failed(new Error(m.fatal));
    const w = waiting.get(m.id); if (w) { waiting.delete(m.id); w(m); }
  });
  return {
    up,
    ask(instructions, input) {
      const id = randomUUID();
      return new Promise(res => { waiting.set(id, res); p.stdin.write(JSON.stringify({id, instructions, input, model, effort}) + '\n'); });
    },
    close() { p.stdin.end(); },
  };
}

const servers = Array.from({length: Number(par)}, server);
await Promise.all(servers.map(s => s.up));
const results = [];
let next = 0;
await Promise.all(servers.map(async s => {
  while (next < asks.length) {
    const a = asks[next++];
    const t0 = Date.now(); let r, raw;
    try {
      const prepared = await prepareFeedGeneration({kind: 'prompt', prompt: a.prompt}, {id: 'agent-generated:' + randomUUID(), createdAt: Date.now()},
        async ({instructions, prompt}) => {
          const m = await s.ask(instructions, prompt);
          if (m.error) throw Object.assign(new Error(m.error), {code: 'MODEL_ERROR'});
          raw = m.final; return {text: m.final, model: m.model || model, usage: m.usage};
        });
      r = {key: a.key, ok: true, ms: Date.now() - t0, model: prepared.model, card: prepared.card};
    } catch (e) {
      r = {key: a.key, ok: false, ms: Date.now() - t0, code: e.code, message: e.message, raw};
    }
    results.push(r);
    console.error(`${a.key} ${r.ok ? 'ok' : r.code} ${Math.round(r.ms / 1000)}s`);
  }
}));
servers.forEach(s => s.close());
results.sort((x, y) => asks.findIndex(a => a.key === x.key) - asks.findIndex(a => a.key === y.key));
writeFileSync(outDir + '/generated.json', JSON.stringify(results, null, 1));
console.log(`${results.filter(r => r.ok).length}/${results.length} generated`);
process.exit(0);
