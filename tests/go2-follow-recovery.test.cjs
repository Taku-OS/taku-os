// Browser-only fault injection. No network traffic reaches a robot.
const {chromium}=require('playwright');
const fs=require('node:fs/promises'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_EXECUTABLE_PATH?{executablePath:process.env.PLAYWRIGHT_EXECUTABLE_PATH}:{})});
 try{
  const page=await browser.newPage(),writes=[],errors=[];let connected=false,owner='ai',blockState=false,releaseState;
  page.on('pageerror',error=>errors.push(error.message));
  await page.route('http://follow.test/**',async route=>{
   const req=route.request(),url=new URL(req.url());
   if(url.pathname.startsWith('/api/')){
    if(req.method()==='POST'){
     writes.push(url.pathname);
     if(url.pathname==='/api/connect'){setTimeout(()=>connected=true,150);return route.fulfill({json:{phase:'connecting',connected:false,error:null}});}
     return route.fulfill({json:{owner:'ai',observationOnly:true,enabled:true,connected:true}});
    }
    if(url.pathname==='/api/assistant/state')return route.fulfill({json:{owner,observationOnly:true}});
    if(url.pathname==='/api/state'){
     if(blockState)await new Promise(r=>releaseState=r);
     return route.fulfill({json:{phase:connected?'connected':'error',connected,error:connected?null:'ConnectionError'}});
    }
    const meta=Buffer.from(JSON.stringify({source:'real',connected,enabled:connected,fresh:false,ageMs:null,frameKey:'test:1:0'}));
    const size=Buffer.alloc(4);size.writeUInt32LE(meta.length);
    return route.fulfill({body:Buffer.concat([size,meta]),contentType:'application/octet-stream'});
   }
   if(url.pathname==='/follow-vision-worker.js')return route.fulfill({body:"self.onmessage=()=>self.postMessage({type:'ready'})",contentType:'text/javascript'});
   const file=url.pathname==='/follow-demo'?'follow-demo.html':url.pathname.slice(1);
   await route.fulfill({body:await fs.readFile(path.join(__dirname,'../tooling/go2-lab/web',file)),contentType:file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html'});
  });
  await page.goto('http://follow.test/follow-demo#native');
  await page.waitForFunction(()=>window.go2FollowStart);
  await page.evaluate(()=>window.go2FollowStart('跟踪前面的人'));
  await page.waitForFunction(()=>window.go2FollowDemo.recoveryAttempts===1);
  await page.waitForTimeout(3000);
  assert.ok(writes.includes('/api/camera/start'),'Camera restarts after asynchronous connection succeeds');
  assert.equal(writes.filter(p=>p==='/api/connect').length,1,'Do not spam connection requests while connecting');
  assert.equal(await page.evaluate(()=>window.go2FollowDemo.live),false,'Stale frames must not appear live');
  await page.evaluate(()=>window.go2FollowStop());const stopped=writes.length;
  await page.waitForTimeout(2800);assert.equal(writes.length,stopped,'Stop cancels recovery');
  connected=false;blockState=true;
  await page.evaluate(()=>{window.go2FollowStart('跟踪前面的人')});
  for(let i=0;i<50&&!releaseState;i++)await page.waitForTimeout(50);
  assert.ok(releaseState);await page.evaluate(()=>window.go2FollowStop());const canceled=writes.length;
  releaseState();blockState=false;await page.waitForTimeout(1000);
  assert.equal(writes.length,canceled,'Late state replies after Stop must not connect or start the camera');
  owner='manual';await page.evaluate(()=>window.go2FollowStart('跟踪前面的人'));
  assert.equal(await page.evaluate(()=>window.go2FollowDemo.active),false,'Manual takeover is respected');
  assert.ok(writes.every(p=>['/api/assistant/observe','/api/connect','/api/camera/start'].includes(p)),'Only observation actions may be sent');
  assert.deepEqual(errors,[]);
  console.log('Recovery passed: reconnect + camera, bounded retries, no stale LIVE, stop race, manual takeover, no motion.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
