import {resolveActionParam, type PropResolutionContext} from '@json-render/core';

export type ActionAvailability = Record<string, {available: boolean; code?: string; message?: string}>;
export const UNAVAILABLE_ACTION_MESSAGE = 'This action is no longer available.';
export const UNAVAILABLE_ACTION_NOTICE = 'Some actions on this card are no longer available.';

export function isActionUnavailable(actionId: unknown, availability: ActionAvailability): boolean {
  return typeof actionId === 'string' && availability[actionId]?.available === false;
}

/** Resolve the same action ID as the renderer, including state and repeated-item expressions. */
export function hasUnavailableBinding(on: unknown, availability: ActionAvailability, context: PropResolutionContext): boolean {
  const blocked = (binding: unknown): boolean => {
    if (Array.isArray(binding)) return binding.some(blocked);
    if (!binding || typeof binding !== 'object') return false;
    const action = binding as {action?: unknown; params?: {actionId?: unknown}};
    return action.action === 'dispatch' && isActionUnavailable(resolveActionParam(action.params?.actionId, context), availability);
  };
  return !!on && typeof on === 'object' && Object.values(on).some(blocked);
}

export function hasUnavailableActions(availability: ActionAvailability): boolean {
  return Object.values(availability).some(action => action?.available === false);
}
