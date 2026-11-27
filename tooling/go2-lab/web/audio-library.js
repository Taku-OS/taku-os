import {SoundControls} from './audio-library-controls.js';
const copy={
 zh:{title:'声音库',subtitle:'挑选一句话，或一种声音。让它替你表达。',volume:'机器狗音量',all:'全部',speech:'中文语音',effect:'音效',presets:'预设音频',credits:'音频来源与许可',selected:'当前选择',choose:'选择一段音频',play:'在机器狗上播放',stop:'停止播放',ready:'已就绪',sending:'播放中',finished:'播放结束',working:'正在处理…',disconnected:'连接机器狗后即可播放',unknown:'读取设备音量中…',confirmed:'设备已确认',draft:'松手调整音量',error:'操作未完成，请重试',noClip:'请先选择一段音频',volumeError:'未能确认音量，请检查连接',missing:'音频库暂不可用',invalid:'音频文件不可用',unsupported:'机器狗未提供音频播放通道',stopped:'已停止播放',count:'段',category:'音频类别',source:'来源',author:'作者',license:'许可',changes:'处理',available:'已保存在手机',simulation:'模拟播放',retry:'点击音量条可重试读取',unit:'秒'},
 en:{title:'Sound library',subtitle:'Pick a phrase or a sound. Let it speak for you.',volume:'Robot volume',all:'All',speech:'Mandarin',effect:'Effects',presets:'Preset sounds',credits:'Sources & licenses',selected:'Selected',choose:'Choose a sound',play:'Play on robot',stop:'Stop playback',ready:'Ready',sending:'Playing',finished:'Finished',working:'Working…',disconnected:'Connect the robot to play',unknown:'Reading device volume…',confirmed:'Confirmed',draft:'Release to apply',error:'Could not complete. Try again.',noClip:'Select a sound first',volumeError:'Volume not confirmed. Check the connection.',missing:'Sound library unavailable',invalid:'Audio file unavailable',unsupported:'Robot audio channel unavailable',stopped:'Playback stopped',count:'clips',category:'Audio categories',source:'Source',author:'Author',license:'License',changes:'Processing',available:'Saved on phone',simulation:'Simulated playback',retry:'Use the slider to retry',unit:'s'}
};
const $=id=>document.getElementById(id);Object.assign(copy.en,{search:'Find a sound or phrase…',previewPhone:'Preview on phone',stopPreview:'Stop phone preview',importAudio:'Import',noResults:'No sounds match your search.',previewError:'Phone preview could not play.'});
Object.assign(copy.zh,{search:'搜索声音或台词…',previewPhone:'在手机试听',stopPreview:'停止手机试听',importAudio:'导入',noResults:'没有找到匹配的音频。',previewError:'手机试听未能播放。'});
let query='',preview=null;
function stopPreview(){if(preview){preview.pause();preview.src='';preview=null;}if(document.getElementById('preview-phone'))document.getElementById('preview-phone').textContent=t('previewPhone');}
let lang=location.hash==='#native-en'?'en':(window.Go2Locale?.getLanguage?.()||localStorage.getItem('go2-language')||(navigator.language.startsWith('zh')?'zh':'en')),clips=[],category='all',online=false,draft=null,dirty=false,loading=false,libraryError=false;
const t=k=>copy[lang][k]||k;
Object.assign(copy.zh,{preparing:'正在传入机器狗',deviceRejected:'机器狗未接受播放请求',notSaved:'音频尚未传入，请重试',stopUnconfirmed:'停止尚未确认，请检查连接'});
Object.assign(copy.en,{preparing:'Sending to robot',deviceRejected:'Robot rejected playback',notSaved:'Audio transfer not confirmed. Retry.',stopUnconfirmed:'Stop not confirmed. Check connection.'});
Object.assign(copy.zh,{english:'英语',mandarin:'中文',transcript:'台词',voice:'声音风格'});
Object.assign(copy.en,{english:'English',mandarin:'Mandarin',transcript:'Transcript',voice:'Voice style'});
document.body.dataset.embedded=String(location.hash.startsWith('#native'));
async function json(path,body){
 const response=await fetch(path,{cache:'no-store',signal:AbortSignal.timeout(12000),...(body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})});
 const result=await response.json();if(!response.ok)throw Error(result.error||'REQUEST_FAILED');return result;
}
const control=new SoundControls((action,body)=>json('/api/audio/'+action,body),render);
function seconds(value){return Number(value||0).toFixed(1)+' '+t('unit');}
function translate(){
 document.documentElement.lang=lang==='zh'?'zh-CN':'en';document.title='Taku · '+t('title');
 document.querySelectorAll('[data-copy]').forEach(el=>el.textContent=t(el.dataset.copy));
 $('language').textContent=lang==='zh'?'EN':'中文';$('filters').setAttribute('aria-label',t('category'));renderList();render();
}
function renderList(){
 const visible=clips.filter(c=>category==='all'||c.category===category||(c.category==='speech'&&(c.language||'zh-CN').startsWith(category)));$('clip-count').textContent=visible.length+' '+t('count');
 $('clips').replaceChildren(...visible.map(clip=>{
  const row=document.createElement('div');row.setAttribute('role','listitem');
  const button=document.createElement('button');button.type='button';button.className='clip';button.dataset.id=clip.id;
  const icon=document.createElement('span');icon.className='clip-mark';icon.setAttribute('aria-hidden','true');icon.textContent=clip.category==='speech'?'♪':clip.id==='dog-bark'?'♬':'✧';
  const text=document.createElement('span');text.className='clip-text';
  const title=document.createElement('span');title.className='clip-title';title.textContent=clip.title[lang];
  if(clip.transcript){const transcript=document.createElement('span');transcript.className='clip-transcript';transcript.lang=clip.language||'en-US';transcript.textContent=clip.transcript;text.append(title,transcript);}else{text.append(title);}
  const description=document.createElement('span');description.className='clip-description';description.textContent=clip.description[lang];text.append(description);
  if(clip.voiceStyle){const style=document.createElement('span');style.className='clip-style';style.textContent=clip.voiceStyle[lang];text.append(style);}
  const duration=document.createElement('span');duration.className='clip-time';duration.textContent=seconds(clip.duration);
  const check=document.createElement('span');check.className='clip-check';check.setAttribute('aria-hidden','true');
  button.append(icon,text,duration,check);button.onclick=()=>control.select(clip.id);row.append(button);return row;
 }));
 $('credits-body').replaceChildren(...clips.map(clip=>{
  const block=document.createElement('section'),title=document.createElement('strong');title.textContent=clip.title[lang];block.append(title);
  for(const value of [...(clip.transcript?[t('transcript')+': '+clip.transcript]:[]),...(clip.voiceStyle?[t('voice')+': '+clip.voiceStyle[lang]]:[]),t('author')+': '+clip.author,t('license')+': '+clip.license,t('source')+': '+clip.source,t('license')+': '+clip.licenseUrl,t('changes')+': '+clip.changes[lang]]){const p=document.createElement('p');p.textContent=value;block.append(p);}return block;
 }));
 document.querySelectorAll('[data-filter]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.filter===category)));
}
function render(){
 const state=control.state,connected=online&&state?.connected&&!state?.replay,busy=control.pending||state?.busy;
 const selected=state?.clip?.presetId,clip=clips.find(c=>c.id===selected),streaming=state?.state==='streaming';
 if(!dirty&&state?.volume!=null)draft=state.volume;
 $('volume').disabled=!connected||busy;$('volume').value=draft??3;$('volume-value').textContent=draft===null?'—':draft*10+'%';
 $('volume-confirmed').textContent=dirty?t('draft'):state?.volume==null?t(connected?'unknown':'disconnected'):t('confirmed')+' '+state.volume*10+'%';
 $('now-title').textContent=clip?clip.title[lang]:state?.clip?.name||t('choose');
 $('duration').textContent=state?.clip?seconds(state.elapsed)+' / '+seconds(state.clip.duration):'';
 $('progress').max=state?.clip?.duration||1;$('progress').value=state?.elapsed||0;
 $('play').disabled=!connected||busy||!state?.clip||streaming;$('stop').disabled=!online;
 document.querySelectorAll('.clip').forEach(b=>{const selectedRow=b.dataset.id===selected;b.disabled=!online||busy;b.setAttribute('aria-pressed',String(selectedRow));b.querySelector('.clip-check').textContent=selectedRow?'✓':'';});
 const error=control.error||state?.error;
 const errors={NO_CLIP:'noClip',INVALID_PRESET:'invalid',VOLUME_MISMATCH:'volumeError',INVALID_VOLUME:'volumeError',VOLUME_REJECTED:'volumeError',AUDIO_TIMEOUT:'error',AUDIO_NOT_NEGOTIATED:'unsupported',NOT_CONNECTED:'disconnected',AUDIO_REJECTED:'deviceRejected',AUDIO_NOT_SAVED:'notSaved',AUDIO_STOP_UNCONFIRMED:'stopUnconfirmed'};
 $('status').textContent=libraryError?t('missing'):error?t(errors[error]||'error'):state?.state==='preparing'?t('preparing')+' '+(state.transferProgress||0)+'%':busy?t('working'):!connected?t('disconnected'):streaming?t(state.simulated?'simulation':'sending'):state?.state==='finished'?t('finished'):state?.clip?t('ready'):t('noClip');
 window.go2SoundState={connected,busy,selected,streaming,volume:state?.volume,draft,elapsed:state?.elapsed,clips:clips.length,error:error||null};
}
async function load(){
 if(loading||control.pending)return;loading=true;const version=control.epoch;
 try{const next=await json('/api/audio');if(version!==control.epoch)return;online=true;control.accept(next);}catch{if(version===control.epoch)online=false;}
 finally{loading=false;render();}
}
$('volume').oninput=()=>{draft=Number($('volume').value);dirty=true;render();};
$('volume').onchange=async()=>{const value=draft;await control.volume(value);dirty=false;render();};
$('play').onclick=()=>control.play();$('stop').onclick=()=>control.stop();
document.querySelectorAll('[data-filter]').forEach(button=>button.onclick=()=>{category=button.dataset.filter;renderList();render();});
$('language').onclick=()=>{lang=lang==='zh'?'en':'zh';localStorage.setItem('go2-language',lang);window.Go2Locale?.setLanguage(lang);translate();};
document.addEventListener('visibilitychange',()=>{if(document.hidden&&control.state?.state==='streaming')control.stop();});
translate();
try{clips=(await json('/api/audio/library')).clips;renderList();}catch{libraryError=true;}
await load();if(control.state?.connected)await control.refresh();
setInterval(load,600);render();

function filterSearch(){
 const needle=query.toLocaleLowerCase();let count=0;
 document.querySelectorAll('#clips>[role=listitem]').forEach(row=>{row.hidden=!!needle&&!row.textContent.toLocaleLowerCase().includes(needle);if(!row.hidden)count++;});
 $('clip-count').textContent=count+' '+t('count');$('no-results').textContent=t('noResults');$('no-results').hidden=count>0||!clips.length;
}
function extras(){ $('search').placeholder=t('search');$('search').setAttribute('aria-label',t('search'));$('import-audio').textContent=t('importAudio');$('preview-phone').textContent=t(preview?'stopPreview':'previewPhone');$('preview-phone').disabled=!control.state?.clip?.presetId;filterSearch();}
$('search').oninput=()=>{query=$('search').value;filterSearch();};
$('import-audio').onclick=()=>{stopPreview();if(window.Go2App)Go2App.openPage('audio_import');else location.href='/#audio';};
$('preview-phone').onclick=async()=>{if(preview){stopPreview();return;}const id=control.state?.clip?.presetId;if(!id)return;preview=new Audio('/api/audio/preset/'+encodeURIComponent(id));const current=preview;current.onended=()=>{if(preview===current)stopPreview();};current.onerror=()=>{if(preview===current){stopPreview();$('status').textContent=t('previewError');}};try{await current.play();extras();}catch{stopPreview();$('status').textContent=t('previewError');}};
$('clips').addEventListener('click',stopPreview);
$('play').addEventListener('click',stopPreview);$('stop').addEventListener('click',stopPreview);
document.addEventListener('visibilitychange',()=>{if(document.hidden)stopPreview();});
window.addEventListener('pagehide',stopPreview);
new MutationObserver(extras).observe($('now-title'),{childList:true});
new MutationObserver(()=>{filterSearch();}).observe($('clips'),{childList:true});
$('language').addEventListener('click',extras);extras();

window.go2AudioStopPreview=stopPreview;
