/**
 * The complete native Panel directory for one surface.
 */
import {useCallback, useEffect, useRef, useState} from 'react';
import {AppState} from 'react-native';
import {listAllPanels, requirePanelContract} from './client';
import {onPanelsChanged} from './host';
import type {PanelRecord} from '../model/panel';
import {record} from '../model/session';

export interface PanelCatalog {
  items: PanelRecord[];
  listRevision: number;
  loading: boolean;
  error: string;
  refresh: () => Promise<void>;
}

const code = (error: unknown): string => {
  const value = record(error).code;
  return typeof value === 'string' && value ? value : error instanceof Error ? error.message : 'UNAVAILABLE';
};

export function usePanelCatalog(active: boolean): PanelCatalog {
  const [state, setState] = useState<{items: PanelRecord[]; listRevision: number; loading: boolean; error: string}>(
    {items: [], listRevision: 0, loading: false, error: ''});
  const generation = useRef(0);
  const invalidate = useCallback(() => { generation.current++; }, []);
  const refresh = useCallback(async () => {
    const ticket = ++generation.current;
    setState(current => ({...current, loading: true}));
    try {
      await requirePanelContract();
      const page = await listAllPanels();
      if (ticket !== generation.current) return;
      setState({...page, loading: false, error: ''});
    } catch (error) {
      if (ticket !== generation.current) return;
      setState(current => ({...current, loading: false, error: code(error)}));
    }
  }, []);
  useEffect(() => {
    if (!active) return;
    void refresh();
    const directory = onPanelsChanged(() => { void refresh(); });
    const lifecycle = AppState.addEventListener('change', status => { if (status === 'active') void refresh(); });
    return () => {
      invalidate();
      directory.remove();
      lifecycle.remove();
    };
  }, [active, invalidate, refresh]);
  return {...state, refresh};
}
