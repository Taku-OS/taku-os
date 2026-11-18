package org.agentos.lab;

import org.json.*;
import java.io.*;
import java.util.zip.*;

/** The installed assistant APK is the single distribution source for context and builtin skills. */
final class HarnessCatalog {
    private static JSONObject replyContract;
    static synchronized JSONObject replyContract() throws Exception {
        if(replyContract==null) {
            JSONObject manifest=new JSONObject(asset("assets/native/reply-contract.json"));
            if(!"taku.reply-contract/1".equals(manifest.optString("schema"))||!"taku.reply/1".equals(manifest.optString("protocol")))
                throw new IOException("Unsupported reply contract");
            JSONObject hashed=new JSONObject(manifest.toString());hashed.remove("contractHash");hashed.remove("prompt");
            if(!MainReplyStream.manifestHash(hashed).equals(manifest.getString("contractHash")))throw new IOException("Reply contract integrity failure");
            JSONObject components=manifest.getJSONObject("components");
            for(java.util.Iterator<String> names=components.keys();names.hasNext();) {
                JSONObject component=components.getJSONObject(names.next());
                schema(component.getJSONObject("props"),false);
                component.getJSONArray("events");component.getJSONArray("contentProps");component.getBoolean("children");
            }
            JSONObject actions=manifest.getJSONObject("actions");
            for(java.util.Iterator<String> names=actions.keys();names.hasNext();) {
                String name=names.next();schema(actions.getJSONObject(name).getJSONObject("params"),name.equals("setState"));
            }
            manifest.getString("prompt");replyContract=manifest;
        }
        return replyContract;
    }
    private static void schema(JSONObject rule,boolean stateValue) throws Exception {
        java.util.Set<String> supported=java.util.Set.of("$schema","type","properties","required","additionalProperties","anyOf","const","enum","items","maxLength","maximum","minimum","exclusiveMinimum","pattern");
        if(rule.length()==0)throw new IOException("Undeclared unconstrained reply schema");
        for(java.util.Iterator<String> keys=rule.keys();keys.hasNext();)if(!supported.contains(keys.next()))throw new IOException("Unsupported reply schema keyword");
        JSONObject properties=rule.optJSONObject("properties");
        if(properties!=null)for(java.util.Iterator<String> keys=properties.keys();keys.hasNext();) {
            String key=keys.next();JSONObject child=properties.getJSONObject(key);
            if(stateValue&&key.equals("value")&&child.length()==0)continue;
            schema(child,false);
        }
        JSONArray alternatives=rule.optJSONArray("anyOf");
        if(alternatives!=null)for(int i=0;i<alternatives.length();i++)schema(alternatives.getJSONObject(i),false);
        if(rule.has("items"))schema(rule.getJSONObject("items"),false);
    }
    static String apk() throws Exception {
        String result=TaskBroker.command("/system/bin/pm","path","org.agentos.assistant").trim();
        for(String line:result.split("\n"))if(line.startsWith("package:")&&line.endsWith(".apk"))return line.substring(8);
        throw new IOException("Assistant package unavailable");
    }
    static String asset(String path) throws Exception {
        try(ZipFile apk=new ZipFile(apk())) {
            ZipEntry entry=apk.getEntry(path);
            if(entry==null)throw new IOException("Harness asset missing: "+path);
            try(InputStream input=apk.getInputStream(entry)) {
                ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
                while((n=input.read(buffer))!=-1){output.write(buffer,0,n);if(output.size()>131072)throw new IOException("Harness asset too large");}
                return output.toString("UTF-8");
            }
        }
    }
    static JSONArray modules() throws Exception {
        JSONArray source=new JSONObject(asset("res/raw/taku_harness.json")).getJSONArray("modules");
        JSONArray result=new JSONArray();
        for(int i=0;i<source.length();i++) {
            JSONObject module=new JSONObject(source.getJSONObject(i).toString());
            module.put("source","assistant-apk/res/raw/taku_harness.json");
            module.put("data",new JSONObject());module.put("hash",HarnessStore.hash(HarnessStore.canonical(module)));
            result.put(module);
        }
        return result;
    }
    static String compose(JSONArray modules) throws Exception {
        StringBuilder text=new StringBuilder("<taku_harness version=\"1\">\n");
        for(int i=0;i<modules.length();i++) {
            JSONObject module=modules.getJSONObject(i);
            text.append("\n[Module ").append(module.getString("id")).append(" / ").append(module.getString("version")).append("]\n")
                    .append(module.getString("content")).append('\n');
            if(module.getJSONObject("data").length()>0)text.append(HarnessStore.canonical(module.getJSONObject("data"))).append('\n');
        }
        return text.append("</taku_harness>").toString();
    }
}
