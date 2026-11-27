// Actual Android runtime, live AMap data, simulated navigation only.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {execFileSync} from 'node:child_process';
const base='http://127.0.0.1:18845';
assert.equal((await(await fetch(base+'/api/state')).json()).source,'simulation');
const quote=value=>"'"+value.replaceAll("'","'\\''")+"'";
function go2(action,value){
 const command='sh /data/local/agentos/go2-lab/go2 '+action+(value?' '+quote(JSON.stringify(value)):'');
 return JSON.parse(execFileSync(process.env.ADB || 'adb',
  ['-s',process.env.TAKU_SERIAL || 'emulator-5554','shell',command],{encoding:'utf8',timeout:40000,maxBuffer:2*1024*1024}));
}
const checks=[];
async function pageState(){
 const pages=await(await fetch('http://127.0.0.1:19245/json')).json();
 const ws=new WebSocket(pages.find(p=>p.url===base+'/').webSocketDebuggerUrl);
 await new Promise(r=>ws.addEventListener('open',r,{once:true}));
 try{return await new Promise((resolve,reject)=>{
  const timeout=setTimeout(()=>reject(Error('WebView sync timeout')),5000);
  ws.addEventListener('message',e=>{const x=JSON.parse(e.data);if(x.id===1){clearTimeout(timeout);resolve(x.result.result.value);}});
  ws.send(JSON.stringify({id:1,method:'Runtime.evaluate',params:{expression:'({geometryId:go2Amap.route?.geometryId,missionName:document.getElementById("geo-mission-name").textContent,routeId:go2Geo.data?.geo?.routeId})',returnByValue:true}}));
 });}finally{ws.close();}
}
try{
 assert.ok(go2('map-state').configured);
 const places=go2('map-search',{keyword:'北京大学',city:'北京'}).places;
 assert.ok(places.length>1);
 const route=go2('map-route',{origin:places[0].location,destination:places[1].location});
 assert.ok(route.pointCount>1);assert.equal(route.mapImage,undefined);
 checks.push('Android CLI queries live AMap and saves full GCJ-02 route without map image output');
 const prepared=go2('geo-prepare-route',{name:'Android 工具链验收',scope:'first10m'});
 assert.equal(prepared.source,'simulation');assert.ok(prepared.geo.routeId);
 const ids={mapId:prepared.mapId,routeId:prepared.geo.routeId};
 let page;
 for(let i=0;i<20;i++){
  page=await pageState();if(page.geometryId===prepared.geo.geometryId&&page.routeId===ids.routeId)break;
  await new Promise(r=>setTimeout(r,300));
 }
 assert.equal(page.geometryId,prepared.geo.geometryId);assert.equal(page.routeId,ids.routeId);
 assert.equal(page.missionName,'Android 工具链验收');
 checks.push('WebView displays the CLI mission and matching cached AMap route');
 assert.ok(go2('geo-start',ids).active);
 await new Promise(r=>setTimeout(r,500));
 assert.ok(go2('geo-heartbeat',ids).active);
 checks.push('Saved route loads on Android runtime; simulated start and heartbeat succeed');
 go2('nav-stop');const stopped=go2('geo-state');
 assert.equal(stopped.active,false);assert.equal(stopped.stopConfirmed,true);
 assert.equal(stopped.realActuationAllowed,false);
 checks.push('Android CLI stop confirmed; real motion remains disabled');
 const out='out/go2-lab/geo-verification';fs.mkdirSync(out,{recursive:true});
 fs.writeFileSync(out+'/cli-results.json',JSON.stringify({checks,routePointCount:route.pointCount,realRobotVerified:false},null,2));
 console.log(JSON.stringify({passed:checks.length,checks}));
}finally{go2('nav-stop');}
