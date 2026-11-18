import {create} from 'zustand';
import {createVideoPlayer, type VideoPlayer} from 'expo-video';
import type {CameraFrameAnalysis} from '../native/agent';
import type {UiConfig} from './genui';

export type MediaPlayback = {
  player: VideoPlayer | null; views: string[]; videoViews: string[]; surfaceOwner: string | null;
  status: string; playing: boolean; currentTime: number; duration: number; generation: number; error: string;
  subscriptions: {remove(): void}[];
};
type Photo = {uri: string; width: number; height: number; capturedAt?: number; provided?: boolean};
type CameraSession = {
  owner: string | null; active: boolean; ready: boolean; facing: 'front' | 'back'; photo: Photo | null;
  filter: 'warm' | 'mono'; intensity: number; compare: boolean; grid: boolean; busy: boolean;
  error: string; saved: string; analysis: CameraFrameAnalysis | null; analysisError: string; analyzing: boolean;
  configuredFilter?: 'warm' | 'mono'; configuredIntensity?: number; configuredAnalysisPrompt?: string;
  analysisPrompt: string; analysisRequest: number; analyzedRequest: number;
};
type MediaState = {playback: Record<string, MediaPlayback>; camera: CameraSession};

// Native handles and the session projection have one owner, shared by every rendered view.
export const useMediaStore = create<MediaState>(() => ({playback: {}, camera: {
  owner: null, active: true, ready: false, facing: 'back', photo: null, filter: 'warm', intensity: 0.65,
  compare: false, grid: false, busy: false, error: '', saved: '', analysis: null, analysisError: '', analyzing: false,
  analysisPrompt: '', analysisRequest: 0, analyzedRequest: 0,
}}));

export const updateCamera = (patch: Partial<CameraSession>) => useMediaStore.setState(s => ({camera: {...s.camera, ...patch}}));
export function applyCameraConfig(config: UiConfig) {
  useMediaStore.setState(state => {
    const camera = state.camera, patch: Partial<CameraSession> = {};
    if (config.imageFilter !== undefined && config.imageFilter !== camera.configuredFilter) {
      patch.configuredFilter = config.imageFilter;
      patch.filter = config.imageFilter;
      patch.saved = '';
    }
    if (config.filterStrength !== undefined && config.filterStrength !== camera.configuredIntensity) {
      patch.configuredIntensity = config.filterStrength;
      patch.intensity = config.filterStrength;
      patch.saved = '';
    }
    if (config.analysisPrompt !== undefined && config.analysisPrompt !== camera.configuredAnalysisPrompt) {
      patch.configuredAnalysisPrompt = config.analysisPrompt;
      patch.analysisPrompt = config.analysisPrompt.trim();
      patch.analysis = null;
      patch.analysisError = '';
      if (patch.analysisPrompt) patch.analysisRequest = camera.analysisRequest + 1;
      else patch.analyzedRequest = camera.analysisRequest;
    }
    return Object.keys(patch).length ? {camera: {...camera, ...patch}} : state;
  });
}
const patchPlayback = (source: string, patch: Partial<MediaPlayback>) => useMediaStore.setState(s => {
  const entry = s.playback[source];
  return entry ? {playback: {...s.playback, [source]: {...entry, ...patch}}} : s;
});
const message = (error: unknown) => error instanceof Error ? error.message : String(error);

// expo-video also plays audio-only sources and exposes native load/playback failures on Android.
// Its player is deliberately independent of React view lifetimes.
export function leasePlayback(source: string, view: string, video: boolean) {
  let entry = useMediaStore.getState().playback[source];
  if (!entry?.player) {
    const currentTime = entry?.currentTime ?? 0;
    entry = {player: null, views: [], videoViews: [], surfaceOwner: null, status: 'loading', playing: false,
      currentTime, duration: entry?.duration ?? 0, generation: (entry?.generation ?? 0) + 1, error: '', subscriptions: []};
    useMediaStore.setState(s => ({playback: {...s.playback, [source]: entry}}));
    try {
      const player = createVideoPlayer(null);
      player.timeUpdateEventInterval = 0.25;
      const update = (patch: Partial<MediaPlayback>) => {
        if (useMediaStore.getState().playback[source]?.player === player) patchPlayback(source, patch);
      };
      const subscriptions = [
        player.addListener('statusChange', ({status, error}) => update({status, error: error?.message ?? (status === 'error' ? 'The media source failed to load or play.' : '')})),
        player.addListener('playingChange', ({isPlaying}) => update({playing: isPlaying})),
        player.addListener('timeUpdate', ({currentTime}) => update({currentTime})),
        player.addListener('sourceLoad', ({duration}) => {
          if (useMediaStore.getState().playback[source]?.player !== player) return;
          update({duration});
          if (currentTime > 0 && duration > currentTime) player.currentTime = currentTime;
        }),
        player.addListener('playToEnd', () => update({playing: false, currentTime: player.currentTime})),
      ];
      patchPlayback(source, {player, subscriptions});
      void player.replaceAsync(source).catch(error => update({status: 'error', error: message(error)}));
    } catch (error) { patchPlayback(source, {status: 'error', error: message(error)}); }
  }
  entry = useMediaStore.getState().playback[source];
  if (!entry.views.includes(view)) patchPlayback(source, {views: [...entry.views, view],
    videoViews: video ? [...entry.videoViews, view] : entry.videoViews,
    surfaceOwner: entry.surfaceOwner ?? (video ? view : null)});
  return () => {
    const current = useMediaStore.getState().playback[source];
    if (!current) return;
    const views = current.views.filter(id => id !== view);
    const videoViews = current.videoViews.filter(id => id !== view);
    patchPlayback(source, {views, videoViews, surfaceOwner: current.surfaceOwner === view ? videoViews[0] ?? null : current.surfaceOwner});
    // Allow a view relocation within one commit to retain the actual native handle.
    queueMicrotask(() => {
      const latest = useMediaStore.getState().playback[source];
      if (!latest || latest.views.length || !latest.player) return;
      const currentTime = latest.player.currentTime;
      latest.subscriptions.forEach(subscription => subscription.remove());
      latest.player.pause();
      latest.player.release();
      patchPlayback(source, {player: null, subscriptions: [], playing: false, currentTime});
    });
  };
}

export function ownVideoSurface(source: string, view: string) { patchPlayback(source, {surfaceOwner: view}); }
export function togglePlayback(source: string) {
  const entry = useMediaStore.getState().playback[source];
  if (!entry?.player) return;
  try {
    if (entry.playing) entry.player.pause();
    else {
      if (entry.duration > 0 && entry.currentTime >= entry.duration - 0.1) {
        patchPlayback(source, {generation: entry.generation + 1});
        entry.player.currentTime = 0;
      }
      entry.player.play();
    }
  } catch (error) { patchPlayback(source, {error: message(error), status: 'error'}); }
}
export function seekPlayback(source: string, seconds: number) {
  const entry = useMediaStore.getState().playback[source];
  if (!entry?.player || !entry.duration) return;
  try {
    patchPlayback(source, {generation: entry.generation + 1});
    entry.player.currentTime = Math.max(0, Math.min(entry.duration, seconds));
  }
  catch (error) { patchPlayback(source, {error: message(error), status: 'error'}); }
}
export function reloadPlayback(source: string) {
  const entry = useMediaStore.getState().playback[source];
  if (!entry?.player) return;
  patchPlayback(source, {error: '', status: 'loading', generation: entry.generation + 1});
  const player = entry.player;
  void player.replaceAsync(source).catch(error => {
    if (useMediaStore.getState().playback[source]?.player === player) patchPlayback(source, {error: message(error), status: 'error'});
  });
}

export function photoColorMatrix(filter: 'warm' | 'mono', intensity: number): number[] {
  const amount = Math.max(0, Math.min(1, intensity));
  const identity = [1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0];
  const target = filter === 'mono' ? [0.2126, 0.7152, 0.0722, 0, 0, 0.2126, 0.7152, 0.0722, 0, 0, 0.2126, 0.7152, 0.0722, 0, 0, 0, 0, 0, 1, 0]
    : [1.08, 0.04, 0, 0, 0.015, 0.02, 1.01, 0, 0, 0.005, 0, 0.02, 0.88, 0, 0, 0, 0, 0, 1, 0];
  return identity.map((value, i) => value + (target[i] - value) * amount);
}
