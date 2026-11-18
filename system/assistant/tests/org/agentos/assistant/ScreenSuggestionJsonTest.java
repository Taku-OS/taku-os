package org.agentos.assistant;
import org.json.JSONObject;

/** Reject malformed or oversized model output before it can become an Agent request. */
public final class ScreenSuggestionJsonTest {
    static int checks;
    static void check(boolean ok) { checks++; if(!ok) throw new AssertionError(); }
    static String valid() throws Exception { return new JSONObject().put("actionable",true).put("title","明晚三人菌汤火锅")
        .put("suggestion","要看看清淡不辣的餐厅吗？").put("task","根据截图中的约饭讨论找餐厅，缺少地点时先询问。").toString(); }
    static void rejects(String value) throws Exception { try { ScreenSuggestionJson.parse(value); } catch(Exception expected) { checks++;return; } throw new AssertionError("accepted invalid output"); }
    public static void main(String[] args) throws Exception {
        ScreenSuggestionJson result=ScreenSuggestionJson.parse(valid());check(result.actionable);check(result.title.contains("三人"));
        check(!ScreenSuggestionJson.parse(new JSONObject().put("actionable",false).put("title","").put("suggestion","").put("task","").toString()).actionable);
        rejects("```json\n"+valid()+"\n```");rejects(valid()+" trailing");rejects("[]");rejects("null");
        rejects(new JSONObject(valid()).put("transcript","screen text").toString());
        rejects(new JSONObject(valid()).put("actionable","true").toString());
        rejects(new JSONObject(valid()).put("title","a".repeat(61)).toString());
        rejects(new JSONObject(valid()).put("task","").toString());
        rejects(new JSONObject(valid()).put("task","a".repeat(1601)).toString());
        rejects(new JSONObject(valid()).put("suggestion",42).toString());
        System.out.println("ScreenSuggestionJsonTest: "+checks+" checks passed");
    }
}
