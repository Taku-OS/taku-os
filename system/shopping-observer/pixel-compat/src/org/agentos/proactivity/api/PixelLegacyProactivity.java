// SPDX-License-Identifier: Apache-2.0
package org.agentos.proactivity.api;

import org.json.JSONObject;

/** Pixel host-v8 wire adaptation only; no old SDK, token redemption or executable target API. */
public final class PixelLegacyProactivity {
    private static final String OBSERVER="org.agentos.shopping.tiktok", EVENT="org.agentos.shopping.garment_ready";
    private static final String SOURCE="org.agentos.shopping", TARGET="org.taku.tryon", CONSENT="live_frame_try_on_v2";
    private PixelLegacyProactivity() {}

    public static JSONObject spec(ObserverSpec spec)throws Exception {
        JSONObject wire=ProactivityWire.spec(spec);
        if(spec.prompt()==null)return wire;
        if(!OBSERVER.equals(spec.id()) || !EVENT.equals(spec.eventType())
                || !new PromptSpec("Try on","Not now").equals(spec.prompt()))
            throw new IllegalArgumentException("Unsupported legacy prompt");
        wire.remove("prompt");
        return wire.put("action",new JSONObject().put("id","try_on").put("label","Try on")
            .put("targetPackage",TARGET).put("consentCode",CONSENT)).put("hintPresentation","island");
    }

    /** Called only after the client verifies the reply's host UID and protocol version. */
    public static JSONObject receipt(JSONObject message)throws Exception {
        if(!"accepted".equals(message.opt("type")) || message.optJSONObject("result")==null)return message;
        JSONObject copy=new JSONObject(message.toString()),result=copy.getJSONObject("result");
        // This pinned legacy host cannot attest modern promptType; derive it only from its action receipt.
        result.remove("promptType");
        if(Boolean.TRUE.equals(copy.opt("ok")) && OBSERVER.equals(result.opt("observerId"))
                && SOURCE.equals(result.opt("sourcePackage")) && "try_on".equals(result.opt("actionId"))
                && "Try on".equals(result.opt("actionLabel")) && TARGET.equals(result.opt("targetPackage"))
                && CONSENT.equals(result.opt("consentCode"))) {
            // Preserve the genuine action/presentation lease fields for the phone-only card host.
            result.put("promptType","choice").put("actionId","accept");
        }
        return copy;
    }
}
