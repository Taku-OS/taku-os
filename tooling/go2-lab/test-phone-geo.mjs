// Real AMap queries from the Android WebView. Requires a personal key configured on Mac.
// Simulation navigation only, gated on source=simulation. Public sample locations only.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {execFileSync} from 'node:child_process';
const base='http://127.0.0.1:18845',out='out/go2-lab/geo-verification';
fs.mkdirSync(out,{recursive:true});
const before=await(await fetch(base+'/api/state')).json();assert.equal(before.source,'simulation');
const pages=await(await fetch('http://127.0.0.1:19245/json')).json();
const ws=new WebSocket(pages.find(p=>p.url===base+'/').webSocketDebuggerUrl);
await new Promise(r=>ws.addEventListener('open',r,{once:true}));
let id=0;const pending=new Map(),errors=[],checks=[];
ws.addEventListener('message',e=>{const x=JSON.parse(e.data);if(x.method==='Runtime.exceptionThrown')errors.push(x.params);if(pending.has(x.id)){pending.get(x.id)(x);pending.delete(x.id);}});
function send(method,params={}){return new Promise((resolve,reject)=>{const key=++id,t=setTimeout(()=>reject(Error('CDP timeout')),10000);pending.set(key,x=>{clearTimeout(t);x.error?reject(Error(JSON.stringify(x.error))):resolve(x.result);});ws.send(JSON.stringify({id:key,method,params}));});}
async function ev(expression){const x=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});assert.ok(!x.exceptionDetails,JSON.stringify(x));return x.result?.value;}
const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function wait(expression){for(let i=0;i<200;i++){if(await ev(expression))return;await delay(200);}throw Error('Timeout '+expression);}
async function click(id){await wait(`!document.getElementById('${id}').disabled`);await ev(`document.getElementById('${id}').click()`);}
function shot(name){fs.writeFileSync(`${out}/${name}.png`,execFileSync(process.env.ADB || 'adb',['-s',process.env.TAKU_SERIAL || 'emulator-5554','exec-out','screencap','-p'],{maxBuffer:10*1024*1024}));}
try{
 await send('Runtime.enable');
 assert.ok(await ev('Boolean(window.go2Amap?.route?.mapViewport)'), 'Run test-phone-amap.mjs first');
 await click('tab-amap');await ev("document.getElementById('geo-scope').value='first10m'");
 await click('geo-prepare');await wait("!go2Geo.pending && Boolean(go2Geo.data?.geo?.routeId)");
 assert.equal(await ev('go2Geo.data.geo.scope'),'first10m');assert.ok(await ev('go2Geo.data.geo.length<=10.01'));
 assert.equal(await ev('go2Geo.data.realActuationAllowed'),false);
 await click('geo-start');await wait('go2Geo.data.geo.active');
 await ev("document.getElementById('geo-monitor').scrollIntoView({block:'center'})");shot('running');
 let ended=false,maxCross=0;const started=Date.now();
 while(Date.now()-started<240000){
  await delay(1000);const s=await ev('go2Geo.data');maxCross=Math.max(maxCross,s.geo.crossTrack);
  if(s.state==='blocked'||s.state==='failed')throw Error(JSON.stringify({state:s.state,reason:s.reason,geo:s.geo,pose:s.pose}));
  if(!s.active&&s.state==='arrived'){ended=true;break;}
  if(Math.round((Date.now()-started)/1000)%25===0)console.log(JSON.stringify({seconds:Math.round((Date.now()-started)/1000),progress:s.geo.progress,cross:s.geo.crossTrack,pose:s.pose.position}));
 }
 assert.equal(ended,true,'Arrival timeout');
 const arrived=await ev('go2Geo.data'),lab=await(await fetch(base+'/api/lab')).json();
 assert.equal(arrived.stopConfirmed,true);assert.equal(lab.simulation.nativeBlocks,0);assert.ok(maxCross>.7);assert.ok(arrived.geo.progress>9.8);
 assert.ok(await ev("document.getElementById('geo-status').textContent.includes('尚未走完全程')"));shot('arrived');
 checks.push({name:'Real AMap prefix followed around synthetic box',progress:arrived.geo.progress,total:arrived.geo.fullLength,maxCross,elapsedSeconds:(Date.now()-started)/1000,stopped:true,physicalEnvelopeBlocks:lab.simulation.nativeBlocks});
 // Operator stop and page departure must preempt geographic motion.
 await click('geo-prepare');await wait('!go2Geo.pending && !go2Geo.data.active');await click('geo-start');await wait('go2Geo.data.active');await click('geo-stop');await wait('!go2Geo.data.active && go2Geo.data.stopConfirmed');checks.push('Visible stop cancels route');
 await click('geo-prepare');await wait('!go2Geo.pending && !go2Geo.data.active');await click('geo-start');await wait('go2Geo.data.active');await click('tab-state');await wait('!go2Geo.data.active && go2Geo.data.stopConfirmed');checks.push('Leaving AMap page stops route');
 await click('tab-amap');await ev("document.getElementById('geo-monitor').scrollIntoView({block:'center'})");shot('stopped');
 assert.deepEqual(errors,[]);fs.writeFileSync(out+'/results.json',JSON.stringify({checks,errors,realRobotVerified:false},null,2));console.log(JSON.stringify({passed:checks.length,checks}));
}finally{
 await fetch(base+'/api/navigation/stop',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});
 ws.close();
}
