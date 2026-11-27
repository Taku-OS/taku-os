Object.assign(messages.zh, {
 amapTab:'高德路线',amapIntro:'搜索地点，选择起点和终点，预览步行路线。',amapBoundary:'支持路线预览和模拟跟随。实机需要位置、朝向标定及避障验收。所选起点不代表狗的实时位置。',
 amapCity:'城市（可选）',amapKeyword:'地点名称',amapSearch:'搜索地点',amapWebSearch:'在高德网页搜索',amapRefresh:'检查连接',amapStart:'设为起点',amapEnd:'设为终点',amapOrigin:'起点 A',amapDestination:'终点 B',amapUnselected:'尚未选择',amapPlan:'规划步行路线',amapOpen:'在高德打开路线',amapReady:'高德服务已配置',amapKey:'尚未配置 Web 服务 Key，请在 Mac 上完成本机配置。',amapOffline:'高德连接服务不可用，请启动 Mac 上的 amap_service.py，并恢复 18846 端口连接。',
 amapLoading:'正在查询高德…',amapEmpty:'没有找到地点，请补充城市或更换关键词。',amapFound:'个地点',amapFailed:'高德查询未完成',amapMapAlt:'高德步行路线预览，A 为起点，B 为终点',amapMapMissing:'地图图片暂不可用，路线步骤仍可查看。',amapDuration:'高德预计步行时间（非机器狗用时）',amapMinutes:'分钟',amapPrivacy:'点击搜索或规划时，查询文字和所选地点坐标会发送给高德。',amapPreview:'高德地图 · 路线总览',amapConfigTitle:'Key 配置说明',amapConfig:'在 Mac 终端运行 amap_service.py configure，输入个人 Web 服务 Key。Key 仅保存在 Mac 私有目录，不发送给 Phone。',amapInput:'请先输入地点名称。',amapSame:'请选两个不同的地点。',amapCoord:'坐标：GCJ-02 · 经度,纬度'
});
Object.assign(messages.en, {
 amapTab:'AMap routes',amapIntro:'Search places, choose a start and destination, then preview a walking route.',amapBoundary:'Preview routes and follow them in simulation. Real motion requires pose calibration and avoidance validation. The selected start is not live robot location.',
 amapCity:'City (optional)',amapKeyword:'Place name',amapSearch:'Search places',amapWebSearch:'Search on AMap website',amapRefresh:'Check connection',amapStart:'Set as start',amapEnd:'Set as destination',amapOrigin:'Start A',amapDestination:'Destination B',amapUnselected:'Not selected',amapPlan:'Plan walking route',amapOpen:'Open route on AMap',amapReady:'AMap service configured',amapKey:'Configure a personal Web Service key on the Mac.',amapOffline:'AMap service unavailable. Start amap_service.py on the Mac and restore the port 18846 tunnel.',
 amapLoading:'Querying AMap…',amapEmpty:'No places found. Add a city or try another keyword.',amapFound:'places',amapFailed:'AMap request failed',amapMapAlt:'AMap walking route overview, from A to B',amapMapMissing:'Map image unavailable. Route steps are still available.',amapDuration:'AMap walking estimate (not robot travel time)',amapMinutes:'min',amapPrivacy:'Searching or planning sends the query and selected coordinates to AMap.',amapPreview:'AMap · Route overview',amapConfigTitle:'Key setup',amapConfig:'Run amap_service.py configure on the Mac and enter your personal Web Service key. It stays in a private Mac directory and is not sent to the Phone.',amapInput:'Enter a place name first.',amapSame:'Choose two different places.',amapCoord:'Coordinates: GCJ-02 · longitude,latitude'
});
(() => {
 const base='http://127.0.0.1:18846';
 const moving=()=>Boolean(window.go2Navigation?.data?.active);
 let selectionVersion=0,picking=false,wasMoving=false,seenMission=null;
 const changed=()=>{selectionVersion++;window.dispatchEvent(new Event('go2-amap-route'));};
 window.go2Amap={get route(){return route;},get origin(){return origin;},get destination(){return destination;},get busy(){return busy;}};
 let ready=false,busy=false,places=[],origin=null,destination=null,route=null,status='amapOffline',code='',lastLang=lang;
 async function api(path,body){
  const response=await fetch(base+path,{method:body?'POST':'GET',cache:'no-store',signal:AbortSignal.timeout(85000),...(body?{headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}:{})});
  const value=await response.json();if(!response.ok)throw Error(value.error||'HTTP_'+response.status);return value;
 }
 function render(){
  $('amap-status').textContent=t(status)+(code?` (${code})`:'');
  for(const id of ['amap-search','amap-plan'])$(id).disabled=moving()||busy||!ready||(id==='amap-plan'&&(!origin||!destination||origin.location===destination.location));
  $('amap-refresh').disabled=busy;
  $('amap-web-search').disabled=busy||!$('amap-keyword').value.trim();
  $('amap-origin').textContent=origin?`${origin.name} · ${origin.location}`:t('amapUnselected');
  $('amap-destination').textContent=destination?`${destination.name} · ${destination.location}`:t('amapUnselected');
  $('amap-results').replaceChildren();
  for(const place of places){
   const row=document.createElement('li'),name=document.createElement('strong'),address=document.createElement('p'),actions=document.createElement('div');
   name.textContent=place.name;address.textContent=place.address;actions.className='actions';row.append(name,address,actions);
   for(const [key,side] of [['amapStart','origin'],['amapEnd','destination']]){
    const button=document.createElement('button');button.textContent=t(key);button.className='secondary';button.disabled=busy||moving();
    button.onclick=()=>{if(moving())return;if(side==='origin')origin=place;else destination=place;route=null;picking=false;changed();render();};actions.append(button);
   }
   $('amap-results').append(row);
  }
  $('amap-route').hidden=!route;
  $('amap-pick').disabled=moving()||busy||!route?.mapViewport||!route?.mapImage;
  $('amap-pick').textContent=t(picking?'geoPicking':'geoPick');
  $('amap-steps').replaceChildren();
  if(route){
   $('amap-summary').textContent=`${route.distance.toFixed(0)} m · ${t('amapDuration')} ${Math.ceil(route.duration/60)} ${t('amapMinutes')}`;
   const img=$('amap-map');img.hidden=!route.mapImage;
   if(route.mapImage)img.src=route.mapImage;else img.removeAttribute('src');
   $('amap-map-error').textContent=route.mapImage?'':t('amapMapMissing');
   for(const step of route.steps){const li=document.createElement('li');li.textContent=`${step.instruction} · ${step.distance} m`;$('amap-steps').append(li);}
  }
 }
 async function check(){
  if(busy)return;busy=true;status='amapLoading';code='';render();
  try{const value=await api('/status');ready=value.configured;status=ready?'amapReady':'amapKey';code=value.error||'';
   const latest=await api('/mcp/latest');
   if(latest.kind==='search'){places=latest.value.places;route=null;}
   if(latest.kind==='route'){route=latest.value;origin={name:t('amapOrigin'),location:route.origin};destination={name:t('amapDestination'),location:route.destination};changed();}
  }
  catch{ready=false;status='amapOffline';}finally{busy=false;render();}
 }
 async function run(path,body){
  if(busy||moving())return;busy=true;picking=false;code='';status='amapLoading';if(path==='/route'){route=null;changed();}else places=[];render();
  try{const result=await api('/mcp'+path,path==='/route'?{...body,city:$('amap-city').value.trim()}:body);if(path==='/search'){places=result.places;status=places.length?'amapReady':'amapEmpty';}else{route=result;status='amapReady';changed();}}
  catch(e){status='amapFailed';code=/^[A-Z_0-9]+$/.test(e.message)?e.message:'';}
  finally{busy=false;render();}
 }
 async function syncMission(g){
  if(!g?.routeId){seenMission=null;return;}
  if(!g.geometryId||seenMission===g.routeId||busy)return;
  seenMission=g.routeId;
  if(route?.geometryId===g.geometryId)return;
  const version=selectionVersion;
  try{
   const preview=await api('/route/'+g.geometryId);
   if(window.go2Navigation?.data?.geo?.routeId!==g.routeId||busy||version!==selectionVersion)return;
   route=preview;origin={name:t('amapOrigin'),location:preview.origin};destination={name:g.name,location:preview.destination};
   picking=false;changed();render();
  }catch{} // A missing preview never substitutes a different route; local task remains visible.
 }
 $('amap-search-form').onsubmit=e=>{e.preventDefault();const keyword=$('amap-keyword').value.trim();if(!ready||busy||moving())return;if(!keyword){status='amapInput';render();return;}run('/search',{keyword,city:$('amap-city').value.trim()});};
 $('amap-plan').onclick=()=>{if(!ready||!origin||!destination||busy||moving())return;run('/route',{origin:origin.location,destination:destination.location});};
 $('amap-pick').onclick=()=>{picking=!picking;render();};
 $('amap-map').onclick=e=>{
  if(!picking||moving()||!route?.mapViewport)return;
  const r=e.target.getBoundingClientRect(),v=route.mapViewport;
  const p=go2GeoProjection.coordinate([(e.clientX-r.left)/r.width*v.width,(e.clientY-r.top)/r.height*v.height],v);
  destination={name:t('geoPicked'),location:p.map(x=>x.toFixed(6)).join(',')};route=null;picking=false;changed();render();
  $('amap-plan').scrollIntoView({block:'center'});
 };
 $('amap-open').onclick=()=>{if(route)location.href=route.url;};
 $('amap-web-search').onclick=()=>{const keyword=$('amap-keyword').value.trim();if(!keyword)return;location.href='https://uri.amap.com/search?'+new URLSearchParams({keyword,city:$('amap-city').value.trim(),view:'map',src:'taku-go2',callnative:'0'});};
 $('amap-keyword').oninput=()=>{$('amap-web-search').disabled=busy||!$('amap-keyword').value.trim();};
 $('amap-refresh').onclick=check;
 window.addEventListener('go2-page',e=>{if(e.detail==='amap')check();});
 window.addEventListener('go2-navigation',e=>{if(wasMoving!==moving()){wasMoving=moving();render();}syncMission(e.detail.geo);});
 window.addEventListener('go2-state',()=>{if(lastLang!==lang){lastLang=lang;render();}});
 translate();render();
})();
