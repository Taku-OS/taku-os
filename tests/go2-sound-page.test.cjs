// Browser contract test; all API traffic is mocked, never points at a robot.
const {chromium}=require('playwright');
const fs=require('node:fs/promises');
const path=require('node:path');
const assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../tooling/go2-lab');
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_EXECUTABLE_PATH?{executablePath:process.env.PLAYWRIGHT_EXECUTABLE_PATH}:{})});
 try{
  const page=await browser.newPage({viewport:{width:393,height:570},locale:'zh-CN'});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  const catalog=JSON.parse(await fs.readFile(path.join(root,'audio-presets/catalog.json'),'utf8'));
  let state={connected:true,clip:null,volume:3,state:'idle',busy:false,error:null,elapsed:0};const calls=[];
  await page.route('http://sound.test/**',async route=>{
   const req=route.request(),url=new URL(req.url());
   if(url.pathname.startsWith('/api/')){
    const action=url.pathname.split('/').pop();
    if(req.method()==='POST'){
     const body=req.postDataJSON();calls.push({action,body});
     if(action==='select'){const item=catalog.clips.find(c=>c.id===body.id);state={...state,clip:{presetId:item.id,name:item.title.zh,duration:item.duration},elapsed:0};}
     if(action==='volume')state.volume=body.volume;
     if(action==='play')state.state='streaming';
     if(action==='stop')state.state='idle';
    }
    return route.fulfill({json:action==='library'?catalog:state});
   }
   const name=url.pathname==='/audio-library'?'audio-library.html':url.pathname.slice(1);
   const type=name.endsWith('.js')?'text/javascript':name.endsWith('.css')?'text/css':'text/html';
   await route.fulfill({body:await fs.readFile(path.join(root,'web',name)),contentType:type});
  });
  await page.goto('http://sound.test/audio-library#native');
  await page.waitForFunction(()=>window.go2SoundState?.clips===27&&!window.go2SoundState.busy);
  assert.equal(await page.locator('.clip').count(),27);
  await page.locator('.clip[data-id=hello]').click();
  await page.waitForFunction(()=>window.go2SoundState.selected==='hello'&&!window.go2SoundState.busy);
  assert.equal(calls.filter(c=>c.action==='play').length,0);
  await page.locator('#volume').evaluate(el=>{el.value='2';el.dispatchEvent(new Event('input'));});
  assert.equal(calls.filter(c=>c.action==='volume').length,0);
  await page.locator('#volume').dispatchEvent('change');
  await page.waitForFunction(()=>window.go2SoundState.volume===2&&!window.go2SoundState.busy);
  assert.equal(calls.filter(c=>c.action==='volume').length,1);
  assert.match(await page.locator('#volume-confirmed').innerText(),/20%/);
  await page.locator('#play').click();await page.waitForFunction(()=>window.go2SoundState.streaming);
  await page.locator('#stop').click();await page.waitForFunction(()=>!window.go2SoundState.streaming&&!window.go2SoundState.busy);
  await page.locator('[data-filter=effect]').click();assert.equal(await page.locator('.clip').count(),3);
  await page.locator('[data-filter=zh]').click();assert.equal(await page.locator('.clip').count(),3);
  await page.locator('[data-filter=en]').click();assert.equal(await page.locator('.clip').count(),21);
  assert.match(await page.locator('.clip[data-id=en-whats-good]').innerText(),/Yo, what's good\? I'm Taku\./);
  await page.locator('.clip[data-id=en-whats-good]').click();
  await page.waitForFunction(()=>window.go2SoundState.selected==='en-whats-good'&&!window.go2SoundState.busy);
  assert.equal(calls.filter(c=>c.action==='play').length,1,'Changing language or selection must not autoplay');
  await page.locator('#language').click();
  assert.equal(await page.locator('[data-filter=en]').innerText(),'English');
  assert.match(await page.locator('.clip[data-id=en-whats-good]').innerText(),/AI-generated/);
  assert.equal(await page.evaluate(()=>localStorage.getItem('go2-language')),'en');
  await page.reload();await page.waitForFunction(()=>window.go2SoundState?.clips===27&&!window.go2SoundState.busy);
  assert.equal(await page.locator('[data-filter=en]').innerText(),'English');
  await page.locator('#language').click();
  await page.locator('#credits summary').click();assert.match(await page.locator('#credits-body').innerText(),/CC BY-SA 3.0/);
  await page.locator('#credits summary').click();
  await page.locator('[data-filter=all]').click();await page.evaluate(()=>scrollTo(0,0));
  const play=await page.locator('#play').boundingBox();assert.ok(play.y>=0&&play.y+play.height<=570);
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  assert.doesNotMatch(await page.locator('body').innerText(),/拍摄|演示|遥控器执行/);
  assert.deepEqual(errors,[]);
  if(process.env.SOUND_SCREENSHOT)await page.screenshot({path:process.env.SOUND_SCREENSHOT});
  console.log('Sound page: 27 presets, English/Chinese filters and transcripts, selection without autoplay, volume, play/stop, credits, narrow layout passed.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
