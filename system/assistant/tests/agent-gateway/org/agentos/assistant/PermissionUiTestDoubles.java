package org.agentos.assistant;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host-only catalog/transport. The real protocol and UI bridge run without a broker or device. */
final class AppPermissionCatalog {
    static JSONArray collectExternalApps(Context context) {
        return new JSONArray().put(new JSONObject().put("packageName", "org.agentos.shopping"));
    }
}

final class NativeClient {
    static int exchanges;
    static JSONObject request;
    static JSONObject exchange(JSONObject input)throws Exception {
        exchanges++;request=new JSONObject(input.toString());
        return new JSONObject().put("accepted",true);
    }
}
