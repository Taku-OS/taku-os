// SPDX-License-Identifier: Apache-2.0
import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {createServer} from 'node:net';
import {mkdtemp, mkdir, copyFile, readFile, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import Ajv from 'ajv';

const root=fileURLToPath(new URL('../../../../',import.meta.url));
const fixture=new URL('../../../../evidence/scenarios/phone-source/api.mjs',import.meta.url);
const serverFile=fileURLToPath(new URL('../server.mjs',import.meta.url));
const records=[];

async function availablePort(){
  const socket=createServer();socket.listen(0,'127.0.0.1');await once(socket,'listening');
  const port=socket.address().port;await new Promise(resolve=>socket.close(resolve));return port;
}
async function start(kit,port){
  const child=spawn(process.execPath,[serverFile],{env:{...process.env,MINI_APP_KIT:kit,MINI_APP_API_PORT:String(port)},stdio:['ignore','pipe','pipe']});
  let logs='';child.stderr.on('data',chunk=>{logs+=chunk;});
  await new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{child.kill('SIGTERM');reject(Error('API readiness timeout: '+logs));},5000);
    child.once('error',error=>{clearTimeout(timer);reject(error);});
    child.once('exit',code=>{clearTimeout(timer);reject(Error('API startup exited '+code+': '+logs));});
    child.stdout.on('data',chunk=>{if(String(chunk).includes('"ready":true')){clearTimeout(timer);resolve();}});
  });
  return child;
}
async function stop(child){
  if(child.exitCode!==null)return;
  const closed=once(child,'exit');child.kill('SIGTERM');
  const timer=setTimeout(()=>child.kill('SIGKILL'),3000);
  try{await closed;}finally{clearTimeout(timer);}
}

test('Ajv 6.14.0 validates the real application API over HTTP', {timeout:20000}, async t=>{
  const kit=await mkdtemp(join(tmpdir(),'taku-api-smoke-'));
  const app='score-smoke';const port=await availablePort();
  await mkdir(join(kit,'apps',app),{recursive:true});
  await copyFile(fixture,join(kit,'apps',app,'api.mjs'));
  let child;let passed=true;
  const check=(name,fn)=>t.test(name,async()=>{try{await fn();}catch(error){passed=false;throw error;}});
  const base='http://127.0.0.1:'+port;
  async function request(name,endpoint,body,expected=200,raw=false){
    const response=await fetch(base+endpoint,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json'},body:body===undefined?undefined:raw?body:JSON.stringify(body),signal:AbortSignal.timeout(5000)});
    const data=await response.json();assert.equal(response.status,expected,JSON.stringify(data));
    records.push({name,status:response.status,response:data});return data;
  }
  const call=(name,method,params,key,expected=200)=>request(name,'/v1/apps/'+app+'/call',{method,params,...(key?{idempotencyKey:key}:{})},expected);
  const state=()=>call('state','score.state',{});
  try{
    child=await start(kit,port);
    await check('patched Ajv 6 is installed and dynamic invalid regex fails without throwing',()=>{
      assert.equal(ajvPackage.version,'6.14.0');
      const dynamic=new Ajv({$data:true});
      const validate=dynamic.compile({type:'object',properties:{pattern:{type:'string'},value:{type:'string',pattern:{$data:'1/pattern'}}}});
      assert.equal(validate({pattern:'[',value:'x'}),false);
      assert.equal(validate({pattern:'^x$',value:'x'}),true);
      assert.equal(validate({pattern:'^x$',value:'y'}),false);
    });
    await check('health, app discovery, descriptors and OpenAPI remain available',async()=>{
      assert.equal((await request('health','/health')).ok,true);
      const listed=await request('apps','/v1/apps');assert.equal(listed.apps[0].id,app);
      const described=await request('describe','/v1/apps/'+app);assert.equal(described.app.methods.length,3);
      const api=await request('openapi','/v1/openapi.json');assert.ok(api.paths['/v1/apps/'+app+'/call']);
      assert.deepEqual((await state()).result.scores,{A:0,B:0});
    });
    await check('request/envelope/schema errors reject without mutation',async()=>{
      const before=await state();
      assert.equal((await request('invalid-json','/v1/apps/'+app+'/call','{',400,true)).error.code,'INVALID_JSON');
      assert.equal((await request('invalid-envelope','/v1/apps/'+app+'/call',{params:{}},400)).error.code,'INVALID_REQUEST');
      for(const params of [{team:'A',delta:'3'},{team:'A',delta:0},{team:'A',delta:1.5},{team:'C',delta:3},{delta:3},{team:'A',delta:3,extra:true}]){
        assert.equal((await call('invalid-params','score.add',params,undefined,400)).error.code,'INVALID_PARAMS');
      }
      assert.equal((await call('unknown-method','unknown',{},undefined,404)).error.code,'METHOD_NOT_FOUND');
      assert.equal((await request('unknown-app','/v1/apps/missing/call',{method:'score.state',params:{}},404)).error.code,'APP_NOT_FOUND');
      const after=await state();assert.deepEqual(after.result,before.result);assert.equal(after.revision,before.revision);
    });
    await check('successful write, canonical idempotent replay and conflict preserve one mutation',async()=>{
      const add=await call('add-a','score.add',{team:'A',delta:3},'add-a');assert.deepEqual(add.result.scores,{A:3,B:0});assert.equal(add.replayed,false);
      const replay=await call('replay-a','score.add',{delta:3,team:'A'},'add-a');assert.equal(replay.replayed,true);assert.deepEqual(replay.result,add.result);assert.equal(replay.revision,add.revision);
      const conflict=await call('conflict-a','score.add',{team:'A',delta:4},'add-a',409);assert.equal(conflict.error.code,'KEY_CONFLICT');
      const current=await state();assert.equal(current.result.history.length,1);assert.equal(current.revision,add.revision);
    });
    await check('negative deltas, undo and undo replay keep the shared state correct',async()=>{
      await call('add-b','score.add',{team:'B',delta:2},'add-b');
      assert.equal((await call('negative-b','score.add',{team:'B',delta:-1},'negative-b')).result.scores.B,1);
      const undo=await call('undo-negative','score.undo',{},'undo-negative');assert.deepEqual(undo.result.scores,{A:3,B:2});
      const replay=await call('undo-replay','score.undo',{},'undo-negative');assert.equal(replay.replayed,true);assert.equal(replay.revision,undo.revision);
      await call('undo-b','score.undo',{},'undo-b');await call('undo-a','score.undo',{},'undo-a');
      const before=await state();assert.deepEqual(before.result.scores,{A:0,B:0});assert.equal(before.result.history.length,0);
      assert.equal((await call('empty-undo','score.undo',{},undefined,409)).error.code,'EMPTY_HISTORY');
      const after=await state();assert.equal(after.revision,before.revision);assert.deepEqual(after.result,before.result);
    });
    await check('handler range failures roll back state and revision',async()=>{
      await call('safe-limit','score.add',{team:'A',delta:Number.MAX_SAFE_INTEGER},'safe-limit');
      const before=await state();
      assert.equal((await call('overflow','score.add',{team:'A',delta:1},'overflow',400)).error.code,'OUT_OF_RANGE');
      const after=await state();assert.deepEqual(after.result,before.result);assert.equal(after.revision,before.revision);
      // Failed call did not consume the key or a receipt; the same key can now succeed.
      assert.equal((await call('failed-key-reuse','score.add',{team:'B',delta:1},'overflow')).result.scores.B,1);
    });
    await check('serialized concurrent mutations do not lose state',async()=>{
      await call('undo-failed-key','score.undo',{},'undo-failed-key');await call('undo-limit','score.undo',{},'undo-limit');
      await Promise.all(Array.from({length:5},(_,i)=>call('concurrent-'+i,'score.add',{team:'A',delta:1},'concurrent-'+i)));
      const current=await state();assert.equal(current.result.scores.A,5);assert.equal(current.result.history.length,5);
    });
    await check('restart preserves score, revision and replay receipts',async()=>{
      const before=await state();await stop(child);child=await start(kit,port);
      const after=await state();assert.deepEqual(after.result,before.result);assert.equal(after.revision,before.revision);
      assert.equal((await call('persisted-replay','score.add',{team:'A',delta:1},'concurrent-0')).replayed,true);
      const final=await state();assert.equal(final.result.scores.A,5);assert.equal(final.revision,before.revision);
    });
    const output=join(root,'out/checks/app-api-http-smoke.json');await mkdir(join(root,'out/checks'),{recursive:true});
    await writeFile(output,JSON.stringify({ok:passed,checkedAt:new Date().toISOString(),node:process.version,ajv:'6.14.0',fixture:'evidence/scenarios/phone-source/api.mjs',fixtureSha256:createHash('sha256').update(await readFile(fixture)).digest('hex'),scope:'Host localhost HTTP API regression; not Android/ROM acceptance or proof of exploitability',records},null,2)+'\n');
  }finally{if(child)await stop(child);await rm(kit,{recursive:true,force:true});}
});

const ajvPackage=JSON.parse(await readFile(new URL('../node_modules/ajv/package.json',import.meta.url),'utf8'));
