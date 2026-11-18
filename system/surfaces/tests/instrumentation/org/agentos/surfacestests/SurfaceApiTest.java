package org.agentos.surfacestests;

import android.app.Instrumentation;
import android.os.Bundle;
import java.util.List;
import org.agentos.surfaces.SurfaceCodec;
import org.agentos.surfaces.SurfaceSpec;
import org.json.JSONObject;

/** Runs against Android's real org.json implementation; no mocked wire parser. */
public final class SurfaceApiTest extends Instrumentation {
    private int assertions;
    @FunctionalInterface private interface Check { void run() throws Exception; }
    private void check(boolean value) { assertions++; if (!value) throw new AssertionError("Check "+assertions); }
    private void rejects(Check action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | org.json.JSONException expected) { assertions++; return; }
        throw new AssertionError("Expected rejection");
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            SurfaceSpec hint=SurfaceSpec.hint("hint",1,"中文提示","说明",new SurfaceSpec.Action("accept","确认",true));
            check(SurfaceCodec.parse(SurfaceCodec.encode(hint)).equals(hint));
            SurfaceSpec choice=SurfaceSpec.choice("choice",2,"选择","",List.of(new SurfaceSpec.Action("yes","是",true)));
            check(SurfaceCodec.parse(SurfaceCodec.encode(choice)).equals(choice));
            SurfaceSpec structured=choice.withBlocks(List.of(new SurfaceSpec.Block("field","Context","A value"),
                    new SurfaceSpec.Block("fact","Date","2026-10-26"),new SurfaceSpec.Block("notice","Note","Review this detail")));
            check(SurfaceCodec.parse(SurfaceCodec.encode(structured)).equals(structured));
            JSONObject unknownBlock=new JSONObject(SurfaceCodec.encode(structured));
            unknownBlock.getJSONArray("blocks").getJSONObject(1).put("type","html");
            rejects(() -> SurfaceCodec.parse(unknownBlock.toString()));
            JSONObject unlabeled=new JSONObject(SurfaceCodec.encode(structured));
            unlabeled.getJSONArray("blocks").getJSONObject(2).put("label","");
            rejects(() -> SurfaceCodec.parse(unlabeled.toString()));
            SurfaceSpec item=SurfaceSpec.result("result",3,"完成","",new SurfaceSpec.ArtifactRef("fixture:document","text/markdown","结果.md"));
            check(SurfaceCodec.parse(SurfaceCodec.encode(item)).equals(item));
            JSONObject v2=new JSONObject(SurfaceCodec.encode(hint));
            JSONObject v1=new JSONObject(v2.toString()); v1.put("version",1); v1.remove("initialState"); v1.remove("tapActionId"); v1.remove("artifact");
            SurfaceSpec old=SurfaceCodec.parse(v1.toString()); check(old.initialState().equals("compact")); check(old.tapActionId().isEmpty()); check(old.artifact()==null);
            JSONObject legacyExtra=new JSONObject(v1.toString()).put("initialState","expanded"); rejects(() -> SurfaceCodec.parse(legacyExtra.toString()));
            rejects(() -> SurfaceCodec.parse(new JSONObject(v2.toString()).put("version",3).toString()));
            JSONObject missing=new JSONObject(v2.toString()); missing.remove("initialState"); rejects(() -> SurfaceCodec.parse(missing.toString()));
            rejects(() -> SurfaceCodec.parse(new JSONObject(v2.toString()).put("tapActionId",true).toString()));
            rejects(() -> SurfaceCodec.parse(new JSONObject(v2.toString()).put("tapActionId","invented").toString()));
            rejects(() -> SurfaceCodec.parse(new JSONObject(SurfaceCodec.encode(choice)).put("tapActionId","yes").toString()));
            rejects(() -> SurfaceCodec.parse(new JSONObject(v2.toString()).put("initialState","fullscreen").toString()));
            JSONObject artifact=new JSONObject().put("ref","x").put("title","Title").put("mediaType","text/plain");
            rejects(() -> SurfaceCodec.parse(new JSONObject(v2.toString()).put("artifact",artifact).toString()));
            rejects(() -> SurfaceCodec.parse(new JSONObject(SurfaceCodec.encode(item)).put("artifact",true).toString()));
            JSONObject unknown=new JSONObject(artifact.toString()).put("execute","shell");
            rejects(() -> SurfaceCodec.parse(new JSONObject(SurfaceCodec.encode(item)).put("artifact",unknown).toString()));
            result.putString("stream",assertions+" Android wire assertions passed\n"); result.putInt("assertions",assertions); finish(-1,result);
        } catch (Throwable failure) {
            result.putString("stream",failure.toString()+"\n"); result.putString("failure",failure.toString()); finish(0,result);
        }
    }
}
