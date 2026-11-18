/**
 * The light's mask shapes: Figma's own blurred layer exports (58px layer
 * blur baked in), used as soft alpha masks by the field. Listening layers
 * 462:13264 / 462:13395; Live Talk layers 462:13323 / 15118 / 15374 / 15552.
 */
import listenGlow2 from '../../assets/effects/voice-light-field/listen-glow-2.svg';
import listenGlow3 from '../../assets/effects/voice-light-field/listen-glow-3.svg';
import listenGlow4 from '../../assets/effects/voice-light-field/listen-glow-4.svg';
import listenGlow5 from '../../assets/effects/voice-light-field/listen-glow-5.svg';
import liveGlow2 from '../../assets/effects/voice-light-field/live-glow-2.svg';
import liveGlow3 from '../../assets/effects/voice-light-field/live-glow-3.svg';
import liveGlow4 from '../../assets/effects/voice-light-field/live-glow-4.svg';
import liveGlow5 from '../../assets/effects/voice-light-field/live-glow-5.svg';
import liveGlow6 from '../../assets/effects/voice-light-field/live-glow-6.svg';
import liveGlow7 from '../../assets/effects/voice-light-field/live-glow-7.svg';

export const LIGHT_MASKS = {
  listenGlow2,
  listenGlow3,
  listenGlow4,
  listenGlow5,
  liveGlow2,
  liveGlow3,
  liveGlow4,
  liveGlow5,
  liveGlow6,
  liveGlow7,
} as const;
