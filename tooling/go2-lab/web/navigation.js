Object.assign(messages.zh,{
 navTab:'自主任务',navScope:'模拟导航原型',navIntro:'在地图上选一个地点，或让 Taku 根据画面寻找目标。任务期间持续查看路径、位置和摄像头。',
 navPrepare:'准备模拟房间',navMapLabel:'导航地图；点击绿色区域选择目标，也可在下方输入坐标',navLegend:'浅绿：预留机身空间后的可行区域 · 深色：障碍 · 灰色：不可通行或未知 · 橙线：路线 · 箭头：狗的位置和朝向',
 navName:'地点名称',navSave:'保存地点',navGo:'前往所选位置',navPlaces:'已保存的地点',navNamed:'前往这个地点',navStop:'停止自主任务',navPick:'点击地图选择位置',navNoPlaces:'尚未保存地点',
 navAiTitle:'让 Taku 决定怎么去',navAiNote:'准备好模拟房间后，在 Taku 对话中说“让模拟狗去接待点”，或“看狗的摄像头，去门口附近”。AI 会读取同一个任务状态、观察画面并提交目标；这里会同步显示执行过程。',
 navBoundary:'当前仅执行模拟导航。实机还需验证定位与雷达坐标对齐、摄像头标定和避障控制；地点仅保留在本次模拟地图中。AI 图像分析可能使用当前配置的模型服务。',
 navReal:'实机自主行走尚未开放：定位对齐、视觉标定和避障控制还未完成实测。',navOffline:'连接服务不可用，无法确认任务是否停止；恢复连接后检查任务状态。',navFailed:'请求被拒绝',navEmpty:'先准备模拟房间，再选择目标',navRoute:'路径点',navSteps:'控制更新',navDistance:'距目标',
 nav_idle:'等待目标',nav_planning:'正在规划',nav_running:'自主任务执行中',nav_arrived:'已到达并停止',nav_cancelled:'任务已停止',nav_blocked:'已暂停：需要处理阻碍',nav_failed:'任务失败',nav_scan_complete:'观察转向完成',navReady:'可以设置目标'
});
Object.assign(messages.en,{
 navTab:'Navigate',navScope:'SIMULATION PROTOTYPE',navIntro:'Choose a map location or ask Taku to find a visual target. Watch the route, pose and camera during the task.',
 navPrepare:'Prepare simulated room',navMapLabel:'Navigation map. Select a green area or enter coordinates below.',navLegend:'Green: observed traversable floor with body clearance · Dark: obstacles · Gray: blocked or unknown · Orange: route · Arrow: robot pose',
 navName:'Place name',navSave:'Save place',navGo:'Go to selection',navPlaces:'Saved places',navNamed:'Go to this place',navStop:'Stop navigation',navPick:'Select a map position',navNoPlaces:'No saved places',
 navAiTitle:'Let Taku plan the trip',navAiNote:'Prepare the simulated room, then ask Taku: “Take the simulated dog to reception” or “Look through its camera and go near the doorway.” The AI observes images and submits a goal to this same task controller; progress appears here.',
 navBoundary:'Simulation only. Real navigation requires validated pose/LiDAR alignment, camera calibration and avoidance control. Places last for this map session. AI image analysis may use the configured model service.',
 navReal:'Real autonomous motion is unavailable: pose alignment, camera calibration and avoidance control need hardware validation.',navOffline:'Bridge unavailable. Stop cannot be confirmed; check the task after reconnecting.',navFailed:'Request rejected',navEmpty:'Prepare the simulated room before selecting a goal',navRoute:'Waypoints',navSteps:'Control updates',navDistance:'To goal',
 nav_idle:'Awaiting goal',nav_planning:'Planning',nav_running:'Navigating',nav_arrived:'Arrived and stopped',nav_cancelled:'Stopped',nav_blocked:'Blocked',nav_failed:'Failed',nav_scan_complete:'Observation turn completed',navReady:'Ready for a goal'
});
const navReasons={
 CAMERA_STALE:['摄像头画面过期或未开启','Camera missing or stale'],CLOUD_STALE:['雷达数据过期','LiDAR stale'],POSE_STALE:['位置数据过期','Pose stale'],STATE_NOT_READY:['机器狗状态尚未就绪','Robot state not ready'],NOT_CONNECTED:['尚未连接','Not connected'],
 CONTROL_BUSY:['其他控制正在执行，请先停止','Another controller owns movement'],STOP_UNCONFIRMED:['未确认停止，保持锁定；请重试停止','Stop unconfirmed; locked. Retry stop'],
 MAP_CHANGED:['地图已改变，请重新选点','Map changed; select again'],TARGET_UNKNOWN_OR_BLOCKED:['目标处不可通行或尚未观测','Target blocked or unknown'],START_BLOCKED:['当前位置没有足够空间','Insufficient clearance at current pose'],
 NO_ROUTE:['暂时没有可通行的路线','No traversable route'],NO_APPROACH_ROUTE:['找不到目标附近的可达位置','No reachable approach position'],PATH_BLOCKED:['行走路径被阻挡','Path blocked'],NO_PROGRESS:['位置没有继续变化，已停止','No progress; stopped'],
 USER_STOP:['用户停止','Stopped by user'],MISSION_TIMEOUT:['任务超时，已停止','Mission timed out'],OBSERVATION_EXPIRED:['观察画面已失效，请重新观察','Observation expired'],OBSERVATION_MOVED:['拍摄后位置变化，请重新观察','Pose changed since observation'],
 TARGET_GROUND_POINT_REQUIRED:['需要目标与地面接触的位置','Target ground contact required'],NATIVE_NOT_ENABLED:['模拟避障状态不正确','Simulated avoidance unavailable'],CANCELLED:['请求已取消','Request cancelled'],EMPTY_CLOUD:['没有可用雷达点','No LiDAR points'],TIME_SKEW:['传感器时间不一致','Sensor timing mismatch'],FRAME_UNVERIFIED:['坐标系未对齐','Coordinate frames unverified']
};
translate();
(() => {
 const canvas=$('nav-map'),ctx=canvas.getContext('2d');
 let data=null,online=false,pending=false,selected=null,mapId=null,error=null,generation=0,received=0;
 const center=()=>data?.goal?.kind==='geographic'&&data?.pose?data.pose.position:[0,0];
 const xy=p=>[300+(p[0]-center()[0])*90,300-(p[1]-center()[1])*90];
 const reason=code=>navReasons[code]?.[lang==='zh'?0:1]||code||'';
 function render(){
  const fresh=online&&performance.now()-received<2000,sim=data?.source==='simulation';
  window.go2Navigation={data,online:fresh,pending,selected};
  const ready=fresh&&sim&&!data.active&&!data.readiness&&!pending;
  $('nav-status').textContent=!fresh?t('navOffline'):!sim?t('navReal'):t('nav_'+data.state)||data.state;
  $('nav-reason').textContent=error?t('navFailed')+' · '+reason(error):reason(data?.reason||data?.readiness);
  if(!sim&&fresh)$('nav-reason').textContent='';
  $('navigation-page').classList.toggle('has-map',Boolean(data?.map));
  $('nav-prepare').hidden=!sim;$('nav-prepare').disabled=!fresh||pending||data?.active;
  $('nav-save').disabled=$('nav-go').disabled=!ready||!selected||!$('nav-name').value.trim();
  $('nav-named').disabled=!ready||!$('nav-places').value;
  $('nav-name').disabled=$('nav-x').disabled=$('nav-y').disabled=!ready;
  $('nav-stop').disabled=!fresh;
  $('nav-selection').textContent=selected?`X ${selected[0].toFixed(2)} m · Y ${selected[1].toFixed(2)} m`:t('navPick');
  const distance=data?.goal&&data.pose?Math.hypot(data.goal.position[0]-data.pose.position[0],data.goal.position[1]-data.pose.position[1]):null;
  $('nav-progress').textContent=data?.map?`${data.goal?.name||t('navReady')} · ${t('navRoute')} ${data.route.length} · ${t('navSteps')} ${data.steps}`+(distance==null?'':` · ${t('navDistance')} ${distance.toFixed(2)} m`):t('navEmpty');
  const names=Object.keys(data?.places||{}),select=$('nav-places'),old=select.value;
  if(select.dataset.names!==JSON.stringify(names)||select.dataset.lang!==lang){
   select.replaceChildren();for(const name of names.length?names:['']){const option=document.createElement('option');option.value=name;option.textContent=name||t('navNoPlaces');select.append(option);}
   if(names.includes(old))select.value=old;select.dataset.names=JSON.stringify(names);select.dataset.lang=lang;
  }
  ctx.fillStyle='#e5e6e1';ctx.fillRect(0,0,600,600);
  const square=(p,color,size)=>{const [x,y]=xy(p);ctx.fillStyle=color;ctx.fillRect(x-size/2,y-size/2,size,size);};
  for(const p of data?.map?.free||[])square(p,'#c3d7ba',9);
  for(const p of data?.map?.obstacles||[])square(p,'#5a645c',9);
  if(data?.route?.length){ctx.beginPath();data.route.forEach((p,i)=>ctx[i?'lineTo':'moveTo'](...xy(p)));ctx.strokeStyle='#ab6e28';ctx.lineWidth=4;ctx.stroke();}
  if(selected)square(selected,'#d18b2c',12);
  if(data?.goal)square(data.goal.position,'#ac642a',13);
  if(data?.pose){const [x,y]=xy(data.pose.position);ctx.save();ctx.translate(x,y);ctx.rotate(-data.pose.yaw);ctx.beginPath();ctx.moveTo(15,0);ctx.lineTo(-9,8);ctx.lineTo(-6,0);ctx.lineTo(-9,-8);ctx.closePath();ctx.fillStyle='#203e32';ctx.fill();ctx.restore();}
 }
 function update(next){if(mapId!==next.mapId){selected=null;mapId=next.mapId;$('nav-x').value=$('nav-y').value='';}data=next;online=true;received=performance.now();render();window.dispatchEvent(new CustomEvent('go2-navigation',{detail:next}));}
 async function poll(){const token=generation;try{const next=await request('/api/navigation');if(token===generation)update(next);}catch{online=false;render();}setTimeout(poll,400);}
 async function command(action,value={}){
  if(pending&&action!=='stop')return;
  const token=++generation;pending=true;error=null;render();
  try{const response=await fetch('/api/navigation/'+action,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(value),signal:AbortSignal.timeout(12000)});const result=await response.json();if(token!==generation)return;if(!response.ok)error=result.requestError||result.error||'REQUEST_FAILED';if(result.mapId)update(result);}
  catch{if(token===generation){online=false;error='REQUEST_FAILED';}}
  finally{if(token===generation){pending=false;render();}}
 }
 const selection=()=>({mapId:data.mapId,name:$('nav-name').value.trim(),x:selected[0],y:selected[1]});
  canvas.onclick=event=>{if(!data?.map||data.active||pending||data.source!=='simulation')return;const r=canvas.getBoundingClientRect(),c=center();selected=[c[0]+((event.clientX-r.left)/r.width*600-300)/90,c[1]+(300-(event.clientY-r.top)/r.height*600)/90].map(v=>Math.round(v*10)/10);$('nav-x').value=selected[0];$('nav-y').value=selected[1];render();};
 for(const id of ['nav-x','nav-y'])$(id).oninput=()=>{selected=[$('nav-x').value,$('nav-y').value].every(v=>v!==''&&Number.isFinite(Number(v)))?[Number($('nav-x').value),Number($('nav-y').value)]:null;render();};
 $('nav-name').oninput=render;$('nav-places').onchange=render;
 window.updateGo2Navigation=update;
 $('nav-prepare').onclick=()=>command('prepare');$('nav-stop').onclick=()=>command('stop');
 $('nav-save').onclick=()=>command('place',selection());$('nav-go').onclick=()=>command('goal',selection());
 $('nav-named').onclick=()=>command('goal',{mapId:data.mapId,name:$('nav-places').value});
 $('language').addEventListener('click',render);setInterval(render,500);render();poll();
})();
