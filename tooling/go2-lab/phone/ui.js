// Only served by the phone runtime; Mac and VM wording remains unchanged.
Object.assign(messages.zh, {
  sourceReal:'Go2 · 本地 Wi-Fi',
  cameraConnect:'先在「我的设备」连接 Go2，再开启摄像头。',
  safety:'控制是否可用，请查看「行进控制」页面。',
  motionIntro:'使用下方手掌图标人工接管。启用后，每次轻点发送一个短时动作。',
  navIntro:'手机迁移预览：模拟自主导航尚未稳定，会因数据过期保护停止。地图准备和观察可用，请勿当作已验收功能。',
  navBoundary:'当前手机上的模拟自主导航未通过完整验收；真实自主导航也保持禁用。手动控制需在微动页现场确认。',
  footer:'手机本机服务正在运行；直连时需与机器狗在同一 Wi-Fi。',
  offline:'手机服务未启动', offlineNote:'请打开连接设置，启动手机本机服务。',
  scanOfflineNote:'手机服务不可达，画面已停止实时更新。',
  simulationFooter:'模拟服务完全运行在这台手机，无需 Mac、USB 或真实机器狗。',
  realPath:'Pixel → Wi-Fi → Go2', simulationPath:'Pixel → 手机本机模拟器', replayPath:'手机本地录制文件',
  cameraNote:'帧龄从手机收到视频帧开始计算，不包含狗端采集和传输耗时。最多显示 12 帧/秒，实际帧率以当前读数为准。',
  motionSpeedWaiting:'等待手机服务返回速度设置。',
  audioNote:'支持 WAV、MP3、M4A 等音频，最多 10 MB、60 秒。录音保存在这台手机，重新选择会替换原录音；播放时需要保持连接。',
  audioSaveFailed:'录音无法保存，请检查手机存储。',
  amapKey:'请配置个人高德 Web 服务 Key。',
  amapOffline:'地图服务未启动，请在连接设置重新启动本机服务。',
  amapConfig:'个人 Key 保存在手机 Termux 私有目录，不包含在 APK 中。搜索和规划需要互联网；狗的局域网连接不依赖地图服务。'
});
Object.assign(messages.en, {
  sourceReal:'Go2 · Local Wi-Fi',
  cameraConnect:'Connect Go2 from My Device, then start the camera.',
  safety:'Check Movement for available controls.',
  motionIntro:'Use the hand icon below to take over. Once enabled, each tap sends one short movement.',
  navIntro:'Phone migration preview: autonomous simulation is not yet reliable and stops on stale data. Map preparation and observations are available; navigation is not validated.',
  navBoundary:'Autonomous simulation has not passed full phone acceptance. Real autonomous navigation remains disabled. Manual controls require confirmation on the Move page.',
  footer:'The service runs on this phone. For direct control, join the robot’s Wi-Fi network.',
  offline:'Phone service stopped', offlineNote:'Open connection settings to start the phone service.',
  scanOfflineNote:'Phone service unavailable. The view is no longer live.',
  simulationFooter:'Simulation runs entirely on this phone. No Mac, USB or real robot needed.',
  realPath:'Pixel → Wi-Fi → Go2', simulationPath:'Pixel → On-phone simulator', replayPath:'Phone recording file',
  cameraNote:'Frame age starts when the phone receives a frame, excluding capture and transport delay. Up to 12 fps; actual performance is shown in the current readings.',
  motionSpeedWaiting:'Waiting for speed settings from the phone service.',
  audioNote:'WAV, MP3, M4A and other audio formats; up to 10 MB and 60 seconds. Saved on this phone. A new clip replaces the previous one. Keep the connection open while playing.',
  audioSaveFailed:'Cannot save the recording. Check phone storage.',
  amapKey:'Configure a personal AMap Web Service key.',
  amapOffline:'Map service stopped. Restart the phone service in connection settings.',
  amapConfig:'Your key stays in private Termux storage, outside the APK. Search and route planning need internet. Robot LAN connections do not depend on maps.'
});
translate();
const phoneErrors = {
  zh: {
    LocalSignalingPortError:'无法访问狗的连接端口。请核对当前 IP、同一热点，以及 VPN 局域网权限和热点客户端隔离。',
    RobotBusyError:'机器狗拒绝了连接。请先断开宇树 App 或其他控制端，再重试。',
    AesKeyRequiredError:'此固件需要本人宇树账号下的设备专属密钥，请在连接设置中填写。',
    AesKeyRejectedError:'设备密钥未能解密响应，请在连接设置中更换为这台狗的密钥。',
    DataChannelTimeoutError:'WebRTC 通道超时。请检查热点客户端隔离、VPN 局域网访问，并退出其他控制端。',
    TimeoutError:'连接超时，请检查网络和设备状态后重试。',
    ConnectionError:'连接已中断，请检查 Wi-Fi 后重新连接。运动权限需要再次确认。',
    NoSdpAnswerError:'此 IP 未返回有效的 Go2 连接信息，请核对 IP 和固件兼容性。'
  },
  en: {
    LocalSignalingPortError:'Robot signaling ports are unreachable. Check the IP, shared hotspot, VPN LAN access and client isolation.',
    RobotBusyError:'The robot rejected the connection. Disconnect the Unitree app or another controller and retry.',
    AesKeyRequiredError:'This firmware needs the per-device key from your own Unitree account. Enter it in connection settings.',
    AesKeyRejectedError:'The device key could not decrypt the response. Replace it with the correct robot key in connection settings.',
    DataChannelTimeoutError:'WebRTC timed out. Check hotspot client isolation and VPN LAN access, and disconnect other controllers.',
    TimeoutError:'Connection timed out. Check the network and device before retrying.',
    ConnectionError:'Connection lost. Check Wi-Fi and reconnect. Motion permission must be confirmed again.',
    NoSdpAnswerError:'This IP did not return a valid Go2 session. Check the device IP and firmware compatibility.'
  }
};
window.addEventListener('go2-state', event => {
  const {state, offline} = event.detail;
  const intro=document.querySelector('[data-i18n="motionIntro"]');
  if(intro)intro.textContent=t(!offline&&state?.externalMotionControl?'motionPolicy':'motionIntro');
  if (!offline && state?.phase === 'error' && phoneErrors[lang][state.error])
    $('notice').textContent = phoneErrors[lang][state.error];
});
window.phoneControlOwner = 'unknown';
async function refreshControlOwner() {
  try {
    const state = await request('/api/assistant/state');
    window.phoneControlOwner = state.takingOver ? 'unknown' : state.owner;
  } catch { window.phoneControlOwner = 'unknown'; }
}
refreshControlOwner(); setInterval(refreshControlOwner, 1000);
