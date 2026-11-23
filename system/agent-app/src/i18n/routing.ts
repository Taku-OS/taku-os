import type {Language} from './index';

const en = {
  title: 'Model groups and scenes',
  entrySubtitle: 'Edit group members and bind scenes by ID',
  groupsTitle: 'Model groups',
  groupsHint: 'A group lists model IDs in the order they are tried.',
  addGroup: 'Add model group',
  editGroup: 'Edit model group',
  groupId: 'Group ID, such as my-group',
  groupName: 'Group name',
  groupMembers: 'Model IDs, one per line',
  noMembers: 'No models',
  save: 'Save',
  cancel: 'Cancel',
  scenesTitle: 'Scenes',
  scenesHint: 'Each scene sends its requests to one model group.',
  addScene: 'Add scene binding',
  sceneId: 'Scene ID, such as memory.text',
  chooseGroup: 'Select a model group for {scene}',
  unbound: 'No model group selected',
  removeScene: 'Remove binding',
  addGroupFirst: 'Add a model group first.',
  saved: 'Saved',
  errorGroup: 'Check the group ID and name. IDs use letters, digits, hyphens and underscores.',
  errorMembers: 'Enter at least one model ID, one per line.',
  errorExists: 'A group with this ID already exists.',
  errorMissing: 'This group no longer exists. Refresh and try again.',
  errorScene: 'Enter a valid scene ID using letters, digits, dots, hyphens and underscores.',
  errorAssistantMode: 'Switch the main assistant to a model service first.',
  errorGeneric: 'The change was not saved. The group may not support this scene. Try again.',
} as const;

export type RoutingTextKey = keyof typeof en;

const zh: Record<RoutingTextKey, string> = {
  title: '模型组与场景',
  entrySubtitle: '编辑模型组成员，并按 ID 绑定场景',
  groupsTitle: '模型组',
  groupsHint: '模型组按顺序列出依次尝试的模型 ID。',
  addGroup: '添加模型组',
  editGroup: '编辑模型组',
  groupId: '模型组 ID，例如 my-group',
  groupName: '模型组名称',
  groupMembers: '模型 ID，每行一个',
  noMembers: '暂无模型',
  save: '保存',
  cancel: '取消',
  scenesTitle: '场景',
  scenesHint: '每个场景的请求会发送到一个模型组。',
  addScene: '添加场景绑定',
  sceneId: '场景 ID，例如 memory.text',
  chooseGroup: '为 {scene} 选择模型组',
  unbound: '未选择模型组',
  removeScene: '移除绑定',
  addGroupFirst: '请先添加模型组。',
  saved: '已保存',
  errorGroup: '请检查模型组 ID 和名称。ID 仅可使用字母、数字、连字符和下划线。',
  errorMembers: '请至少输入一个模型 ID，每行一个。',
  errorExists: '已存在使用此 ID 的模型组。',
  errorMissing: '该模型组已不存在，请刷新后重试。',
  errorScene: '请输入有效的场景 ID，仅可使用字母、数字、点、连字符和下划线。',
  errorAssistantMode: '请先将主助手切换为模型服务。',
  errorGeneric: '更改未保存，该模型组可能不支持此场景。请重试。',
};

export type RoutingText = (key: RoutingTextKey, values?: Record<string, string>) => string;

export function routingTextFor(language: Language): RoutingText {
  const table = language === 'zh' ? zh : en;
  return (key, values) => {
    let text: string = table[key];
    if (values) for (const [name, value] of Object.entries(values)) text = text.replace(`{${name}}`, value);
    return text;
  };
}
