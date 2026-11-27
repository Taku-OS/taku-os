Object.assign(messages.zh,{
 geoInstructions:'查看高德分步指引',geoPick:'在图上选择终点',geoPicking:'请点击地图上的目的地',geoPicked:'地图选点',geoTitle:'让模拟狗沿这条路线走',geoSimulation:'使用真实高德路线和合成平地、纸箱、雷达及摄像头。环境不代表该地点的真实路况；不会连接真狗。',geoScope:'验证范围',geoFirst:'先验证前 10 米',geoFull:'全程模拟（低速，可能耗时很长）',geoPrepare:'载入模拟路线',geoStart:'开始模拟跟随',geoStop:'停止路线跟随',
 geoMonitor:'定位与跟随状态',geoProgress:'路线进度',geoLocalMap:'随狗移动的局部雷达地图与跟随路径',geoLocalNote:'绿色为合成已观测平地，深色为障碍，橙线为局部避障路径。地图随模拟狗移动。',geoCalibration:'实机／人工位置与朝向标定',geoCalibrationNote:'在已知位置输入高德坐标及狗当前的朝向。朝向以北为 0°、东为 90°。只有里程计时，结果是会漂移的估计位置；人工标定不等于 GPS 定位，也不会解锁实机运动。',geoUseOrigin:'填入所选起点坐标',geoLongitude:'经度（GCJ-02）',geoLatitude:'纬度（GCJ-02）',geoHeading:'狗面朝的方向（0–360°）',geoCalibrate:'标定当前位置与朝向',geoUncalibrated:'尚未标定位置与朝向',geoPreparing:'正在处理…',geoSimPosition:'模拟位置',geoEstimated:'人工标定＋里程计估计',geoHeadingLabel:'朝向',geoError:'误差估计（非精度保证）',geoRemaining:'剩余',geoScopeDone:'首段模拟已完成并停止；尚未走完全程',geoDone:'全程模拟已到达并停止',geoIdle:'路线已载入，等待开始',geoNoRoute:'选择高德路线后可载入模拟',geoOffline:'机器人服务不可用，无法确认是否停止',geoRealBlocked:'实机尚缺：可靠绝对位置、雷达／里程计对齐、避障控制实测。',geoFullDistance:'完整路线折线长度',geoThisDistance:'本次验证'
});
Object.assign(messages.en,{
 geoInstructions:'AMap step-by-step directions',geoPick:'Choose destination on map',geoPicking:'Tap the destination on the map',geoPicked:'Map selection',geoTitle:'Follow this route in simulation',geoSimulation:'Real AMap route with synthetic flat ground, box, LiDAR and camera. The simulated scene is not real terrain at this location. No real robot connection.',geoScope:'Scope',geoFirst:'First 10 meters',geoFull:'Full route (slow; may take a long time)',geoPrepare:'Load simulated route',geoStart:'Start simulated following',geoStop:'Stop route following',
 geoMonitor:'Localization and following',geoProgress:'Route progress',geoLocalMap:'Rolling local LiDAR map and following path',geoLocalNote:'Green: synthetic observed floor. Dark: obstacles. Orange: local avoidance path. The map follows the simulated robot.',geoCalibration:'Manual position and heading calibration',geoCalibrationNote:'At a known location, enter GCJ-02 coordinates and the robot heading: north 0°, east 90°. Odometry-only position estimates drift. Calibration is not a GPS fix and does not unlock real motion.',geoUseOrigin:'Use selected start coordinates',geoLongitude:'Longitude (GCJ-02)',geoLatitude:'Latitude (GCJ-02)',geoHeading:'Robot heading (0–360°)',geoCalibrate:'Calibrate current pose',geoUncalibrated:'Position and heading not calibrated',geoPreparing:'Processing…',geoSimPosition:'Simulated position',geoEstimated:'Manual reference + odometry estimate',geoHeadingLabel:'Heading',geoError:'Assumed uncertainty',geoRemaining:'Remaining',geoScopeDone:'Prefix simulation completed and stopped; full route not completed',geoDone:'Full simulation arrived and stopped',geoIdle:'Route loaded; awaiting start',geoNoRoute:'Choose an AMap route to load a simulation',geoOffline:'Robot service unavailable; stop cannot be confirmed',geoRealBlocked:'Real motion still needs reliable absolute location, LiDAR/odom alignment and avoidance validation.',geoFullDistance:'Full route geometry',geoThisDistance:'This test'
});
Object.assign(navReasons,{
 GEO_CALIBRATION_REQUIRED:['需要标定地理位置和朝向','Geographic reference required'],GEO_REFERENCE_CHANGED:['连接或地图已变化，请重新标定','Connection/map changed; recalibrate'],GEO_FRAME_CHANGED:['里程计坐标系变化，请重新标定','Odometry frame changed; recalibrate'],ODOMETRY_DISCONTINUITY:['位置或朝向突跳，标定已失效','Pose discontinuity; reference invalidated'],GEO_UNCERTAINTY_HIGH:['估计定位误差过大','Estimated location uncertainty too high'],SUPERVISION_LOST:['操作界面或监控心跳中断，已停止','Supervisor heartbeat lost; stopped'],ROUTE_DEVIATION:['偏离路线过远，已停止','Route deviation; stopped'],GEO_LOCAL_PATH_BLOCKED:['当前雷达范围内没有可行通路','No traversable local path'],GEO_ROUTE_CHANGED:['路线已改变，请重新载入','Route changed; load it again'],GEO_START_MISMATCH:['当前位置不在路线起点附近，请重新载入模拟','Not near the start; reload simulation'],REAL_NAVIGATION_DISABLED:['实机自动跟随尚未通过验收','Real navigation not validated'],CANCELLED:['操作已取消','Cancelled']
});
(() => {
 let data=null,received=0,pending=false,error=null,page=document.body.dataset.page,revision=0,loaded=-1,generation=0;
 const canvas=$('geo-local-map'),ctx=canvas.getContext('2d');
 const fresh=()=>data&&performance.now()-received<2000;
 const route=()=>window.go2Amap?.route;
 const reason=code=>navReasons[code]?.[lang==='zh'?0:1]||code||'';
 function render(){
  const g=data?.geo,loc=g?.localization,active=data?.active,online=fresh();
  window.go2Geo={data,online,pending,loadedRevision:loaded,revision};
  $('amap-page').classList.toggle('geo-active',Boolean(active&&g?.routeId));
  $('geo-prepare').disabled=!online||pending||active||data.source!=='simulation'||!route()||window.go2Amap?.busy;
  const matching=Boolean(g?.routeId&&g.geometryId===route()?.geometryId);
  $('geo-start').disabled=!online||pending||active||!matching||data.source!=='simulation'||data.readiness;
  $('geo-mission-name').textContent=g?.routeId?g.name:'';
  // A tool may prepare a different mission. Never overlay its robot on an old route image.
  $('amap-map').parentElement.hidden=Boolean(g?.active&&!matching);
  $('geo-scope').disabled=pending||active;
  $('geo-stop').disabled=!online;
  $('geo-calibrate').disabled=!online||pending||active||!data.pose;
  const status=!online?'geoOffline':pending?'geoPreparing':data.source!=='simulation'?'geoRealBlocked':!g?.routeId?'geoNoRoute':data.state==='arrived'?(g.scope==='first10m'&&g.fullLength>g.length+.1?'geoScopeDone':'geoDone'):data.state==='idle'?'geoIdle':'nav_'+data.state;
  $('geo-status').textContent=t(status)+(error||data?.reason?' · '+reason(error||data.reason):'');
  $('geo-location').textContent=loc?.valid?`${t(data.source==='simulation'?'geoSimPosition':'geoEstimated')} · ${loc.position.map(v=>v.toFixed(6)).join(', ')} · ${t('geoHeadingLabel')} ${loc.heading.toFixed(1)}° · ${t('geoError')} ${loc.assumedErrorMeters.toFixed(2)} m`:reason(loc?.reason)||t('geoUncalibrated');
  $('geo-progress').textContent=g?.routeId?`${t('geoThisDistance')} ${g.progress.toFixed(2)} / ${g.length.toFixed(2)} m · ${t('geoFullDistance')} ${g.fullLength.toFixed(1)} m`:'';
  $('geo-progress-bar').max=g?.length||1;$('geo-progress-bar').value=g?.progress||0;
  const marker=$('geo-robot-marker'),viewport=route()?.mapViewport;
  marker.hidden=true;
  if(loc?.valid&&viewport&&route()?.mapImage&&(!g?.routeId||matching)){
   const [x,y]=go2GeoProjection.pixel(loc.position,viewport);
   if(x>=0&&y>=0&&x<=viewport.width&&y<=viewport.height){marker.hidden=false;marker.style.left=`${x/viewport.width*100}%`;marker.style.top=`${y/viewport.height*100}%`;marker.style.transform=`translate(-50%,-50%) rotate(${loc.heading-90}deg)`;}
  }
  ctx.fillStyle='#e5e6e1';ctx.fillRect(0,0,600,600);
  const center=data?.pose?.position||[0,0],xy=p=>[300+(p[0]-center[0])*90,300-(p[1]-center[1])*90];
  for(const [points,color] of [[data?.map?.free,'#c3d7ba'],[data?.map?.obstacles,'#5a645c']]){ctx.fillStyle=color;for(const p of points||[]){const [x,y]=xy(p);ctx.fillRect(x-4.5,y-4.5,9,9);}}
  for(const [points,color,width] of [[g?.route,'#96a7c5',2],[data?.route,'#ab6e28',4]]){ctx.beginPath();(points||[]).forEach((p,i)=>ctx[i?'lineTo':'moveTo'](...xy(p)));ctx.strokeStyle=color;ctx.lineWidth=width;ctx.stroke();}
  if(data?.pose){ctx.save();ctx.translate(300,300);ctx.rotate(-data.pose.yaw);ctx.beginPath();ctx.moveTo(15,0);ctx.lineTo(-9,8);ctx.lineTo(-6,0);ctx.lineTo(-9,-8);ctx.closePath();ctx.fillStyle='#203e32';ctx.fill();ctx.restore();}
 }
 async function command(action,value={}){
  if(pending&&action!=='stop')return;
  const token=++generation,requestRevision=revision;pending=true;error=null;render();
  try{
   const response=await fetch('/api/navigation/'+action,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(value),signal:AbortSignal.timeout(12000)});
   const result=await response.json();if(token!==generation)return;
   if(!response.ok)error=result.requestError||result.error;else if(action==='geo-prepare')loaded=requestRevision;else if(action==='geo-calibrate')loaded=-1;
   if(result.mapId)window.updateGo2Navigation(result);
  }catch{if(token===generation)error='REQUEST_FAILED';}
  finally{if(token===generation){pending=false;render();}}
 }
 $('geo-prepare').onclick=()=>{
  if(!route())return;
  const points=route().steps.flatMap(s=>s.polyline.map(p=>p.split(',').map(Number)));
  command('geo-prepare',{points,name:(window.go2Amap.destination?.name||t('geoPicked')).slice(0,80),scope:$('geo-scope').value});
 };
 $('geo-start').onclick=()=>{if(data?.geo?.routeId)command('geo-start',{mapId:data.mapId,routeId:data.geo.routeId});};
 $('geo-stop').onclick=()=>command('stop');
 $('geo-use-origin').onclick=()=>{const p=window.go2Amap?.origin?.location?.split(',');if(p){$('geo-longitude').value=p[0];$('geo-latitude').value=p[1];}};
 $('geo-calibrate').onclick=()=>{
  if(['geo-longitude','geo-latitude','geo-heading'].some(id=>!$(id).value||!$(id).checkValidity())){error='INVALID_CALIBRATION';render();return;}
  command('geo-calibrate',{mapId:data.mapId,longitude:Number($('geo-longitude').value),latitude:Number($('geo-latitude').value),heading:Number($('geo-heading').value)});
 };
 window.addEventListener('go2-navigation',e=>{data=e.detail;received=performance.now();render();});
 window.addEventListener('go2-amap-route',()=>{revision++;render();});
 window.addEventListener('go2-page',e=>{page=e.detail;if(page!=='amap'&&data?.geo?.active)command('stop');render();});
 document.addEventListener('visibilitychange',()=>{if(document.hidden&&data?.geo?.active)command('stop');});
 $('language').addEventListener('click',render);
 async function heartbeat(){
  if(data?.geo?.active&&page==='amap'&&!document.hidden&&fresh()){
   try{await fetch('/api/navigation/geo-heartbeat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({mapId:data.mapId,routeId:data.geo.routeId}),signal:AbortSignal.timeout(2000)});}catch{}
  }
  setTimeout(heartbeat,600);
 }
 translate();render();heartbeat();setInterval(render,500);
})();
