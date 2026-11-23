package org.agentos.assistant;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Regression for permission scope names being rejected by the unrelated workspace scope guard. */
public final class AppPermissionUiBridgeTest {
    private static int assertions;
    private static void check(boolean value,String reason){assertions++;if(!value)throw new AssertionError(reason);}
    private static JSONObject scope(Object scope,Object allowed)throws Exception {
        return new JSONObject().put("packageName","org.agentos.shopping").put("scope",scope).put("allowed",allowed);
    }
    private static void rejectsTypes(JSONObject input)throws Exception {
        int before=NativeClient.exchanges;
        JSONObject validated=AgentGatewayProtocol.request("setAppPermissionScope",input.toString());
        try {
            AppPermissionUiBridge.execute(null,"setAppPermissionScope",validated);
            throw new AssertionError("Invalid permission field type reached native transport");
        } catch(IOException expected) {
            check("INVALID_ARGUMENT".equals(expected.getMessage()),"UI bridge preserves its strict type error");
        }
        check(NativeClient.exchanges==before,"invalid field types cannot reach native authorization");
    }
    public static void main(String[] args)throws Exception {
        for(String scope:new String[]{"memory.read","memory.write","taku.control","taku.root"}) {
            for(boolean allowed:new boolean[]{false,true}) {
                JSONObject input=scope(scope,allowed);
                JSONObject validated=AgentGatewayProtocol.request("setAppPermissionScope",input.toString());
                check(validated.similar(input),"permission scope survives the gateway unchanged");
                AppPermissionUiBridge.execute(null,"setAppPermissionScope",validated);
                JSONObject nativeRequest=NativeClient.request;
                check(nativeRequest.getString("op").equals("appPermissions")
                    && nativeRequest.getString("action").equals("setScope")
                    && nativeRequest.getString("origin").equals("ui"),"real UI bridge selects only its normal permission operation");
                check(nativeRequest.getString("packageName").equals("org.agentos.shopping")
                    && nativeRequest.getString("scope").equals(scope)
                    && nativeRequest.get("allowed").equals(allowed),"one intended scope and boolean are transported exactly");
                check(nativeRequest.getJSONArray("installedApps").length()==1,"bridge attaches the current catalog");
            }
        }
        for(Object invalid:new Object[]{true,1,JSONObject.NULL,new JSONObject(),new JSONArray()})
            rejectsTypes(scope(invalid,true));
        for(Object invalid:new Object[]{"true",1,JSONObject.NULL,new JSONObject()})
            rejectsTypes(scope("taku.control",invalid));
        rejectsTypes(scope("taku.control",true).put("packageName",false));
        JSONObject home=new JSONObject().put("scope","home");
        check(AgentGatewayProtocol.request("getWorkspaces",home.toString()).similar(home),"workspace home query remains available");
        for(Object invalid:new Object[]{"taku.control","desktop","",true,1,JSONObject.NULL}) {
            try {
                AgentGatewayProtocol.request("getWorkspaces",new JSONObject().put("scope",invalid).toString());
                throw new AssertionError("Invalid workspace scope accepted");
            } catch(IOException expected) {
                check("INVALID_WORKSPACE_SCOPE".equals(expected.getMessage()),"workspace-only home restriction remains strict");
            }
        }
        for(String origin:new String[]{"ui","cli"}) {
            try {
                AgentGatewayProtocol.request("setAppPermissionScope",scope("taku.control",true).put("origin",origin).toString());
                throw new AssertionError("Caller-supplied origin accepted");
            } catch(IOException expected) {
                check("UNSUPPORTED_PARAMETER".equals(expected.getMessage()),"callers cannot override the trusted UI origin");
            }
        }
        System.out.println("AppPermissionUiBridgeTest: "+assertions+" protocol/bridge assertions passed (no broker, model or device)");
    }
}
