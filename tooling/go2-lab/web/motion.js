Object.assign(messages.zh,{
  motionPolicy:'当前连接模式不开放 App 行进控制，摄像头、语音与建议仍可使用。',motionStopApp:'停止 App 任务',motionTab:'微动',motionIntro:'每点一次，低速动一下，随后请求停止。',
  motionLimits:'当前速度：前后 {linear} m/s，转向 {angular} rad/s；每次 {seconds} 秒。',
  motionSpeedTitle:'调整速度',motionLinear:'前后速度',motionAngular:'转向速度',motionSpeedApply:'应用速度',
  motionSpeedNote:'前后 0.08–0.20 m/s；转向 0.15–0.40 rad/s。先停止并锁定再调速；应用后重新启用微动。',
  motionSpeedPreview:'所选速度每次理论约 {cm} cm / {degrees}°，实际步幅可能不同。',
  motionSpeedDirty:'速度尚未应用，微动暂不可用。',motionSpeedSaved:'速度已应用；确认现场后重新启用微动。',
  motionSpeedFailed:'速度未确认应用，请检查当前速度后重试。',motionSpeedWaiting:'等待 Mac 服务返回速度设置。',
  motionReady:'狗已站稳，平地四周至少约 1 米空地，我在旁边。',motionArm:'启用微动 · 60 秒',
  motionForward:'↑ 轻微前进',motionBackward:'↓ 轻微后退',motionLeft:'↶ 左转一点',motionRight:'↷ 右转一点',motionStop:'停止移动 / 锁定',
  motionLocked:'已锁定',motionArmed:'可微动，剩余',motionBusy:'正在处理动作，请观察机器狗',motionCooldown:'稍候再操作',motionAck:'停止指令已确认，请观察是否站稳',
  motionMode:'当前模式不支持微动，需要普通行走模式',motionUnavailable:'连接或状态未就绪',motionError:'动作未完成，请查看连接并重新启用',motionStopUnknown:'未收到停止确认。请立即用实体遥控器停止，勿靠近运动中的腿部。',
  motionFootnote:'按一下即可，不会长按连发。前后方向以狗朝向为准。每次间隔至少 2 秒。断网时软件停止可能无法送达；本按钮不是硬件急停。'
});
Object.assign(messages.en,{
  motionPolicy:'App movement is unavailable in this connection mode. Camera, voice and guidance remain available.',motionStopApp:'Stop app task',motionTab:'Move',motionIntro:'One click sends a small, slow pulse followed by a stop request.',
  motionLimits:'Current speed: forward/back {linear} m/s, turn {angular} rad/s; {seconds} s per pulse.',
  motionSpeedTitle:'Adjust speed',motionLinear:'Forward/back speed',motionAngular:'Turn speed',motionSpeedApply:'Apply speed',
  motionSpeedNote:'Forward/back 0.08–0.20 m/s; turn 0.15–0.40 rad/s. Stop and lock before adjusting; enable pulses again after applying.',
  motionSpeedPreview:'Selected speed gives nominally {cm} cm / {degrees}° per pulse; actual gait can differ.',
  motionSpeedDirty:'Speed changes are not applied. Movement is disabled.',motionSpeedSaved:'Speed applied. Check the surroundings and enable pulses again.',
  motionSpeedFailed:'Speed update not confirmed. Check current values and retry.',motionSpeedWaiting:'Waiting for speed settings from the Mac service.',
  motionReady:'The robot is standing on level ground, with about 1 m clear around it, and I am beside it.',motionArm:'Enable pulses · 60 s',
  motionForward:'↑ Forward a little',motionBackward:'↓ Back a little',motionLeft:'↶ Turn left',motionRight:'↷ Turn right',motionStop:'Stop / lock',
  motionLocked:'Locked',motionArmed:'Enabled, remaining',motionBusy:'Processing; watch the robot',motionCooldown:'Wait before the next action',motionAck:'Stop acknowledged; check the robot is steady',
  motionMode:'Normal walking mode is required for pulses',motionUnavailable:'Connection or state not ready',motionError:'Action failed; check connection and enable again',motionStopUnknown:'Stop not acknowledged. Use the physical remote immediately and stay clear of moving legs.',
  motionFootnote:'Click once; holding does not repeat. Directions follow the robot’s heading. Allow at least 2 s between actions. Software stop may not arrive during network loss; this is not a hardware emergency stop.'
});
translate();
(() => {
  let m=null,seen=0,available=false,serviceAvailable=false,replaying=false,pending=false,pendingAction=null,requestId=0,stopUnconfirmed=false;
  let draft=null,draftDirty=false,speedMessage=null,restricted=false;
  const directions=['forward','backward','left','right'];
  const format=(key,values)=>Object.entries(values).reduce((s,[k,v])=>s.replaceAll('{'+k+'}',v),t(key));
  function renderMotion(){
    const age=performance.now()-seen,online=available&&age<1800;
    const manualAllowed=!restricted&&(window.phoneControlOwner===undefined||window.phoneControlOwner==='manual');
    $('motion-ready').disabled=restricted;
    const profile=Number.isFinite(m?.linearSpeed)&&Number.isFinite(m?.angularSpeed)&&Number.isFinite(m?.durationMs)&&m?.speedLimits;
    const serviceOnline=serviceAvailable&&age<1800&&Boolean(profile);
    if(profile&&!draftDirty)draft={linearSpeed:m.linearSpeed,angularSpeed:m.angularSpeed};
    const speedLocked=!manualAllowed||!serviceOnline||replaying||Boolean(m?.armed)||Boolean(m?.busy)||pending||stopUnconfirmed||m?.error==='STOP_UNCONFIRMED';
    $('motion-speeds').disabled=speedLocked;
    $('motion-speed-apply').disabled=speedLocked||!draftDirty;
    for(const [id,key] of [['linear','linearSpeed'],['angular','angularSpeed']]){
      const input=$('motion-'+id),limits=m?.speedLimits?.[key];
      if(limits){input.min=limits.min;input.max=limits.max;input.step=limits.step;}
      input.disabled=speedLocked;
      if(draft){input.value=draft[key];$('motion-'+id+'-value').textContent=draft[key].toFixed(2)+(id==='linear'?' m/s':' rad/s');}
    }
    $('motion-limits').textContent=profile?format('motionLimits',{linear:m.linearSpeed.toFixed(2),angular:m.angularSpeed.toFixed(2),seconds:(m.durationMs/1000).toFixed(2)}):t('motionSpeedWaiting');
    $('motion-speed-preview').textContent=profile&&draft?format('motionSpeedPreview',{cm:(draft.linearSpeed*m.durationMs/10).toFixed(1),degrees:(draft.angularSpeed*m.durationMs/1000*180/Math.PI).toFixed(1)}):'';
    $('motion-speed-status').textContent=speedMessage?t(speedMessage):draftDirty?t('motionSpeedDirty'):'';
    const remaining=Math.max(0,(m?.remainingMs||0)-age),cooldown=Math.max(0,(m?.cooldownMs||0)-age);
    const armed=online&&m?.armed&&remaining>0;
    for(const action of directions)$('motion-'+action).disabled=!manualAllowed||!serviceOnline||replaying||draftDirty||stopUnconfirmed||!armed||pending||m?.busy||cooldown>0||!m?.ready;
    $('motion-arm').disabled=!manualAllowed||!serviceOnline||replaying||draftDirty||stopUnconfirmed||!online||!m?.ready||!$('motion-ready').checked||pending||m?.busy||cooldown>0||armed;
    $('motion-stop').disabled=false;$('motion-stop').textContent=t(restricted?'motionStopApp':'motionStop');
    let key=stopUnconfirmed||m?.error==='STOP_UNCONFIRMED'?'motionStopUnknown':!online?'motionUnavailable':m?.error?'motionError':!m?.ready?(m?.mode&&m.mode!=='normal'?'motionMode':'motionUnavailable'):pending||m?.busy?'motionBusy':cooldown>0?'motionCooldown':!armed?'motionLocked':m?.result==='stop_acknowledged'?'motionAck':'motionArmed';
    $('motion-status').textContent=t(restricted?'motionPolicy':key)+(armed&&!stopUnconfirmed&&m?.error!=='STOP_UNCONFIRMED'?` · ${Math.ceil(remaining/1000)} s`:'');
  }
  window.addEventListener('go2-state',event=>{
    available=!event.detail.offline&&event.detail.state?.connected;
    serviceAvailable=!event.detail.offline&&Boolean(event.detail.state);
    replaying=event.detail.state?.source==='replay';restricted=event.detail.state?.externalMotionControl===true;
    if(!pending&&event.detail.receivedAt>=seen){m=event.detail.state?.motion;seen=event.detail.receivedAt;}
    renderMotion();
  });
  async function command(action,payload={}){
    const id=++requestId;pending=true;pendingAction=action;renderMotion();
    try{
      const result=await request('/api/motion/'+action,'POST',payload);
      if(id===requestId){m=result;seen=performance.now();if(action==='stop')stopUnconfirmed=result.error==='STOP_UNCONFIRMED';
        if(action==='speed'){
          const confirmed=!result.requestError&&result.armed===false&&result.linearSpeed===payload.linearSpeed&&result.angularSpeed===payload.angularSpeed;
          draftDirty=!confirmed;speedMessage=confirmed?'motionSpeedSaved':'motionSpeedFailed';}}
    }catch{if(id===requestId){available=false;if(action==='stop')stopUnconfirmed=true;if(action==='speed')speedMessage='motionSpeedFailed';}}
    finally{if(id===requestId){pending=false;pendingAction=null;renderMotion();}}
  }
  for(const [id,key] of [['linear','linearSpeed'],['angular','angularSpeed']])$('motion-'+id).oninput=()=>{
    if($('motion-'+id).disabled||!draft)return;
    draft[key]=Number($('motion-'+id).value);
    draftDirty=draft.linearSpeed!==m.linearSpeed||draft.angularSpeed!==m.angularSpeed;
    speedMessage=null;$('motion-ready').checked=false;renderMotion();
  };
  $('motion-speed-apply').onclick=()=>{if(!$('motion-speed-apply').disabled){$('motion-ready').checked=false;command('speed',{...draft});}};
  for(const action of directions)$('motion-'+action).onclick=()=>command(action);
  $('motion-arm').onclick=()=>command('arm');
  $('motion-stop').onclick=()=>{ $('motion-ready').checked=false;command('stop'); };
  $('motion-ready').onchange=()=>{
    if(!$('motion-ready').checked&&(m?.armed||pending))command('stop');
    renderMotion();
  };
  $('motion-page').addEventListener('keydown',event=>{if(event.repeat&&(event.key==='Enter'||event.key===' '))event.preventDefault();});
  function leave(){if(m?.armed||(pending&&pendingAction!=='speed')){$('motion-ready').checked=false;command('stop');}}
  document.addEventListener('visibilitychange',()=>{if(document.hidden)leave();});
  window.addEventListener('go2-page',event=>{if(event.detail!=='motion')leave();});
  $('language').addEventListener('click',renderMotion);
  setInterval(renderMotion,100);renderMotion();
})();
