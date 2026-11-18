import org.agentos.surfaces.SurfaceSpec;
import org.agentos.surfaces.SurfaceStore;
import java.util.List;

public final class SurfaceStoreTest {
    private static int assertions;
    private static void check(boolean condition) { assertions++; if (!condition) throw new AssertionError("Check "+assertions); }
    private static SurfaceSpec spec(String id,long revision) {
        return new SurfaceSpec(id,revision,"choice","top","中文标题","",List.of(),List.of(new SurfaceSpec.Action("select","选择",true)),1000);
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("Expected rejection");
    }
    public static void main(String[] args) {
        SurfaceStore store=new SurfaceStore();
        SurfaceStore.Entry one=store.publish(10,spec("same",1),0);
        SurfaceStore.Entry two=store.publish(20,spec("same",1),0);
        check(store.entries().size()==2);
        store.remove(one.key); check(store.get(two.key)!=null); // Caller-scoped identifiers.
        rejects(() -> store.publish(20,spec("same",1),0));
        store.publish(20,spec("same",2),0);
        check(!store.select(two.key,1,"select")); // A click from a replaced view cannot act.
        check(!store.select(two.key,2,"invented"));
        check(store.select(two.key,2,"select")); check(!store.select(two.key,2,"select"));
        store.publish(20,spec("same",3),500); check(store.select(two.key,3,"select"));
        check(store.expire(1000).isEmpty()); check(store.expire(1500).size()==1); check(store.entries().isEmpty());
        for (int i=0;i<8;i++) store.publish(i,spec("card",1),0);
        rejects(() -> store.publish(99,spec("card",1),0));
        store.publish(0,spec("card",2),0); check(store.entries().size()==8);
        rejects(() -> spec("../bad",1)); rejects(() -> spec("bad",0));
        rejects(() -> spec("bad",Long.MAX_VALUE));
        rejects(() -> new SurfaceSpec("x",1,"hint","center","Title","",List.of(),List.of(),1000));
        rejects(() -> new SurfaceSpec("x",1,"hint","top","Title","",List.of(),List.of(),Long.MAX_VALUE));
        rejects(() -> new SurfaceSpec("x",1,"hint","top","Title","",List.of(),List.of(
            new SurfaceSpec.Action("a","A",true),new SurfaceSpec.Action("a","B",false)),1000));
        rejects(() -> new SurfaceSpec.Block("html","","<script>"));
        check(new SurfaceSpec.Block("text","","Original text").text().equals("Original text"));
        check(new SurfaceSpec.Block("field","Original field","Value").type().equals("field"));
        check(new SurfaceSpec.Block("fact","Date","2026-10-26").type().equals("fact"));
        check(new SurfaceSpec.Block("notice","Important","A note for review").type().equals("notice"));
        for (String type:List.of("field","fact","notice")) {
            rejects(() -> new SurfaceSpec.Block(type," ","Value"));
            rejects(() -> new SurfaceSpec.Block(type,"Label"," "));
        }
        rejects(() -> new SurfaceSpec.Block("image","Label","https://example.com/image"));
        store.clear(); check(store.entries().isEmpty());
        SurfaceSpec hint=SurfaceSpec.hint("hint",1,"提示","",new SurfaceSpec.Action("accept","确认",true));
        check(hint.initialState().equals("compact") && hint.tapActionId().equals("accept"));
        SurfaceSpec choice=SurfaceSpec.choice("choice",1,"选择","",List.of(new SurfaceSpec.Action("a","A",true)));
        check(choice.initialState().equals("expanded") && choice.tapActionId().isEmpty());
        SurfaceSpec.ArtifactRef artifact=new SurfaceSpec.ArtifactRef("demo:document","text/markdown","中文.md");
        SurfaceSpec result=SurfaceSpec.result("result",1,"结果","",artifact);
        check(result.tapActionId().equals("open-details") && result.artifact().equals(artifact));
        SurfaceSpec configured=choice.withInitialState("compact").withPlacement("bottom").withTtlMs(2000)
                .withBlocks(List.of(new SurfaceSpec.Block("field","标签","值")));
        check(configured.initialState().equals("compact") && configured.ttlMs()==2000 && configured.blocks().size()==1);
        SurfaceSpec structured=choice.withBlocks(List.of(new SurfaceSpec.Block("fact","Time","20:00"),new SurfaceSpec.Block("notice","Review","Check details")));
        check(structured.initialState().equals("expanded") && structured.tapActionId().isEmpty() && structured.blocks().size()==2);
        check(spec("legacy",1).initialState().equals("compact") && spec("legacy",1).tapActionId().isEmpty());
        rejects(() -> new SurfaceSpec("bad",1,"choice","top","Title","",List.of(),choice.actions(),1000,"compact","a",null));
        rejects(() -> new SurfaceSpec("bad",1,"hint","top","Title","",List.of(),hint.actions(),1000,"compact","missing",null));
        rejects(() -> new SurfaceSpec("bad",1,"hint","top","Title","",List.of(),hint.actions(),1000,"compact","",artifact));
        rejects(() -> SurfaceSpec.choice("bad",1,"选择","",List.of()));
        rejects(() -> new SurfaceSpec.ArtifactRef("ref","not-a-mime","Title"));
        rejects(() -> configured.withInitialState("fullscreen"));
        SurfaceStore.Entry prompt=store.publish(10,hint,0);
        check(store.select(prompt.key,1,"accept")); check(!store.select(prompt.key,1,"accept"));
        SurfaceStore.Entry output=store.publish(10,result,0);
        check(store.select(output.key,1,"open-details")); check(!store.select(output.key,1,"open-details"));
        check(store.get(output.key).spec.artifact().equals(artifact));
        System.out.println(assertions+" surface lifecycle assertions passed");
    }
}
