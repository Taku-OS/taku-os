package org.agentos.assistant;

import android.content.Context;
import java.io.IOException;
import org.json.JSONObject;

/**
 * Bridges the signed Taku Agent Settings UI (`AgentGatewayProvider`) to `AppPermissionPolicy`
 * in `taku_broker` with `origin = "ui"`.
 */
final class AppPermissionUiBridge {
    private AppPermissionUiBridge() {}

    static JSONObject execute(Context context, String action, JSONObject input) throws Exception {
        JSONObject req = new JSONObject()
                .put("op", "appPermissions")
                .put("origin", "ui")
                .put("installedApps", AppPermissionCatalog.collectExternalApps(context));
        switch (action) {
            case "readAppPermissions":
                req.put("action", "snapshot");
                if (input.has("query")) {
                    Object q = input.get("query");
                    if (!(q instanceof String) || ((String) q).length() > 200) {
                        throw new IOException("INVALID_ARGUMENT");
                    }
                    req.put("query", q);
                }
                break;
            case "setAppPermissionMode": {
                Object mode = input.opt("mode");
                if (!(mode instanceof String)) throw new IOException("INVALID_ARGUMENT");
                req.put("action", "setMode").put("mode", mode);
                if (input.has("allowRootInYolo")) {
                    Object flag = input.get("allowRootInYolo");
                    if (!(flag instanceof Boolean)) throw new IOException("INVALID_ARGUMENT");
                    req.put("allowRootInYolo", flag);
                }
                break;
            }
            case "setAppPermissionScope": {
                Object pkg = input.opt("packageName");
                Object scope = input.opt("scope");
                Object allowed = input.opt("allowed");
                if (!(pkg instanceof String) || !(scope instanceof String) || !(allowed instanceof Boolean)) {
                    throw new IOException("INVALID_ARGUMENT");
                }
                req.put("action", "setScope")
                        .put("packageName", pkg)
                        .put("scope", scope)
                        .put("allowed", allowed);
                break;
            }
            case "resetAppPermission": {
                Object pkg = input.opt("packageName");
                if (!(pkg instanceof String)) throw new IOException("INVALID_ARGUMENT");
                req.put("action", "reset").put("packageName", pkg);
                break;
            }
            default:
                throw new IOException("UNSUPPORTED_ACTION");
        }
        return NativeClient.exchange(req);
    }
}
