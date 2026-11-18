package org.agentos.lab;

import org.json.*;
import java.io.File;
import java.util.UUID;

/** Real filesystem invariant tests; no fake model or cache-hit acceptance. */
public final class HarnessStoreTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        check(HarnessStore.hash("abc").equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),"SHA-256 UTF-8 contract");
        JSONObject first=new JSONObject().put("b",2).put("a",1),same=new JSONObject().put("a",1).put("b",2);
        check(HarnessStore.canonical(first).equals(HarnessStore.canonical(same)),"Property insertion order must not change context bytes");
        HarnessStore store=new HarnessStore(new File("/data/local/tmp/harness-store-tests-"+UUID.randomUUID()));
        String original=store.object(first),thread=UUID.randomUUID().toString();
        store.append(thread,new JSONObject().put("kind","background").put("snapshot",original));
        String oldBytes=TaskBroker.read(new File(store.root,"objects/"+original+".json"));
        String changed=store.object(new JSONObject().put("a",1).put("b",3));
        store.append(thread,new JSONObject().put("kind","context-update").put("snapshot",changed));
        check(!original.equals(changed),"Changed data must receive a new snapshot identity");
        check(TaskBroker.read(new File(store.root,"objects/"+original+".json")).equals(oldBytes),"Update must preserve original bytes");
        JSONArray receipts=store.events(thread);check(receipts.length()==2,"Append preserves both receipts");
        check(receipts.getJSONObject(1).getString("previousHash").equals(receipts.getJSONObject(0).getString("receiptHash")),"Chain links previous immutable receipt");
        TaskBroker.write(new File(store.thread(thread),"state.json"),"{\"sequence\":0,\"head\":\"\"}");
        check(store.state(thread).getInt("sequence")==2,"Written immutable receipts recover a stale mutable head after interruption");
        TaskBroker.write(new File(store.root,"objects/"+changed+".json"),"{\"a\":9}");
        boolean rejected=false;try{store.object(changed);}catch(java.io.IOException expected){rejected=true;}
        check(rejected,"Corrupt context must be rejected");
        JSONObject parsed=AgentSkills.metadata(new File(args[0]));check(parsed.getString("name").equals("taku-app-development"),"Builtin skill metadata parse");
        System.out.println("PASS: immutable snapshots, canonical order, append-only chain, corruption rejection and builtin skill metadata. Protocol/filesystem only.");
    }
}
