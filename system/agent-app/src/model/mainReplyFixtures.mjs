import {templateSpec, uiSignature} from './genui.ts';
import {CATALOG_VERSION} from './genuiCatalog.ts';

export const block = (id, kind, patch = {}) => ({id, kind, title: '', text: '', value: {type: 'text', value: null}, image: '', url: '', items: [], config: {}, ...patch});
export const request = key => ({version: 2, key, mode: 'panel', title: 'Reading notes', summary: 'Known information', blocks: [
  block('note', 'input', {value: {type: 'text', value: 'Source note'}, config: {editor: 'text'}}),
  block('topics', 'choices', {items: [{id: 'cinema', label: 'Cinema', detail: '', image: '', url: '', icon: '', value: {type: 'text', value: null}}]}),
], actions: [{id: 'send', label: 'Send', reply: 'Send this note', style: 'primary'}]});

export function mainReply(threadId, key, revision = 1, phase = 'streaming', patch = {}) {
  const data = patch.request ?? request(key), spec = templateSpec(data);
  spec.elements['native-card'] = spec.elements.root;
  delete spec.elements.root;
  spec.root = 'native-card';
  return {schema: 'taku.main-reply/1', id: `reply-${key}`, key, revision, readableText: 'Reading notes\nKnown information\nSource note\nCinema\nSend',
    provenance: {kind: 'primary-assistant-reply', threadId, turnId: 'turn', anchorOrdinal: 0, anchorHash: 'b'.repeat(64), sourceItemIds: ['answer'], contractHash: 'a'.repeat(64)},
    lifecycle: {phase, ...(['failed', 'interrupted'].includes(phase) ? {code: phase === 'failed' ? 'INVALID_REPLY_RECORD' : 'TURN_INTERRUPTED'} : {})},
    card: {request: data, view: {catalogVersion: CATALOG_VERSION, signature: uiSignature(data), spec,
      metrics: {level: 'primary', fallback: '', ms: 0, jevSteps: 0, inputTokens: null, llmUsage: null}}, assembly: {version: 1, fields: {body: phase === 'streaming' ? 'streaming' : phase === 'failed' || phase === 'interrupted' ? 'failed' : 'ready'}}},
    ...Object.fromEntries(Object.entries(patch).filter(([key]) => key !== 'request'))};
}

export const primary = reply => ({kind: 'primary', reply});
export const matter = (request, sourceId = 'turn/matter') => ({kind: 'matter', request, sourceId});
/** A native matter snapshot for a primary reply: content, presentation and card view together. */
export const cardView = (matterId, uiRevision, ui, patch = {}) => ({matterId, uiRevision, ui, ...patch});
export const replyItems = reply => reply.provenance.sourceItemIds.map((id, index) => ({id, type: 'agentMessage', text: index ? '' : reply.readableText,
  ...(index ? {} : {replyId: reply.id}), _streamCompleted: ['completed', 'failed', 'interrupted'].includes(reply.lifecycle.phase)}));
export const matterSnapshot = (reply, revision = 1, patch = {}) => ({schema: 'taku.matter/1', matterId: `${reply.provenance.threadId}/${reply.key}`, revision,
  request: {...reply.card.request, actions: reply.card.request.actions.map(action => ({...action, actionRef: `native-${action.id}`}))},
  presentation: {kind: 'primary', reply},
  meta: {sourceId: 'native-source', sourceStatus: 'fresh'}, commands: {}, ...patch});
