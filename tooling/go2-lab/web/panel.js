"use strict";
const copy = {
  en: {
    myDevice: "My Device",
    skills: "Skills",
    settings: "Settings",
    yourRobot: "Your robot, your way.",
    sixSkills: "6 SKILLS",
    vision: "VISION",
    onPhone: "ON PHONE",
    camera: "Live Camera",
    cameraNote: "See, track & guide",
    sounds: "Voice & Sounds",
    soundsNote: "Presets, playback & volume",
    move: "Movement",
    moveNote: "Direction & pace",
    surroundings: "Surroundings",
    surroundingsNote: "Live LiDAR view",
    navigate: "Navigation",
    navigateNote: "Location & route planning",
    avoidance: "Obstacle Assist",
    avoidanceNote: "Clearance & safeguards",
    deviceDetails: "Device details",
    local: "Made for your Go2.",
    checking: "Checking",
    offline: "Offline",
    live: "Live",
    stale: "No updates",
    connecting: "Connecting",
    locked: "Locked",
    unlocked: "Enabled",
    connect: "Connect robot",
    disconnect: "Disconnect robot",
    close: "Close",
    cancel: "Cancel",
    retry: "Retry",
    openSettings: "Connection settings",
    ready: "Camera, voice and device status are ready.",
    idle: "Connect to your Go2 on the same Wi-Fi.",
    lost: "No recent device data. Check the connection.",
    serviceOffline:
      "Connection service is unavailable. Open Settings to restart.",
    failure: "Could not connect. Check Wi-Fi and device settings.",
    simulation: "Simulation · Values are not from a real robot.",
    replay: "Replay · Recorded device data.",
    disconnectNote:
      "The live view and voice playback will stop. You can reconnect any time.",
    connectionTitle: "Your connection",
    lockTitle: "App movement controls",
    lockNote:
      "Movement is currently locked in this app. Open Movement to review control availability. Camera and voice remain available.",
    unlockedNote:
      "App movement controls are enabled. Lock them to stop app-controlled movement.",
    lockNow: "Lock controls",
    lockedDone: "App movement controls locked.",
    skillsNote: "Choose what you want to do with Go2.",
    advanced: "All controls & status",
    requestFailed: "Could not complete the request. Please try again.",
    noNative: "Open this page in the Go2 app to change device settings.",
  },
  zh: {
    myDevice: "我的设备",
    skills: "技能",
    settings: "设置",
    yourRobot: "让机器狗听你安排",
    sixSkills: "6 项能力",
    vision: "视觉",
    onPhone: "已存手机",
    camera: "实时摄像头",
    cameraNote: "查看、识别与行动建议",
    sounds: "语音与声音",
    soundsNote: "预设音频、播放与音量",
    move: "行进控制",
    moveNote: "方向与速度",
    surroundings: "周围环境",
    surroundingsNote: "实时激光雷达视图",
    navigate: "定位与导航",
    navigateNote: "定位状态与路线规划",
    avoidance: "避障辅助",
    avoidanceNote: "环境净空与防护",
    deviceDetails: "设备详情",
    local: "专为你的 Go2 设计。",
    checking: "检查中",
    offline: "未连接",
    live: "实时",
    stale: "未更新",
    connecting: "连接中",
    locked: "已锁定",
    unlocked: "已启用",
    connect: "连接机器狗",
    disconnect: "断开机器狗",
    close: "关闭",
    cancel: "取消",
    retry: "重试",
    openSettings: "连接设置",
    ready: "摄像头、语音与设备状态已就绪。",
    idle: "在同一 Wi-Fi 下连接你的 Go2。",
    lost: "暂未收到最新数据，请检查连接。",
    serviceOffline: "连接服务不可用，请在设置中重新启动。",
    failure: "连接未完成，请检查 Wi-Fi 与设备设置。",
    simulation: "模拟模式 · 数值不来自真实机器狗。",
    replay: "历史回放 · 正在显示录制数据。",
    disconnectNote: "实时画面与语音播放将停止，你可以随时重新连接。",
    connectionTitle: "连接状态",
    lockTitle: "App 行进控制",
    lockNote:
      "App 中的行进控制已锁定。进入行进控制查看可用状态，摄像头与语音仍可使用。",
    unlockedNote: "App 行进控制已启用，锁定将停止 App 控制的移动。",
    lockNow: "锁定控制",
    lockedDone: "App 行进控制已锁定。",
    skillsNote: "选择你想让 Go2 完成的事情。",
    advanced: "全部控制与状态",
    requestFailed: "操作未完成，请重试。",
    noNative: "请在 Go2 App 中修改设备设置。",
  },
};
let language =
  window.Go2Locale?.getLanguage?.() ||
  localStorage.getItem("go2-language") ||
  (navigator.language.startsWith("zh") ? "zh" : "en");
if (!copy[language]) language = "en";
const $ = (id) => document.getElementById(id),
  t = (key) => copy[language][key];
let state = null,
  offline = true,
  busy = false,
  receivedAt = 0,
  toastTimer,
  pollTimer,
  polling = false;
function translate() {
  document.documentElement.lang = language === "zh" ? "zh-CN" : "en";
  document
    .querySelectorAll("[data-copy]")
    .forEach((el) => (el.textContent = t(el.dataset.copy)));
  $("language").textContent = language === "zh" ? "EN" : "中文";
  $("language").setAttribute(
    "aria-label",
    language === "zh" ? "Switch to English" : "切换到中文",
  );
  $("sheet-close").setAttribute("aria-label", t("close"));
  render();
}
function toast(message) {
  $("toast").textContent = message;
  $("toast").hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => ($("toast").hidden = true), 4500);
}
async function request(path, post = false) {
  const response = await fetch(path, {
    cache: "no-store",
    signal: AbortSignal.timeout(post ? 12000 : 5000),
    ...(post
      ? {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: "{}",
        }
      : {}),
  });
  if (!response.ok) throw Error("HTTP " + response.status);
  return response.json();
}
function render() {
  const starting = Boolean(window.Go2App?.isConnecting?.());
  $("settings").disabled = starting;
  const elapsed = performance.now() - receivedAt,
    battery = state?.telemetry?.["rt/lf/lowstate"];
  const fresh =
    !offline &&
    state?.connected &&
    battery?.fresh &&
    Number.isFinite(battery.ageMs) &&
    battery.ageMs + elapsed < 5000;
  const phase = starting ? "connecting" : offline ? "offline" : state?.phase;
  document.body.dataset.live = String(!!fresh);
  document.body.dataset.phase = phase || "offline";
  $("connection-label").textContent = t(
    phase === "connecting"
      ? "connecting"
      : state?.connected && !offline
        ? fresh
          ? "live"
          : "stale"
        : "offline",
  );
  $("battery-value").textContent =
    fresh && Number.isFinite(battery?.values?.batteryPercent)
      ? Math.round(battery.values.batteryPercent) + "%"
      : "—";
  $("battery-value").parentElement.setAttribute(
    "aria-label",
    language === "zh" ? "电量与设备详情" : "Battery and device details",
  );
  $("lock-label").textContent = t(
    !offline && state?.motion?.armed ? "unlocked" : "locked",
  );
  $("power").disabled = busy || starting || phase === "connecting";
  $("power").setAttribute(
    "aria-busy",
    String(busy || starting || phase === "connecting"),
  );
  $("power").setAttribute(
    "aria-label",
    t(state?.connected ? "disconnect" : "connect"),
  );
  $("connection-note").textContent = t(
    starting
      ? "connecting"
      : state?.source === "simulation" && !offline
        ? "simulation"
        : state?.source === "replay" && !offline
          ? "replay"
          : offline
            ? "serviceOffline"
            : phase === "connecting"
              ? "connecting"
              : phase === "error"
                ? "failure"
                : state?.connected
                  ? fresh
                    ? "ready"
                    : "lost"
                  : "idle",
  );
  $("details-caption").textContent = state?.robotIp || "Go2 Pro";
}
function sheet(title, description, actions = [], content = []) {
  $("sheet-title").textContent = title;
  $("sheet-description").textContent = description;
  $("sheet-content").replaceChildren(...content);
  $("sheet-actions").replaceChildren(
    ...actions.map(({ label, run, secondary }) => {
      const b = document.createElement("button");
      b.textContent = label;
      if (secondary) b.className = "secondary";
      b.onclick = () => {
        $("sheet").close();
        run?.();
      };
      return b;
    }),
  );
  $("sheet").showModal();
}
function settings() {
  if (window.Go2App) window.Go2App.settings();
  else toast(t("noNative"));
}
function openPage(page) {
  if (window.Go2App) window.Go2App.openPage(page);
  else {
    const path =
      page === "audio"
        ? "/audio-library"
        : page === "follow_view"
          ? "/follow-demo"
          : page === "navigator"
            ? "/navigator"
            : "/#" + page;
    location.href = path;
  }
}
async function command(action) {
  if (busy) return;
  busy = true;
  render();
  try {
    await request("/api/" + action, true);
    await refresh();
  } catch {
    toast(t("requestFailed"));
  } finally {
    busy = false;
    render();
  }
}
async function refresh() {
  try {
    state = await request("/api/state");
    receivedAt = performance.now();
    offline = false;
  } catch {
    offline = true;
  }
  render();
}
async function poll() {
  if (polling) return;
  clearTimeout(pollTimer);
  polling = true;
  try {
    await refresh();
  } finally {
    polling = false;
    if (!document.hidden) pollTimer = setTimeout(poll, 1500);
  }
}
setInterval(() => {
  if (!document.hidden) render();
}, 1000);
document
  .querySelectorAll("[data-page]")
  .forEach((el) => (el.onclick = () => openPage(el.dataset.page)));
$("language").onclick = () => {
  language = language === "en" ? "zh" : "en";
  localStorage.setItem("go2-language", language);
  window.Go2Locale?.setLanguage?.(language);
  translate();
};
$("settings").onclick = settings;
$("sheet-close").onclick = () => $("sheet").close();
$("sheet").addEventListener("click", (e) => {
  if (e.target === $("sheet")) {
    const r = $("sheet").getBoundingClientRect();
    if (
      e.clientX < r.left ||
      e.clientX > r.right ||
      e.clientY < r.top ||
      e.clientY > r.bottom
    )
      $("sheet").close();
  }
});
$("power").onclick = () => {
  if (offline) return settings();
  if (state?.connected) {
    sheet(t("disconnect"), t("disconnectNote"), [
      { label: t("cancel"), secondary: true },
      { label: t("disconnect"), run: () => command("disconnect") },
    ]);
  } else command("connect");
};
$("connection").onclick = () =>
  sheet(t("connectionTitle"), $("connection-note").textContent, [
    { label: t("openSettings"), run: settings },
    {
      label: t("deviceDetails"),
      secondary: true,
      run: () => openPage("state"),
    },
  ]);
$("control-lock").onclick = () =>
  sheet(
    t("lockTitle"),
    t(state?.motion?.armed && !offline ? "unlockedNote" : "lockNote"),
    [
      { label: t("move"), secondary: true, run: () => openPage("motion") },
      ...(!offline && state?.motion?.armed
        ? [{ label: t("lockNow"), run: () => command("motion/stop") }]
        : []),
    ],
  );
$("skills").onclick = () => {
  const entries = [
    ["camera", "camera", "follow_view"],
    ["sounds", "sound", "audio"],
    ["move", "move", "motion"],
    ["surroundings", "radar", "environment"],
    ["navigate", "navigation", "navigator"],
    ["avoidance", "shield", "avoidance"],
    ["advanced", "info", "state"],
  ];
  sheet(
    t("skills"),
    t("skillsNote"),
    [],
    entries.map(([label, icon, page]) => {
      const b = document.createElement("button");
      b.className = "skill-link";
      b.innerHTML =
        '<svg><use href="#icon-' +
        icon +
        '"/></svg><span></span><svg><use href="#icon-arrow"/></svg>';
      b.querySelector("span").textContent = t(label);
      b.onclick = () => {
        $("sheet").close();
        openPage(page);
      };
      return b;
    }),
  );
};
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) poll();
  else clearTimeout(pollTimer);
});
translate();
poll();
