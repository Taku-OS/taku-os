// Real Taku Phone WebView; every movement is gated to the isolated simulator.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
const base='http://127.0.0.1:18845',out='out/go2-lab/camera-verification';fs.mkdirSync(out,{recursive:true});
const backend=async()=>{const s=await(await fetch(base+'/api/state')).json();assert.equal(s.source,'simulation','Refuse real robot');return s;};
await backend();
await fetch(base+'/api/disconnect',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});
const pages=await(await fetch('http://127.0.0.1:19245/json')).json();
const ws=new WebSocket(pages.find(p=>p.url===base+'/').webSocketDebuggerUrl);
await new Promise(r=>ws.addEventListener('open',r,{once:true}));
let id=0,freeze=false;const pending=new Map(),exceptions=[],paused=[],checks=[];
ws.addEventListener('message',e=>{const x=JSON.parse(e.data);if(x.method==='Runtime.exceptionThrown')exceptions.push(x.params);if(x.method==='Fetch.requestPaused'){paused.push(x.params.requestId);if(!freeze)send('Fetch.continueRequest',{requestId:x.params.requestId});}if(pending.has(x.id)){pending.get(x.id)(x);pending.delete(x.id);}});
function send(method,params={}){return new Promise((resolve,reject)=>{const key=++id,timer=setTimeout(()=>{pending.delete(key);reject(Error('CDP timeout '+method));},10000);pending.set(key,x=>{clearTimeout(timer);x.error?reject(Error(JSON.stringify(x.error))):resolve(x.result);});ws.send(JSON.stringify({id:key,method,params}));});}
const ev=async expression=>{const x=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});assert.ok(!x.exceptionDetails,JSON.stringify(x));return x.result?.value;};
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const wait=async expression=>{for(let i=0;i<150;i++){if(await ev(expression))return;await delay(100);}throw Error(expression);};
const click=async id=>{await backend();await wait(`document.getElementById('${id}') && !document.getElementById('${id}').disabled`);await ev(`document.getElementById('${id}').click()`);};
const screenshot=name=>{const filename=path.join(out,name+'.png');fs.writeFileSync(filename,execFileSync(path.join(os.homedir(),'Library/Android/sdk/platform-tools/adb'),['-s',process.env.TAKU_SERIAL || 'emulator-5554','exec-out','screencap','-p'],{maxBuffer:10*1024*1024}));return filename;};
async function touch(id){
 await backend();await wait(`!document.getElementById('${id}').disabled`);
 await ev(`document.getElementById('${id}').scrollIntoView({block:'center'})`);
 const r=await ev(`(()=>{const b=document.getElementById('${id}').getBoundingClientRect(),c=document.getElementById('camera-dock').getBoundingClientRect();return {x:b.x+b.width/2,y:b.y+b.height/2,top:b.top,bottom:b.bottom,cameraBottom:c.bottom,viewport:innerHeight};})()`);
 assert.ok(r.top>=r.cameraBottom&&r.bottom<=r.viewport,JSON.stringify(r));
 await send('Input.dispatchMouseEvent',{type:'mousePressed',button:'left',clickCount:1,x:r.x,y:r.y});
 await send('Input.dispatchMouseEvent',{type:'mouseReleased',button:'left',clickCount:1,x:r.x,y:r.y});
 return r;
}
try{
 await send('Page.reload',{ignoreCache:true});await wait('Boolean(window.go2Camera && window.go2LabState?.data)');await send('Runtime.enable');
 await click('tab-camera');assert.equal(await ev("document.getElementById('camera-start').disabled"),true);checks.push('Camera cannot start disconnected');
 await click('tab-state');await click('connect');await wait('go2LabState.data.connected');await click('tab-camera');await click('camera-start');await wait('go2Camera.live');
 assert.equal(await ev("document.getElementById('camera-sim-label').hidden"),false);
 const before=await ev('go2Camera.drawCount');await delay(4000);const fps=((await ev('go2Camera.drawCount'))-before)/4;
 assert.ok(fps>=5,`WebView updates too slow: ${fps}`);checks.push({name:'Live JPEG decode/render in WebView',fps,screenshot:screenshot('camera-page')});
 await click('tab-motion');
 for(const action of ['forward','backward','left','right']){
  await wait("!document.getElementById('motion-ready').disabled && !go2LabState.data.motion?.busy");
  await ev("document.getElementById('motion-ready').checked=true;document.getElementById('motion-ready').dispatchEvent(new Event('change'))");
  if(!(await backend()).motion.armed)await click('motion-arm');
  await wait("!document.getElementById('motion-'+"+JSON.stringify(action)+").disabled");
  const before=await ev('go2Camera.drawCount'),r=await touch('motion-'+action);
  await wait("document.getElementById('motion-'+"+JSON.stringify(action)+").disabled");
  await delay(900);assert.ok((await ev('go2Camera.drawCount'))>before+2);
  assert.equal(await ev('go2Camera.live'),true);checks.push({name:'Pinned camera during '+action,geometry:r});
 }
 screenshot('camera-with-motion');
 await click('tab-avoidance');await ev("document.getElementById('avoid-forward').scrollIntoView({block:'center'})");
 const rect=await ev("(()=>{const c=document.getElementById('camera-dock').getBoundingClientRect(),b=document.getElementById('avoid-forward').getBoundingClientRect(),s=document.getElementById('avoid-stop').getBoundingClientRect();return {cameraTop:c.top,cameraBottom:c.bottom,buttonTop:b.top,buttonBottom:b.bottom,stopTop:s.top};})()");
 assert.ok(rect.cameraTop>=-1&&rect.cameraBottom<=rect.buttonTop&&rect.buttonBottom<=rect.stopTop,JSON.stringify(rect));checks.push({name:'Pinned camera and avoidance controls remain visible',geometry:rect});screenshot('camera-with-avoidance');
 // Stall only the camera HTTP request; telemetry/control continue normally.
 freeze=true;await send('Fetch.enable',{patterns:[{urlPattern:'*/api/camera/frame*'}]});await delay(1400);
 assert.equal(await ev('go2Camera.live'),false);assert.equal(await ev("document.getElementById('camera-canvas').hidden"),true);
 checks.push('Stalled frame is hidden within freshness limit');screenshot('camera-stale');
 freeze=false;await send('Fetch.disable');await wait('go2Camera.live');
 await click('camera-stop');await wait('!go2Camera.meta.enabled');assert.equal(await ev("document.getElementById('camera-canvas').hidden"),true);checks.push('Stop clears picture');
 await click('camera-start');await wait('go2Camera.live');await click('tab-state');await click('disconnect');await wait('!go2Camera.live && !go2Camera.meta.connected');checks.push('Restart and disconnect cleanup');
 await click('tab-camera');await click('language');assert.equal(await ev("document.getElementById('camera-title').textContent"),'Robot view');await click('language');
 for(const width of [320,768,1024,1440]){
  await send('Emulation.setDeviceMetricsOverride',{width,height:900,deviceScaleFactor:1,mobile:true});
  const dims=await ev('({scroll:document.documentElement.scrollWidth,width:innerWidth})');assert.ok(dims.scroll<=dims.width+1,JSON.stringify(dims));
 }
 await send('Emulation.clearDeviceMetricsOverride');checks.push('English/Chinese and responsive widths');
 assert.deepEqual(exceptions,[]);
 fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({checks,exceptions,source:'simulation',realRobotVerified:false},null,2));console.log(JSON.stringify({passed:checks.length,fps,checks}));
}finally{await send('Fetch.disable').catch(()=>{});await send('Emulation.clearDeviceMetricsOverride').catch(()=>{});ws.close();}
