import {peopleFromLandmarks,PersonFollower} from './follow-person.js';
import {FollowDrive} from './follow-control.js';
import {VisualGuidance,scenePreparation} from './follow-guidance.js';
import {SceneVoice,MAIN_LINE,BARK} from './follow-scene-voice.js';
const copy={
 zh:{autoNotReady:'等待机器狗站稳与姿态更新',autoPreparing:'正在准备运动模式',auto:'自动跟随 · 保持初始距离',autoWaiting:'等待锁定人物',autoForward:'自动前进',autoBackward:'自动后退',autoLeft:'向左转向',autoRight:'向右转向',autoHold:'保持距离',autoStopped:'自动跟随已停止',autoOffline:'连接机器狗后，重新输入跟随指令。',autoFailed:'自动跟随未启动或已中断，请重新输入指令。',autoStop:'停止跟随',autoFresh:'正在等待实时画面',title:'视觉跟踪',subtitle:'从你的视角，到它的视线。',view:'实时视角',target:'关注目标',distance:'距离变化',
 cameraStandby:'等待画面',targetStandby:'等待指令',waiting:'寻找目标',tracking:'已锁定人物',lost:'请重新选择目标',reacquiring:'正在找回目标',recovering:'正在恢复画面',paused:'已停止',loading:'正在加载视觉识别…',modelError:'视觉识别暂不可用，请重新打开页面。',
 online:'LIVE',offline:'等待连接',
 empty:'等待机器狗的实时画面',connectError:'暂未收到画面，请检查机器狗连接。',
 reselect:'重新选择',calibrate:'距离标定',calibrationTitle:'设置当前距离',calibrationHint:'输入镜头与目标之间已知的距离，保持人物站立。',meters:'米',save:'确认标定',cancel:'取消',
 invalidDistance:'请输入 0.5–10 米，且目标需保持清晰可见。',relative:'相对初始距离',metric:'视觉估算',choose:'多人入镜时，点击要关注的人。',
 lostHint:'目标离开较久或无法确定时，点击人物继续。',reacquireHint:'正在根据位置与衣着外观找回目标；也可以直接点选。',recoverHint:'画面中断后会自动恢复。',trackHint:'根据人物画面尺度估计远近。',pausedHint:'输入指令可重新开始。',
 ready:'告诉 Taku 你想关注谁。',received:'正在寻找画面中的人物。',trackingReply:'已锁定人物，持续估计距离。',lostReply:'暂时无法确定目标，请点击人物重新选择。',reacquireReply:'目标暂时被遮挡，正在找回。',recoverReply:'正在恢复实时画面。',
 pausedReply:'已停止视觉跟踪。',unsupported:'可以输入“跟踪前面的人”或“停止跟踪”。',
 inputLabel:'输入跟踪指令',placeholder:'跟踪前面黑裤子黑鞋子的人',send:'发送',cameraLabel:'实时画面，点击人物选择目标',
 frameHint:'让目标身体尽量入镜；多人时点击要关注的人。'},
 en:{autoNotReady:'Waiting for a stable standing pose',autoPreparing:'Preparing motion mode',auto:'Auto follow · Keep initial distance',autoWaiting:'Waiting for target lock',autoForward:'Moving forward',autoBackward:'Moving backward',autoLeft:'Turning left',autoRight:'Turning right',autoHold:'Holding distance',autoStopped:'Auto follow stopped',autoOffline:'Connect the robot, then enter a new follow instruction.',autoFailed:'Auto follow unavailable or interrupted. Enter a new instruction.',autoStop:'Stop following',autoFresh:'Waiting for live camera',title:'Visual tracking',subtitle:'Your intent. Its point of view.',view:'Live view',target:'Target',distance:'Distance change',
 cameraStandby:'Waiting for camera',targetStandby:'Ready to track',waiting:'Finding target',tracking:'Person tracked',lost:'Select target again',reacquiring:'Finding target again',recovering:'Restoring live view',paused:'Stopped',loading:'Loading vision…',modelError:'Vision unavailable. Please reopen this page.',
 online:'LIVE',offline:'Offline',
 empty:'Waiting for the robot camera',connectError:'No camera feed. Check the robot connection.',
 reselect:'Select again',calibrate:'Calibrate',calibrationTitle:'Set current distance',calibrationHint:'Enter the known camera-to-person distance while the person stands upright.',meters:'m',save:'Calibrate',cancel:'Cancel',
 invalidDistance:'Enter 0.5–10 meters with a clear target in view.',relative:'Relative to initial distance',metric:'Visual estimate',choose:'Multiple people? Tap the person to follow.',
 lostHint:'Tap the person after a long absence or an uncertain match.',reacquireHint:'Matching position and clothing appearance. You can also tap the person.',recoverHint:'The live view will recover automatically.',trackHint:'Distance is estimated from apparent body scale.',pausedHint:'Enter an instruction to start again.',
 ready:'Tell Taku who to follow.',received:'Looking for a person in the camera.',trackingReply:'Person selected. Estimating distance.',lostReply:'Target is uncertain. Tap the person to continue.',reacquireReply:'Target is briefly out of view. Finding it again.',recoverReply:'Restoring the live camera.',
 pausedReply:'Visual tracking stopped.',unsupported:'Enter “follow the person” or “stop tracking”.',
 inputLabel:'Enter a tracking instruction',placeholder:'Follow the person in black trousers and shoes',send:'Send',cameraLabel:'Live camera. Tap a person to select.',
 frameHint:'Keep the person in view. Tap your target when several people are present.'}
};
const $=id=>document.getElementById(id),video=$('video'),ctx=video.getContext('2d'),overlay=$('tracking'),draw=overlay.getContext('2d');
const tracker=new PersonFollower(),embedded=location.hash.startsWith('#native');
let lang=location.hash.includes('scene')?'en':(window.Go2Locale?.getLanguage?.()||localStorage.getItem('go2-language')||(navigator.language.startsWith('zh')?'zh':'en')),active=false,started=false,live=false,meta=null,lastAt=0,frameKey='',people=[],reply='ready',busy=false;
let worker,modelState='loading',workerBusy=false,workId=0,pending=null,lastInference=0,modelMs=0,generation=0,frameCount=0;
let latestDetectionAt=0,detectionFrameKey='',connectionError=false;
let recoveryBusy=false,recoveryAt=0,recoveryDelay=1500,recoveryAttempts=0;
let autoMode=false,targetIdentity=0,scene=null,previousLanguage='zh';
const guidance=new VisualGuidance();
const voice=new SceneVoice(json,renderSceneVoice);
const drive=new FollowDrive(json,()=>{if(started)render();});
const t=k=>copy[lang][k]||k;
document.body.dataset.embedded=String(embedded);
function fresh(){return live&&!document.hidden&&performance.now()-lastAt+(meta?.ageMs??10000)<1000;}
function status(){
 const s=tracker.view();
 if(!fresh())return {state:started?(active?'recovering':'paused'):'waiting',box:null};
 return active?s:{state:started?'paused':'waiting',box:null};
}
function translate(){
 document.documentElement.lang=lang==='zh'?'zh-CN':'en';document.title='Taku · '+t('title');
 document.querySelectorAll('[data-copy]').forEach(el=>el.textContent=t(el.dataset.copy));
 $('command').placeholder=t('placeholder');$('language').textContent=lang==='zh'?'EN':'中文';
 overlay.setAttribute('aria-label',t('cameraLabel'));render();
}
function render(){
 const s=status(),ok=fresh();draw.clearRect(0,0,overlay.width,overlay.height);
 $('live-state').textContent=ok?t('online'):t(active?'recovering':'offline');$('live-state').classList.toggle('live',ok);
 $('empty-view').hidden=ok;video.hidden=!ok;$('empty-view').textContent=t(active?'recoverReply':connectionError?'connectError':'empty');
 $('target-state').textContent=scene?.phase==='confirm'?'Ready':scene?.phase==='complete'?'Complete':scene&&!frameCount?'Waiting for camera':!scene&&!active&&!started?t(ok?'targetStandby':'cameraStandby'):t(s.state);
 if(scene&&!frameCount){$('live-state').textContent='Camera';$('empty-view').textContent='Waiting for the robot camera';}
 const change=s.box?s.change:0;
 $('distance-state').textContent=s.box&&s.ratio!==null?(s.meters!==null?'≈ '+s.meters.toFixed(1)+' '+t('meters'):(change>=0?'+':'')+change.toFixed(1)+'%'):'—';
 $('distance-detail').textContent=t(s.meters!=null?'metric':'relative');
 const direction=s.box?s.direction:(s.state==='lost'||s.state==='paused'?'hold':'await');
 $('reselect').disabled=!active||!ok||modelState!=='ready';$('calibrate').disabled=!s.box||s.ratio===null;
 $('tracking-hint').textContent=t(modelState==='error'?'modelError':modelState!=='ready'?'loading':!active&&started?'pausedHint':s.state==='tracking'?'trackHint':s.state==='recovering'?'recoverHint':s.state==='reacquiring'?'reacquireHint':s.state==='lost'?'lostHint':people.length>1?'choose':'frameHint');
 $('reply').textContent=t(active?(s.state==='tracking'?'trackingReply':s.state==='recovering'?'recoverReply':s.state==='reacquiring'?'reacquireReply':s.state==='lost'&&tracker.box?'lostReply':'received'):reply);
 if(scene)$('reply').textContent=scene.pending?(scenePreparation(scene.pending,scene.pendingStage)?.title||'Processing your command…'):scene.manual?'You’re in control.':({confirm:'A little face-to-face. I’ve got this.',running:'Heading to the target.',returning:'On my way back to you.',complete:'All good. Ready when you are.',holding:'Staying right here.',return_hold:'Holding here. Ready when you are.',circling:'Going around. Keeping it smooth.'}[scene.phase]||'Watching the scene. Keeping you updated.');
 const advice=guidance.update({fresh:ok,model:modelState,view:s,people:people.length,phase:scene?.phase||'running',manual:scene?.manual||(!scene&&started&&!active),pending:scene?.pending||'',pendingStage:scene?.pendingStage||'',frameKey:detectionFrameKey,approach:!!scene},lang);
 $('guidance-label').textContent=lang==='en'?'AI guidance':'AI 操作建议';
 $('guidance-source').textContent=advice.step?advice.step+' / 2':advice.basis==='intent'?(lang==='en'?'Task plan':'任务计划'):advice.live?(lang==='en'?'Live vision':'实时视觉'):(lang==='en'?'Standby':'等待画面');
 $('guidance-title').textContent=advice.title;$('guidance-reason').textContent=advice.reason;
 $('guidance-pace').textContent=(lang==='en'?'Suggested pace: ':'建议节奏：')+advice.pace;
 const range=s.box&&ok?(s.meters!=null?'≈ '+s.meters.toFixed(1)+' '+t('meters'):s.change>8?(lang==='en'?'Farther away':'目标变远'):s.change<-8?(lang==='en'?'Getting closer':'目标变近'):(lang==='en'?'Range steady':'距离平稳')):'—';
 $('guidance-range').textContent=range;
 $('scene-calibrate').hidden=!scene||!s.box||s.ratio===null;
 $('send').disabled=busy;
 $('auto-control').hidden=!autoMode;
 const control=drive.value;
 $('auto-state').textContent=t(control.error==='NOT_CONNECTED'?'autoOffline':control.error?'autoFailed':!control.active?'autoStopped':control.state==='robot_not_ready'?'autoNotReady':control.state==='preparing'?'autoPreparing':control.state==='waiting'?'autoWaiting':({forward:'autoForward',backward:'autoBackward',left:'autoLeft',right:'autoRight'}[control.action]||'autoHold'));
 $('auto-stop').disabled=!control.active&&!drive.starting;
 $('auto-speed').textContent=control.active&&control.action!=='hold'?((control.action==='forward'||control.action==='backward')?(control.linearSpeed||0).toFixed(2)+' m/s':''):'';
 if(s.box&&ok){
  const b=s.box,x=b.x*overlay.width,y=b.y*overlay.height,w=b.w*overlay.width,h=b.h*overlay.height,k=Math.min(30,w/4,h/4);
  draw.fillStyle='#c9ffa510';draw.fillRect(x,y,w,h);draw.strokeStyle='#c9ffa5';draw.lineWidth=3;
  for(const [a,c,dx,dy] of [[x,y,1,1],[x+w,y,-1,1],[x,y+h,1,-1],[x+w,y+h,-1,-1]]){draw.beginPath();draw.moveTo(a+dx*k,c);draw.lineTo(a,c);draw.lineTo(a,c+dy*k);draw.stroke();}
  const label=t('tracking');draw.font='18px sans-serif';const labelWidth=draw.measureText(label).width+18;
  const labelY=y>32?y-30:y+5;draw.fillStyle='#163a29e8';draw.fillRect(x,labelY,labelWidth,27);draw.fillStyle='#d3ffab';draw.fillText(label,x+9,labelY+20);
 }else if(active&&ok){
  draw.strokeStyle='#ffffff80';draw.lineWidth=2;
  for(const b of people)draw.strokeRect(b.x*overlay.width,b.y*overlay.height,b.w*overlay.width,b.h*overlay.height);
 }
 window.go2FollowDemo={active,started,live:ok,state:s.state,box:s.box,ratio:s.ratio,change:s.change,meters:s.meters,
  guidance:advice,scene:scene?{phase:scene.phase,manual:scene.manual,pending:scene.pending,pendingStage:scene.pendingStage}:null,
  direction,frames:tracker.frames,candidates:people,source:meta?.source,modelState,modelMs,frameCount,recoveryBusy,recoveryAttempts,trackingDiagnostics:tracker.diagnostics,control:{...drive.value,token:undefined},autoMode};
}
function startWorker(){
 worker=new Worker('/follow-vision-worker.js');
 worker.onmessage=({data})=>{
  if(data.type==='ready'){modelState='ready';render();return;}
  if(data.type==='error'){modelState='error';workerBusy=false;pending=null;render();return;}
  if(data.type!=='result')return;
  workerBusy=false;modelMs=data.elapsedMs;
  const work=pending;pending=null;
  if(!work||work.id!==data.id||work.generation!==generation||!active||!fresh()||performance.now()-work.at>1000)return;
  people=data.people||peopleFromLandmarks(data.landmarks);latestDetectionAt=performance.now();detectionFrameKey=work.frameKey;
  tracker.update(people);
  if(autoMode)drive.sample(work.frameKey,tracker.view(),targetIdentity);
  render();
 };
 worker.onerror=()=>{modelState='error';workerBusy=false;pending=null;render();};
 worker.postMessage({type:'init'});
}
async function infer(){
 if(!active||!fresh()||modelState!=='ready'||workerBusy||performance.now()-lastInference<230)return;
 workerBusy=true;lastInference=performance.now();
 const id=++workId,turn=generation,key=frameKey;
 try{
  const image=await createImageBitmap(video,{resizeWidth:640,resizeHeight:Math.round(640*video.height/video.width)});
  if(turn!==generation||!fresh()){image.close();workerBusy=false;return;}
  pending={id,generation:turn,at:performance.now(),frameKey:key};
  worker.postMessage({type:'frame',id,timestamp:performance.now(),image},[image]);
 }catch{workerBusy=false;modelState='error';}
}
async function poll(){
 const start=performance.now();
 try{
  const response=await fetch('/api/camera/frame?after='+encodeURIComponent(frameKey),{cache:'no-store',signal:AbortSignal.timeout(1800)});
  if(!response.ok)throw Error('camera');
  const bytes=await response.arrayBuffer();if(bytes.byteLength<4)throw Error('header');
  const n=new DataView(bytes).getUint32(0,true);if(n>16384||bytes.byteLength<4+n)throw Error('header');
  const next=JSON.parse(new TextDecoder().decode(new Uint8Array(bytes,4,n)));
  if(next.source!=='real'||!next.fresh||!next.enabled||!next.connected||next.ageMs+performance.now()-start>=1000)throw Error('stale');
  if(bytes.byteLength>4+n&&!document.hidden){
   const bitmap=await createImageBitmap(new Blob([bytes.slice(4+n)],{type:'image/jpeg'}));
   try{
    if(next.ageMs+performance.now()-start>=1000)throw Error('stale');
    if(video.width!==bitmap.width||video.height!==bitmap.height){video.width=overlay.width=bitmap.width;video.height=overlay.height=bitmap.height;}
    ctx.drawImage(bitmap,0,0);live=true;meta={...next,ageMs:next.ageMs+performance.now()-start};lastAt=performance.now();frameKey=next.frameKey;frameCount++;connectionError=false;recoveryDelay=1500;
    infer();
   }finally{bitmap.close();}
  }
 }catch{if(!fresh())people=[];}
 finally{if(active&&!document.hidden&&!fresh())recoverStream();render();setTimeout(poll,document.hidden?700:140);}
}
function stop(){
 drive.stop();
 generation++;active=false;started=true;reply='pausedReply';people=[];render();
}
async function json(path,body){
 const response=await fetch(path,{cache:'no-store',signal:AbortSignal.timeout(6000),...(body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})});
 const value=await response.json();if(!response.ok||value.requestError||value.error)throw Error(value.requestError||value.error||'REQUEST_FAILED');return value;
}
async function recoverStream(){
 if(!active||document.hidden||recoveryBusy||performance.now()<recoveryAt)return;
 const turn=generation,current=()=>turn===generation&&active&&!document.hidden;
 recoveryBusy=true;recoveryAt=performance.now()+recoveryDelay;
 try{
  if(!scene){
   const owner=await json('/api/assistant/state');if(!current())return;
   // An explicit takeover must not silently re-enable autonomous following.
   if((!autoMode&&!owner.observationOnly)||owner.owner!=='ai'){stop();return;}
   if(autoMode&&drive.token&&!owner.follow?.active){drive.stop();return;}
  }
  const state=await (await fetch('/api/state',{cache:'no-store',signal:AbortSignal.timeout(6000)})).json();if(!current())return;
  if(!state.connected){
   if(state.phase!=='connecting'){await json('/api/connect',{});recoveryAttempts++;}
   recoveryDelay=Math.min(8000,recoveryDelay*1.6);return;
  }
  await json('/api/camera/start',{});if(!current())return;
  connectionError=false;recoveryDelay=1500;
 }catch{if(current()){connectionError=true;recoveryDelay=Math.min(8000,recoveryDelay*1.6);}}
 finally{recoveryBusy=false;recoveryAt=performance.now()+recoveryDelay;render();}
}
async function start(text,fromAi=false,autonomous=false){
 text=String(text||'').trim().slice(0,800);if(!text||busy)return;
 if(scene){sceneCommand(text);return;}
 if(/停止|暂停|不要跟|别跟|stop|pause/i.test(text)){stop();return;}
 $('user-message').textContent=text;$('user-message').hidden=false;
 if(!fromAi&&!/跟踪|追踪|跟随|跟着|跟我|follow|track/i.test(text)){reply='unsupported';render();return;}
 await drive.stop();
 autoMode=autonomous;targetIdentity++;
 generation++;active=false;started=true;tracker.reset();people=[];busy=true;reply='received';render();
 const turn=generation;
 try{
  const policy=autoMode?await json('/api/assistant/state'):await json('/api/assistant/observe',{});if(turn!==generation)return;
  active=true;recoveryAt=0;recoveryDelay=1500;await recoverStream();
  if(autoMode){
   drive.starting=true;
   const until=performance.now()+10000;
   while(turn===generation&&!document.hidden&&!fresh()&&performance.now()<until)await new Promise(resolve=>setTimeout(resolve,100));
   if(turn!==generation||document.hidden)return;
   if(!fresh()){active=false;await drive.stop();reply='autoOffline';drive.value={active:false,state:'stopped',error:'NOT_CONNECTED'};return;}
   await drive.start(policy.follow?.requestId);
  }
 }catch{connectionError=true;}
 finally{busy=false;render();}
}
function pick(point=null){
 if(!active||!fresh()||performance.now()-latestDetectionAt>1000)return;
 if(tracker.select(people,point)){targetIdentity++;guidance.reset();}render();
}
$('command-form').onsubmit=e=>{e.preventDefault();const text=$('command').value;$('command').value='';$('command').blur();start(text);};
$('reselect').onclick=()=>{targetIdentity++;tracker.reset();guidance.reset();pick();};
$('auto-stop').onclick=stop;
overlay.onclick=e=>{const r=overlay.getBoundingClientRect();pick([(e.clientX-r.left)/r.width,(e.clientY-r.top)/r.height]);};
overlay.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();pick();}};
$('language').onclick=()=>{lang=lang==='zh'?'en':'zh';localStorage.setItem('go2-language',lang);window.Go2Locale?.setLanguage(lang);translate();};
$('scene-calibrate').onclick=()=>$('calibrate').click();
$('calibrate').onclick=()=>{$('calibration-error').textContent='';$('calibration-dialog').showModal();};
$('calibration-cancel').onclick=()=>$('calibration-dialog').close();
$('calibration-form').onsubmit=e=>{e.preventDefault();if(fresh()&&tracker.calibrate(Number($('known-distance').value))){$('calibration-dialog').close();guidance.reset();render();}else $('calibration-error').textContent=t('invalidDistance');};
document.addEventListener('visibilitychange',()=>{if(document.hidden){drive.stop();live=false;people=[];generation++;}else recoveryAt=0;render();});
window.go2FollowStart=(text,autonomous=false)=>start(text,true,autonomous);window.go2FollowStop=stop;
function renderSceneVoice(){
 const a=voice.state,occupied=voice.pending||a?.busy||a?.state==='streaming',bark=voice.selected===BARK,queued=['say','bark'].includes(scene?.pending),processing=!!scene?.pending;
 $('scene-speak').disabled=!a?.connected||!!occupied||processing;
 $('scene-prepare').disabled=!a?.connected||!!occupied||processing;
 $('scene-line').disabled=$('scene-bark').disabled=!!occupied||processing;$('scene-preview').disabled=processing;
 $('scene-line').setAttribute('aria-pressed',String(!bark));$('scene-bark').setAttribute('aria-pressed',String(bark));
 $('scene-voice-caption').textContent=bark?'A playful bark. A little attitude.':'“Talking to you? I’m enough.”';
 $('scene-speak').textContent=queued?'Processing…':a?.state==='streaming'?'Playing':a?.state==='preparing'?'Loading…':bark?'Play bark':'Say it';
 $('scene-audio-stop').hidden=!occupied&&!voice.player&&!queued;
 $('scene-preview').hidden=!!occupied;$('scene-prepare').hidden=!!occupied;
 $('scene-preview').textContent=voice.player?'Playing':'Phone preview';
 $('scene-voice-status').textContent=queued?'Preparing sound…':voice.error?'Please retry':a?.state==='streaming'?'Playing on Go2':a?.state==='preparing'?'Loading onto Go2…':a?.preparedPreset===voice.selected?'Ready on Go2':'Saved on phone';
}
function sceneCommand(text){
 if(window.Go2Scene){window.Go2Scene.command(String(text));return;}
 // A browser preview can render the same page, but never acquire motion control.
 const go=/^(go handle him for me[.!]?|去会会他)$/i.test(text),back=/^(come back[.!]?|回来)$/i.test(text),stay=/^(stay[.!]?|停留)$/i.test(text),circle=/^(circle[.!]?|环绕)$/i.test(text);
 if(go)applyScene({phase:'confirm',request:'Go handle him for me.',manual:false});
 else if(back&&scene)applyScene({...scene,phase:'returning',request:'Come back.'});
 else if(stay&&scene)applyScene({...scene,phase:['returning','return_hold'].includes(scene.phase)?'return_hold':'holding',request:'Stay here.'});
 else if(circle&&scene)applyScene({...scene,phase:'circling',request:'Circle around him.'});
}
async function applyScene(next){
 const before=scene;scene=next;
 document.body.dataset.scene=String(!!scene);document.body.dataset.scenePhase=scene?.phase||'';
 const processing=!!scene?.pending,preparation=scenePreparation(scene?.pending,scene?.pendingStage);document.body.dataset.scenePending=String(processing);
 $('scene-processing-text').textContent=preparation?.title||'Processing your command…';
 $('scene-processing').hidden=!processing;$('scene-actions').setAttribute('aria-busy',String(processing));$('scene-go').disabled=processing;
 $('scene-strip').hidden=!scene;$('scene-go').hidden=!!scene&&scene.phase!=='complete';$('scene-shortcuts').hidden=!!scene&&scene.phase!=='complete';
 const holding=['holding','return_hold'].includes(scene?.phase),returning=['returning','return_hold'].includes(scene?.phase);
 $('scene-actions').hidden=!scene||['confirm','complete'].includes(scene.phase);$('scene-voice').hidden=!scene||scene.phase==='confirm';
 $('scene-stay').setAttribute('aria-pressed',String(holding&&!processing));$('scene-stay').disabled=holding&&!processing;
 $('scene-circle').setAttribute('aria-pressed',String(scene?.phase==='circling'));$('scene-circle').hidden=returning;
 $('scene-circle').disabled=processing||!!scene?.manual||scene?.phase==='circling';
 $('scene-return').disabled=processing||!!scene?.manual||scene?.phase==='returning';$('scene-return').textContent=scene?.phase==='return_hold'?'Resume return ↙':'Come back. ↙';
 renderSceneVoice();
 if(!scene){generation++;active=false;guidance.reset();lang=previousLanguage;translate();return;}
 if(!before)previousLanguage=lang;lang='en';autoMode=false;
 // The existing FollowDrive is never started from this scene. Stop any old lease first.
 await drive.stop();
 $('user-message').textContent=scene.pendingRequest||scene.request;$('user-message').hidden=false;
 $('scene-phase').textContent=processing?(preparation?.badge||'Processing'):scene.manual?'Manual':scene.phase==='confirm'?'Ready':scene.phase==='complete'?'Complete':holding?'Holding':'Running';
 $('scene-task').textContent=preparation?(scene.pending==='return'?'Return to start':'Approach target'):({running:'Approaching target',returning:'Coming back',return_hold:'Return paused',holding:'Staying here',circling:'Circling',complete:'All set'}[scene.phase]||'Face to face');
 if(!before||scene.phase==='confirm'&&before.phase!=='confirm'){tracker.reset();guidance.reset();generation++;people=[];}
 if(scene.phase==='circling'&&before?.phase!=='circling')voice.select(BARK);
 started=true;active=['running','holding','circling','returning','return_hold'].includes(scene.phase);
 if(active){recoveryAt=0;recoveryDelay=1500;recoverStream();}
 translate();voice.refresh();
}
$('scene-go').onclick=()=>sceneCommand('Go handle him for me.');
$('scene-return').onclick=()=>sceneCommand('Come back.');
$('scene-stay').onclick=()=>sceneCommand('Stay');$('scene-circle').onclick=()=>sceneCommand('Circle');
$('scene-line').onclick=()=>voice.select(MAIN_LINE);$('scene-bark').onclick=()=>voice.select(BARK);$('scene-audio-stop').onclick=()=>window.Go2Scene?sceneCommand('Stop audio'):voice.stop();
$('scene-speak').onclick=()=>window.Go2Scene?sceneCommand(voice.selected===BARK?'Bark':'Say it'):voice.run('play');$('scene-preview').onclick=()=>voice.preview();$('scene-prepare').onclick=()=>voice.run('prepare');
window.go2SceneUpdate=applyScene;window.go2SceneSpeak=()=>voice.run('play',MAIN_LINE);window.go2SceneBark=()=>voice.run('play',BARK);window.go2SceneStopAudio=()=>voice.stop();
document.addEventListener('visibilitychange',()=>{if(document.hidden)voice.stopPreview();});
translate();startWorker();setInterval(render,200);setInterval(()=>{if(scene&&!document.hidden)voice.refresh();},800);poll();
