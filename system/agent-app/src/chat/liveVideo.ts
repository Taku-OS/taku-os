export type LiveVideoSource = 'none' | 'screen' | 'camera';

/** The video button cycles off → screen → camera → off. */
export function nextVideoSource(current: LiveVideoSource): LiveVideoSource {
  return current === 'none' ? 'screen' : current === 'screen' ? 'camera' : 'none';
}

/** Video is offered only by providers whose engine driver streams frames, on a live, unmuted connection. */
export function liveVideoSupported(provider: string | null | undefined, lifecycle: string | undefined, muted: boolean | undefined): boolean {
  return provider === 'moss-live' && lifecycle === 'active' && !muted;
}

/** Accessibility label key describing what the next press does. */
export function liveVideoLabel(current: LiveVideoSource, supported: boolean) {
  if (!supported) return 'liveVideoUnavailable' as const;
  return current === 'none' ? 'liveVideoShareScreen' as const : current === 'screen' ? 'liveVideoUseCamera' as const : 'liveVideoStop' as const;
}
