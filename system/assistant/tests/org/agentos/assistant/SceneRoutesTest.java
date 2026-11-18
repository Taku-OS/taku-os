package org.agentos.assistant;

import org.json.*;

public final class SceneRoutesTest {
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        JSONObject c = new JSONObject().put("scenes", new JSONObject().put("jev", "group/old").put("memory.text", "group/text"))
                .put("groups", new JSONArray().put(new JSONObject().put("id", "text").put("members", new JSONArray().put("chat/model"))));
        check(SceneRoutes.bindUnified(c, "jev", "group/new", false).equals("group/old"));
        check(SceneRoutes.bindUnified(c, "jev", "group/new", true).equals("group/new"));
        check(c.getJSONObject("scenes").getString("memory.text").equals("group/text"));
        check(SceneRoutes.bindUnified(c, "jev", "provider/decision", true).equals("group/route-jev"));
        check(c.getJSONArray("groups").getJSONObject(1).getJSONArray("members").getString(0).equals("provider/decision"));
        check(SceneRoutes.bindUnified(c, "jev", "provider/decision", true).equals("group/route-jev"));
        check(SceneRoutes.bindUnified(c, "jev", "provider/other", true).equals("group/route-jev-2"));
        System.out.println("PASS SceneRoutesTest: unified bindings preserve other scenes and reuse groups");
    }
}
