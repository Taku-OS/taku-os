package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

public final class PhoneConfirmationTest {
    private static int checks;
    private static void check(boolean value,String message) { checks++;if(!value)throw new AssertionError(message); }
    private static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    private static JSONObject arguments() throws Exception {
        return new JSONObject().put("title","Confirm reservation").put("summary","Review these details before submitting")
            .put("confirmLabel","Confirm booking").put("fields",new JSONArray()
                .put(new JSONObject().put("label","Restaurant").put("value","Haidilao · Longyang Plaza").put("style","field"))
                .put(new JSONObject().put("label","When").put("value","2026-10-26 20:00 Asia/Shanghai").put("style","fact"))
                .put(new JSONObject().put("label","Guests").put("value","3 people").put("style","fact"))
                .put(new JSONObject().put("label","Allergy note").put("value","Seafood allergy; merchant acknowledgment pending").put("style","notice")));
    }
    private static JSONObject response(String decision) throws Exception { return new JSONObject().put("decision",decision); }
    private static void rejects(JSONObject args,JSONObject response) throws Exception {
        try {PhoneConfirmation.answer(args,response);}catch(Exception expected){checks++;return;}
        throw new AssertionError("Accepted invalid confirmation: "+args+" / "+response);
    }
    private static String hash(JSONObject args) throws Exception { return PhoneConfirmation.answer(args,response("confirm")).getString("contentHash"); }
    public static void main(String[] unused) throws Exception {
        JSONObject args=arguments(),result=PhoneConfirmation.answer(args,response("confirm")
            .put("snapshot",new JSONObject().put("title","Forged details")).put("userProvided",false).put("contentHash","forged"));
        JSONObject snapshot=result.getJSONObject("snapshot");
        check(result.getBoolean("userProvided"),"Real response flag");
        check(snapshot.toString().equals(PhoneConfirmation.validate(args).toString()),"Snapshot must come from pending arguments");
        check(result.getString("contentHash").matches("[0-9a-f]{64}"),"SHA-256 digest");
        String initialHash=result.getString("contentHash");
        args.getJSONArray("fields").getJSONObject(0).put("value","Changed after response");
        check(snapshot.getJSONArray("fields").getJSONObject(0).getString("value").equals("Haidilao · Longyang Plaza"),"No alias to input");
        snapshot.getJSONArray("fields").getJSONObject(1).put("value","Changed result");
        check(!args.getJSONArray("fields").getJSONObject(1).getString("value").equals("Changed result"),"No alias from output");

        args=arguments();
        for(String decision:new String[]{"confirm","modify","cancel"}) {
            JSONObject answered=PhoneConfirmation.answer(args,response(decision));
            check(answered.getString("decision").equals(decision),"Decision must be preserved");
            check(answered.getString("contentHash").equals(initialHash),"Decision does not alter shown content");
        }
        JSONObject reversed=new JSONObject().put("confirmLabel",args.getString("confirmLabel"))
            .put("fields",new JSONArray()).put("summary",args.getString("summary")).put("title",args.getString("title"));
        for(int i=0;i<args.getJSONArray("fields").length();i++) {
            JSONObject field=args.getJSONArray("fields").getJSONObject(i);
            reversed.getJSONArray("fields").put(new JSONObject().put("style",field.getString("style"))
                .put("value",field.getString("value")).put("label",field.getString("label")));
        }
        check(hash(reversed).equals(initialHash),"JSON key insertion order must not affect hash");
        JSONObject extras=copy(args).put("hiddenApproval",true);
        extras.getJSONArray("fields").getJSONObject(0).put("hiddenInstruction","forged");
        check(hash(extras).equals(initialHash),"Undisplayed fields are excluded");
        check(!PhoneConfirmation.validate(extras).has("hiddenApproval"),"Only schema fields in snapshot");
        for(String key:new String[]{"title","summary","confirmLabel"})check(!hash(copy(args).put(key,"Different "+key)).equals(initialHash),key+" affects hash");
        for(String key:new String[]{"label","value","style"}) {
            JSONObject changed=copy(args);changed.getJSONArray("fields").getJSONObject(0).put(key,key.equals("style")?"fact":"Different");
            check(!hash(changed).equals(initialHash),"Field "+key+" affects hash");
        }
        JSONObject reordered=copy(args);JSONArray old=reordered.getJSONArray("fields");
        reordered.put("fields",new JSONArray().put(old.getJSONObject(1)).put(old.getJSONObject(0)).put(old.getJSONObject(2)).put(old.getJSONObject(3)));
        check(!hash(reordered).equals(initialHash),"Visible field order affects hash");

        for(Object bad:new Object[]{"", " ", 1, true, JSONObject.NULL}) {
            for(String key:new String[]{"title","summary","confirmLabel"})rejects(copy(args).put(key,bad),response("confirm"));
            for(String key:new String[]{"label","value","style"}) {
                JSONObject invalid=copy(args);invalid.getJSONArray("fields").getJSONObject(0).put(key,bad);rejects(invalid,response("confirm"));
            }
        }
        for(String key:new String[]{"title","summary","confirmLabel","fields"}) {JSONObject invalid=copy(args);invalid.remove(key);rejects(invalid,response("confirm"));}
        for(Object bad:new Object[]{"confirm",new JSONObject(),JSONObject.NULL,new JSONArray(),new JSONArray().put("text")})rejects(copy(args).put("fields",bad),response("confirm"));
        int[] bounds={120,300,40};String[] keys={"title","summary","confirmLabel"};
        for(int i=0;i<keys.length;i++) {
            check(PhoneConfirmation.validate(copy(args).put(keys[i],"x".repeat(bounds[i]))).getString(keys[i]).length()==bounds[i],"Boundary accepted");
            rejects(copy(args).put(keys[i],"x".repeat(bounds[i]+1)),response("confirm"));
        }
        for(String key:new String[]{"label","value"}) {
            int limit=key.equals("label")?80:500;JSONObject invalid=copy(args);
            invalid.getJSONArray("fields").getJSONObject(0).put(key,"x".repeat(limit));PhoneConfirmation.validate(invalid);checks++;
            invalid.getJSONArray("fields").getJSONObject(0).put(key,"x".repeat(limit+1));rejects(invalid,response("confirm"));
        }
        JSONArray many=new JSONArray();for(int i=0;i<8;i++)many.put(args.getJSONArray("fields").getJSONObject(0));
        check(PhoneConfirmation.validate(copy(args).put("fields",many)).getJSONArray("fields").length()==8,"Eight fields accepted");
        many.put(args.getJSONArray("fields").getJSONObject(0));rejects(copy(args).put("fields",many),response("confirm"));
        JSONObject invalidStyle=copy(args);invalidStyle.getJSONArray("fields").getJSONObject(0).put("style","html");rejects(invalidStyle,response("confirm"));
        for(Object decision:new Object[]{"accept","confirmed","",1,true,JSONObject.NULL})rejects(args,new JSONObject().put("decision",decision));
        rejects(args,new JSONObject());rejects(args,null);rejects(null,response("confirm"));
        // Arbitrary domains use the same contract, without reservation-specific fields.
        JSONObject generic=new JSONObject().put("title","Publish document").put("summary","Make this version public")
            .put("confirmLabel","Publish").put("fields",new JSONArray().put(new JSONObject().put("label","Version").put("value","2.4").put("style","field")));
        check(PhoneConfirmation.answer(generic,response("confirm")).getJSONObject("snapshot").getString("title").equals("Publish document"),"Generic confirmation");
        System.out.println("PhoneConfirmationTest: "+checks+" checks passed");
    }
}
