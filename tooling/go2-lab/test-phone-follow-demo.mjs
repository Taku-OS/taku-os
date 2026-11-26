// WebView-only rehearsal verification. Synthetic images are visibly marked test replay.
// Only GET camera frames / POST camera start are permitted; no robot control APIs.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
const out='out/go2-lab/follow-demo';fs.mkdirSync(out,{recursive:true});
const pages=await(await fetch('http://127.0.0.1:19245/json')).json();
const ws=new WebSocket(pages.find(p=>p.url.startsWith('http://127.0.0.1:18845/')).webSocketDebuggerUrl);
await new Promise(r=>ws.addEventListener('open',r,{once:true}));
let id=0;const pending=new Map(),errors=[],checks=[];
ws.addEventListener('message',e=>{const m=JSON.parse(e.data);if(m.method==='Runtime.exceptionThrown')errors.push(m.params);if(pending.has(m.id)){pending.get(m.id)(m);pending.delete(m.id);}});
function send(method,params={}){return new Promise((resolve,reject)=>{const key=++id,timer=setTimeout(()=>{pending.delete(key);reject(Error('CDP timeout '+method));},10000);pending.set(key,m=>{clearTimeout(timer);m.error?reject(Error(JSON.stringify(m.error))):resolve(m.result);});ws.send(JSON.stringify({id:key,method,params}));});}
async function ev(expression){const r=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});assert.ok(!r.exceptionDetails,JSON.stringify(r));return r.result?.value;}
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const screenshot=name=>fs.writeFileSync(out+'/'+name,execFileSync(process.env.ADB||path.join(os.homedir(),'Library/Android/sdk/platform-tools/adb'),['-s',process.env.TAKU_SERIAL||'emulator-5554','exec-out','screencap','-p']));
await send('Runtime.enable');await send('Page.enable');
const mock=`(() => {
 const original=window.fetch.bind(window);let seq=0;window.demoTest={x:.42,y:.24,w:.16,h:.58,empty:false,stale:false,calls:[]};
 window.fetch=async (url,options={})=>{
  const u=String(url),s=window.demoTest;s.calls.push([options.method||'GET',u]);
  if(u==='/api/camera/start')return new Response(JSON.stringify({enabled:true,connected:true}),{status:200});
  if(!u.startsWith('/api/camera/frame'))throw Error('Unexpected request '+u);
  const c=document.createElement('canvas');c.width=960;c.height=540;const x=c.getContext('2d');x.fillStyle='#c8c6bc';x.fillRect(0,0,960,540);
  if(!s.empty){x.fillStyle='#20232a';x.fillRect(s.x*960,s.y*540,s.w*960,s.h*540);x.fillStyle='#c8c6bc';x.fillRect((s.x+s.w*.43)*960,(s.y+s.h*.5)*540,s.w*.14*960,s.h*.5*540);}
  const b64=c.toDataURL('image/jpeg').split(',')[1],jpg=Uint8Array.from(atob(b64),c=>c.charCodeAt(0));
  const head=new TextEncoder().encode(JSON.stringify({enabled:true,connected:true,source:'test-replay',fresh:!s.stale,ageMs:0,frameKey:'test:1:'+seq++,width:960,height:540,frames:seq,fps:8}));
  const bytes=new Uint8Array(4+head.length+jpg.length);new DataView(bytes.buffer).setUint32(0,head.length,true);bytes.set(head,4);bytes.set(jpg,4+head.length);return new Response(bytes);
 };
})();`;
let injected;
try{
 injected=(await send('Page.addScriptToEvaluateOnNewDocument',{source:mock})).identifier;
 await send('Page.navigate',{url:'http://127.0.0.1:18845/follow-demo'});await delay(1300);
 await ev(`document.getElementById('command').value='跟随我';document.getElementById('command-form').requestSubmit()`);await delay(1100);
 assert.equal(await ev('window.go2FollowDemo.state'),'tracking');checks.push('command response and target box');
 assert.match(await ev("document.querySelector('.stage-label').textContent"),/测试回放/);
 await ev('window.demoTest.h=.48;window.demoTest.y=.30');await delay(900);
 assert.equal(await ev("document.getElementById('distance-state').textContent"),'正在变远');checks.push('apparent distance changes without invented meters');
 screenshot('test-target-tracking.png');
 await ev('window.demoTest.empty=true');await delay(700);
 assert.equal(await ev('window.go2FollowDemo.state'),'lost');assert.equal(await ev('window.go2FollowDemo.box'),null);
 await ev('window.demoTest.empty=false');await delay(400);
 assert.equal(await ev('window.go2FollowDemo.state'),'lost');checks.push('loss latches until explicit selection');
 await ev("document.getElementById('reselect').click()");await delay(300);
 assert.equal(await ev('window.go2FollowDemo.state'),'tracking');
 await ev('window.demoTest.stale=true');await delay(400);
 assert.equal(await ev('window.go2FollowDemo.live'),false);assert.equal(await ev('window.go2FollowDemo.box'),null);checks.push('stale video hides target and image');
 await ev('window.demoTest.stale=false');await delay(400);await ev("document.getElementById('reselect').click();document.getElementById('language').click()");
 assert.equal(await ev("document.querySelector('.demo-boundary')"),null);assert.equal(await ev("document.documentElement.lang"),'en');checks.push('English and Chinese labels');
 for(const width of [320,393,768,1024,1440]){
  await send('Emulation.setDeviceMetricsOverride',{width,height:900,deviceScaleFactor:1,mobile:true});await delay(100);
  assert.ok(await ev('document.documentElement.scrollWidth<=innerWidth'),`overflow ${width}`);
 }
 checks.push('responsive widths 320–1440');await send('Emulation.clearDeviceMetricsOverride');
 await ev("document.getElementById('command').value='停止跟随';document.getElementById('command-form').requestSubmit()");assert.equal(await ev('window.go2FollowDemo.active'),false);
 assert.equal(await ev("document.getElementById('pause')===null&&document.getElementById('reset')===null"),true);checks.push('text stop works; removed filming buttons absent');
 const calls=await ev('window.demoTest.calls');assert.ok(calls.every(([method,url])=>(method==='GET'&&url.startsWith('/api/camera/frame'))||(method==='POST'&&url==='/api/camera/start')));checks.push('no movement, stop, mode or navigation requests');
 assert.deepEqual(errors,[]);fs.writeFileSync(out+'/webview-verification.json',JSON.stringify({checks,errors,requests:calls.length,source:'synthetic-test-replay',motionRequests:0},null,2));
 console.log(JSON.stringify({checks,errors,motionRequests:0},null,2));
}finally{
 if(injected)await send('Page.removeScriptToEvaluateOnNewDocument',{identifier:injected});
 await send('Emulation.clearDeviceMetricsOverride');await send('Page.navigate',{url:'http://127.0.0.1:18845/follow-demo'});await delay(1200);
 screenshot('live-ready.png');ws.close();
}
