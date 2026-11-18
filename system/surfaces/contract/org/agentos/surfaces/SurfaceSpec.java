package org.agentos.surfaces;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Renderer input only. No task execution, model access or reference resolution. */
public record SurfaceSpec(String id, long revision, String kind, String placement,
        String title, String summary, List<Block> blocks, List<Action> actions, long ttlMs,
        String initialState, String tapActionId, ArtifactRef artifact) {
    public static final long DEFAULT_TTL_MS=300000;
    /** Caller-owned reference. The display host never resolves or opens it. */
    public record ArtifactRef(String ref,String mediaType,String title) {
        public ArtifactRef {
            ref=bounded(ref,512,false); title=bounded(title,160,false);
            mediaType=bounded(mediaType,160,false);
            if (!mediaType.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))
                throw new IllegalArgumentException("Invalid media type");
        }
    }
    /** Original v1 constructor keeps its compact, expand-on-tap behavior. */
    public SurfaceSpec(String id,long revision,String kind,String placement,String title,String summary,
            List<Block> blocks,List<Action> actions,long ttlMs) {
        this(id,revision,kind,placement,title,summary,blocks,actions,ttlMs,"compact","",null);
    }
    public record Block(String type, String label, String text) {
        public Block {
            if (!Set.of("text", "field", "fact", "notice").contains(type)) throw new IllegalArgumentException("Unsupported block type");
            label = bounded(label, 120, true); text = bounded(text, 2000, false);
            if (!type.equals("text") && label.isBlank()) throw new IllegalArgumentException("Block label required");
        }
    }
    public record Action(String id, String label, boolean primary) {
        public Action { identifier(id); label = bounded(label, 80, false); }
    }
    public SurfaceSpec {
        identifier(id);
        if (revision < 1 || revision > 9007199254740991L) throw new IllegalArgumentException("Revision out of range");
        if (!Set.of("hint", "choice", "result").contains(kind)) throw new IllegalArgumentException("Unsupported kind");
        if (!Set.of("top", "bottom").contains(placement)) throw new IllegalArgumentException("Unsupported placement");
        title = bounded(title, 160, false); summary = bounded(summary, 500, true);
        blocks = List.copyOf(blocks); actions = List.copyOf(actions);
        if (blocks.size() > 8 || actions.size() > 3) throw new IllegalArgumentException("Content limit exceeded");
        Set<String> ids = new HashSet<>(); int primary = 0;
        for (Action action : actions) {
            if (!ids.add(action.id())) throw new IllegalArgumentException("Duplicate action id");
            if (action.primary()) primary++;
        }
        if (primary > 1) throw new IllegalArgumentException("Only one primary action allowed");
        if (ttlMs < 1000 || ttlMs > 1800000) throw new IllegalArgumentException("TTL must be 1s–30min");
        if (!Set.of("compact","expanded").contains(initialState)) throw new IllegalArgumentException("Unsupported initial state");
        if (tapActionId==null) throw new IllegalArgumentException("tapActionId required");
        if (!tapActionId.isEmpty()) {
            identifier(tapActionId);
            if (kind.equals("choice") || !ids.contains(tapActionId))
                throw new IllegalArgumentException("Tap action must reference a hint/result action");
        }
        if (artifact!=null && !kind.equals("result")) throw new IllegalArgumentException("Artifact requires result kind");
    }
    public static SurfaceSpec hint(String id,long revision,String title,String summary,Action action) {
        Objects.requireNonNull(action,"Hint action required");
        return new SurfaceSpec(id,revision,"hint","bottom",title,summary,List.of(),List.of(action),DEFAULT_TTL_MS,
                "compact",action.id(),null);
    }
    public static SurfaceSpec choice(String id,long revision,String title,String summary,List<Action> options) {
        if (options==null || options.isEmpty()) throw new IllegalArgumentException("Choice options required");
        return new SurfaceSpec(id,revision,"choice","bottom",title,summary,List.of(),options,DEFAULT_TTL_MS,
                "expanded","",null);
    }
    public static SurfaceSpec result(String id,long revision,String title,String summary,ArtifactRef artifact) {
        Objects.requireNonNull(artifact,"Result artifact required");
        return new SurfaceSpec(id,revision,"result","bottom",title,summary,List.of(),
                List.of(new Action("open-details","View details",true)),DEFAULT_TTL_MS,"compact","open-details",artifact);
    }
    public SurfaceSpec withPlacement(String value) { return new SurfaceSpec(id,revision,kind,value,title,summary,blocks,actions,ttlMs,initialState,tapActionId,artifact); }
    public SurfaceSpec withTtlMs(long value) { return new SurfaceSpec(id,revision,kind,placement,title,summary,blocks,actions,value,initialState,tapActionId,artifact); }
    public SurfaceSpec withInitialState(String value) { return new SurfaceSpec(id,revision,kind,placement,title,summary,blocks,actions,ttlMs,value,tapActionId,artifact); }
    public SurfaceSpec withBlocks(List<Block> value) { return new SurfaceSpec(id,revision,kind,placement,title,summary,value,actions,ttlMs,initialState,tapActionId,artifact); }
    static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))
            throw new IllegalArgumentException("Invalid id");
    }
    static String bounded(String value, int max, boolean empty) {
        if (value == null || value.length() > max || (!empty && value.isBlank()))
            throw new IllegalArgumentException("Invalid text length");
        return value;
    }
}
