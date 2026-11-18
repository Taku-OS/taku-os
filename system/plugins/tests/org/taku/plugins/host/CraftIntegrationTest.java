package org.taku.plugins.host;

import java.nio.file.*;
import java.util.*;

public final class CraftIntegrationTest {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("evidence-test");
        EvidenceLedger ledger = new EvidenceLedger(root);
        Map<String,Object> first = new LinkedHashMap<>();
        first.put("params", Map.of("z", 2, "a", 1)); first.put("coverage", "partial"); first.put("unknown", List.of("missing data"));
        Map<String,Object> second = new LinkedHashMap<>();
        second.put("unknown", List.of("missing data")); second.put("coverage", "partial"); second.put("params", Map.of("a", 1, "z", 2));
        String id = ledger.append(first);
        check(id.equals(ledger.append(second)), "canonical hash must ignore key order");
        check(ledger.list().size() == 1, "deduplicate identical content");
        check(ledger.read(id).get("coverage").equals("partial"), "partial coverage retained");
        check(ledger.read(id).get("unknown").equals(List.of("missing data")), "unknown retained");
        Files.writeString(root.resolve("evidence").resolve(id + ".json"), "{\"id\":\"" + id + "\",\"coverage\":\"complete\"}");
        try { ledger.read(id); throw new AssertionError("tamper not detected"); } catch (PluginException error) { check(error.code.equals("CORRUPT_EVIDENCE"), "tamper error code"); }
        Map<String,Object> raw = new LinkedHashMap<>(Map.of("name", "read", "inputSchema", Map.of("type", "object"), "toolset", "files", "unrecognized", 42));
        ToolSpec spec = new ToolSpec(raw, "org.example");
        check(spec.toolset().equals("files") && !spec.summary().containsKey("inputSchema"), "summary and toolset");
        check(((Map<?,?>)spec.detail().get("extensions")).containsKey("unrecognized"), "unknown fields retained");
        WorkspaceState state = new WorkspaceState("new").transition("inspecting").transition("building").transition("checking").transition("ready");
        check(state.value().equals("ready"), "ready state");
        try { state.transition("building"); throw new AssertionError("invalid transition accepted"); } catch (IllegalArgumentException expected) { }
        System.out.println("PASS: evidence, tool spec, workspace state");
    }
}
