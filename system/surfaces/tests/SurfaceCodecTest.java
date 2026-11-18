package org.agentos.surfaces;

import java.util.List;
import org.json.JSONObject;

/** Host wire regression; Android's implementation is separately covered by SurfaceApiTest. */
public final class SurfaceCodecTest {
    private static int assertions;
    @FunctionalInterface private interface Check { void run() throws Exception; }
    private static void check(boolean value) { assertions++; if (!value) throw new AssertionError("Wire check "+assertions); }
    private static void rejects(Check action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | org.json.JSONException expected) { assertions++; return; }
        throw new AssertionError("Expected rejection");
    }
    public static void main(String[] args) throws Exception {
        SurfaceSpec original=SurfaceSpec.choice("review",1,"Review details","Address or context",List.of(
            new SurfaceSpec.Action("confirm","Confirm and continue",true),new SurfaceSpec.Action("modify","Modify",false),new SurfaceSpec.Action("cancel","Cancel",false)));
        for (String type:List.of("text","field","fact","notice")) {
            SurfaceSpec current=original.withBlocks(List.of(new SurfaceSpec.Block(type,type.equals("text")?"":"Label","正文 / content")));
            check(SurfaceCodec.parse(SurfaceCodec.encode(current)).equals(current));
        }
        SurfaceSpec mixed=original.withBlocks(List.of(new SurfaceSpec.Block("text","","Existing text"),new SurfaceSpec.Block("field","Name","Content"),
            new SurfaceSpec.Block("fact","Time","20:00"),new SurfaceSpec.Block("notice","Note","Keep this visible")));
        check(SurfaceCodec.parse(SurfaceCodec.encode(mixed)).equals(mixed));
        JSONObject v1=new JSONObject(SurfaceCodec.encode(mixed));
        v1.put("version",1); v1.remove("initialState"); v1.remove("tapActionId"); v1.remove("artifact");
        SurfaceSpec legacy=SurfaceCodec.parse(v1.toString());
        check(legacy.initialState().equals("compact") && legacy.tapActionId().isEmpty() && legacy.blocks().equals(mixed.blocks()));
        for (String invalid:List.of("html","image","script","unknown","Fact")) {
            JSONObject wire=new JSONObject(SurfaceCodec.encode(mixed)); wire.getJSONArray("blocks").getJSONObject(0).put("type",invalid);
            rejects(() -> SurfaceCodec.parse(wire.toString()));
        }
        for (String type:List.of("field","fact","notice")) {
            JSONObject wire=new JSONObject(SurfaceCodec.encode(mixed));
            wire.getJSONArray("blocks").getJSONObject(0).put("type",type).put("label","");
            rejects(() -> SurfaceCodec.parse(wire.toString()));
        }
        JSONObject extra=new JSONObject(SurfaceCodec.encode(mixed));
        extra.getJSONArray("blocks").getJSONObject(0).put("style","execute"); rejects(() -> SurfaceCodec.parse(extra.toString()));
        JSONObject invalidType=new JSONObject(SurfaceCodec.encode(mixed));
        invalidType.getJSONArray("blocks").getJSONObject(0).put("type",42); rejects(() -> SurfaceCodec.parse(invalidType.toString()));
        System.out.println(assertions+" surface wire assertions passed");
    }
}
