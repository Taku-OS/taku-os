package org.agentos.lab;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable content objects and append-only context receipts; native threads own chat history. */
final class HarnessStore {
    final File root;
    HarnessStore(File root) throws Exception {
        this.root = root;
        root.mkdirs();
        android.system.Os.chown(root.getPath(), 1000, 1000);
        android.system.Os.chmod(root.getPath(), 0700);
        new File(root, "objects").mkdirs();
        new File(root, "threads").mkdirs();
    }
    static String canonical(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>(); object.keys().forEachRemaining(keys::add);
            Collections.sort(keys);
            List<String> fields = new ArrayList<>();
            for (String key : keys) fields.add(JSONObject.quote(key) + ":" + canonical(object.get(key)));
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; List<String> fields = new ArrayList<>();
            for (int i=0;i<array.length();i++) fields.add(canonical(array.get(i)));
            return "[" + String.join(",", fields) + "]";
        }
        if (value == null || value == JSONObject.NULL) return "null";
        return value instanceof String ? JSONObject.quote((String)value) : value.toString();
    }
    static String hash(String text) throws Exception {
        return hash(text.getBytes(StandardCharsets.UTF_8));
    }
    static String hash(byte[] input) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(input);
        StringBuilder out = new StringBuilder(); for(byte b:bytes) out.append(String.format(Locale.ROOT,"%02x",b & 255));
        return out.toString();
    }
    String object(JSONObject value) throws Exception {
        String text = canonical(value), digest = hash(text);
        immutable(new File(root,"objects/"+digest+".json"),text);
        return digest;
    }
    JSONObject object(String digest) throws Exception {
        if (!digest.matches("[a-f0-9]{64}")) throw new IOException("Invalid object hash");
        String text = TaskBroker.read(new File(root,"objects/"+digest+".json"));
        if (!hash(text).equals(digest)) throw new IOException("Context object integrity failure");
        return new JSONObject(text);
    }
    File thread(String id) throws Exception {
        if (!id.matches("[a-f0-9-]{36}")) throw new IOException("Invalid native thread ID");
        File folder = new File(root,"threads/"+id);folder.mkdirs();return folder;
    }
    JSONObject state(String id) throws Exception {
        File file = new File(thread(id),"state.json");
        JSONObject state=file.exists() ? new JSONObject(TaskBroker.read(file)) : new JSONObject().put("sequence",0).put("head","");
        boolean repaired=false;
        for(int count=0;count<10000;count++) {
            int next=state.optInt("sequence")+1;
            File index=new File(thread(id),String.format(Locale.ROOT,"%06d.json",next));if(!index.exists())break;
            String digest=new JSONObject(TaskBroker.read(index)).getString("hash");JSONObject record=object(digest);
            if(record.optInt("sequence")!=next||!record.optString("previousHash").equals(state.optString("head")))throw new IOException("Context journal recovery invalid");
            state.put("sequence",next).put("head",digest);repaired=true;
        }
        if(repaired)TaskBroker.write(file,canonical(state));
        return state;
    }
    void state(String id, JSONObject value) throws Exception {
        TaskBroker.write(new File(thread(id),"state.json"),canonical(value));
    }
    JSONObject append(String id, JSONObject event) throws Exception {
        JSONObject state = state(id);
        JSONObject record = new JSONObject(event.toString());
        int sequence = state.optInt("sequence")+1;
        record.put("sequence",sequence).put("previousHash",state.optString("head"));
        record.put("observedAt",System.currentTimeMillis());
        String digest = object(record);
        immutable(new File(thread(id),String.format(Locale.ROOT,"%06d.json",sequence)),canonical(new JSONObject().put("hash",digest)));
        state.put("sequence",sequence).put("head",digest);state(id,state);
        return record.put("receiptHash",digest);
    }
    JSONArray events(String id) throws Exception {
        JSONArray result = new JSONArray(); JSONObject state=state(id); String previous="";
        for(int n=1;n<=state.optInt("sequence");n++) {
            JSONObject ref=new JSONObject(TaskBroker.read(new File(thread(id),String.format(Locale.ROOT,"%06d.json",n))));
            JSONObject record=object(ref.getString("hash"));
            if(!previous.equals(record.optString("previousHash")))throw new IOException("Context receipt chain invalid");
            previous=ref.getString("hash");
            if(n>state.optInt("sequence")-100)result.put(record.put("receiptHash",previous));
        }
        return result;
    }
    private static void immutable(File file,String text) throws Exception {
        if(file.exists()) {
            if(!TaskBroker.read(file).equals(text))throw new IOException("Immutable context collision");
            return;
        }
        TaskBroker.write(file,text);
    }
}
