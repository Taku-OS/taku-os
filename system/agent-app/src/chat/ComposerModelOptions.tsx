import React, {useState} from 'react';
import {Pressable, StyleSheet, Text, View} from 'react-native';
import {chatColors, chatType, themedStyles} from './chatTheme';

const fieldOrder = ['model', 'effort', 'serviceTier', 'multiAgentMode', 'permissionMode', 'responseStyle'] as const;
type ComposerOption = typeof fieldOrder[number];
export type ComposerOptionPatch = Partial<Record<ComposerOption, string | null>>;
type ComposerOptionValues = Record<ComposerOption, string | null>;

type RuntimeModel = {
  model: string;
  displayName?: string;
  supportedReasoningEfforts?: {reasoningEffort: string}[];
  serviceTiers?: {id: string; name: string}[];
  additionalSpeedTiers?: string[];
  defaultServiceTier?: string | null;
};

export type ComposerOptionsSnapshot = {
  threadId: string;
  options: ComposerOptionValues;
  effective: ComposerOptionValues;
  models: RuntimeModel[];
};

const copy = {
  en: {
    model: 'Model', effort: 'Reasoning effort', serviceTier: 'Service tier', multiAgentMode: 'Multi-agent policy',
    permissionMode: 'Permissions', responseStyle: 'Response style', inherit: 'Use default', unavailable: 'No choices available',
  },
  zh: {
    model: '模型', effort: '推理强度', serviceTier: '服务等级', multiAgentMode: '多智能体策略',
    permissionMode: '权限', responseStyle: '回复风格', inherit: '使用默认设置', unavailable: '暂无可用选项',
  },
};

type Language = keyof typeof copy;
type LocalizedLabel = readonly [string, string];
const effortLabels: Record<string, LocalizedLabel> = {
  none: ['None', '无'], minimal: ['Minimal', '最低'], low: ['Low', '低'], medium: ['Medium', '中'], high: ['High', '高'],
  xhigh: ['Extra high', '很高'], max: ['Maximum', '最高'], ultra: ['Ultra', '超强'], persistent: ['Persistent', '持续'],
};
const tierLabels: Record<string, LocalizedLabel> = {
  default: ['Standard', '标准'], standard: ['Standard', '标准'], fast: ['Fast', '快速'],
  priority: ['Priority', '优先'], flex: ['Flex', '弹性'],
};
const fixedChoices = {
  multiAgentMode: {explicitRequestOnly: ['Only when requested', '仅在明确请求时'], proactive: ['Allow proactive delegation', '允许主动委派']},
  permissionMode: {full: ['Full access', '完全访问'], ask: ['Ask before acting', '操作前询问'], readOnly: ['Read only', '只读']},
  responseStyle: {concise: ['Concise', '简洁'], detailed: ['Detailed', '详细']},
} satisfies Record<Exclude<ComposerOption, 'model' | 'effort' | 'serviceTier'>, Record<string, LocalizedLabel>>;

type Choice = {value: string; label: string};
function choicesFor(field: ComposerOption, state: ComposerOptionsSnapshot, language: Language): Choice[] {
  const localized = (labels: LocalizedLabel) => labels[language === 'zh' ? 1 : 0];
  if (field === 'model') return state.models.map(model => ({value: model.model, label: model.displayName || model.model}));
  const model = state.models.find(entry => entry.model === state.effective.model);
  if (field === 'effort') return (model?.supportedReasoningEfforts ?? []).map(({reasoningEffort: value}) => ({
    value, label: Object.prototype.hasOwnProperty.call(effortLabels, value) ? localized(effortLabels[value]) : value,
  }));
  if (field === 'serviceTier') {
    const tiers = new Map<string, string>();
    if (model?.serviceTiers?.length) {
      for (const {id, name} of model.serviceTiers) tiers.set(id, name);
    } else {
      for (const id of model?.additionalSpeedTiers ?? []) tiers.set(id, id);
    }
    const fallback = model?.defaultServiceTier;
    if (fallback && !tiers.has(fallback)) tiers.set(fallback, fallback);
    return Array.from(tiers, ([value, label]) => ({
      value, label: Object.prototype.hasOwnProperty.call(tierLabels, value) ? localized(tierLabels[value]) : label || value,
    }));
  }
  return Object.entries(fixedChoices[field]).map(([value, labels]) => ({value, label: localized(labels)}));
}

export function ComposerModelOptions({language, value, busy, onChange}: {
  language: Language;
  value: ComposerOptionsSnapshot | null;
  busy: boolean;
  onChange: (options: ComposerOptionPatch) => void;
}) {
  const [open, setOpen] = useState<ComposerOption | null>(null);
  if (!value) return null;
  const label = copy[language];
  const chinese = language === 'zh';
  return <View style={styles.group}>
    {fieldOrder.map(field => {
      const choices = choicesFor(field, value, language);
      const current = value.options[field];
      const effective = value.effective[field];
      const effectiveLabel = choices.find(choice => choice.value === effective)?.label ?? effective;
      const currentLabel = current === null
        ? [label.inherit, effectiveLabel].filter(Boolean).join(' · ')
        : choices.find(choice => choice.value === current)?.label ?? current;
      const expanded = open === field;
      return <View key={field}>
        <Pressable style={styles.row} accessibilityRole="button" accessibilityState={{expanded, disabled: busy}}
          disabled={busy} onPress={() => setOpen(expanded ? null : field)}>
          <Text style={chatType.bodyMdMedium(chinese)}>{label[field]}</Text>
          <Text style={[chatType.bodyMd(chinese), styles.current]}>{currentLabel}</Text>
        </Pressable>
        {expanded && <View style={styles.choices}>
          {[{value: null, label: label.inherit}, ...choices].map(choice => {
            const selected = choice.value === current;
            return <Pressable key={choice.value === null ? 'inherit' : `option:${choice.value}`} accessibilityRole="radio" accessibilityState={{checked: selected, disabled: busy}}
              style={[styles.choice, selected && styles.selected]} disabled={busy}
              onPress={() => {
                if (!selected) onChange({[field]: choice.value});
                setOpen(null);
              }}>
              <Text style={chatType.bodyMd(chinese)}>{choice.label}</Text>
            </Pressable>;
          })}
          {choices.length === 0 && <Text style={[chatType.bodySm(chinese), styles.unavailable]}>{label.unavailable}</Text>}
        </View>}
      </View>;
    })}
  </View>;
}

const styles = themedStyles(() => StyleSheet.create({
  group: {gap: 4},
  row: {minHeight: 48, justifyContent: 'space-between', alignItems: 'center', flexDirection: 'row', gap: 12,
    paddingHorizontal: 12, paddingVertical: 8, backgroundColor: chatColors.fillControl, borderRadius: 12},
  current: {flexShrink: 1, color: chatColors.textSecondary, textAlign: 'right'},
  choices: {paddingLeft: 12},
  choice: {minHeight: 48, paddingHorizontal: 12, paddingVertical: 8, justifyContent: 'center', borderRadius: 12},
  selected: {backgroundColor: chatColors.surfaceRaised},
  unavailable: {paddingHorizontal: 12, paddingVertical: 8, color: chatColors.textTertiary},
}));
