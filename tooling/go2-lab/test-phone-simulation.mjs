// Run against the real Taku Phone WebView, backed ONLY by simulator.py.
// ADB forward tcp:19245 to the app's webview_devtools_remote_<PID> first.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { execFileSync } from 'node:child_process';

const output=path.resolve('out/go2-lab/phone-simulation');fs.mkdirSync(output,{recursive:true});
const adb=process.env.ADB || path.join(os.homedir(),'Library/Android/sdk/platform-tools/adb');
const backend=async()=>{
  const state=await(await fetch('http://127.0.0.1:18845/api/lab')).json();
  assert.equal(state.simulation?.realTransportLoaded,false,'Refusing non-simulator backend');
  assert.ok(['simulation','replay'].includes(state.source),'Refusing real source');return state;
};
await backend();
const pages=await(await fetch('http://127.0.0.1:19245/json')).json();
const page=pages.find(p=>p.url==='http://127.0.0.1:18845/');assert.ok(page,'Go2 WebView must be open');
const socket=new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
let sequence=0;const pending=new Map(),exceptions=[],results=[];
socket.addEventListener('message',event=>{
  const value=JSON.parse(event.data);
  if(value.method==='Runtime.exceptionThrown')exceptions.push(value.params.exceptionDetails);
  if(pending.has(value.id)){pending.get(value.id)(value);pending.delete(value.id);}
});
const send=(method,params={})=>new Promise((resolve,reject)=>{
  const id=++sequence,timer=setTimeout(()=>{pending.delete(id);reject(Error('CDP timeout '+method));},20000);
  pending.set(id,value=>{clearTimeout(timer);value.error?reject(Error(JSON.stringify(value.error))):resolve(value.result);});
  socket.send(JSON.stringify({id,method,params}));
});
const ev=async expression=>{
  const result=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});
  if(result.exceptionDetails)throw Error(JSON.stringify(result.exceptionDetails));return result.result?.value;
};
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function wait(expression,timeout=12000){
  const end=Date.now()+timeout;
  do{if(await ev(expression))return;await delay(100);}while(Date.now()<end);
  throw Error('UI timeout: '+expression);
}
async function click(id){
  await backend();await wait(`document.getElementById(${JSON.stringify(id)}) && !document.getElementById(${JSON.stringify(id)}).disabled`);
  await ev(`document.getElementById(${JSON.stringify(id)}).click()`);
}
async function settled(){await wait('go2LabState.pending===0 && !go2LabState.data.avoidance.busy');await delay(250);}
async function scene(name){
  await ev(`document.getElementById('sim-scenario').value=${JSON.stringify(name)}`);await click('sim-apply');
  await wait(`go2LabState.data.scenario===${JSON.stringify(name)} && go2LabState.pending===0`);await settled();
}
async function enable(){await click('avoid-enable');await settled();}
async function arm(){
  await wait('go2LabState.data.geometry.valid');
  await ev("if(!document.getElementById('avoid-ready').checked)document.getElementById('avoid-ready').click()");
  await click('avoid-arm');await wait('go2LabState.data.avoidance.armed && go2LabState.pending===0');
}
async function run(name,fn){
  const evidence=await fn();results.push({name,passed:true,evidence});
  console.log('PASS '+name);
}
function screenshot(name){
  const target=path.join(output,name+'.png');
  fs.writeFileSync(target,execFileSync(adb,['-s',process.env.TAKU_SERIAL || 'emulator-5554','exec-out','screencap','-p'],{maxBuffer:10*1024*1024}));
  return target;
}

try{
  await send('Runtime.enable');await send('Page.reload',{ignoreCache:true});
  await wait('window.go2LabState?.data?.source==="simulation"');
  if(!(await backend()).connected){await click('connect');await wait('go2LabState.data.connected');}
  await click('tab-avoidance');
  await run('simulation banner and initial lock',async()=>{
    assert.equal(await ev("document.getElementById('source-banner').dataset.source"),'simulation');
    assert.equal(await ev("document.getElementById('avoid-forward').disabled"),true);
    return {screenshot:screenshot('initial')};
  });
  await run('simulation copy and scenario discovery',async()=>{
    await click('tab-state');await wait('document.getElementById("notice").textContent.includes("模拟状态")');
    assert.match(await ev('document.getElementById("freshness").textContent'),/模拟/);
    assert.match(await ev('document.getElementById("app-footer").textContent'),/无需连接机器狗/);
    await click('tab-avoidance');
    const top=await ev('document.getElementById("sim-scenario").getBoundingClientRect().top');
    assert.ok(top<(await ev('innerHeight'))-100,'Scenario picker must be visible before scrolling');
    return {scenarioTop:top};
  });
  for(const action of ['forward','backward','left','right'])await run('clear '+action,async()=>{
    await scene('clear');await enable();await arm();
    const before=(await backend()).simulation.commands;
    await click('avoid-'+action);await wait(`go2LabState.data.simulation.commands>${before} && !go2LabState.data.avoidance.armed && go2LabState.pending===0`);
    const s=await backend();assert.equal(s.avoidance.stopConfirmed,true);
    const value=action==='forward'||action==='backward'?s.simulation.position[0]:s.simulation.yaw;
    assert.ok(['forward','left'].includes(action)?value>.005:value<-.005);
    await wait('document.getElementById("sim-result").textContent.includes("cm")');
    return {position:s.simulation.position,yaw:s.simulation.yaw,stop:s.avoidance.result};
  });
  for(const [name,direction] of [['front','forward'],['rear','backward'],['left','left'],['right','right']])await run(name+' obstacle gate',async()=>{
    await scene(name);await enable();await arm();
    assert.equal(await ev(`document.getElementById('avoid-${direction}').disabled`),true);
    const s=await backend();assert.equal(s.geometry.directions[direction],'blocked');
    assert.match(await ev('document.getElementById("direction-summary").innerText'),/有障碍/);
    if(name==='front')screenshot('front-obstacle');
    await click('avoid-stop');await settled();return s.geometry.directions;
  });
  await run('new obstacle interrupts movement',async()=>{
    await scene('obstacle-appears');await enable();await arm();await click('avoid-forward');
    await wait('go2LabState.data.avoidance.error==="OBSTACLE_BLOCKED" && go2LabState.pending===0');
    const s=await backend();assert.ok(s.simulation.position[0]<.025);assert.equal(s.avoidance.armed,false);
    return {position:s.simulation.position,error:s.avoidance.error};
  });
  await run('simulated native interception',async()=>{
    await scene('native-block');await enable();await arm();await click('avoid-forward');
    await wait('go2LabState.data.simulation.nativeBlocks>0 && go2LabState.pending===0');
    const s=await backend();assert.equal(s.simulation.position[0],0);return s.simulation;
  });
  for(const name of ['stale-cloud','stale-pose','empty-cloud','pose-mismatch','mode-change'])await run(name+' prevents arm',async()=>{
    await scene(name);await enable();await delay(500);
    await ev("document.getElementById('avoid-ready').checked=true;document.getElementById('avoid-ready').dispatchEvent(new Event('change'))");
    await delay(150);assert.equal(await ev("document.getElementById('avoid-arm').disabled"),true);
    return {reason:(await backend()).readiness};
  });
  for(const name of ['enable-rejected','readback-mismatch','invalid-state','query-timeout'])await run(name+' fails closed',async()=>{
    await scene(name);await enable();const s=await backend();
    assert.equal(s.avoidance.armed,false);assert.notEqual(s.avoidance.error,null);
    return {error:s.avoidance.error,enabled:s.avoidance.enabled};
  });
  await run('failed stop latch and explicit recovery',async()=>{
    await scene('stop-timeout');await click('avoid-stop');await settled();
    assert.equal((await backend()).avoidance.stopConfirmed,false);
    assert.equal(await ev("document.getElementById('avoid-arm').disabled"),true);
    screenshot('stop-unconfirmed');
    await scene('clear');await click('avoid-stop');await settled();
    assert.equal((await backend()).avoidance.stopConfirmed,true);
  });
  await run('Android background revokes control',async()=>{
    await scene('clear');await enable();await arm();
    execFileSync(adb,['-s',process.env.TAKU_SERIAL || 'emulator-5554','shell','input','keyevent','KEYCODE_HOME']);
    await delay(1500);const s=await backend();assert.equal(s.avoidance.armed,false);
    execFileSync(adb,['-s',process.env.TAKU_SERIAL || 'emulator-5554','shell','am','start','-n','org.taku.go2lab/.Go2Activity']);
    await delay(500);return {armed:s.avoidance.armed,result:s.avoidance.result};
  });
  await run('phone transport outage revokes control without auto rearm',async()=>{
    await scene('clear');await enable();await arm();
    await send('Network.enable');
    // Fail existing keep-alive requests too, without navigating away from the UI.
    await send('Network.emulateNetworkConditions',{offline:true,latency:0,downloadThroughput:-1,uploadThroughput:-1});
    execFileSync(adb,['-s',process.env.TAKU_SERIAL || 'emulator-5554','reverse','--remove','tcp:18845']);
    try {
      await wait('!go2LabState.online');await delay(1800);
      assert.equal((await backend()).avoidance.armed,false);
      assert.equal(await ev("document.getElementById('avoid-forward').disabled"),true);
    } finally {
      execFileSync(adb,['-s',process.env.TAKU_SERIAL || 'emulator-5554','reverse','tcp:18845','tcp:18845']);
      await send('Network.emulateNetworkConditions',{offline:false,latency:0,downloadThroughput:-1,uploadThroughput:-1});
    }
    await wait('go2LabState.online');
    assert.equal(await ev("document.getElementById('avoid-forward').disabled"),true);
    return {armed:(await backend()).avoidance.armed};
  });
  await run('full sensor recording and motion-free replay',async()=>{
    await scene('clear');await enable();
    await ev("document.getElementById('record-tools').open=true");await click('record-start');
    await wait('go2LabState.data.recording.active');await arm();await click('avoid-forward');await settled();
    await delay(1500);await click('record-stop');await wait('go2LabState.data.recording.replayAvailable');
    const before=await backend();assert.ok(before.recording.bytes>10000);
    await click('replay-start');await wait('go2LabState.data.source==="replay"');
    await wait('document.getElementById("status").textContent==="历史回放"');
    assert.match(await ev('document.getElementById("freshness").textContent'),/录制/);
    await wait('go2LabState.data.geometry.analyzedPoints>0');
    await click('replay-pause');await wait('go2LabState.data.replay.paused');
    const paused=(await backend()).replay.time;await delay(350);
    assert.equal((await backend()).replay.time,paused);
    await click('replay-pause');await wait('!go2LabState.data.replay.paused');
    assert.equal(await ev("document.getElementById('avoid-forward').disabled"),true);
    const after=await backend();assert.equal(after.simulation.commands,before.simulation.commands);
    screenshot('replay');await click('replay-stop');await settled();
    await click('tab-state');await click('connect');await wait('go2LabState.data.connected');await click('tab-avoidance');
    return {events:before.recording.events,bytes:before.recording.bytes,replayedPoints:after.geometry.analyzedPoints};
  });
  await run('disconnect and reconnect do not rearm',async()=>{
    await scene('disconnect');assert.equal((await backend()).avoidance.armed,false);
    await click('tab-state');await click('connect');await wait('go2LabState.data.connected');await click('tab-avoidance');
    await scene('clear');assert.equal((await backend()).avoidance.armed,false);
  });
  await run('responsive layout and English',async()=>{
    const widths=[];
    for(const width of [320,768,1024,1440]){
      await send('Emulation.setDeviceMetricsOverride',{width,height:900,deviceScaleFactor:1,mobile:true});await delay(100);
      const actual=await ev('({width:innerWidth,scroll:document.documentElement.scrollWidth})');
      assert.ok(actual.scroll<=actual.width);widths.push(actual);
    }
    await send('Emulation.clearDeviceMetricsOverride');await click('language');
    assert.match(await ev("document.getElementById('source-banner').textContent"),/SIMULATION/);
    await click('language');return widths;
  });
  await run('WebGL draws every decoded simulation point',async()=>{
    await click('tab-environment');
    await wait('window.go2PointStats?.sourcePoints>0 && go2PointStats.drawnPoints===go2PointStats.sourcePoints');
    const stats=await ev('go2PointStats');
    assert.equal(stats.uploadedPoints,stats.sourcePoints);assert.equal(stats.rendererError,null);
    screenshot('full-point-cloud');await click('tab-avoidance');return stats;
  });
  await click('avoid-stop');await settled();await ev('window.scrollTo(0,0)');
  screenshot('final');await ev("document.getElementById('avoid-arm').scrollIntoView({block:'center'})");
  await delay(350);screenshot('controls');await ev('window.scrollTo(0,0)');assert.deepEqual(exceptions,[]);
  fs.writeFileSync(path.join(output,'results.json'),JSON.stringify({source:'simulation',results,exceptions},null,2));
  console.log(JSON.stringify({passed:results.length,output}));
}catch(error){
  fs.writeFileSync(path.join(output,'failure.json'),JSON.stringify({error:String(error),results,exceptions,state:await backend()},null,2));
  screenshot('failure');throw error;
}finally{
  try{await backend();await fetch('http://127.0.0.1:18845/api/avoidance/stop',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});}catch{}
  try{await send('Emulation.clearDeviceMetricsOverride');}catch{}
  socket.close();
}
