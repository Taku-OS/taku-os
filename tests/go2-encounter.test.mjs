import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
const importSource=async name=>import('data:text/javascript;base64,'+Buffer.from(await readFile(new URL('../tooling/go2-lab/web/'+name,import.meta.url),'utf8')).toString('base64'));
const {VisualGuidance}=await importSource('follow-guidance.js');
const {SceneVoice}=await importSource('follow-scene-voice.js');
const target=(x=.5,h=.4,extra={})=>({state:'tracking',box:{x:x-.1,y:.1,w:.2,h,anchor:[x,.5]},ratio:1,change:0,meters:null,...extra});
const sample=(view,extra={})=>({fresh:true,model:'ready',view,people:1,phase:'running',approach:true,...extra});
function stabilize(g,view,extra={}){let result;for(let n=1;n<=3;n++)result=g.update(sample(view,{...extra,frameKey:String(n)}));return result;}
test('left/right/approach/near-range advice follows actual target geometry',()=>{
 for(const [view,action] of [[target(.25),'left'],[target(.75),'right'],[target(.5,.35),'forward'],[target(.5,.72),'hold'],[target(.5,.9),'back']])assert.equal(stabilize(new VisualGuidance(),view).action,action);
});
test('stale frames or lost target immediately clear a previously stable movement suggestion',()=>{
 const g=new VisualGuidance();assert.equal(stabilize(g,target(.2)).action,'left');
 assert.equal(g.update(sample(target(.2),{fresh:false})).action,'waiting');
 assert.equal(g.update(sample({state:'reacquiring',box:null})).action,'lost');
 assert.equal(g.update(sample({state:'waiting',box:null},{people:2})).action,'select');
 assert.equal(g.update(sample({state:'waiting',box:null},{people:0})).action,'search');
});
test('jitter and repeated renders of one frame cannot switch a direction',()=>{
 const g=new VisualGuidance();stabilize(g,target(.2));
 for(let n=0;n<10;n++)assert.equal(g.update(sample(target(.8),{frameKey:'4'})).action,'left');
 assert.equal(g.update(sample(target(.2),{frameKey:'5'})).action,'left');
 assert.equal(g.update(sample(target(.8),{frameKey:'6'})).action,'left');
 assert.equal(g.update(sample(target(.8),{frameKey:'7'})).action,'left');
 assert.equal(g.update(sample(target(.8),{frameKey:'8'})).action,'right');
});
test('calibrated distance, relative follow range, manual and return intent are distinct',()=>{
 assert.equal(stabilize(new VisualGuidance(),target(.5,.4,{meters:1.8})).action,'hold');
 assert.equal(stabilize(new VisualGuidance(),target(.5,.4,{meters:3})).action,'forward');
 assert.equal(stabilize(new VisualGuidance(),target(.5,.4,{ratio:1}),{approach:false}).action,'hold');
 assert.equal(new VisualGuidance().update(sample(target(),{manual:true})).action,'paused');
 const returning=new VisualGuidance().update(sample(target(),{phase:'returning'}));assert.equal(returning.action,'return');assert.equal(returning.basis,'intent');assert.equal(returning.live,false);
 assert.equal(new VisualGuidance().update(sample(target(),{phase:'returning',fresh:false})).action,'waiting');
});
test('voice preparation never plays and playback reads the device volume first',async()=>{
 const calls=[];const voice=new SceneVoice(async(path,body)=>{calls.push([path,body]);return {connected:true,volume:path.endsWith('refresh')?2:null};},()=>{});
 await voice.run('prepare');assert.deepEqual(calls.map(c=>c[0]),['/api/audio/select','/api/audio/prepare']);
 calls.length=0;await voice.run('play');assert.deepEqual(calls.map(c=>c[0]),['/api/audio/select','/api/audio/refresh','/api/audio/play']);
});
test('Stay latches through target movement, target loss and camera interruption on both legs',()=>{
 for(const [phase,action] of [['holding','staying'],['return_hold','returnHold']]){
  const g=new VisualGuidance();stabilize(g,target(.2));
  for(const view of [target(.8),target(.5,.2),{state:'lost',box:null}]){
   for(const fresh of [true,false]){
    const result=g.update(sample(view,{phase,fresh}));
    assert.equal(result.action,action);assert.equal(result.pace,'Hold');assert.equal(result.basis,'intent');
   }
  }
 }
});
test('Circle is intent guidance, manual and stale camera states retain priority',()=>{
 const g=new VisualGuidance();const circle=g.update(sample(target(),{phase:'circling'}));
 assert.equal(circle.action,'circle');assert.equal(circle.basis,'intent');assert.equal(circle.live,false);
 assert.equal(g.update(sample(target(),{phase:'circling',fresh:false})).action,'waiting');
 assert.equal(g.update(sample(target(),{phase:'circling',manual:true})).action,'paused');
 assert.equal(g.update(sample(target(),{phase:'holding'})).action,'staying');
 assert.equal(g.update(sample(target(),{phase:'returning'})).action,'return');
});
test('processing replaces directional advice until command acceptance, while manual mode takes priority',()=>{
 const g=new VisualGuidance();stabilize(g,target(.2));
 for(const pending of ['go','confirm','circle','return','say','bark','stay','complete']){
  const result=g.update(sample(target(.8),{pending}));
  assert.equal(result.action,'processing');assert.equal(result.pace,'—');assert.equal(result.basis,'intent');assert.equal(result.live,false);
 }
 assert.equal(g.update(sample(target(),{pending:'circle',manual:true})).action,'paused');
 assert.equal(g.update(sample(target(),{phase:'circling'})).action,'circle');
 assert.equal(g.update(sample(target(),{phase:'holding'})).action,'staying');
});
test('outbound and return location stages are ordered task presentation, then movement advice resumes',()=>{
 for(const pending of ['go','return']){
  const g=new VisualGuidance();
  const locating=g.update(sample(target(),{pending,pendingStage:'locating'}));
  const planning=g.update(sample(target(),{pending,pendingStage:'planning'}));
  assert.equal(locating.action,'locating');assert.equal(locating.step,1);assert.match(locating.title,/Acquiring.*location/);
  assert.equal(planning.action,'planning');assert.equal(planning.step,2);assert.match(planning.title,/Planning.*route/);
  assert.equal(locating.basis,'intent');assert.equal(planning.live,false);
  assert.equal(g.update(sample(target(),{pending,pendingStage:'planning',manual:true})).action,'paused');
  assert.equal(g.update(sample(target(),{phase:'holding'})).action,'staying');
 }
 assert.equal(stabilize(new VisualGuidance(),target(.5,.35)).action,'forward');
 assert.equal(new VisualGuidance().update(sample(target(),{phase:'returning'})).action,'return');
});
test('selecting dog bark is silent; Play sends the bark preset through the real audio endpoint',async()=>{
 const calls=[];const voice=new SceneVoice(async(path,body)=>{calls.push([path,body]);return {connected:true,volume:4};},()=>{});
 assert.equal(voice.select('dog-bark'),true);assert.equal(calls.length,0);
 await voice.run('play');assert.deepEqual(calls,[['/api/audio/select',{id:'dog-bark'}],['/api/audio/play',{}]]);
 calls.length=0;await voice.run('play','en-taunt-enough');assert.equal(calls[0][1].id,'en-taunt-enough');
 assert.equal(voice.select('unknown'),false);
});
test('Stay/return cancellation during preset selection cannot start delayed playback',async()=>{
 const calls=[];let finish;
 const voice=new SceneVoice(async(path)=>{calls.push(path);if(path.endsWith('select'))return new Promise(r=>{finish=r;});return {connected:true,state:'idle',volume:4};},()=>{});
 const playing=voice.run('play','dog-bark');await voice.stop();finish({connected:true,volume:4});await playing;
 assert.deepEqual(calls,['/api/audio/select','/api/audio/stop']);assert.equal(voice.pending,false);
});
test('the task is embedded in the existing follow view and native AI composer',async()=>{
 const root=new URL('../tooling/go2-lab/',import.meta.url);
 const activity=await readFile(new URL('src/org/taku/go2lab/Go2Activity.java',root),'utf8');
 const script=await readFile(new URL('src/org/taku/go2lab/EncounterScript.java',root),'utf8');
 const web=await readFile(new URL('web/follow-demo.js',root),'utf8');
 assert.ok(activity.includes('follow-demo'));assert.ok(activity.includes('Go2Scene'));assert.ok(!activity.includes('EncounterActivity'));
 assert.ok(!script.includes('AppApi'));assert.ok(!script.includes('AiClient'));
 const scene=web.slice(web.indexOf('async function applyScene'),web.indexOf("$('scene-go').onclick"));assert.ok(!scene.includes('drive.start'));assert.ok(!scene.includes('/api/motion'));assert.ok(scene.includes('autoMode=false'));
});
