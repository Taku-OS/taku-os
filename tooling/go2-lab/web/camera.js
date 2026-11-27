Object.assign(messages.zh,{
  followDemoOpen:'打开视觉跟随',
  cameraTab:'摄像头',cameraTitle:'狗的视角',cameraStart:'开启画面',cameraStop:'关闭画面',
  cameraIntro:'开启后，微动和避障页面顶部会持续显示画面，滚动操作时也能看到。',
  cameraConnect:'先在「状态」连接机器狗，再开启画面。',cameraOff:'摄像头未开启',cameraWaiting:'等待摄像头画面…',
  cameraLive:'实时画面',cameraStale:'画面已过期，请暂停操作并观察现场',cameraOffline:'连接中断，画面不可用',
  cameraError:'视频通道异常，请关闭画面后重试',cameraReplay:'历史回放不包含摄像头视频',
  cameraSim:'模拟画面 · 非真实摄像头',cameraFrameAge:'帧龄',cameraFPS:'更新',cameraAlt:'机器狗前方摄像头画面',
  cameraNote:'帧龄从 Mac 收到视频帧开始计算，不包含狗端采集和传输耗时。画面最多 12 帧/秒，采用最新帧显示；真实流畅度取决于连接。',
  cameraPrivacy:'普通预览在本地显示；交给 AI 分析的图片可能发送到当前模型服务。摄像头看不到后方，不能代替雷达或现场观察。',
  cameraFailed:'请求未完成，请检查连接后重试。',cameraJump:'查看大画面'
});
Object.assign(messages.en,{
  followDemoOpen:'Open visual follow',
  cameraTab:'Camera',cameraTitle:'Robot view',cameraStart:'Start camera',cameraStop:'Stop camera',
  cameraIntro:'The view stays pinned above Move and Avoidance controls, including while scrolling.',
  cameraConnect:'Connect the robot in Status, then start the camera.',cameraOff:'Camera is off',cameraWaiting:'Waiting for camera frames…',
  cameraLive:'Live view',cameraStale:'Outdated image. Pause operation and check the surroundings.',cameraOffline:'Connection lost. View unavailable.',
  cameraError:'Video channel error. Stop and restart the camera.',cameraReplay:'Historical replay does not include camera video.',
  cameraSim:'SIMULATION · Not a real camera',cameraFrameAge:'Frame age',cameraFPS:'Updates',cameraAlt:'Robot forward camera view',
  cameraNote:'Frame age starts when the Mac receives a video frame. It excludes robot capture and transport delay. Up to 12 fps, showing the latest frame; actual performance depends on the connection.',
  cameraPrivacy:'Normal preview is local. Images handed to AI may be sent to the configured model service. The camera cannot see behind the robot or replace LiDAR and on-site observation.',
  cameraFailed:'Request failed. Check the connection and retry.',cameraJump:'Larger view'
});
translate();
(() => {
  const canvas=$('camera-canvas'),ctx=canvas.getContext('2d');
  let page='state',meta=null,online=false,pending=false,failed=false,frameKey='',shownKey='',generation=0;
  let observedAt=0,requestAge=0,hasImage=false,fetching=false,drawCount=0;
  function render(){
    const age=meta?.ageMs==null?Infinity:meta.ageMs+requestAge+performance.now()-observedAt;
    const live=online&&meta?.connected&&meta?.enabled&&meta?.fresh&&age<1000&&hasImage&&shownKey===meta.frameKey;
    const compact=page!=='camera';
    $('camera-dock').hidden=!['camera','motion','avoidance','navigation'].includes(page)&&!meta?.enabled;
    $('camera-dock').classList.toggle('compact',compact);
    $('camera-jump').hidden=!compact;
    canvas.hidden=!live;
    $('camera-overlay').hidden=live;
    $('camera-sim-label').hidden=meta?.source!=='simulation';
    $('camera-start').disabled=pending||!online||!meta?.connected||meta?.source==='replay'||meta?.enabled;
    $('camera-stop').disabled=pending||!online||!meta?.enabled;
    let key=failed?'cameraFailed':!online?'cameraOffline':meta?.source==='replay'?'cameraReplay':!meta?.connected?'cameraConnect':
      meta?.error?'cameraError':!meta?.enabled?'cameraOff':live?'cameraLive':meta?.frames?'cameraStale':'cameraWaiting';
    $('camera-status').textContent=t(key);
    $('camera-overlay').textContent=t(key);
    $('camera-metrics').textContent=live?`${meta.width}×${meta.height} · ${meta.fps} fps · ${t('cameraFrameAge')} ${Math.round(age)} ms`:'';
    window.go2Camera={live,meta,ageMs:Number.isFinite(age)?age:null,drawCount,page};
  }
  async function poll(){
    if(fetching)return;
    if(pending){setTimeout(poll,100);return;}
    fetching=true;
    const token=generation,started=performance.now();
    try{
      const response=await fetch('/api/camera/frame?after='+encodeURIComponent(frameKey),{cache:'no-store',signal:AbortSignal.timeout(2500)});
      if(!response.ok)throw Error('HTTP');
      const bytes=await response.arrayBuffer();
      if(bytes.byteLength<4)throw Error('HEADER');
      const n=new DataView(bytes).getUint32(0,true);
      if(n>16384||bytes.byteLength<4+n)throw Error('HEADER');
      const next=JSON.parse(new TextDecoder().decode(new Uint8Array(bytes,4,n)));
      if(token!==generation)return;
      // Include the complete HTTP round trip as a conservative local age bound.
      if(!next.fresh||!next.enabled||!next.connected){hasImage=false;frameKey='';shownKey='';}
      if(next.fresh&&bytes.byteLength>4+n&&!document.hidden){
        const bitmap=await createImageBitmap(new Blob([bytes.slice(4+n)],{type:'image/jpeg'}));
        try{
          if(token!==generation)return;
          if(next.ageMs+performance.now()-started<1000){
            canvas.width=bitmap.width;canvas.height=bitmap.height;
            ctx.drawImage(bitmap,0,0);hasImage=true;shownKey=next.frameKey;frameKey=next.frameKey;drawCount++;
          }else{hasImage=false;frameKey='';shownKey='';}
        }finally{bitmap.close();}
      }
      meta=next;online=true;observedAt=performance.now();requestAge=performance.now()-started;
    }catch{if(token===generation){online=false;hasImage=false;frameKey='';shownKey='';}}
    finally{fetching=false;render();setTimeout(poll,document.hidden?1000:meta?.enabled?80:500);}
  }
  async function command(action){
    if(pending)return;
    generation++;hasImage=false;frameKey='';shownKey='';pending=true;failed=false;render();
    try{meta=await request('/api/camera/'+action,'POST');online=true;failed=Boolean(meta.requestError||meta.error);observedAt=performance.now();requestAge=0;}
    catch{failed=true;online=false;}
    finally{pending=false;render();}
  }
  $('camera-start').onclick=()=>command('start');$('camera-stop').onclick=()=>command('stop');
  $('follow-demo-open').onclick=()=>{window.location.href='/follow-demo';};
  $('camera-jump').onclick=()=>{window.setGo2Page('camera');$('camera-dock').scrollIntoView({block:'start'});};
  window.addEventListener('go2-page',event=>{page=event.detail;render();});
  window.addEventListener('go2-state',event=>{
    if(event.detail.offline||event.detail.state?.connected===false){hasImage=false;frameKey='';shownKey='';render();}
  });
  document.addEventListener('visibilitychange',()=>{if(document.hidden){hasImage=false;frameKey='';shownKey='';}render();});
  $('language').addEventListener('click',render);
  setInterval(render,100);render();poll();
})();
