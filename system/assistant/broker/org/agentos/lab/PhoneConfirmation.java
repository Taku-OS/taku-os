package org.agentos.lab;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Confirmation of the exact, bounded details shown by one pending native call. */
final class PhoneConfirmation {
    static JSONObject validate(JSONObject arguments) throws Exception {
        if(arguments==null)throw new IllegalArgumentException("Confirmation arguments required");
        String title=text(arguments,"title",120),summary=text(arguments,"summary",300);
        String confirmLabel=text(arguments,"confirmLabel",40);
        Object raw=arguments.get("fields");
        if(!(raw instanceof JSONArray))throw new IllegalArgumentException("Confirmation fields must be an array");
        JSONArray fields=(JSONArray)raw,copy=new JSONArray();
        if(fields.length()<1 || fields.length()>8)throw new IllegalArgumentException("Confirmation needs 1–8 fields");
        for(int i=0;i<fields.length();i++) {
            if(!(fields.get(i) instanceof JSONObject))throw new IllegalArgumentException("Invalid confirmation field");
            JSONObject field=fields.getJSONObject(i);
            String label=text(field,"label",80),value=text(field,"value",500),style=text(field,"style",10);
            if(!Set.of("field","fact","notice").contains(style))throw new IllegalArgumentException("Invalid confirmation field style");
            copy.put(new JSONObject().put("label",label).put("value",value).put("style",style));
        }
        return new JSONObject().put("title",title).put("summary",summary).put("fields",copy).put("confirmLabel",confirmLabel);
    }

    static JSONObject answer(JSONObject arguments,JSONObject response) throws Exception {
        JSONObject snapshot=validate(arguments);
        if(response==null)throw new IllegalArgumentException("Explicit confirmation decision required");
        String decision=text(response,"decision",10);
        if(!Set.of("confirm","modify","cancel").contains(decision))throw new IllegalArgumentException("Invalid confirmation decision");
        return new JSONObject().put("snapshot",snapshot).put("decision",decision).put("userProvided",true)
            .put("contentHash",contentHash(snapshot));
    }

    private static String text(JSONObject object,String key,int maximum) throws Exception {
        Object raw=object.get(key);
        if(!(raw instanceof String) || ((String)raw).isBlank() || ((String)raw).length()>maximum)
            throw new IllegalArgumentException("Invalid confirmation "+key);
        return (String)raw;
    }

    private static String contentHash(JSONObject snapshot) throws Exception {
        // Arrays fix key ordering and preserve visible field order, without delimiter ambiguity.
        JSONArray fields=new JSONArray(),source=snapshot.getJSONArray("fields");
        for(int i=0;i<source.length();i++) {
            JSONObject field=source.getJSONObject(i);
            fields.put(new JSONArray().put(field.getString("label")).put(field.getString("value")).put(field.getString("style")));
        }
        JSONArray canonical=new JSONArray().put("taku.confirm-action.v1").put(snapshot.getString("title"))
            .put(snapshot.getString("summary")).put(fields).put(snapshot.getString("confirmLabel"));
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder(64);
        for(byte b:digest)hex.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));
        return hex.toString();
    }
}
