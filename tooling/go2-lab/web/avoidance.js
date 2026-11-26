Object.assign(messages.zh, {
  avoidTab:'避障',avoidTitle:'避障实验',avoidIntro:'查看环境、确认保护状态，再执行一次轻微动作。',
  avoidLimits:'前后：0.08 m/s × 0.35 秒；转向：0.15 rad/s × 0.35 秒。理论约 2.8 cm / 3°，实际步幅可能不同。',
  sourceSimulation:'离线模拟 · 未连接真实机器狗',sourceReplay:'历史回放 · 所有运动入口已禁用',
  sourceReal:'实机通道 · 原生避障运动尚未开放',sourceUnknown:'正在确认数据来源…',
  avoidOn:'避障：已回读开启',avoidOff:'避障：关闭',avoidUnknown:'避障：状态未知',
  poseAligned:'坐标已对齐（模拟）',poseUnknown:'坐标未验证',avoidEnable:'开启并验证',avoidDisable:'关闭避障',
  avoidReady:'我已确认数据来源；每次动作后需要重新启用。',avoidArm:'启用本次微动',
  localMapLabel:'机器人周围的俯视图；正前方朝上，左方朝左',
  localMapNote:'矩形表示机身范围，虚线表示检查余量。此模型仅验证平地障碍逻辑，不代表真实避障性能。',
  simScenario:'模拟场景',simApply:'应用场景',simFeedback:'模拟位姿',simIntercept:'模拟机载拦截次数',
  recordTitle:'录制与回放',recordNote:'保存完整解码点云、位姿、时间和指令记录。回放只展示传感器数据，不执行记录中的动作。',
  recordStart:'开始录制',recordStop:'结束录制',recordIdle:'未录制',recordActive:'正在录制',recordEvents:'条记录',
  replayStart:'回放最近录制',replayPause:'暂停回放',replayResume:'继续回放',replayStop:'结束回放',replayEnded:'回放结束',
  avoidLocked:'已锁定。先开启避障、勾选确认，再启用本次微动。',avoidArmed:'已启用；请选择允许的方向。',
  avoidWorking:'正在处理，请稍候。停止始终可用。',avoidStopped:'停止请求已确认，控制已锁定。',
  avoidRealLocked:'实机运动需要完成固件、模式和停止行为验证，目前仅允许查询。',
  avoidBlocked:'该方向的检查范围内有障碍，已阻止动作。',avoidStale:'环境或位姿未及时更新，运动已锁定。',
  avoidInvalid:'环境数据无法用于运动判断，运动已锁定。',avoidMismatch:'开启结果与回读不一致，未启用运动。',
  avoidDisabled:'避障未确认开启，未启用运动。',avoidControlBusy:'另一控制入口正在使用，请先停止并锁定。',
  avoidLease:'操作心跳已失效，已请求停止并锁定。',avoidRequestFailed:'请求未完成；请检查状态后重试。',
  avoidCooldown:'动作结束，正在冷却。',mapFront:'前',mapLeft:'左',mapRight:'右',mapBack:'后',
  'scenario-clear':'空旷平地','scenario-front':'前方障碍','scenario-rear':'后方障碍','scenario-left':'左转角障碍',
  'scenario-right':'右转角障碍','scenario-obstacle-appears':'运动中出现障碍','scenario-native-block':'模拟机载拦截',
  'scenario-stale-cloud':'点云停止更新','scenario-stale-pose':'位姿停止更新','scenario-empty-cloud':'空点云',
  'scenario-pose-mismatch':'坐标系不一致','scenario-enable-rejected':'开启请求被拒绝','scenario-readback-mismatch':'开启后回读不一致',
  'scenario-query-timeout':'状态查询超时','scenario-invalid-state':'无效状态响应','scenario-stop-timeout':'停止未确认',
  'scenario-mode-change':'运动模式改变','scenario-disconnect':'模拟连接中断'
});
Object.assign(messages.en, {
  avoidTab:'Avoidance',avoidTitle:'Avoidance lab',avoidIntro:'Inspect the scene, confirm protection, then send one small movement.',
  avoidLimits:'Forward/back: 0.08 m/s × 0.35 s; turn: 0.15 rad/s × 0.35 s. Nominally 2.8 cm / 3°; actual gait can differ.',
  sourceSimulation:'OFFLINE SIMULATION · No real robot connected',sourceReplay:'RECORDED REPLAY · All movement disabled',
  sourceReal:'REAL CONNECTION · Native avoidance movement is not enabled',sourceUnknown:'Checking data source…',
  avoidOn:'Avoidance: verified on',avoidOff:'Avoidance: off',avoidUnknown:'Avoidance: unknown',
  poseAligned:'Frame aligned (simulation)',poseUnknown:'Frame unverified',avoidEnable:'Enable and verify',avoidDisable:'Disable avoidance',
  avoidReady:'I have checked the data source. Each movement requires enabling again.',avoidArm:'Enable this movement',
  localMapLabel:'Top view around the robot; forward is up and left is left',
  localMapNote:'Solid box: body envelope. Dashed box: checking margin. This flat-floor model does not establish real avoidance performance.',
  simScenario:'Simulation scenario',simApply:'Apply scenario',simFeedback:'Simulated pose',simIntercept:'Simulated native blocks',
  recordTitle:'Recording and replay',recordNote:'Save full decoded clouds, poses, timestamps and command evidence. Replay displays sensors only and never executes recorded actions.',
  recordStart:'Start recording',recordStop:'Finish recording',recordIdle:'Not recording',recordActive:'Recording',recordEvents:'events',
  replayStart:'Replay last recording',replayPause:'Pause replay',replayResume:'Resume replay',replayStop:'End replay',replayEnded:'Replay ended',
  avoidLocked:'Locked. Enable avoidance, confirm the source, then enable a movement.',avoidArmed:'Enabled. Choose an available direction.',
  avoidWorking:'Processing. Stop remains available.',avoidStopped:'Stop request acknowledged; controls locked.',
  avoidRealLocked:'Real movement awaits firmware, mode and stop validation. Queries only.',
  avoidBlocked:'An obstacle intersects the checked envelope. Movement blocked.',avoidStale:'Environment or pose updates are stale. Movement locked.',
  avoidInvalid:'Environment data cannot support a movement decision. Movement locked.',avoidMismatch:'Readback did not confirm the requested setting. Movement not enabled.',
  avoidDisabled:'Avoidance is not confirmed on. Movement not enabled.',avoidControlBusy:'Another controller owns movement. Stop and lock it first.',
  avoidLease:'Control heartbeat expired. Stop requested and controls locked.',avoidRequestFailed:'Request did not complete. Check the state and retry.',
  avoidCooldown:'Movement complete. Cooling down.',mapFront:'Front',mapLeft:'Left',mapRight:'Right',mapBack:'Back',
  'scenario-clear':'Clear floor','scenario-front':'Front obstacle','scenario-rear':'Rear obstacle','scenario-left':'Left turn corner',
  'scenario-right':'Right turn corner','scenario-obstacle-appears':'Obstacle appears during movement','scenario-native-block':'Simulated native interception',
  'scenario-stale-cloud':'Cloud updates stop','scenario-stale-pose':'Pose updates stop','scenario-empty-cloud':'Empty cloud',
  'scenario-pose-mismatch':'Frame mismatch','scenario-enable-rejected':'Enable rejected','scenario-readback-mismatch':'Readback mismatch',
  'scenario-query-timeout':'Status query timeout','scenario-invalid-state':'Invalid state response','scenario-stop-timeout':'Stop unconfirmed',
  'scenario-mode-change':'Motion mode changes','scenario-disconnect':'Connection drops'
});
Object.assign(messages.zh,{'scenario-navigation-room':'自主导航房间','scenario-navigation-blocked':'导航路线封闭'});
Object.assign(messages.en,{'scenario-navigation-room':'Navigation room','scenario-navigation-blocked':'Navigation route blocked'});
translate();
Object.assign(messages.zh,{
  mapExplanation:'怎么看这张图',directionClear:'未检出障碍',directionBlocked:'有障碍',directionUnknown:'无法判断',
  nextConnect:'先到「状态」页连接模拟器。',nextEnable:'第 1 步：点击「开启并验证」。',
  nextConfirm:'第 2 步：勾选数据来源确认。',nextArm:'第 3 步：点击「启用本次微动」，再选方向。',
  nextArmed:'已启用。灰色方向有障碍或数据无效，不能操作。',nextSimStop:'模拟停止未确认。先应用「空旷平地」，再点「停止移动 / 锁定」重试。',
  nextInvalid:'当前场景无法支持运动判断。选择「空旷平地」恢复后重新启用。',
  nextQuery:'避障状态未能确认。移除模拟故障后重新开启并验证。',nextMode:'模拟运动模式已改变。应用「空旷平地」恢复。',
  simulationStopped:'模拟动作已停止，控制已锁定。',simulationRecovery:'模拟故障已解除，请重新启用。',
  simulationResult:'本次模拟变化',resultDistance:'位移',resultHeading:'转角',noMovement:'未发生位姿变化',
  scenarioActive:'当前场景',scenarioPending:'尚未应用',simulationStop:'停止模拟 / 锁定',
  avoidGoConnect:'前往连接',readinessUnavailable:'连接未就绪，请先连接。'
});
Object.assign(messages.en,{
  mapExplanation:'Reading this map',directionClear:'No obstacle detected',directionBlocked:'Obstacle',directionUnknown:'Unknown',
  nextConnect:'Connect the simulator on the Status page first.',nextEnable:'Step 1: choose Enable and verify.',
  nextConfirm:'Step 2: confirm the data source.',nextArm:'Step 3: enable this movement, then choose a direction.',
  nextArmed:'Enabled. Grey directions have obstacles or invalid data.',nextSimStop:'Simulated stop is unconfirmed. Apply Clear floor, then retry Stop / lock.',
  nextInvalid:'This scenario cannot support movement decisions. Apply Clear floor, then enable again.',
  nextQuery:'Avoidance state is unconfirmed. Remove the simulated fault, then enable and verify again.',nextMode:'Simulated motion mode changed. Apply Clear floor to recover.',
  simulationStopped:'Simulated movement stopped; controls locked.',simulationRecovery:'Simulated fault cleared. Enable again.',
  simulationResult:'This simulated movement',resultDistance:'Displacement',resultHeading:'Rotation',noMovement:'No pose change',
  scenarioActive:'Active scenario',scenarioPending:'Not applied',simulationStop:'Stop simulation / lock',
  avoidGoConnect:'Go to connection',readinessUnavailable:'Connection is not ready. Connect first.'
});
translate();
(() => {
  const directions=['forward','backward','left','right'];
  const scenarios=['clear','front','rear','left','right','obstacle-appears','native-block','stale-cloud','stale-pose',
    'empty-cloud','pose-mismatch','enable-rejected','readback-mismatch','query-timeout','invalid-state','stop-timeout','mode-change','disconnect'];
  const errors={OBSTACLE_BLOCKED:'avoidBlocked',CLOUD_STALE:'avoidStale',POSE_STALE:'avoidStale',TIME_SKEW:'avoidStale',
    POSE_UNVERIFIED:'avoidInvalid',FRAME_MISMATCH:'avoidInvalid',EMPTY_OR_INVALID_CLOUD:'avoidInvalid',NO_DATA:'avoidInvalid',
    READBACK_MISMATCH:'avoidMismatch',AVOIDANCE_DISABLED:'avoidDisabled',AVOIDANCE_UNKNOWN:'avoidUnknown',
    CONTROL_BUSY:'avoidControlBusy',CONTROL_LEASE_EXPIRED:'avoidLease',STOP_UNCONFIRMED:'motionStopUnknown',
    REAL_VALIDATION_REQUIRED:'avoidRealLocked',REPLAY_ACTIVE:'sourceReplay',STATE_NOT_READY:'motionUnavailable',
    COOLDOWN:'avoidCooldown',LOCKED:'avoidLocked',CANCELLED:'avoidLocked'};
  let data=null,arrived=0,offline=true,pending=0,sequence=0,requestSequence=0,page='state',actionError=null,stopUnknown=false,lastMovement=null,directionKey='';
  const sourceKey=()=>data?.source==='simulation'?'sourceSimulation':data?.source==='replay'?'sourceReplay':data?.source==='real'?'sourceReal':'sourceUnknown';
  function translateScenarios(){
    const selected=$('sim-scenario').value||'clear';
    $('sim-scenario').replaceChildren(...scenarios.map(name=>{
      const option=document.createElement('option');option.value=name;option.textContent=t('scenario-'+name);return option;
    }));
    $('sim-scenario').value=selected;
  }
  function render(){
    const age=performance.now()-arrived,online=!offline&&age<1100,a=data?.avoidance,g=data?.geometry;
    const replay=data?.source==='replay',allowed=online&&data?.connected&&a?.actuationAllowed&&!replay;
    const busy=pending>0||a?.busy,armed=allowed&&a?.armed&&a.remainingMs>age;
    $('source-banner').textContent=t(sourceKey());$('source-banner').dataset.source=data?.source||'unknown';
    $('avoid-native-state').textContent=t(!online||!a?.statusFresh||a?.enabled==null?'avoidUnknown':a.enabled?'avoidOn':'avoidOff');
    $('avoid-pose-state').textContent=t(g?.valid?'poseAligned':'poseUnknown');
    const error=actionError||a?.error;
    const status=stopUnknown||a?.stopConfirmed===false?'motionStopUnknown':!online?'offline':replay?'sourceReplay':
      !a?.actuationAllowed?'avoidRealLocked':error?(errors[error]||'avoidRequestFailed'):busy?'avoidWorking':
      data?.connected&&data?.readiness?(errors[data.readiness]||'avoidInvalid'):
      (a?.cooldownMs||0)>age?'avoidCooldown':armed?'avoidArmed':a?.result==='stop_acknowledged'?'avoidStopped':'avoidLocked';
    const simSource=data?.source==='simulation';
    const simStatus=status==='motionStopUnknown'?'nextSimStop':status==='avoidStopped'?'simulationStopped':
      status==='motionUnavailable'?(data?.scenario==='mode-change'?'nextMode':'readinessUnavailable'):status;
    $('avoid-status').textContent=t(simSource?simStatus:status);
    $('avoid-next').textContent=t(!online?'offlineNote':replay?'sourceReplay':!data?.connected?'nextConnect':
      !a?.actuationAllowed?'avoidRealLocked':stopUnknown||a?.stopConfirmed===false?(simSource?'nextSimStop':'motionStopUnknown'):
      data?.readiness?(simSource?'nextInvalid':errors[data.readiness]||'avoidInvalid'):
      !a?.statusFresh?'nextQuery':!a?.enabled?'nextEnable':armed?'nextArmed':!$('avoid-ready').checked?'nextConfirm':'nextArm');
    $('avoid-connect').hidden=!online||data?.connected||replay;
    $('avoid-enable').disabled=data?.controlOwner==='navigation'||!allowed||busy||stopUnknown||a?.stopConfirmed===false||a?.enabled===true;
    $('avoid-disable').disabled=data?.controlOwner==='navigation'||!allowed||busy||stopUnknown||a?.stopConfirmed===false||a?.enabled===false;
    $('avoid-arm').disabled=!allowed||busy||armed||!a?.enabled||!a?.statusFresh||!g?.valid||
      !$('avoid-ready').checked||stopUnknown||a?.stopConfirmed===false||data?.readiness||(a?.cooldownMs||0)>age;
    for(const d of directions)$('avoid-'+d).disabled=!armed||busy||stopUnknown||!g?.valid||g.directions[d]!=='clear'||!a?.statusFresh;
    $('avoid-stop').disabled=replay||!a?.actuationAllowed;
    $('avoid-stop').textContent=t(simSource?'simulationStop':'motionStop');
    $('simulation-tools').hidden=data?.source!=='simulation';
    $('sim-apply').disabled=busy||!online||replay;
    const sim=data?.simulation;
    $('scenario-status').textContent=data?.scenario?`${t('scenarioActive')}：${t('scenario-'+data.scenario)}${$('sim-scenario').value!==data.scenario?' · '+t('scenarioPending'):''}`:'';
    $('sim-feedback').textContent=sim?`${t('simFeedback')}: x ${sim.position[0].toFixed(3)} m · y ${sim.position[1].toFixed(3)} m · ${(sim.yaw*180/Math.PI).toFixed(1)}° · ${t('simIntercept')}: ${sim.nativeBlocks}`:'';
    $('sim-result').textContent=lastMovement?`${t('simulationResult')}：${t('resultDistance')} ${lastMovement.distance.toFixed(1)} cm · ${t('resultHeading')} ${lastMovement.heading.toFixed(1)}°`:'';
    const labels={forward:'mapFront',backward:'mapBack',left:'mapLeft',right:'mapRight'};
    const nextDirectionKey=JSON.stringify([lang,online,g?.valid,g?.directions]);
    if(directionKey!==nextDirectionKey){
      directionKey=nextDirectionKey;
      $('direction-summary').replaceChildren(...directions.map(d=>{
        const kind=online&&g?.valid?g.directions[d]:'unknown',el=document.createElement('span');el.dataset.state=kind;
        el.textContent=`${t(labels[d])} · ${t(kind==='clear'?'directionClear':kind==='blocked'?'directionBlocked':'directionUnknown')}`;
        return el;
      }));
    }
    const record=data?.recording;
    $('record-status').textContent=record?.error?t('avoidRequestFailed'):
      `${t(record?.active?'recordActive':'recordIdle')} · ${record?.events||0} ${t('recordEvents')} · ${((record?.bytes||0)/1024).toFixed(0)} KB`;
    $('record-start').disabled=!online||!data?.connected||record?.active||busy||replay;
    $('record-stop').disabled=!online||(!record?.active&&!record?.error)||busy;
    $('replay-start').disabled=!online||!record?.replayAvailable||record?.active||busy||replay;
    $('replay-pause').disabled=!online||!replay||busy||data?.replay?.ended;
    $('replay-pause').textContent=t(data?.replay?.paused?'replayResume':'replayPause');
    $('replay-stop').disabled=!online||!replay||busy;
    $('replay-status').textContent=data?.replay?.error?t('avoidRequestFailed'):replay?
      `${t(data.replay.ended?'replayEnded':'sourceReplay')} · ${data.replay.time.toFixed(1)} / ${data.replay.duration.toFixed(1)} s`:'';
    if(page==='avoidance')drawMap(g,online);
    window.go2LabState={data,online,pending,actionError,stopUnknown};
  }
  function drawMap(g,online){
    const canvas=$('local-map'),ctx=canvas.getContext('2d'),w=canvas.clientWidth,h=canvas.clientHeight;
    if(!w||!h||!ctx)return;
    const ratio=Math.min(devicePixelRatio||1,2),pw=Math.round(w*ratio),ph=Math.round(h*ratio);
    if(canvas.width!==pw||canvas.height!==ph){canvas.width=pw;canvas.height=ph;}
    ctx.setTransform(ratio,0,0,ratio,0,0);
    ctx.fillStyle='#14251f';ctx.fillRect(0,0,w,h);
    const scale=Math.min(w,h)/4,cx=w/2,cy=h/2;
    ctx.strokeStyle='#344d40';ctx.lineWidth=1;
    for(let i=-2;i<=2;i++){ctx.beginPath();ctx.moveTo(cx+i*scale,0);ctx.lineTo(cx+i*scale,h);ctx.moveTo(0,cy+i*scale);ctx.lineTo(w,cy+i*scale);ctx.stroke();}
    ctx.fillStyle=g?.valid&&online?'#e0c77f':'#829087';
    for(const [x,y] of g?.points||[])ctx.fillRect(cx-y*scale-1,cy-x*scale-1,2,2);
    ctx.strokeStyle='#e9b75f';ctx.setLineDash([5,4]);ctx.strokeRect(cx-.4*scale,cy-.6*scale,.8*scale,1.2*scale);ctx.setLineDash([]);
    ctx.fillStyle=g?.valid&&online?'#a8c79c':'#829087';ctx.fillRect(cx-.25*scale,cy-.45*scale,.5*scale,.9*scale);
    ctx.strokeStyle='#193023';ctx.beginPath();ctx.moveTo(cx,cy+.15*scale);ctx.lineTo(cx,cy-.3*scale);ctx.lineTo(cx-.10*scale,cy-.18*scale);ctx.moveTo(cx,cy-.3*scale);ctx.lineTo(cx+.10*scale,cy-.18*scale);ctx.stroke();
    ctx.fillStyle='#d6e5d4';ctx.font='12px system-ui';ctx.textAlign='center';
    ctx.fillText(t('mapFront'),cx,18);ctx.fillText(t('mapBack'),cx,h-12);ctx.fillText(t('mapLeft'),24,cy);ctx.fillText(t('mapRight'),w-24,cy);
  }
  async function poll(){
    const id=++sequence;
    try{
      const result=await request('/api/lab');
      if(id!==sequence)return;
      if(data?.source&&data.source!==result.source)$('avoid-ready').checked=false;
      data=result;arrived=performance.now();offline=false;
      if(result.avoidance.stopConfirmed===false)stopUnknown=true;
    }catch{if(id===sequence)offline=true;}
    render();
  }
  async function act(family,action){
    const id=++requestSequence;pending++;actionError=null;
    const before=family==='avoidance'&&directions.includes(action)&&data?.source==='simulation'?structuredClone(data.simulation):null;
    if(family==='simulation'||family==='replay')lastMovement=null;
    if(action==='stop')$('avoid-ready').checked=false;
    render();
    try{
      const result=await request(`/api/${family}/${action}`,'POST');
      if(id===requestSequence){
        actionError=result.requestError||result.error||null;
        if(family==='avoidance'&&action==='stop')stopUnknown=result.stopConfirmed!==true;
      }
    }catch{
      if(id===requestSequence){actionError='AVOIDANCE_REQUEST_FAILED';if(family==='avoidance'&&action==='stop')stopUnknown=true;}
    }finally{
      pending--;await poll();
      if(before&&id===requestSequence&&data?.source==='simulation'){
        lastMovement={distance:Math.hypot(data.simulation.position[0]-before.position[0],data.simulation.position[1]-before.position[1])*100,
          heading:(data.simulation.yaw-before.yaw)*180/Math.PI};render();
      }
    }
  }
  for(const action of ['enable','disable','arm','stop',...directions])$('avoid-'+action).onclick=()=>act('avoidance',action);
  $('avoid-ready').onchange=()=>{if(!$('avoid-ready').checked&&(data?.avoidance?.armed||pending))act('avoidance','stop');render();};
  $('sim-apply').onclick=()=>{$('avoid-ready').checked=false;act('simulation',$('sim-scenario').value);};
  $('sim-scenario').onchange=render;
  $('avoid-connect').onclick=()=>{window.setGo2Page('state');$('connect').focus();};
  for(const action of ['start','stop'])$('record-'+action).onclick=()=>act('recording',action);
  $('replay-start').onclick=()=>act('replay','start');$('replay-stop').onclick=()=>act('replay','stop');
  $('replay-pause').onclick=()=>act('replay',data?.replay?.paused?'resume':'pause');
  function stopOnLeave(){
    if(data?.avoidance?.actuationAllowed&&(data.avoidance.armed||pending)){
      $('avoid-ready').checked=false;act('avoidance','stop');
    }
  }
  document.addEventListener('visibilitychange',()=>{if(document.hidden)stopOnLeave();});
  window.addEventListener('go2-page',event=>{if(page==='avoidance'&&event.detail!=='avoidance')stopOnLeave();page=event.detail;render();});
  $('language').addEventListener('click',()=>{translateScenarios();render();});
  $('avoidance-page').addEventListener('keydown',event=>{if(event.repeat&&(event.key==='Enter'||event.key===' '))event.preventDefault();});
  setInterval(()=>{
    if(page==='avoidance'&&!document.hidden&&!offline&&performance.now()-arrived<1100&&data?.avoidance?.armed&&$('avoid-ready').checked)
      request('/api/avoidance/heartbeat','POST').catch(()=>{});
  },250);
  async function loop(){await poll();setTimeout(loop,200);}
  translateScenarios();loop();setInterval(render,100);
})();
