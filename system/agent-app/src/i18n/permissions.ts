import type {Language} from './index';
import type {PermissionErrorCode, PermissionMode, PermissionScope, RelativeTime} from '../model/appPermissions';

const en = {
  title: 'App permissions', back: 'Back', close: 'Close', cancel: 'Cancel', retry: 'Try again',
  entrySubtitle: 'Third-party access to memory, Taku modules and Root', entryAllowed: '{n} allowed', entryRootForAll: 'Root for all apps',
  loading: 'Reading permissions…', updating: 'Updating…',
  readFailed: 'Couldn’t read app permissions', refreshFailed: 'Couldn’t refresh. Showing the last read.', updateFailed: 'Couldn’t update',
  errorUnavailable: 'The system permission service isn’t connected.', errorUnsupported: 'Update Taku to manage app permissions.',
  errorInvalid: 'The permission service returned an unreadable state.', errorDenied: 'This change isn’t allowed from here.',
  errorNotFound: 'This app is no longer installed.', errorConflict: 'Permissions changed elsewhere. Showing the latest.', errorGeneric: 'Check the system service and try again.',
  modeSection: 'Access mode', recommended: 'Recommended',
  'mode.deny': 'Block third-party apps', 'mode.deny.body': 'Only Taku system apps, the main Agent and ADB have access.',
  'mode.manual': 'Ask & manage manually', 'mode.manual.body': 'You allow each app yourself. Agent can’t grant access for you.',
  'mode.auto': 'Agent auto-review', 'mode.auto.body': 'Agent grants memory read and Taku modules to safe apps. Memory changes and Root still need you.',
  'mode.yolo': 'Open access (YOLO)', 'mode.yolo.body': 'All installed apps share memory and Taku modules automatically.',
  rootForAll: 'Allow Root execution for all apps', rootForAllOff: 'Root still needs approval for each app.', rootForAllOn: 'Every app has full control of this device.',
  recentSection: 'Recent requests', blocked: 'Blocked', allowed: 'Allowed', agentBlocked: 'Blocked by Agent', agentAllowed: 'Allowed by Agent', notInstalled: 'Not installed',
  appsSection: 'Third-party apps', search: 'Search apps or package names', clearSearch: 'Clear search',
  'filter.all': 'All', 'filter.allowed': 'Allowed', 'filter.custom': 'Customized', 'filter.root': 'Root',
  noApps: 'No third-party apps', noAppsBody: 'Apps you install will appear here.', noMatches: 'No matching apps', clearFilters: 'Clear filters',
  'source.manual': 'Manual', 'source.auto': 'Auto', disabledApp: 'Disabled',
  noAccess: 'No access', memoryRead: 'Memory read', memoryWrite: 'Memory write', memoryReadWrite: 'Memory read & write', takuModules: 'Taku modules', root: 'Root',
  'scope.memory.read': 'Read personal memory', 'scope.memory.write': 'Modify & manage memory',
  'scope.taku.control': 'Use Taku modules & AI', 'scope.taku.root': 'Root & native plugin execution',
  'scope.memory.read.hint': 'Search what you saw on screen and in notifications.', 'scope.memory.write.hint': 'Edit or delete your memories.',
  'scope.taku.control.hint': 'Use Home cards, voice and AI requests.', 'scope.taku.root.hint': 'Run any command with full control of this device.',
  highRisk: 'High risk', version: 'Version',
  'note.rootForAll': 'All apps have Root in Open access.', 'note.savedPaused': 'Saved. Not active in this mode.',
  'note.byAgent': 'Allowed by Agent.', 'note.byOpenAccess': 'Allowed for all apps in Open access.', 'note.needsYou': 'Only you can allow this.',
  agentAssessment: 'Agent assessment', denyBanner: 'Third-party apps are blocked. Switch to manual approval to allow this app.', switchToManual: 'Allow manual approval',
  resetToMode: 'Reset to mode default',
  confirmRootTitle: 'Allow Root for {app}?', confirmRootBody: '{app} will be able to run any command as root, with full control of this device and all of its data. Allow only if you trust its developer.', confirmRootAction: 'Allow Root',
  confirmRootAllTitle: 'Allow Root for all apps?', confirmRootAllBody: 'Every installed app, and any you install later, gets full control of this device, including all data and any command.', confirmRootAllAction: 'Allow Root for all',
  confirmYoloTitle: 'Open access for all apps?', confirmYoloBody: 'Every app will be able to read and change your personal memory and use Taku modules and AI. Root stays off.', confirmYoloAction: 'Open access',
  justNow: 'Just now', minutesAgo: '{n} min ago', appCountOne: '1 app', appCount: '{n} apps',
} as const;

export type PermissionTextKey = keyof typeof en;

const zh: Record<PermissionTextKey, string> = {
  title: '应用权限', back: '返回', close: '关闭', cancel: '取消', retry: '重试',
  entrySubtitle: '管理第三方应用对记忆、Taku 模块与 Root 的访问', entryAllowed: '{n} 个应用已允许', entryRootForAll: '所有应用可用 Root',
  loading: '正在读取权限…', updating: '正在更新…',
  readFailed: '无法读取应用权限', refreshFailed: '刷新失败，当前显示上次读取的状态。', updateFailed: '未能更新',
  errorUnavailable: '系统权限服务尚未连接。', errorUnsupported: '请更新 Taku 系统后管理应用权限。',
  errorInvalid: '权限服务返回的状态无法识别。', errorDenied: '这项修改不允许在此处进行。',
  errorNotFound: '该应用已不在设备上。', errorConflict: '权限已在其他位置变更，当前显示最新状态。', errorGeneric: '请检查系统服务后重试。',
  modeSection: '访问模式', recommended: '推荐',
  'mode.deny': '禁止第三方应用', 'mode.deny.body': '仅 Taku 系统应用、主 Agent 与 ADB 可以访问。',
  'mode.manual': '手动授权', 'mode.manual.body': '每个第三方应用都需你逐项允许，Agent 无权代为授权。',
  'mode.auto': 'Agent 自动评估', 'mode.auto.body': 'Agent 为安全应用授予记忆读取与 Taku 模块，修改记忆与 Root 仍需你批准。',
  'mode.yolo': '完全开放（YOLO）', 'mode.yolo.body': '所有已安装应用自动共享记忆与 Taku 模块。',
  rootForAll: '允许所有应用执行 Root 命令', rootForAllOff: 'Root 仍需逐个应用批准。', rootForAllOn: '所有应用都能完全控制这台设备。',
  recentSection: '最近请求', blocked: '已拦截', allowed: '已允许', agentBlocked: 'Agent 已拦截', agentAllowed: 'Agent 已允许', notInstalled: '已卸载',
  appsSection: '第三方应用', search: '搜索应用名称或包名', clearSearch: '清除搜索',
  'filter.all': '全部', 'filter.allowed': '已允许', 'filter.custom': '已自定义', 'filter.root': 'Root',
  noApps: '暂无第三方应用', noAppsBody: '安装的应用会显示在这里。', noMatches: '没有匹配的应用', clearFilters: '清除筛选',
  'source.manual': '手动', 'source.auto': '自动评估', disabledApp: '已停用',
  noAccess: '无访问权限', memoryRead: '记忆只读', memoryWrite: '修改记忆', memoryReadWrite: '记忆读写', takuModules: 'Taku 模块', root: 'Root',
  'scope.memory.read': '读取个人记忆', 'scope.memory.write': '修改与管理记忆',
  'scope.taku.control': '调用 Taku 模块与 AI', 'scope.taku.root': 'Root 与底层插件执行',
  'scope.memory.read.hint': '可搜索你在屏幕和通知中看到的内容。', 'scope.memory.write.hint': '可编辑或删除你的记忆。',
  'scope.taku.control.hint': '可使用桌面卡片、语音与 AI 请求。', 'scope.taku.root.hint': '可执行任意命令，完全控制这台设备。',
  highRisk: '高风险', version: '版本',
  'note.rootForAll': '完全开放模式下所有应用均可使用 Root。', 'note.savedPaused': '已保存，当前模式下不生效。',
  'note.byAgent': '由 Agent 评估后允许。', 'note.byOpenAccess': '完全开放模式下对所有应用允许。', 'note.needsYou': '仅可由你手动允许。',
  agentAssessment: 'Agent 评估', denyBanner: '第三方应用当前全部被禁止。切换到手动授权后才能允许此应用。', switchToManual: '切换到手动授权',
  resetToMode: '恢复跟随全局模式',
  confirmRootTitle: '允许 {app} 使用 Root？', confirmRootBody: '{app} 将能以 Root 身份执行任意命令，完全控制这台设备及其全部数据。仅在你信任其开发者时允许。', confirmRootAction: '允许 Root',
  confirmRootAllTitle: '允许所有应用使用 Root？', confirmRootAllBody: '所有已安装及之后安装的应用都将完全控制这台设备，包括读取全部数据和执行任意命令。', confirmRootAllAction: '全部允许 Root',
  confirmYoloTitle: '对所有应用完全开放？', confirmYoloBody: '所有应用都能读取和修改你的个人记忆，并使用 Taku 模块与 AI。Root 仍保持关闭。', confirmYoloAction: '完全开放',
  justNow: '刚刚', minutesAgo: '{n} 分钟前', appCountOne: '1 个应用', appCount: '{n} 个应用',
};

const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

const zhAutoReasons: Record<string, string> = {
  'Built on this device and has no network access.': '在此设备上构建，且无网络访问权限。',
  'Built or installed locally and declares no network access.': '本地构建或安装，且未声明网络访问权限。',
  'Requests sensitive listener/SMS permissions alongside network access.': '同时申请了高敏感监听或短信权限与网络访问权限。',
  'Offline utility without network access; Taku module access allowed, memory kept off by default.': '无网络访问权限的离线工具；已允许调用 Taku 模块，个人记忆默认保持关闭。',
  'Locally installed network app; Taku modules allowed, enable personal memory manually if needed.': '本地安装的联网应用；已允许调用 Taku 模块，如需读取个人记忆请手动开启。',
  'Network-capable third-party app; manual approval required.': '具备联网能力的第三方应用，需手动批准。',
  'Reviewed by Taku AI.': '已由 Taku AI 完成安全评估。',
};

export type PermissionText = ((key: PermissionTextKey, values?: Record<string, string | number>) => string) & {
  mode: (mode: PermissionMode) => string;
  modeBody: (mode: PermissionMode) => string;
  scope: (scope: PermissionScope) => string;
  scopeHint: (scope: PermissionScope) => string;
  error: (code: PermissionErrorCode) => string;
  time: (time: RelativeTime) => string;
  appCount: (count: number) => string;
  autoReason: (reason: string) => string;
};

export function permissionTextFor(language: Language): PermissionText {
  const table = language === 'zh' ? zh : en;
  const t = ((key: PermissionTextKey, values: Record<string, string | number> = {}) =>
    table[key].replace(/\{(\w+)\}/g, (match, name: string) => name in values ? String(values[name]) : match)) as PermissionText;
  t.mode = mode => t(`mode.${mode}`);
  t.modeBody = mode => t(`mode.${mode}.body`);
  t.scope = scope => t(`scope.${scope}`);
  t.scopeHint = scope => t(`scope.${scope}.hint`);
  t.error = code => t(code === 'UNSUPPORTED' ? 'errorUnsupported' : code === 'UNAVAILABLE' ? 'errorUnavailable' : code === 'INVALID_RESPONSE' ? 'errorInvalid'
    : code === 'PERMISSION_DENIED' ? 'errorDenied' : code === 'NOT_FOUND' ? 'errorNotFound' : code === 'CONFLICT' ? 'errorConflict' : 'errorGeneric');
  t.time = time => time.kind === 'now' ? t('justNow') : time.kind === 'minutes' ? t('minutesAgo', {n: time.value}) : time.kind === 'time' ? time.value
    : language === 'zh' ? `${time.month}月${time.day}日` : `${months[time.month - 1]} ${time.day}`;
  t.appCount = count => count === 1 ? t('appCountOne') : t('appCount', {n: count});
  t.autoReason = reason => (language === 'zh' && reason in zhAutoReasons ? zhAutoReasons[reason] : reason);
  return t;
}
