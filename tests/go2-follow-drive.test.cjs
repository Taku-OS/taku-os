// UI integration fixtures only. No request can reach a physical robot.
const {chromium}=require('playwright');
const fs=require('node:fs/promises'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_EXECUTABLE_PATH?{executablePath:process.env.PLAYWRIGHT_EXECUTABLE_PATH}:{})});
 try{
  const page=await browser.newPage(),writes=[],errors=[],samples=[];
  let seq=0,ratio=1,x=.5,person=true,connected=true,serverActive=false,owner='ai';
  const jpeg=require('./helpers/go2-image-fixture.cjs');
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('http://drive.test/**',async route=>{
   const req=route.request(),url=new URL(req.url());
   const state=()=>({active:serverActive,state:serverActive?'holding':'stopped',action:'hold',error:null});
   if(url.pathname.startsWith('/api/')){
    if(req.method()==='POST'){
     writes.push(url.pathname);const body=req.postDataJSON();
     if(url.pathname==='/api/follow/start'){assert.equal(body.requestId,'request-1');serverActive=true;return route.fulfill({json:{...state(),token:'session-1'}});}
     if(url.pathname==='/api/follow/observe'){assert.equal(body.token,'session-1');samples.push(body);return route.fulfill({json:state()});}
     if(url.pathname==='/api/follow/stop')serverActive=false;
     return route.fulfill({json:state()});
    }
    if(url.pathname==='/api/assistant/state')return route.fulfill({json:{owner,observationOnly:!serverActive,follow:{...state(),requestId:'request-1'}}});
    if(url.pathname==='/api/state')return route.fulfill({json:{connected,phase:connected?'connected':'disconnected'}});
    const meta=Buffer.from(JSON.stringify({source:'real',connected,enabled:true,fresh:connected,ageMs:0,frameKey:'fixture:'+ ++seq}));
    const size=Buffer.alloc(4);size.writeUInt32LE(meta.length);
    return route.fulfill({body:Buffer.concat([size,meta,connected?jpeg:Buffer.alloc(0)]),contentType:'application/octet-stream'});
   }
   if(url.pathname==='/follow-vision-worker.js')return route.fulfill({body:`self.onmessage=async ({data})=>{if(data.type==='init'){self.postMessage({type:'ready'});return;}data.image.close();const p=await(await fetch('/fixture-person')).json();self.postMessage({type:'result',id:data.id,elapsedMs:10,people:p,landmarks:[]});}`,contentType:'text/javascript'});
   if(url.pathname==='/fixture-person')return route.fulfill({json:person?[{x:x-.1,y:.1,w:.2,h:.7,anchor:[x,.5],scale:.2/ratio,measurements:{body:.2/ratio},appearance:{upper:[1,0]},match:true}]:[]});
   const file=url.pathname==='/follow-demo'?'follow-demo.html':url.pathname.slice(1);
   await route.fulfill({body:await fs.readFile(path.join(__dirname,'../tooling/go2-lab/web',file)),contentType:file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html'});
  });
  await page.goto('http://drive.test/follow-demo#native');
  await page.waitForFunction(()=>window.go2FollowDemo?.modelState==='ready');
  await page.evaluate(()=>window.go2FollowStart('跟随前面的人，保持初始距离',true));
  await page.waitForFunction(()=>window.go2FollowDemo?.control?.active);
  await page.waitForTimeout(1200);
  assert.ok(samples.some(s=>s.target?.ratio===1));
  ratio=1.6;await page.waitForTimeout(1500);
  assert.ok(samples.some(s=>s.target?.ratio>1.4));
  person=false;await page.waitForTimeout(800);
  assert.equal(samples.at(-1).target,null,'Lost target must be sent as null, never stale coordinates');
  person=true;ratio=1;x=.58;await page.waitForTimeout(1300);
  assert.ok(samples.at(-1).target,'Reacquisition resumes fresh observations');
  await page.click('#auto-stop');await page.waitForTimeout(300);
  assert.equal(serverActive,false);assert.equal(await page.evaluate(()=>window.go2FollowDemo.active),false);
  assert.ok(!await page.locator('#intent-card').count(),'Removed guidance card stays removed');
  assert.ok(writes.every(p=>['/api/follow/start','/api/follow/observe','/api/follow/stop','/api/camera/start'].includes(p)),'No raw robot or manual motion requests from the page');
  assert.deepEqual(errors,[]);
  console.log(JSON.stringify({fixtureOnly:true,checks:['AI page start','initial distance','distance changes','target loss','reacquisition','stop','no raw motion'],samples:samples.length}));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
