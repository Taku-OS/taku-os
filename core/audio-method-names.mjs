// SPDX-License-Identifier: Apache-2.0
// The one list of audio protocol methods. Dependency-free so protocol.mjs, audio-methods.mjs
// and the Android adapter can all import it without an import cycle.
export const AUDIO_METHODS = Object.freeze(['audio.capabilities','audio.session.open','audio.session.append','audio.session.finish','audio.session.close','audio.session.status']);
