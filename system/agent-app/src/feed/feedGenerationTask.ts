import {callAgent, newRequestId} from '../native/agent';
import {beginFeedGeneration, completeFeedGeneration} from './native';
import {feedGenerationFailure, prepareFeedGeneration, type FeedGenerationFailure, type GenerationInput} from './feedGeneration';

type FeedGenerationPayload = {attemptId: string; id: string; createdAt: number; input: GenerationInput};
type Receipt = {ok: true} | {ok: true; card: Record<string, unknown>; model: string} | FeedGenerationFailure;

export async function feedGenerationTask(data: FeedGenerationPayload): Promise<void> {
  if (!data || typeof data.attemptId !== 'string') return;
  const {attemptId} = data;
  let claimed = false;
  let receipt: Receipt;
  try {
    claimed = beginFeedGeneration(attemptId);
    if (!claimed) return;
    const prepared = await prepareFeedGeneration(data.input, {id: data.id, createdAt: data.createdAt},
      args => callAgent('generateUi', {...args, requestId: newRequestId()}));
    receipt = data.input.kind === 'card' ? {ok: true} : {ok: true, card: prepared.card, model: prepared.model!};
  } catch (error) {
    if (!claimed) return;
    receipt = feedGenerationFailure(error);
  }
  try { completeFeedGeneration(attemptId, JSON.stringify(receipt)); }
  catch { /* Native attempt deadlines remain authoritative if its bridge has retired. */ }
}
