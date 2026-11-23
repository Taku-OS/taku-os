// SPDX-License-Identifier: Apache-2.0
package org.agentos.proactivity.api;

import android.os.Message;
import android.util.Log;
import org.json.JSONObject;

/** Test-only bounded protocol metadata. Never logs bodies, identifiers, tokens or exception messages. */
public final class ObserverLinkDiagnostic {
    private static final String TAG="TakuObserverLink";
    private ObserverLinkDiagnostic(){}
    public static void stage(String fixed){Log.i(TAG,"state="+fixed);}
    public static void host(int uid){Log.i(TAG,"state=expected_host expectedHostUid="+uid);}
    public static void bound(boolean value){Log.i(TAG,"state=bind_result bound="+value);}
    public static void failure(String fixed,Throwable error){
        String name=error==null?"none":error.getClass().getSimpleName();
        if(!name.matches("[A-Za-z][A-Za-z0-9_$]{0,63}"))name="other";
        Log.i(TAG,"state="+fixed+" exceptionClass="+name);
    }
    public static void reply(Message message,int expected){
        Log.i(TAG,"state=reply what="+message.what+" sendingUid="+message.sendingUid+" expectedHostUid="+expected);
        try {
            String raw=message.getData().getString("payload","");
            if(raw==null||raw.length()>ProactivityWire.MAX_BYTES){stage("reply_payload_unavailable");return;}
            receipt("client_receipt",new JSONObject(raw),false);
        }catch(Exception error){failure("reply_parse",error);}
    }
    public static void receipt(String fixed,JSONObject value,boolean policy){
        try {
            JSONObject error=value.optJSONObject("error");
            Object rawCode=error==null?value.opt("code"):error.opt("code");
            if(rawCode==null || rawCode==JSONObject.NULL)rawCode=value.opt("code");
            String code=fixedCode(rawCode);
            String line="state="+fixed+" version="+integer(value.opt("version"))+" type="+word(value.opt("type"),32)
                    +" ok="+bool(value.opt("ok"))+" code="+code;
            if(policy){JSONObject result=value.optJSONObject("result");
                line+=" approved="+bool(result==null?null:result.opt("approved"))+" enabled="+bool(result==null?null:result.opt("enabled"))
                        +" activeRevision="+integer(result==null?null:result.opt("activeRevision"))+" pendingRevision="+integer(result==null?null:result.opt("pendingRevision"));}
            Log.i(TAG,line);
        }catch(Exception error){failure("receipt_metadata",error);}
    }
    private static String word(Object raw,int maximum){
        if(!(raw instanceof String value)||value.length()>maximum||!value.matches("[A-Za-z][A-Za-z_]*"))return "absent_or_other";
        return value;
    }
    private static String fixedCode(Object raw){
        if(raw==null || raw==JSONObject.NULL)return "none";
        if(!(raw instanceof String value))return "absent_or_other";
        return switch(value){
            case "rate_limited","not_authorized","invalid_or_unavailable","action_unavailable",
                    "unsupported_operation","invalid_request","permission_denied" -> value;
            default -> "absent_or_other";
        };
    }
    private static String bool(Object value){return value instanceof Boolean?value.toString():"absent";}
    private static String integer(Object value){return value instanceof Integer||value instanceof Long?value.toString():"absent";}
}
