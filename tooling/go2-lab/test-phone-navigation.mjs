// End-to-end WebView tasks; refuse to send any command to a real bridge.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {execFileSync} from 'node:child_process';
const base='http://127.0.0.1:18845',out='out/go2-lab/navigation-verification';fs.mkdirSync(out,{recursive:true});
async function source(){const s=await(await fetch(base+'/api/state')).json();assert.equal(s.source,'simulation');}
async function api(action,value={}){await source();const r=await fetch(base+'/api/navigation/'+action,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(value)});return {status:r.status,data:await r.json()};}
await source();const pages=await(await fetch('http://127.0.0.1:19245/json')).json();const ws=new WebSocket(pages.find(p=>p.url===base+'/').webSocketDebuggerUrl);
await new Promise(r=>ws.addEventListener('open',r,{once:true}));let id=0;const pending=new Map(),errors=[],checks=[];
ws.addEventListener('message',e=>{const x=JSON.parse(e.data);if(x.method==='Runtime.exceptionThrown')errors.push(x.params);if(pending.has(x.id)){pending.get(x.id)(x);pending.delete(x.id);}});
function send(method,params={}){return new Promise((resolve,reject)=>{const key=++id,t=setTimeout(()=>reject(Error('CDP timeout')),10000);pending.set(key,x=>{clearTimeout(t);x.error?reject(Error(JSON.stringify(x.error))):resolve(x.result);});ws.send(JSON.stringify({id:key,method,params}));});}
async function ev(expression){const x=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});assert.ok(!x.exceptionDetails,JSON.stringify(x));return x.result?.value;}
const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function wait(expression,seconds=15){for(let i=0;i<seconds*5;i++){if(await ev(expression))return;await delay(200);}throw Error('Timeout '+expression);}
async function click(id){await source();await wait(`!document.getElementById('${id}').disabled`);await ev(`document.getElementById('${id}').click()`);}
async function shot(name){fs.writeFileSync(`${out}/${name}.png`,execFileSync(process.env.ADB || 'adb',['-s',process.env.TAKU_SERIAL || 'emulator-5554','exec-out','screencap','-p'],{maxBuffer:10*1024*1024}));}
try{
 await send('Runtime.enable');await send('Page.reload',{ignoreCache:true});await wait('Boolean(window.go2Navigation?.online)');
 await click('tab-navigation');await click('nav-prepare');await wait('go2Navigation.data.map && go2Navigation.data.readiness===null && go2Camera.live');
 await ev("document.getElementById('navigation-page').scrollIntoView()");await shot('navigation-map');
 assert.equal(await ev("document.getElementById('nav-go').disabled"),true);
 // Real pointer event on the map chooses a floor cell at (1.7,1.3).
 const r=await ev("(()=>{const r=document.getElementById('nav-map').getBoundingClientRect();return {x:r.x+r.width*(300+1.7*90)/600,y:r.y+r.height*(300-1.3*90)/600};})()");
 await send('Input.dispatchMouseEvent',{type:'mousePressed',x:r.x,y:r.y,button:'left',clickCount:1});await send('Input.dispatchMouseEvent',{type:'mouseReleased',x:r.x,y:r.y,button:'left',clickCount:1});
 assert.deepEqual(await ev('go2Navigation.selected'),[1.7,1.3]);
 await ev("document.getElementById('nav-name').value='接待点';document.getElementById('nav-name').dispatchEvent(new Event('input'))");
 await click('nav-save');await wait("Boolean(go2Navigation.data.places['接待点'])");checks.push('Map pointer selection saved a named place');
 await click('nav-named');await wait('go2Navigation.data.active');
 assert.equal(await ev("document.getElementById('nav-go').disabled"),true);
 const before=await ev('go2Camera.drawCount');await delay(1200);assert.ok((await ev('go2Camera.drawCount'))>before+3);assert.equal(await ev('go2Camera.live'),true);
 await shot('navigation-running');checks.push('Named goal runs with live camera and exclusive controls');
 await wait("go2Navigation.data.state==='arrived'",125);
 const arrived=await ev('go2Navigation.data');assert.equal(arrived.stopConfirmed,true);assert.equal(arrived.active,false);assert.ok(Math.hypot(arrived.pose.position[0]-1.7,arrived.pose.position[1]-1.3)<.16);
 const lab=await(await fetch(base+'/api/lab')).json();assert.equal(lab.simulation.nativeBlocks,0);await shot('navigation-arrived');checks.push({name:'Named map goal reached around box without collision',position:arrived.pose.position,steps:arrived.steps,replans:arrived.replans});
 await click('nav-prepare');await wait("go2Navigation.data.state==='idle' && go2Navigation.data.readiness===null");
 const mapId=await ev('go2Navigation.data.mapId');await api('goal',{mapId,name:'临时目标',x:1.7,y:1.3});await wait('go2Navigation.data.active');await click('nav-stop');await wait("go2Navigation.data.state==='cancelled' && !go2Navigation.data.active");assert.equal(await ev('go2Navigation.data.stopConfirmed'),true);checks.push('Visible stop cancels active navigation');
 await api('goal',{mapId,name:'过期数据测试',x:1.7,y:1.3});await wait('go2Navigation.data.active');await click('camera-stop');await wait("go2Navigation.data.state==='blocked'");assert.equal(await ev('go2Navigation.data.reason'),'CAMERA_STALE');await shot('navigation-stopped-stale');checks.push('Stopping camera halts navigation');
 await click('language');assert.equal(await ev("document.getElementById('tab-navigation').textContent"),'Navigate');await click('language');
 for(const width of [320,768,1024]){await send('Emulation.setDeviceMetricsOverride',{width,height:900,deviceScaleFactor:1,mobile:true});assert.ok(await ev('document.documentElement.scrollWidth<=innerWidth+1'));}
 await send('Emulation.clearDeviceMetricsOverride');checks.push('Chinese/English and responsive navigation page');
 // Schema and cross-origin protections are part of the externally callable tool contract.
 assert.equal((await api('goal',{mapId,name:'x',x:0,y:0,speed:5})).status,400);
 const foreign=await fetch(base+'/api/navigation/prepare',{method:'POST',headers:{'Content-Type':'application/json',Origin:'https://untrusted.example'},body:'{}'});assert.equal(foreign.status,403);checks.push('Unknown control fields and foreign origins rejected');
 assert.deepEqual(errors,[]);fs.writeFileSync(out+'/phone-results.json',JSON.stringify({checks,errors,source:'simulation',realRobotVerified:false},null,2));console.log(JSON.stringify({passed:checks.length,checks}));
}finally{await api('stop');await send('Emulation.clearDeviceMetricsOverride').catch(()=>{});ws.close();}
