/**
 * The Panel directory and shared card-state wire client for the Assistant gateway.
 *
 * Both chat and the desktop go through these calls; neither one validates or stores a panel itself.
 * Contract version is checked once, because an incompatible Assistant must be updated rather than
 * served by a second, older code path.
 */
import {AgentGatewayError, callAgent, newRequestId} from '../native/agent';
import {record, string, type Json} from '../model/session';
import {
  PANEL_CONTRACT, parseCardView, parseMoveReceipt, parsePanelDetail, parsePanelPage, parsePinReceipt,
  parseRenameReceipt, parseUnpinReceipt,
  type CardChange, type CardView, type PanelDetail, type PanelPage, type PanelRecord, type PinSource,
} from '../model/panel';

export type * from '../model/panel';

const panels = <T>(operation: string, args: Json) => callAgent<T>('panels', {operation, args});

let contract: Promise<string> | null = null;
/** Resolves the Assistant's contract version once; a failure is retried by the next caller. */
function panelContract(): Promise<string> {
  if (!contract) contract = panels<Json>('describe', {})
    .then(result => string(result.version))
    .catch(error => { contract = null; throw error; });
  return contract;
}

const incompatible = () => new AgentGatewayError('This Assistant cannot show Panels. Update it to continue.', 'PANEL_CONTRACT_MISMATCH');
export async function requirePanelContract(): Promise<void> {
  let version: string;
  try { version = await panelContract(); }
  catch (error) {
    if (['UNKNOWN_METHOD', 'UNSUPPORTED_ACTION'].includes(String(record(error).code))) throw incompatible();
    throw error;
  }
  if (version !== PANEL_CONTRACT) throw incompatible();
}

async function listPanels(limit = 100, cursor?: string | null): Promise<PanelPage> {
  return parsePanelPage(await panels<Json>('list', {limit, ...(cursor ? {cursor} : {})}));
}

/** Every page of the directory. A LIST_CHANGED cursor restarts instead of mixing two directories. */
export async function listAllPanels(): Promise<{items: PanelRecord[]; listRevision: number}> {
  for (let attempt = 0; ; attempt++) {
    const items: PanelRecord[] = [];
    let cursor: string | null = null, listRevision = 0;
    try {
      do {
        const page = await listPanels(100, cursor);
        items.push(...page.items);
        listRevision = page.listRevision;
        cursor = page.nextCursor;
      } while (cursor);
      return {items, listRevision};
    } catch (error) {
      if (record(error).code !== 'LIST_CHANGED' || attempt >= 2) throw error;
    }
  }
}

export const getPanel = async (panelId: string): Promise<PanelDetail> =>
  parsePanelDetail(await panels<Json>('get', {panelId}));

export const pinPanel = async (source: PinSource, requestId = newRequestId()) =>
  parsePinReceipt(await panels<Json>('pin', {source, requestId}));

export const unpinPanel = async (panelId: string, expectedListRevision: number, requestId = newRequestId()) =>
  parseUnpinReceipt(await panels<Json>('unpin', {panelId, expectedListRevision, requestId}));

export const renamePanel = async (panelId: string, title: string, expectedMetadataRevision: number,
  requestId = newRequestId()) =>
  parseRenameReceipt(await panels<Json>('rename', {panelId, title, expectedMetadataRevision, requestId}));

export const movePanel = async (panelId: string, beforePanelId: string | null, expectedListRevision: number,
  requestId = newRequestId()) =>
  parseMoveReceipt(await panels<Json>('move', {panelId, beforePanelId, expectedListRevision, requestId}));

/** Accepted means the navigation request was accepted, not that the page has been drawn. */
export async function openPanel(panelId: string): Promise<void> {
  const result = await panels<Json>('open', {panelId});
  if (result.accepted !== true) throw new Error('The Assistant did not accept the navigation request');
}

/** The chat's actual send while it holds this panel as navigation context. */
export const continuePanel = (panelId: string, text: string, expectedSourceVersion: number,
  requestId = newRequestId()): Promise<Json> =>
  panels<Json>('continue', {panelId, text, expectedSourceVersion, requestId});

export async function readCardView(matterId: string): Promise<CardView> {
  const view = parseCardView(await callAgent<Json>('cardView', {operation: 'read', args: {matterId}}), matterId);
  if (!view) throw new Error('The Assistant sent an unexpected card view');
  return view;
}

export async function applyCardView(matterId: string, expectedUiRevision: number, changes: CardChange[],
  requestId = newRequestId()): Promise<CardView> {
  const view = parseCardView(await callAgent<Json>('cardView', {operation: 'apply',
    args: {matterId, expectedUiRevision, requestId, changes}}), matterId);
  if (!view) throw new Error('The Assistant sent an unexpected card view');
  return view;
}
