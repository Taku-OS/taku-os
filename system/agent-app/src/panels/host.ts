/**
 * The Taku Home projection of the Panel directory: the committed invalidation token and the pager's
 * actual navigation state. It is not a second directory; every id here comes from the native pager.
 */
import {requireOptionalNativeModule} from 'expo';
import {record} from '../model/session';

export interface PanelNavigation {available: string[]; visible: string[]; selectedPanelId: string | null}

type Subscription = {remove(): void};
type TakuHomePanelModule = {
  panelNavigation(): string;
  addListener(event: 'onPanelsChanged', listener: (event: {listRevision: number}) => void): Subscription;
  addListener(event: 'onPanelNavigation', listener: (event: unknown) => void): Subscription;
};

const nativeModule = requireOptionalNativeModule<TakuHomePanelModule>('TakuHome');

const ids = (value: unknown): string[] => Array.isArray(value) ? value.filter((id): id is string => typeof id === 'string' && !!id) : [];

function parsePanelNavigation(raw: unknown): PanelNavigation | null {
  if (!raw) return null;
  try {
    const value = record(typeof raw === 'string' ? JSON.parse(raw) : raw);
    return {available: ids(value.available), visible: ids(value.visible),
      selectedPanelId: typeof value.selectedPanelId === 'string' && value.selectedPanelId ? value.selectedPanelId : null};
  } catch { return null; }
}

/** The pager's current state, or null before Home has projected one. */
export function panelNavigationNow(): PanelNavigation | null {
  try { return parsePanelNavigation(nativeModule?.panelNavigation() ?? ''); } catch { return null; }
}

/** Fires on every selection or mounted-root change; never opens the desktop. */
export const onPanelNavigation = (listener: (navigation: PanelNavigation) => void): Subscription =>
  nativeModule?.addListener('onPanelNavigation', event => {
    const navigation = parsePanelNavigation(event);
    if (navigation) listener(navigation);
  }) ?? {remove() {}};

/** Fires after any committed directory change, cross-process included. An invalidation, not a delta. */
export const onPanelsChanged = (listener: () => void): Subscription =>
  nativeModule?.addListener('onPanelsChanged', () => listener()) ?? {remove() {}};
