// Run against a local, explicitly supplied camera fixture. Never connects to a robot.
const {chromium}=require('playwright');
const http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../../tooling/go2-lab/web');
(async()=>{
 const fixture=process.argv[2];if(!fixture)throw Error('Pass a camera image fixture');
 const writes=[];let sequence=0,offline=false;
 const server=http.createServer((req,res)=>{
  if(req.method==='POST'){writes.push(req.url);res.setHeader('Content-Type','application/json');res.end('{}');return;}
  if(req.url==='/api/assistant/state'){res.setHeader('Content-Type','application/json');res.end('{"owner":"ai","observationOnly":true}');return;}
  if(req.url==='/api/state'){res.setHeader('Content-Type','application/json');res.end('{"connected":true}');return;}
  if(req.url.startsWith('/api/camera/frame')){
   const meta=Buffer.from(JSON.stringify({fresh:!offline,connected:true,enabled:true,source:'real',ageMs:0,width:974,height:548,fps:8,frameKey:'fixture:one:'+sequence++}));
   const size=Buffer.alloc(4);size.writeUInt32LE(meta.length);
   res.end(Buffer.concat([size,meta,fs.readFileSync(fixture)]));return;
  }
  if(req.url==='/follow-demo')req.url='/follow-demo.html';
  const file=req.url==='/fixture.png'?path.resolve(fixture):path.resolve(root,'.'+req.url);
  if(req.url==='/'){res.setHeader('Content-Type','text/html');res.end('<html><body></body></html>');return;}
  if(req.url!=='/fixture.png'&&!file.startsWith(root+'/')){res.writeHead(403).end();return;}
  try{res.setHeader('Content-Type',file.endsWith('.js')?'text/javascript':file.endsWith('.wasm')?'application/wasm':file.endsWith('.html')?'text/html':file.endsWith('.css')?'text/css':'application/octet-stream');
   res.setHeader('Content-Security-Policy',"default-src 'self'; connect-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self'; img-src 'self' data:");
   res.end(fs.readFileSync(file));}catch{res.writeHead(404).end();}
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({headless:true});
 try{
  const page=await browser.newPage();page.on('pageerror',e=>console.error(e.message));page.on('console',m=>{if(m.type()==='error')console.error(m.text())});
  await page.goto('http://127.0.0.1:'+server.address().port);
  const result=await page.evaluate(async()=>{
   const {peopleFromLandmarks}=await import('/follow-person.js');
   const image=await createImageBitmap(await (await fetch('/fixture.png')).blob());
   const canvas=document.createElement('canvas');canvas.width=image.width;canvas.height=image.height;
   const c=canvas.getContext('2d',{willReadFrequently:true});c.drawImage(image,0,0);const pixels=c.getImageData(0,0,canvas.width,canvas.height);
   const worker=new Worker('/follow-vision-worker.js');
   return await new Promise((resolve,reject)=>{
    const deadline=setTimeout(()=>reject(Error('vision timeout')),60000);
    worker.onerror=e=>reject(Error(e.message));
    worker.onmessage=({data})=>{
     if(data.type==='error'){clearTimeout(deadline);reject(Error(data.error));}
     if(data.type==='ready')worker.postMessage({type:'frame',id:1,timestamp:1000,image},[image]);
     if(data.type==='result'){clearTimeout(deadline);worker.terminate();resolve({elapsedMs:data.elapsedMs,poseCount:data.landmarks.length,people:peopleFromLandmarks(data.landmarks,pixels.data,pixels.width,pixels.height),landmarks:data.landmarks});}
    };worker.postMessage({type:'init'});
   });
  });
  console.log(JSON.stringify(result));
  if(process.argv.includes('--page')){
   const assert=require('node:assert/strict');
   await page.setViewportSize({width:393,height:620});
   await page.goto('http://127.0.0.1:'+server.address().port+'/follow-demo#native');
   await page.waitForFunction(()=>window.go2FollowDemo?.modelState==='ready');
   await page.evaluate(()=>window.go2FollowStart('跟踪前面黑裤子黑鞋子的人'));
   await page.waitForFunction(()=>window.go2FollowDemo?.state==='tracking');
   assert.equal(await page.locator('#target-state').textContent(),'已锁定人物');
   assert.doesNotMatch(await page.locator('body').innerText(),/拍摄|演示|遥控器/);
   await page.locator('#calibrate').click();await page.locator('#calibration-form button[type=submit]').click();
   await page.waitForFunction(()=>window.go2FollowDemo?.meters>1.9);
   await page.screenshot({path:path.resolve('out/go2-lab/vision-test/browser-follow.png'),fullPage:true});
   offline=true;await page.waitForFunction(()=>window.go2FollowDemo?.state==='recovering');
   assert.equal(await page.locator('#target-state').textContent(),'正在恢复画面');
   assert.equal(await page.locator('#intent-card').count(),0);
   assert.equal(await page.locator('#distance-state').textContent(),'—');
   offline=false;await page.waitForFunction(()=>window.go2FollowDemo?.state==='tracking');
   await page.evaluate(()=>window.go2FollowStop());assert.equal(await page.locator('#target-state').textContent(),'已停止');
   assert.equal(writes[0],'/api/assistant/observe');
   assert.ok(writes.includes('/api/camera/start'));
   assert.ok(writes.every(p=>['/api/assistant/observe','/api/camera/start'].includes(p)));
   console.log(JSON.stringify({pageChecks:'passed',writes,fixtureOnly:true}));
  }
 }finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);process.exit(1)});
