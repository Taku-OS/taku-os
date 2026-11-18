package org.agentos.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

public final class SystemAiCompatibilityTest {
  private static void check(boolean value) { if (!value) throw new AssertionError(); }
  public static void main(String[] args) {
    JSONArray catalog = new JSONArray()
        .put(new JSONObject().put("id", "group/text").put("kind", "generation")
                 .put("textInput", true).put("imageInput", false))
        .put(new JSONObject().put("id", "group/vision").put("kind", "generation")
                 .put("textInput", true).put("imageInput", true))
        .put(new JSONObject().put("id", "group/decision").put("kind", "decision")
                 .put("textInput", true).put("imageInput", true));
    check(SystemAiCompatibility.supports("ime.vision", "group/vision", catalog));
    check(!SystemAiCompatibility.supports("ime.vision", "group/text", catalog));
    check(!SystemAiCompatibility.supports("ime.vision", "group/decision", catalog));
    check(SystemAiCompatibility.supports("screen", "group/vision", catalog));
    check(SystemAiCompatibility.supports("assistant", "group/text", catalog));
    check(!SystemAiCompatibility.supports("assistant", "group/decision", catalog));
    check(SystemAiCompatibility.supports("memory.text", "group/decision", catalog));
    check(SystemAiCompatibility.supports("jev", "group/decision", catalog));
    check(!SystemAiCompatibility.supports("jev", "group/vision", catalog));
    check(!SystemAiCompatibility.supports("jev", "group/missing", catalog));
    check(!SystemAiCompatibility.supports("ime.vision", "group/vision", null));
    System.out.println("PASS SystemAiCompatibilityTest");
  }
}
