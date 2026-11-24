/**
 * Softened presentation parameters for the Live-mode bottom light field in AgentChatScreen.
 * Ordinary text chat and AI streaming/thinking/tool output never mount this effect.
 */
export const LIVE_LIGHT_OPACITY = 0.45;
export const LIVE_LIGHT_HEIGHT_RATIO = 280 / 412;

export function shouldShowLiveLight(liveActive: boolean): boolean {
  return liveActive;
}

export function liveLightStyle(screenWidth: number) {
  return {
    position: 'absolute' as const,
    left: 0,
    right: 0,
    bottom: 0,
    height: screenWidth * LIVE_LIGHT_HEIGHT_RATIO,
    opacity: LIVE_LIGHT_OPACITY,
  };
}
