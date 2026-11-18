/** What a card's data-source state means for the user (issue #32 C7); kept free of React for tests. */
type Json = Record<string, unknown>;
const record = (value: unknown): Json => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Json : {};

export type SourceNotice = {message: string; details: string; alert: boolean};

/** Known source codes → the next step the user can take. */
const STEPS: Record<string, {en: string; zh: string}> = {
  LOCATION_OR_CITY_REQUIRED: {en: 'Tell Taku which city to use.', zh: '告诉 Taku 要查哪个城市。'},
  LOCATION_PERMISSION_DENIED: {en: 'Allow location access, or tell Taku which city to use.', zh: '允许位置权限，或告诉 Taku 要查哪个城市。'},
  NETWORK_UNAVAILABLE: {en: 'Check your connection; Taku will update when it’s back.', zh: '请检查网络，恢复后会自动更新。'},
};

/** null while the source is available and fresh: nothing to say. */
export function sourceNotice(source: Json, language: 'en' | 'zh', now: number): SourceNotice | null {
  const zh = language === 'zh';
  const provenance = record(source.provenance), kind = String(provenance.kind ?? '');
  if (kind === 'audio-session') return null;
  const available = source.state === 'available', denied = source.state === 'denied';
  const stale = available && typeof source.validUntil === 'number' && source.validUntil < now;
  const limited = available && source.coverageComplete === false;
  if (available && !stale && !limited) return null;
  const code = typeof source.code === 'string' ? source.code : '';
  const step = STEPS[code];
  const message = !available
    ? (step ? (zh ? step.zh : step.en) : denied ? (zh ? '需要授权后才能更新这部分。' : 'This part needs permission to update.')
      : (zh ? '暂时无法更新，显示的是上次的结果。' : 'Couldn’t update right now; showing the last result.'))
    : stale ? (zh ? '这是之前的结果，可能已过时。' : 'This is an earlier result and may be out of date.')
    : (zh ? '这个来源只提供有限的更新时段。' : 'This source only updates for a limited window.');
  const name = kind === 'weather-model' ? 'Open-Meteo' : kind === 'public-feed' ? 'Figma blog' :
    kind === 'memory-record' ? (zh ? '记忆来源' : 'Memory source') : kind === 'task-broker-receipt' ? 'TaskBroker' : (zh ? '数据来源' : 'Data source');
  const stamp = Number(source.lastSuccessfulAt ?? source.observedAt ?? 0);
  const details = [name, stamp > 0 ? `${zh ? '读取于 ' : 'Read '}${new Date(stamp).toLocaleString(zh ? 'zh-CN' : 'en-US')}` : '', code].filter(Boolean).join(' · ');
  return {message, details, alert: !available};
}
