// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

/** Fixed, immutable wire profiles. Selecting one never changes another session. */
public enum FalRealtimeProfile {
    LEGACY_VTON("decart/lucy2-vton/realtime", "lucy2-vton", "https://rest.fal.ai/tokens/", true, false),
    CHARACTER_26("decart/lucy-2-6/realtime", "lucy-2-6", "https://rest.alpha.fal.ai/tokens/", false, true);

    public final String endpointId;
    final String tokenAlias, tokenUrl;
    final boolean runtimeUpdates, expandInitialPrompt;

    FalRealtimeProfile(String endpointId, String tokenAlias, String tokenUrl,
            boolean runtimeUpdates, boolean expandInitialPrompt) {
        this.endpointId = endpointId; this.tokenAlias = tokenAlias; this.tokenUrl = tokenUrl;
        this.runtimeUpdates = runtimeUpdates; this.expandInitialPrompt = expandInitialPrompt;
    }

    String tokenRequestBody() {
        return "{\"allowed_apps\":[\"" + tokenAlias + "\"],\"token_expiration\":120}";
    }
}
