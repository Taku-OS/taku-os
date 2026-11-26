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
 await send('Runtime.enable');await click('tab-amap');if(!(await ev('go2Amap.route'))){await click('amap-plan');await wait('Boolean(go2Amap.route?.mapViewport)');}await click('geo-prepare');await wait('!go2Geo.pending && Boolean(go2Geo.data?.geo?.routeId)');
 await click('geo-start');await wait('go2Geo.data.geo.active');await delay(1500);
 assert.ok(await ev("document.getElementById('amap-page').classList.contains('geo-active')"));
 await ev('window.scrollTo(0,0)');await delay(300);shot('integrated-running');
 assert.equal(await ev("document.getElementById('geo-robot-marker').hidden"),false);
 await click('geo-stop');await wait('!go2Geo.data.active && go2Geo.data.stopConfirmed');checks.push('Compact live route, robot marker, camera and local plan visible during execution');
 await click('geo-use-origin');await ev("document.getElementById('geo-heading').value='90'");await click('geo-calibrate');await wait('!go2Geo.pending && !go2Geo.data.geo.routeId');
 assert.equal(await ev('go2Geo.data.geo.localization.absoluteFix'),false);assert.equal(await ev("document.getElementById('geo-start').disabled"),true);checks.push('Manual calibration reports estimate and invalidates old route');
 await click('amap-pick');await ev("document.getElementById('amap-map').scrollIntoView({block:'center'})");await delay(300);
 const viewport=await ev('go2Amap.route.mapViewport');const rect=await ev("(()=>{const r=document.getElementById('amap-map').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2};})()");
 await send('Input.dispatchMouseEvent',{type:'mousePressed',x:rect.x,y:rect.y,button:'left',clickCount:1});await send('Input.dispatchMouseEvent',{type:'mouseReleased',x:rect.x,y:rect.y,button:'left',clickCount:1});
 await wait('go2Amap.route===null');const selected=await ev('go2Amap.destination.location');const pixel=await ev('go2GeoProjection.pixel('+JSON.stringify(selected.split(',').map(Number))+','+JSON.stringify(viewport)+')');assert.ok(Math.hypot(pixel[0]-viewport.width/2,pixel[1]-viewport.height/2)<2,'Pointer quantization must stay within two source-map pixels');checks.push('Actual map pointer selects GCJ-02 destination and invalidates previous preview');
 await click('amap-plan');await wait("Boolean(go2Amap.route?.mapImage) && !document.getElementById('amap-plan').disabled");checks.push('Selected map destination produces a new real AMap route');
 for(const width of [320,768,1024,1440]){await send('Emulation.setDeviceMetricsOverride',{width,height:900,deviceScaleFactor:1,mobile:true});assert.ok(await ev('document.documentElement.scrollWidth<=innerWidth+1'));}
 await send('Emulation.clearDeviceMetricsOverride');await click('language');assert.equal(await ev("document.getElementById('geo-start').textContent"),'Start simulated following');await click('language');
 assert.deepEqual(errors,[]);fs.writeFileSync(out+'/ui-results.json',JSON.stringify({checks,errors},null,2));console.log(JSON.stringify({passed:checks.length,checks}));
}finally{await fetch(base+'/api/navigation/stop',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});await send('Emulation.clearDeviceMetricsOverride').catch(()=>{});ws.close();}
