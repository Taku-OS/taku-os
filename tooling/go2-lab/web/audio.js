Object.assign(messages.zh,{
  audioTab:'语音',audioIntro:'把录好的内容通过狗的扬声器播放。',audioFile:'选择录音文件',
  audioNote:'支持 WAV、MP3、M4A 等音频，最多 10 MB、60 秒。录音保存在这台 Mac，重新选择会替换原录音；播放时需要保持连接。',
  audioEmpty:'尚未导入录音',audioPlay:'在狗上播放',audioStop:'停止播放',audioVolume:'扬声器音量',
  audioRefresh:'读取狗的音量',audioApply:'应用音量',audioUnknown:'设备音量尚未读取',
  audioCurrent:'设备已确认音量：',audioSelected:'所选音量：',audioIdle:'录音已就绪，点击播放。',
  audioDisconnected:'可以先导入录音。连接狗后，读取音量再播放。',audioSending:'正在向狗发送音频；实际出声请现场确认。',
  audioFinished:'音频发送结束。',audioWorking:'正在处理…',audioSimulation:'模拟模式：只验证导入、播放状态和音量流程，不会让真实机器狗发声。',
  audioSimPlaying:'模拟播放中，不会发声。',audioReplay:'回放模式下不可播放或调节设备音量。',audioFailed:'操作未完成，请检查连接后重试。',
  audioSize:'文件超过 10 MB 或为空，请选择较小的录音。',audioLong:'录音超过 60 秒，请先剪短。',audioInvalid:'无法读取这段音频，请尝试 WAV、MP3 或 M4A 文件。',
  audioReadFirst:'请先读取狗的音量。',audioUnsupported:'连接未提供发送音频通道，需要实机检查固件兼容性。',
  audioVolumeFailed:'音量未确认：接口拒绝、超时或读回不一致。',audioSaveFailed:'录音无法保存，请检查 Mac 磁盘。',audioBusy:'音频操作正在进行，请先停止播放。',
  audioBoundary:'这是社区音频接口；页面显示发送状态，不能证明扬声器实际出声。停止会结束本次音频发送，狗端可能还有短暂缓冲。'
});
Object.assign(messages.en,{
  audioTab:'Audio',audioIntro:'Play a prerecorded clip through the robot’s speaker.',audioFile:'Choose audio file',
  audioNote:'WAV, MP3, M4A and other audio formats; up to 10 MB and 60 seconds. Saved on this Mac. Choosing a new clip replaces the previous one. Keep the connection open while playing.',
  audioEmpty:'No clip imported',audioPlay:'Play on robot',audioStop:'Stop playback',audioVolume:'Speaker volume',
  audioRefresh:'Read robot volume',audioApply:'Apply volume',audioUnknown:'Device volume has not been read',
  audioCurrent:'Confirmed device volume: ',audioSelected:'Selected volume: ',audioIdle:'Clip ready. Press play.',
  audioDisconnected:'Import a clip first. Connect the robot, then read its volume before playing.',audioSending:'Sending audio to the robot. Confirm audible playback in person.',
  audioFinished:'Audio transmission finished.',audioWorking:'Processing…',audioSimulation:'Simulation: verifies import, playback state and volume only. No real robot audio.',
  audioSimPlaying:'Simulated playback. No sound.',audioReplay:'Device playback and volume are unavailable during replay.',audioFailed:'Action failed. Check the connection and retry.',
  audioSize:'File is empty or exceeds 10 MB. Choose a smaller recording.',audioLong:'Clip exceeds 60 seconds. Trim it first.',audioInvalid:'Cannot decode this audio. Try WAV, MP3 or M4A.',
  audioReadFirst:'Read the robot volume first.',audioUnsupported:'No negotiated audio sender. Firmware compatibility needs checking on the robot.',
  audioVolumeFailed:'Volume not confirmed: rejected, timed out or readback mismatch.',audioSaveFailed:'Cannot save the recording. Check the Mac disk.',audioBusy:'Audio is busy. Stop playback first.',
  audioBoundary:'Uses a community audio interface. Transmission status does not confirm audible output. Stop ends this stream; a brief buffer may remain on the robot.'
});
translate();
(() => {
  let state=null,pending=false,online=false,error=null,draft=3,polling=false;
  const errors={FILE_SIZE:'audioSize',DURATION_LIMIT:'audioLong',INVALID_AUDIO:'audioInvalid',READ_VOLUME_FIRST:'audioReadFirst',
    AUDIO_NOT_NEGOTIATED:'audioUnsupported',VOLUME_REJECTED:'audioVolumeFailed',INVALID_VOLUME:'audioVolumeFailed',VOLUME_MISMATCH:'audioVolumeFailed',
    SAVE_FAILED:'audioSaveFailed',AUDIO_BUSY:'audioBusy',NOT_CONNECTED:'audioDisconnected'};
  function render(){
    const connected=online&&state?.connected&&!state?.replay;
    $('audio-choose').disabled=$('audio-file').disabled=pending||state?.busy||state?.state==='streaming'||!online;
    $('audio-clip').textContent=state?.clip?`${state.clip.name} · ${state.clip.duration.toFixed(1)} s`:t('audioEmpty');
    $('audio-volume').disabled=!connected||pending||state?.busy;
    $('audio-volume-value').textContent=t('audioSelected')+draft+'/10';
    $('audio-volume-current').textContent=state?.volume==null?t('audioUnknown'):t('audioCurrent')+state.volume+'/10';
    $('audio-refresh').disabled=!connected||pending||state?.busy;
    $('audio-apply').disabled=!connected||pending||state?.busy;
    $('audio-play').disabled=!connected||pending||state?.busy||!state?.clip||state?.volume==null||state?.state==='streaming';
    $('audio-stop').disabled=!online;
    $('audio-simulation').hidden=!state?.simulated;
    const failure=error||state?.error;
    $('audio-status').textContent=failure?t(errors[failure]||'audioFailed'):pending||state?.busy?t('audioWorking'):state?.replay?t('audioReplay'):
      !connected?t('audioDisconnected'):state?.state==='streaming'?t(state.simulated?'audioSimPlaying':'audioSending'):
      state?.state==='finished'?t('audioFinished'):!state?.clip?t('audioEmpty'):state.volume==null?t('audioReadFirst'):t('audioIdle');
  }
  async function load(){
    if(polling||pending)return;polling=true;
    try{state=await request('/api/audio');online=true;}catch{online=false;}finally{polling=false;render();}
  }
  async function command(action,payload={}){
    // Stop can preempt an in-flight volume request; never queues a later play.
    if(pending&&action!=='stop')return;
    pending=true;error=null;render();
    try{const result=await request('/api/audio/'+action,'POST',payload);state=result;error=result.requestError||null;}
    catch{error='AUDIO_FAILED';}finally{pending=false;render();load();}
  }
  $('audio-choose').onclick=()=>$('audio-file').click();
  $('audio-file').onchange=async()=>{
    const file=$('audio-file').files?.[0];if(!file)return;
    if(file.size===0||file.size>10*1024*1024){error='FILE_SIZE';render();return;}
    pending=true;error=null;render();
    try{
      const response=await fetch('/api/audio/import?name='+encodeURIComponent(file.name),{method:'POST',headers:{'Content-Type':'application/octet-stream'},body:file,signal:AbortSignal.timeout(35000)});
      const result=await response.json();if(!response.ok)throw Error();state=result;
    }catch{error='AUDIO_FAILED';}finally{pending=false;$('audio-file').value='';render();}
  };
  $('audio-volume').oninput=()=>{draft=Number($('audio-volume').value);render();};
  $('audio-apply').onclick=()=>command('volume',{volume:draft});
  for(const action of ['play','stop','refresh'])$('audio-'+action).onclick=()=>command(action);
  $('language').addEventListener('click',render);
  window.addEventListener('go2-page',event=>{if(event.detail==='audio')load();});
  setInterval(load,1000);load();
})();
