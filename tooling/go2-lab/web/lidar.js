// Full XYZ buffers, one WebGL draw call per display frame, no point sampling.
(() => {
  const canvas=$('cloud');
  let renderer;
  try { renderer=new Go2PointRenderer(canvas); }
  catch { renderer={error:'WEBGL_INIT_FAILED',points:new Float32Array(),drawnPoints:0,upload(){}}; }
  let cloud=null,arrived=0,offline=true,busy=false,active=false,frameKey='';
  let yaw=-.6,pitch=.65,top=false,pointer=null;
  let windowStart=performance.now(),draws=0,uploads=0,fps=0,dataHz=0;
  let transferMs=0,uploadedFrames=0,sourceFrames=0;
  const metadata=()=>cloud?.values;
  function isLive(){return !offline&&cloud?.enabled&&cloud?.fresh&&cloud.ageMs+performance.now()-arrived<=5000;}
  function renderCloud(){
    const live=isLive(),hasData=!!metadata();
    const status=renderer.error?'scanGpuError':offline?'offline':cloud?.error?'scanError':!cloud?.enabled?hasData?'scanPaused':'scanIdle':live?'scanLive':hasData?'scanStale':'scanWaiting';
    $('cloud-status').textContent=t(status);canvas.dataset.live=String(!!live&&!renderer.error);
    $('cloud-age').textContent=hasData?`${t('scanAge')} ${((cloud.ageMs+performance.now()-arrived)/1000).toFixed(1)} s`:t('scanUnknown');
    $('cloud-count').textContent=metadata()?.pointCount?.toLocaleString()??'—';
    $('cloud-frames').textContent=cloud?.count??0;
    $('cloud-displayed').textContent=renderer.points?.length?(renderer.points.length/3).toLocaleString():'—';
    $('cloud-fps').textContent=`${fps.toFixed(0)} FPS`;
    $('cloud-hz').textContent=`${dataHz.toFixed(1)} Hz`;
    const note=renderer.error?'scanGpuErrorNote':offline?'scanOfflineNote':cloud?.error?'scanErrorNote':!cloud?.enabled?hasData?'scanPausedNote':'scanIdleNote':live?metadata()?.pointCount>0?'scanLiveNote':'scanWaitingNote':hasData?'scanStaleNote':'scanWaitingNote';
    $('cloud-notice').textContent=t(note);
    $('cloud-empty').hidden=!!metadata()?.pointCount&&!renderer.error;
    $('cloud-empty').textContent=t(renderer.error?'scanGpuError':'scanUnknown');
    $('scan-start').disabled=busy||offline||!state?.connected||cloud?.enabled||!!renderer.error;
    $('scan-stop').disabled=busy||offline||!cloud?.enabled;
    const scale=metadata()?Math.min(canvas.clientWidth,canvas.clientHeight)*.64/metadata().span:0;
    $('cloud-scale').hidden=!scale; $('cloud-scale').style.width=`${scale}px`;
    window.go2PointStats={sourcePoints:metadata()?.pointCount||0,uploadedPoints:(renderer.points?.length||0)/3,drawnPoints:renderer.drawnPoints||0,drawCalls:renderer.drawCalls||0,uploadedFrames,sourceFrames,fps,dataHz,transferMs,frameKey,rendererError:renderer.error||null};
  }
  async function pollCloud(){
    if(active&&!busy){
      try{
        const started=performance.now();
        const response=await fetch(`/api/lidar/frame?after=${encodeURIComponent(frameKey)}`,{cache:'no-store',signal:AbortSignal.timeout(8000)});
        if(!response.ok)throw Error('FRAME_HTTP_FAILED');
        const buffer=await response.arrayBuffer();
        if(buffer.byteLength<4)throw Error('INVALID_FRAME');
        const headerLength=new DataView(buffer).getUint32(0,true),offset=4+headerLength;
        if(offset>buffer.byteLength||offset%4)throw Error('INVALID_FRAME_HEADER');
        const next=JSON.parse(new TextDecoder().decode(new Uint8Array(buffer,4,headerLength)));
        if(next.format!=='xyz-f32-le-v1')throw Error('UNKNOWN_FORMAT');
        if(next.hasPoints){
          const points=new Float32Array(buffer,offset);
          if(points.length!==next.values.pointCount*3)throw Error('INCOMPLETE_POINT_FRAME');
          renderer.upload(points,next.values);uploads++;uploadedFrames++;
          transferMs=performance.now()-started;
        }else if(!next.values||!next.values.pointCount){renderer.upload(new Float32Array(),null);}
        else if(next.frameKey!==frameKey){throw Error('MISSING_POINT_FRAME');}
        frameKey=next.frameKey;cloud=next;arrived=performance.now();offline=false;
        sourceFrames=next.count;
      }catch{offline=true;}
      renderCloud();
    }
    setTimeout(pollCloud,50);
  }
  async function scan(action){
    busy=true;renderCloud();
    try{cloud=await request(`/api/lidar/${action}`,'POST');arrived=performance.now();offline=false;}
    catch{offline=true;}
    finally{busy=false;renderCloud();}
  }
  function view(page){
    document.body.dataset.page=page;
    const environment=page==='environment';
    $('motion-page').hidden=page!=='motion';
    $('audio-page').hidden=page!=='audio';
    $('camera-page').hidden=page!=='camera';
    $('navigation-page').hidden=page!=='navigation';
    $('amap-page').hidden=page!=='amap';
    $('avoidance-page').hidden=page!=='avoidance';
    active=environment;$('state-page').hidden=page!=='state';$('environment-page').hidden=!environment;
    for(const [id,selected] of [['tab-state',page==='state'],['tab-environment',environment],['tab-motion',page==='motion'],['tab-avoidance',page==='avoidance'],['tab-audio',page==='audio'],['tab-camera',page==='camera'],['tab-navigation',page==='navigation'],['tab-amap',page==='amap']]){
      $(id).classList.toggle('active',selected);$(id).setAttribute('aria-pressed',String(selected));
    }
    windowStart=performance.now();draws=uploads=0;if(environment)renderCloud();
    window.dispatchEvent(new CustomEvent('go2-page',{detail:page}));
  }
  function animate(now){
    if(active&&!document.hidden){
      if(renderer.draw?.(yaw,pitch,isLive()))draws++;
      if(window.go2PointStats){
        window.go2PointStats.drawnPoints=renderer.drawnPoints||0;
        window.go2PointStats.drawCalls=renderer.drawCalls||0;
      }
      if(now-windowStart>=1000){const seconds=(now-windowStart)/1000;fps=draws/seconds;dataHz=uploads/seconds;draws=uploads=0;windowStart=now;renderCloud();}
    }
    requestAnimationFrame(animate);
  }
  window.setGo2Page=view;
  $('tab-state').onclick=()=>view('state');$('tab-environment').onclick=()=>view('environment');$('tab-motion').onclick=()=>view('motion');
  $('tab-avoidance').onclick=()=>view('avoidance');
  $('tab-audio').onclick=()=>view('audio');
  $('tab-camera').onclick=()=>view('camera');
  $('tab-navigation').onclick=()=>view('navigation');
  $('tab-amap').onclick=()=>view('amap');
  $('scan-start').onclick=()=>scan('start');$('scan-stop').onclick=()=>scan('stop');
  function mode(isTop){
    top=isTop;pitch=top?Math.PI/2:.65;
    for(const [id,selected] of [['view-top',top],['view-3d',!top]]){$(id).setAttribute('aria-pressed',String(selected));$(id).classList.toggle('selected',selected);}
  }
  $('view-top').onclick=()=>mode(true);$('view-3d').onclick=()=>mode(false);$('view-reset').onclick=()=>{yaw=-.6;mode(false);};
  canvas.onpointerdown=e=>{pointer=[e.clientX,e.clientY];canvas.setPointerCapture(e.pointerId);};
  canvas.onpointermove=e=>{if(!pointer)return;yaw+=(e.clientX-pointer[0])*.008;if(!top)pitch=Math.max(.1,Math.min(1.5,pitch+(e.clientY-pointer[1])*.006));pointer=[e.clientX,e.clientY];};
  canvas.onpointerup=canvas.onpointercancel=()=>{pointer=null;};
  canvas.onkeydown=e=>{if(!e.key.startsWith('Arrow'))return;e.preventDefault();if(e.key==='ArrowLeft')yaw-=.1;if(e.key==='ArrowRight')yaw+=.1;if(!top&&e.key==='ArrowUp')pitch=Math.min(1.5,pitch+.1);if(!top&&e.key==='ArrowDown')pitch=Math.max(.1,pitch-.1);};
  $('language').addEventListener('click',renderCloud);
  setInterval(()=>{if(active)renderCloud();},500);
  pollCloud();requestAnimationFrame(animate);
})();
