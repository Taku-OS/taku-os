// Real AMap queries from the Android WebView. Requires a personal key configured on Mac.
// No robot control endpoints are called. Public sample locations only.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {execFileSync} from 'node:child_process';
const base='http://127.0.0.1:18845',out='out/go2-lab/amap-verification';
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
 await send('Runtime.enable');await send('Page.reload',{ignoreCache:true});await wait("!!document.getElementById('amap-search-form')?.onsubmit");
 await click('tab-amap');await wait("document.getElementById('amap-status').textContent==='高德服务已配置'");checks.push('Dedicated map service reachable from Android WebView');
 await ev("document.getElementById('amap-city').value='北京';document.getElementById('amap-keyword').value='北京大学';document.getElementById('amap-keyword').dispatchEvent(new Event('input'))");
 await click('amap-search');await wait("document.querySelectorAll('#amap-results li').length>=2 && !document.getElementById('amap-search').disabled");
 const names=await ev("Array.from(document.querySelectorAll('#amap-results li strong')).map(x=>x.textContent)");assert.ok(names.some(n=>n.includes('北京大学')));checks.push({name:'Live AMap POI search',count:names.length});shot('search-results');
 await ev("document.querySelectorAll('#amap-results li')[0].querySelectorAll('button')[0].click()");
 // Select a distinct nearby POI; most results for this keyword are campus gates and buildings.
 await ev("document.querySelectorAll('#amap-results li')[1].querySelectorAll('button')[1].click()");
 await click('amap-plan');await wait("!document.getElementById('amap-route').hidden && document.getElementById('amap-map').complete && document.getElementById('amap-map').naturalWidth>0");
 assert.ok(await ev("document.querySelectorAll('#amap-steps li').length>0"));
 await ev("document.getElementById('amap-route').scrollIntoView()");await delay(300);shot('walking-route');checks.push({name:'Live walking route and static map rendered',summary:await ev("document.getElementById('amap-summary').textContent")});
 await click('language');assert.equal(await ev("document.getElementById('tab-amap').textContent"),'AMap routes');await click('language');
 for(const width of [320,768,1024,1440]){await send('Emulation.setDeviceMetricsOverride',{width,height:900,deviceScaleFactor:1,mobile:true});assert.ok(await ev('document.documentElement.scrollWidth<=innerWidth+1'));}
 await send('Emulation.clearDeviceMetricsOverride');checks.push('Chinese/English and four viewport widths');
 await click('tab-navigation');assert.equal(await ev("document.getElementById('amap-page').hidden"),true);await click('tab-amap');await wait("!document.getElementById('amap-refresh').disabled");
 // Offline errors must not erase a successful route or appear as robot readiness.
 await ev("window.amapOriginalFetch=window.fetch;window.fetch=(url,...args)=>String(url).startsWith('http://127.0.0.1:18846')?Promise.reject(new TypeError('offline')):window.amapOriginalFetch(url,...args)");
 await click('amap-refresh');await wait("document.getElementById('amap-search').disabled && document.getElementById('amap-status').textContent.includes('不可用')");
 assert.equal(await ev("document.getElementById('amap-route').hidden"),false);
 await ev('window.fetch=window.amapOriginalFetch');await click('amap-refresh');await wait("!document.getElementById('amap-search').disabled");checks.push('Map outage clearly reported and recovers; previous preview preserved');
 const after=await(await fetch(base+'/api/state')).json(),nav=await(await fetch(base+'/api/navigation')).json();
 assert.equal(after.source,'simulation');assert.equal(after.connected,before.connected);assert.deepEqual(after.outgoing,before.outgoing);assert.equal(nav.active,false);assert.equal(nav.realActuationAllowed,false);checks.push('No robot connection or motion triggered by map operations');
 assert.deepEqual(errors,[]);fs.writeFileSync(out+'/results.json',JSON.stringify({checks,errors,realMapApiVerified:true,realRobotVerified:false},null,2));console.log(JSON.stringify({passed:checks.length,checks}));
 await ev("document.getElementById('amap-route').scrollIntoView()");shot('ready');
}finally{await ev('if(window.amapOriginalFetch)window.fetch=window.amapOriginalFetch').catch(()=>{});await send('Emulation.clearDeviceMetricsOverride').catch(()=>{});ws.close();}
