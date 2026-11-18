package org.agentos.surfaces;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Shared v1/v2 wire codec, used by the host and the Android client SDK. */
public final class SurfaceCodec {
    private static final Set<String> V1_KEYS=Set.of("version","id","revision","kind","placement","title","summary","blocks","actions","ttlMs");
    private static final Set<String> V2_KEYS=Set.of("version","id","revision","kind","placement","title","summary","blocks","actions","ttlMs","initialState","tapActionId","artifact");
    private SurfaceCodec() {}
    public static SurfaceSpec parse(String source) throws JSONException {
        if (source == null || source.length() > 24000) throw new IllegalArgumentException("Payload limit exceeded");
        JSONObject value = new JSONObject(source);
        long version=integer(value,"version");
        if (version!=1 && version!=2) throw new IllegalArgumentException("Unsupported protocol version");
        keys(value,version==1?V1_KEYS:V2_KEYS);
        List<SurfaceSpec.Block> blocks = new ArrayList<>();
        JSONArray rawBlocks = value.getJSONArray("blocks");
        if (rawBlocks.length() > 8) throw new IllegalArgumentException("Too many blocks");
        for (int i=0; i<rawBlocks.length(); i++) {
            JSONObject block=rawBlocks.getJSONObject(i); keys(block,Set.of("type","label","text"));
            blocks.add(new SurfaceSpec.Block(text(block,"type"),text(block,"label"),text(block,"text")));
        }
        List<SurfaceSpec.Action> actions = new ArrayList<>();
        JSONArray rawActions = value.getJSONArray("actions");
        if (rawActions.length() > 3) throw new IllegalArgumentException("Too many actions");
        for (int i=0; i<rawActions.length(); i++) {
            JSONObject action=rawActions.getJSONObject(i); keys(action,Set.of("id","label","primary"));
            if (!(action.get("primary") instanceof Boolean)) throw new IllegalArgumentException("primary must be boolean");
            actions.add(new SurfaceSpec.Action(text(action,"id"),text(action,"label"),action.getBoolean("primary")));
        }
        SurfaceSpec.ArtifactRef artifact=null;
        if (version==2 && value.has("artifact") && !value.isNull("artifact")) {
            JSONObject item=value.getJSONObject("artifact"); keys(item,Set.of("ref","mediaType","title"));
            artifact=new SurfaceSpec.ArtifactRef(text(item,"ref"),text(item,"mediaType"),text(item,"title"));
        }
        return new SurfaceSpec(text(value,"id"),integer(value,"revision"),text(value,"kind"),
                text(value,"placement"),text(value,"title"),text(value,"summary"),blocks,actions,integer(value,"ttlMs"),
                version==1?"compact":text(value,"initialState"),version==1?"":text(value,"tapActionId"),artifact);
    }
    /** Typed clients emit v2; the host continues accepting the original v1 payload. */
    public static String encode(SurfaceSpec spec) {
        try {
            JSONObject value=new JSONObject().put("version",2).put("id",spec.id()).put("revision",spec.revision())
                    .put("kind",spec.kind()).put("placement",spec.placement()).put("title",spec.title()).put("summary",spec.summary())
                    .put("ttlMs",spec.ttlMs()).put("initialState",spec.initialState()).put("tapActionId",spec.tapActionId());
            JSONArray blocks=new JSONArray(),actions=new JSONArray();
            for (SurfaceSpec.Block block:spec.blocks()) blocks.put(new JSONObject().put("type",block.type()).put("label",block.label()).put("text",block.text()));
            for (SurfaceSpec.Action action:spec.actions()) actions.put(new JSONObject().put("id",action.id()).put("label",action.label()).put("primary",action.primary()));
            value.put("blocks",blocks).put("actions",actions);
            SurfaceSpec.ArtifactRef item=spec.artifact();
            value.put("artifact",item==null?JSONObject.NULL:new JSONObject().put("ref",item.ref()).put("mediaType",item.mediaType()).put("title",item.title()));
            String encoded=value.toString();
            if (encoded.length()>24000) throw new IllegalArgumentException("Payload limit exceeded");
            return encoded;
        } catch (JSONException impossible) { throw new IllegalStateException("Cannot encode surface",impossible); }
    }
    private static String text(JSONObject object, String key) throws JSONException {
        Object value=object.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException(key+" must be string");
        return (String)value;
    }
    private static long integer(JSONObject object, String key) throws JSONException {
        Object value=object.get(key);
        if (!(value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException(key+" must be integer");
        return ((Number)value).longValue();
    }
    private static void keys(JSONObject value, Set<String> allowed) {
        Iterator<String> keys=value.keys();
        while (keys.hasNext()) if (!allowed.contains(keys.next())) throw new IllegalArgumentException("Unknown property");
    }
}
