/**
 * Rename, Delete and Edit with Taku for one Panel, shared by the Panels grid (3-1-2) and the detail
 * page (3-2-2). Every change goes through the Assistant's Panel directory, so Home, this page and an
 * agent read the same result.
 */
import React, {useState} from 'react';
import {DeleteDialog, RenameDialog} from '../chat/ChatOverlays';
import {chatText} from '../chat/chatText';
import {openPanelConversation} from '../native/agent';
import {renamePanel, requirePanelContract, unpinPanel} from './client';
import {panelErrorCode, panelErrorText} from './panelErrors';
import type {PanelRecord} from '../model/panel';

type Language = 'en' | 'zh';

export function usePanelActions({language, onChanged, onDeleted}: {
  language: Language; onChanged: () => void; onDeleted?: (panel: PanelRecord) => void;
}) {
  const t = chatText(language);
  const [renaming, setRenaming] = useState<PanelRecord | null>(null);
  const [deleting, setDeleting] = useState<{panel: PanelRecord; listRevision: number} | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const close = () => { setRenaming(null); setDeleting(null); setError(''); };
  const run = async (action: () => Promise<unknown>, fallback: 'panelActionFailed' | 'panelDeleteFailed', done: () => void) => {
    setBusy(true); setError('');
    try { await requirePanelContract(); await action(); done(); onChanged(); }
    catch (problem) { setError(panelErrorText(panelErrorCode(problem), language, fallback)); }
    finally { setBusy(false); }
  };
  /** Edit with Taku: the panel's own conversation opens in chat with the panel attached (4-1). */
  const edit = (panel: PanelRecord) => {
    setNotice('');
    void openPanelConversation(panel.panelId).catch(problem =>
      setNotice(panelErrorCode(problem) === 'SOURCE_THREAD_NOT_FOUND' ? t('panelSourceThreadUnavailable') : t('panelContinueFailed')));
  };
  const dialogs = <>
    {renaming && <RenameDialog key={renaming.panelId} title={t('renamePanelTitle')} label={t('panelTitleLabel')} initial={renaming.title}
      language={language} busy={busy} error={error} onCancel={close}
      onConfirm={title => {
        const panel = renaming;
        if (title === panel.title) { close(); return; }
        void run(() => renamePanel(panel.panelId, title, panel.metadataRevision), 'panelActionFailed', close);
      }}/>}
    {deleting && <DeleteDialog title={t('deletePanelTitle')} name={t('deletePanelName').replace('{title}', deleting.panel.title || t('panelPreview'))}
      body={t('deletePanelBody')} language={language} busy={busy} error={error} onCancel={close}
      onConfirm={() => {
        const {panel, listRevision} = deleting;
        void run(() => unpinPanel(panel.panelId, listRevision), 'panelDeleteFailed', () => { close(); onDeleted?.(panel); });
      }}/>}
  </>;
  return {
    rename: (panel: PanelRecord) => { setError(''); setRenaming(panel); },
    remove: (panel: PanelRecord, listRevision: number) => { setError(''); setDeleting({panel, listRevision}); },
    edit, dialogs, notice, clearNotice: () => setNotice(''),
  };
}
