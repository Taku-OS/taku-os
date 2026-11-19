import type {Language} from './index';

const en = {
  title: 'Taku AI',
  back: 'Back',
  readingSettings: 'Reading settings…',
  unavailableTitle: 'System AI is temporarily unavailable',
  unavailableBody: 'Check the system service and retry',
  retry: 'Retry',
  refreshFailed: 'Could not refresh. Showing the last settings read. Tap to retry.',
  enableTitle: 'Enable Taku AI',
  enableSubtitle: 'Turning off pauses new AI requests',
  featuresTitle: 'Features',
  featuresSubtitle: 'Choose a model group for each feature. Changes apply immediately.',
  'scene.assistant': 'Main assistant',
  'scene.ime.vision': 'Input method vision',
  'scene.screen': 'Screen reading',
  'scene.memory.text': 'Memory',
  'scene.jev': 'Jev',
  'scene.org.agentos.assistant.text': 'App text processing',
  'group.route-memory': 'Text model',
  'group.route-vision': 'Vision model',
  'group.route-assistant': 'Main assistant model',
  'group.taku-memory-jev': 'Taku Memory',
  customModelGroup: 'Custom model group',
  unnamedModel: 'Unnamed model',
  noModelGroupSelected: 'No model group selected',
  modelGroupUnavailable: 'Model group unavailable',
  chatGptSubscription: 'ChatGPT subscription',
  paused: 'Paused',
  manageTitle: 'Manage models and providers',
  manageSubtitle: 'Configure models, providers, and model groups in Magpie',
  manageLink: 'Manage models and providers  ›',
  updated: 'Updated',
  undone: 'Undone',
  undo: 'Undo',
  switchAssistantFirst: 'Switch the main assistant to a model service first',
  updateFailed: 'Update failed. Check the current settings and retry',
  openMagpieFailed: 'Could not open Magpie management. Retry later',
  subscriptionAlertTitle: 'The main assistant currently uses a ChatGPT subscription',
  subscriptionAlertBody: 'Switch to a model service in Main assistant connection before selecting a model.',
  cancel: 'Cancel',
  openConnection: 'Open connection',
  selectModelGroup: 'Select model group',
  appliesImmediately: 'Applies immediately after selection',
  noModels: 'No models',
  noAvailableGroupsTitle: 'No available model groups',
  noAvailableGroupsBody: 'Configure a model group supporting this feature in Magpie.',
} as const;

export type SystemAiTextKey = keyof typeof en;

const zh: Record<SystemAiTextKey, string> = {
  title: 'Taku AI',
  back: '返回',
  readingSettings: '正在读取设置…',
  unavailableTitle: '系统 AI 暂不可用',
  unavailableBody: '请检查系统服务后重试',
  retry: '重试',
  refreshFailed: '刷新失败，当前显示上次读取的设置。点按重试。',
  enableTitle: '启用 Taku AI',
  enableSubtitle: '关闭后将暂停新的 AI 请求',
  featuresTitle: '功能路由',
  featuresSubtitle: '为每项功能选择模型组，修改立即生效。',
  'scene.assistant': '主助手',
  'scene.ime.vision': '输入法视觉',
  'scene.screen': '屏幕阅读',
  'scene.memory.text': '记忆',
  'scene.jev': 'Jev',
  'scene.org.agentos.assistant.text': '应用文本处理',
  'group.route-memory': '文本模型',
  'group.route-vision': '视觉模型',
  'group.route-assistant': '主助手模型',
  'group.taku-memory-jev': 'Taku 记忆',
  customModelGroup: '自定义模型组',
  unnamedModel: '未命名模型',
  noModelGroupSelected: '未选择模型组',
  modelGroupUnavailable: '模型组不可用',
  chatGptSubscription: 'ChatGPT 订阅',
  paused: '已暂停',
  manageTitle: '管理模型与服务商',
  manageSubtitle: '在 Magpie 中配置模型、服务商与模型组',
  manageLink: '管理模型与服务商  ›',
  updated: '已更新',
  undone: '已撤销',
  undo: '撤销',
  switchAssistantFirst: '请先将主助手切换为模型服务',
  updateFailed: '更新失败，请检查当前设置后重试',
  openMagpieFailed: '无法打开 Magpie 管理页，请稍后重试',
  subscriptionAlertTitle: '主助手当前正在使用 ChatGPT 订阅',
  subscriptionAlertBody: '请先在「主助手连接」中切换为模型服务，再选择模型组。',
  cancel: '取消',
  openConnection: '打开连接设置',
  selectModelGroup: '选择模型组',
  appliesImmediately: '选择后立即生效',
  noModels: '暂无模型',
  noAvailableGroupsTitle: '暂无可用模型组',
  noAvailableGroupsBody: '请在 Magpie 中配置支持此功能的模型组。',
};

export type SystemAiScene =
  | 'assistant'
  | 'ime.vision'
  | 'screen'
  | 'memory.text'
  | 'jev'
  | 'org.agentos.assistant.text';

export type SystemAiText = ((key: SystemAiTextKey) => string) & {
  scene: (scene: SystemAiScene) => string;
  builtInGroup: (groupId: string) => string | undefined;
};

const builtInGroupKeys: Record<string, SystemAiTextKey> = {
  'route-memory': 'group.route-memory',
  'route-vision': 'group.route-vision',
  'route-assistant': 'group.route-assistant',
  'taku-memory-jev': 'group.taku-memory-jev',
};

export function systemAiTextFor(language: Language): SystemAiText {
  const table = language === 'zh' ? zh : en;
  const t = ((key: SystemAiTextKey) => table[key]) as SystemAiText;
  t.scene = scene => t(`scene.${scene}`);
  t.builtInGroup = groupId => (groupId in builtInGroupKeys ? t(builtInGroupKeys[groupId]) : undefined);
  return t;
}
