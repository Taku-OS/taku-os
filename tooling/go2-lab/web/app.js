const messages = {
  zh: {readOnly:'手动微动',title:'我的机器狗',subtitle:'看看它现在的状态。',battery:'电池电量',voltage:'电池电压',height:'机身高度',connect:'连接机器狗',disconnect:'断开连接',safety:'轻微动作需在微动页手动启用',details:'连接详情',path:'连接路径',samples:'收到的数据',footer:'需要 Mac 连接服务保持运行，并与机器狗处于同一网络。',disconnected:'未连接',connecting:'正在连接',connected:'已连接',error:'连接失败',offline:'Mac 服务未连接',fresh:'实时读数',stale:'上次读数',waiting:'等待读数',liveNote:'正在接收机器狗的实时状态。',idleNote:'点击连接，读取电量与状态。',connectingNote:'正在建立本地连接并验证通信，请稍候。',staleNote:'状态暂未更新，以上读数不能代表当前状态。',offlineNote:'无法访问 Mac 连接服务。请确认服务与设备连接通道正在运行。',errorNote:'连接未完成。请确认狗在线，退出其他控制会话后重试。',actionFailed:'请求未完成，请查看连接状态后重试。'},
  en: {readOnly:'Manual pulses',title:'My robot dog',subtitle:'A look at how it is doing.',battery:'Battery level',voltage:'Battery voltage',height:'Body height',connect:'Connect',disconnect:'Disconnect',safety:'Enable small movements manually on the Move page',details:'Connection details',path:'Connection',samples:'Received samples',footer:'Keep the Mac bridge running and the Mac and robot on the same network.',disconnected:'Disconnected',connecting:'Connecting',connected:'Connected',error:'Connection failed',offline:'Mac bridge unavailable',fresh:'Live reading',stale:'Last reading',waiting:'Waiting for data',liveNote:'Receiving live state from your robot.',idleNote:'Connect to read battery and status.',connectingNote:'Establishing and validating the local connection.',staleNote:'Updates have stopped. These readings may be out of date.',offlineNote:'Cannot reach the Mac bridge. Check the service and device tunnel.',errorNote:'Connection did not complete. Check the robot is online and close other control sessions before retrying.',actionFailed:'Request did not complete. Check connection state before retrying.'}
};
Object.assign(messages.zh, {stateTab:'状态',environmentTab:'周围环境',environmentSubtitle:'来自机器狗激光雷达的实时点云。',cloudLabel:'实时点云；拖动或使用方向键旋转视角',topView:'俯视',resetView:'复位',cloudHint:'拖动旋转 · 颜色区分高度 · 自动居中显示',sourcePoints:'本帧点数',receivedFrames:'收到帧数',displayPoints:'完整显示',startScan:'开始查看',stopScan:'暂停查看',cloudSafety:'查看环境不会触发移动',scanLive:'正在接收',scanWaiting:'等待点云',scanIdle:'未开始',scanPaused:'已暂停',scanStale:'数据已过期',scanError:'点云处理异常',scanLiveNote:'显示环境轮廓，暂未标注狗的位置与前后方向。',scanIdleNote:'连接机器狗后，开始查看周围环境。',scanWaitingNote:'正在等待雷达数据；空白不代表没有障碍物。',scanPausedNote:'已暂停接收。画面保留最后一帧，不代表当前环境。',scanStaleNote:'点云未及时更新。保留的画面不能用于判断当前障碍物。',scanErrorNote:'暂时无法处理点云数据，请暂停后重试。',scanOfflineNote:'Mac 服务不可达，画面已停止实时更新。',scanAge:'最近收到',scanUnknown:'尚无数据'});
Object.assign(messages.en, {stateTab:'Status',environmentTab:'Surroundings',environmentSubtitle:'Live point cloud from your robot’s LiDAR.',cloudLabel:'Live point cloud; drag or use arrow keys to rotate',topView:'Top',resetView:'Reset',cloudHint:'Drag to rotate · Color shows height · Auto-centered',sourcePoints:'Source points',receivedFrames:'Received frames',displayPoints:'Full points',startScan:'Start viewing',stopScan:'Pause',cloudSafety:'Viewing surroundings does not trigger movement',scanLive:'Receiving',scanWaiting:'Waiting for points',scanIdle:'Not started',scanPaused:'Paused',scanStale:'Out of date',scanError:'Point cloud error',scanLiveNote:'Environmental contours. Robot position and heading are not marked.',scanIdleNote:'Connect the robot, then start viewing its surroundings.',scanWaitingNote:'Waiting for LiDAR. A blank view does not mean the path is clear.',scanPausedNote:'Reception paused. The last frame does not show the current environment.',scanStaleNote:'Updates have stopped. Do not use this frame to judge current obstacles.',scanErrorNote:'Could not process the point cloud. Pause and retry.',scanOfflineNote:'Mac bridge unavailable. The view is no longer live.',scanAge:'Last received',scanUnknown:'No data yet'});
Object.assign(messages.zh,{drawRate:'画面绘制',dataRate:'点云更新',scanGpuError:'无法启用 WebGL',scanGpuErrorNote:'当前图形环境无法绘制完整点云，请检查 WebGL 支持。不会自动降为抽样显示。'});
Object.assign(messages.en,{drawRate:'Rendering',dataRate:'New clouds',scanGpuError:'WebGL unavailable',scanGpuErrorNote:'The graphics environment cannot render the full cloud. Check WebGL support. No automatic downsampling is applied.'});
Object.assign(messages.zh,{
  simulationTag:'离线模拟',replayTag:'历史回放',simulationTitle:'模拟机器狗',replayTitle:'历史状态',
  simulationSubtitle:'无需机器狗，在这里体验连接与控制。',replaySubtitle:'查看录制数据；所有运动入口已禁用。',
  simulationConnect:'连接模拟器',simulationLive:'正在接收模拟状态，数值不来自真实机器狗。',simulationIdle:'点击连接模拟器，再进入「避障」选择场景。',
  simulationFresh:'模拟读数',replayFresh:'录制读数',replay:'历史回放',replayNote:'正在展示录制数据；到「避障」页结束回放后再连接。',
  simulationFooter:'离线模拟仅需 Mac 服务运行，无需连接机器狗。',replayFooter:'历史数据不能代表机器狗当前状态。',
  realPath:'Taku Phone → Mac → Go2',simulationPath:'Taku Phone → Mac 模拟器',replayPath:'Mac 本地录制文件',
  simulationEnvironment:'模拟场景的完整点云，不来自真实雷达。',replayEnvironment:'录制中的点云；暂停和结束不会触发运动。',
  simulationMotionReady:'我确认顶部为离线模拟，动作仅影响模拟位置。',simulationSafety:'在「避障」页选择场景并体验受限动作',pageTabs:'页面'
});
Object.assign(messages.en,{
  simulationTag:'Simulation',replayTag:'Replay',simulationTitle:'Simulated robot',replayTitle:'Recorded state',
  simulationSubtitle:'Try connection and control without a robot.',replaySubtitle:'Recorded data; all movement is disabled.',
  simulationConnect:'Connect simulator',simulationLive:'Receiving simulated state. Values are not from a real robot.',simulationIdle:'Connect the simulator, then choose a scenario in Avoidance.',
  simulationFresh:'Simulated reading',replayFresh:'Recorded reading',replay:'Recorded replay',replayNote:'Showing recorded data. End replay in Avoidance before connecting.',
  simulationFooter:'Offline simulation needs only the Mac service. No robot connection is required.',replayFooter:'Historical data does not describe the robot’s current state.',
  realPath:'Taku Phone → Mac → Go2',simulationPath:'Taku Phone → Mac simulator',replayPath:'Mac recording file',
  simulationEnvironment:'Full point cloud from the simulated scene, not a real LiDAR.',replayEnvironment:'Recorded point clouds. Pause and end do not trigger movement.',
  simulationMotionReady:'The source is offline simulation; movement affects the simulated pose only.',simulationSafety:'Choose a scenario and try protected movements in Avoidance',pageTabs:'Pages'
});
let lang = window.Go2Locale?.getLanguage?.()||localStorage.getItem('go2-language')||(navigator.language.startsWith('zh')?'zh':'en');
const t = key => messages[lang][key];
function translate() {
  document.documentElement.lang = lang === 'zh' ? 'zh-CN' : 'en';
  document.title = `${t('title')} · Taku`;
  document.querySelectorAll('[data-i18n]').forEach(el => el.textContent = t(el.dataset.i18n));
  document.querySelectorAll('[data-i18n-aria]').forEach(el => el.setAttribute('aria-label', t(el.dataset.i18nAria)));
  document.getElementById('language').textContent = lang === 'zh' ? 'EN' : '中文';
  document.getElementById('language').setAttribute('aria-label', lang === 'zh' ? 'Switch to English' : '切换到中文');
}
translate();
const $ = id => document.getElementById(id);
let busy = false;
let state = null;
let receivedAt = 0;
let offlineState = true;
function render(s, offline = false) {
  const battery = s?.telemetry?.['rt/lf/lowstate'];
  const sport = s?.telemetry?.['rt/lf/sportmodestate'];
  const elapsed = performance.now() - receivedAt;
  const batteryFresh = battery?.fresh && battery.ageMs + elapsed <= 5000;
  const sportFresh = sport?.fresh && sport.ageMs + elapsed <= 5000;
  const live = !offline && s?.connected && batteryFresh;
  const phase = offline ? 'offline' : (s?.phase || 'disconnected');
  const simulation=s?.source==='simulation',replay=s?.source==='replay';
  for(const [id,real,sim,history] of [
    ['app-mode','readOnly','simulationTag','replayTag'],['app-title','title','simulationTitle','replayTitle'],
    ['app-subtitle','subtitle','simulationSubtitle','replaySubtitle'],['app-footer','footer','simulationFooter','replayFooter'],
    ['connection-path','realPath','simulationPath','replayPath'],['environment-subtitle','environmentSubtitle','simulationEnvironment','replayEnvironment'],
    ['state-safety','safety','simulationSafety','replayNote'],['motion-confirmation','motionReady','simulationMotionReady','replayNote']
  ]){if($(id))$(id).textContent=t(replay?history:simulation?sim:real)||'';}
  $('connect').textContent=t(simulation?'simulationConnect':'connect');
  document.title=`${t(replay?'replayTitle':simulation?'simulationTitle':'title')} · Taku`;
  document.body.dataset.live = String(!!live);
  $('status').textContent = t(phase);
  $('battery').textContent = battery?.values?.batteryPercent ?? '—';
  $('battery-fill').style.width = `${battery?.values?.batteryPercent ?? 0}%`;
  $('freshness').textContent = t(replay?'replayFresh':live ? simulation?'simulationFresh':'fresh' : battery?.values ? 'stale' : 'waiting');
  $('voltage').textContent = battery?.values?.voltage == null ? '—' : `${battery.values.voltage.toFixed(2)} V`;
  $('height').textContent = sport?.values?.bodyHeight == null ? '—' : `${(sport.values.bodyHeight * 100).toFixed(1)} cm`;
  $('ip').textContent = s?.robotIp || '—';
  $('samples').textContent = Object.values(s?.telemetry || {}).reduce((sum, item) => sum + item.count, 0);
  const note = offline ? 'offlineNote' : replay?'replayNote':phase === 'connecting' ? 'connectingNote' : phase === 'error' ? 'errorNote' : s?.connected ? live && sportFresh ? simulation?'simulationLive':'liveNote' : 'staleNote' : simulation?'simulationIdle':'idleNote';
  $('notice').textContent = t(note) + (s?.error ? ` (${s.error})` : '');
  window.dispatchEvent(new CustomEvent('go2-state',{detail:{state:s,offline,receivedAt}}));
  $('connect').disabled = offline || busy || replay || phase === 'connecting' || phase === 'connected';
  $('disconnect').disabled = offline || busy || !['connecting','connected'].includes(phase);
}
async function request(path, method = 'GET', payload = {}) {
  const response = await fetch(path, {method, cache:'no-store', signal:AbortSignal.timeout(12000), ...(method === 'POST' ? {headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)} : {})});
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}
async function poll() {
  try { state = await request('/api/state'); receivedAt = performance.now(); offlineState = false; render(state); }
  catch { offlineState = true; render(state, true); }
  setTimeout(poll, 1000);
}
async function command(action) {
  busy = true; render(state);
  try { state = await request(`/api/${action}`, 'POST'); receivedAt = performance.now(); render(state); }
  catch { $('notice').textContent = t('actionFailed'); }
  finally { busy = false; }
}
$('connect').onclick = () => command('connect');
$('disconnect').onclick = () => command('disconnect');
render(null, true);
poll();

setInterval(() => render(state, offlineState), 1000);

$('language').onclick = () => { lang = lang === 'zh' ? 'en' : 'zh'; localStorage.setItem('go2-language',lang);window.Go2Locale?.setLanguage(lang); translate(); render(state, offlineState); };
