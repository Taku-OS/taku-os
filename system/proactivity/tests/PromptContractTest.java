package org.agentos.proactivity;

import android.content.Context;
import java.nio.file.Files;
import org.agentos.proactivity.api.*;
import org.agentos.surfaces.SurfaceSpec;
import org.json.JSONObject;

/** Offline declarations, renderer inputs, approval and policy editing; no model or device calls. */
public final class PromptContractTest {
    private static int assertions;
    private static void check(boolean condition){assertions++;if(!condition)throw new AssertionError("assertion "+assertions);}
    private interface Checked {void run()throws Exception;}
    private static void rejects(Checked action)throws Exception{
        try{action.run();throw new AssertionError("Invalid change accepted");}
        catch(IllegalArgumentException|IllegalStateException|org.json.JSONException expected){assertions++;}
    }
    private static ObserverSpec legacy(){return new ObserverSpec("example.app.rule","Rule","example.app.changed",700,1500,3,10000,"Try it on?","Preview this item");}
    private static ObserverSpec choice(){var s=legacy();return new ObserverSpec(s.id(),s.name(),s.eventType(),s.quietMs(),s.cooldownMs(),s.maxRecords(),s.lookbackMs(),s.title(),s.summary(),new PromptSpec("Try on","Not now"));}
    private static ObserverSpec timing(ObserverSpec s,long quiet,PromptSpec prompt){return new ObserverSpec(s.id(),s.name(),s.eventType(),quiet,s.cooldownMs(),s.maxRecords(),s.lookbackMs(),s.title(),s.summary(),prompt);}
    public static void main(String[] args)throws Exception{
        ObserverSpec old=legacy(),prompt=choice();
        check(old.prompt()==null);check(ProactivityWire.spec(old).length()==9);check(ProactivityWire.spec(ProactivityWire.spec(old)).equals(old));
        check(ProactivityWire.spec(prompt).length()==10);check(ProactivityWire.spec(ProactivityWire.spec(prompt)).equals(prompt));
        rejects(()->new PromptSpec(" ","Not now"));rejects(()->new PromptSpec("Try on",null));rejects(()->new PromptSpec("x".repeat(81),"Not now"));
        rejects(()->ProactivityWire.spec(ProactivityWire.spec(old).put("prompt",JSONObject.NULL)));
        rejects(()->ProactivityWire.spec(ProactivityWire.spec(prompt).put("prompt",new JSONObject().put("acceptLabel","Try on"))));
        rejects(()->ProactivityWire.spec(ProactivityWire.spec(prompt).put("prompt",new JSONObject().put("acceptLabel","Try on").put("declineLabel","Not now").put("service","arbitrary.Service"))));
        rejects(()->ProactivityWire.spec(ProactivityWire.spec(prompt).put("prompt",new JSONObject().put("acceptLabel","Try on").put("actionId","launch"))));
        rejects(()->ProactivityWire.spec(ProactivityWire.spec(old).put("extra",true)));
        SurfaceSpec hint=PromptPresentation.surface("candidate-1",1,old),card=PromptPresentation.surface("candidate-2",2,prompt);
        check(hint.kind().equals("hint") && hint.actions().get(0).label().equals("Viewed"));
        check(PromptPresentation.type(old).equals("hint") && PromptPresentation.type(prompt).equals("choice"));
        check(card.kind().equals("choice") && card.placement().equals("top") && card.initialState().equals("expanded"));
        check(card.tapActionId().isEmpty() && card.ttlMs()==120000);
        check(card.actions().size()==2 && card.actions().get(0).equals(new SurfaceSpec.Action("cancel","Not now",false))
            && card.actions().get(1).equals(new SurfaceSpec.Action("accept","Try on",true)));
        check(PromptPresentation.accepts(old,"accept") && !PromptPresentation.accepts(old,"cancel"));
        check(PromptPresentation.accepts(prompt,"accept") && PromptPresentation.accepts(prompt,"cancel") && !PromptPresentation.accepts(prompt,"launch"));
        Context context=new Context(Files.createTempDirectory("proactivity-prompt-test-").toFile());
        RegistryStore store=new RegistryStore(context);String key="example.app/"+old.id();
        JSONObject row=store.register("example.app","signer",1,old);long first=row.getLong("pendingRevision");
        check(!RegistryStore.active(row));store.approve(key,first);check(RegistryStore.active(store.item(key)));
        row=store.register("example.app","signer",1,prompt);long pending=row.getLong("pendingRevision");
        check(pending>first && !RegistryStore.active(row));check(RegistryStore.activeSpec(row).prompt()==null);
        rejects(()->store.approve(key,first));rejects(()->store.editPolicy(key,first,timing(old,800,old.prompt())));
        store.approve(key,pending);row=store.item(key);check(RegistryStore.active(row) && RegistryStore.activeSpec(row).equals(prompt));
        check(store.register("example.app","signer",1,prompt).getLong("pendingRevision")==0);
        rejects(()->store.editPolicy(key,pending,timing(prompt,800,null)));
        rejects(()->store.editPolicy(key,pending,timing(prompt,800,new PromptSpec("Launch","Later"))));
        store.editPolicy(key,pending,timing(prompt,800,prompt.prompt()));row=store.item(key);long edited=row.getLong("activeRevision");
        check(edited>pending && RegistryStore.activeSpec(row).prompt().equals(prompt.prompt()) && RegistryStore.activeSpec(row).quietMs()==800);
        check(new RegistryStore(context).item(key).toString().equals(row.toString()));
        store.rollback(key,edited);row=store.item(key);check(RegistryStore.activeSpec(row).equals(prompt));
        store.rollback(key,row.getLong("activeRevision"));check(RegistryStore.activeSpec(store.item(key)).prompt().equals(prompt.prompt()));
        System.out.println(assertions+" proactivity prompt contract/approval assertions passed (offline; no model, emulator or ROM validation)");
    }
}
