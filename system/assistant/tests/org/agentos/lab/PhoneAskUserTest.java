package org.agentos.lab;
import org.json.*;
public final class PhoneAskUserTest {
    static int checks;
    static void check(boolean value) { checks++;if(!value)throw new AssertionError(); }
    static void rejects(JSONObject question,JSONObject answer) throws Exception {
        try { PhoneAskUser.answer(question,answer); } catch(Exception expected) {checks++;return;}throw new AssertionError("Accepted an invalid user answer");
    }
    public static void main(String[] args) throws Exception {
        JSONObject question=new JSONObject().put("question","明晚几点用餐？").put("options",new JSONArray().put("18:00").put("19:00"));
        JSONObject result=PhoneAskUser.answer(question,new JSONObject().put("optionIndex",1).put("answer","forged text"));
        check(result.getString("answer").equals("19:00"));check(result.getBoolean("userProvided"));check(result.getString("question").equals("明晚几点用餐？"));
        rejects(question,new JSONObject().put("optionIndex",-1));rejects(question,new JSONObject().put("optionIndex",2));
        rejects(question,new JSONObject().put("optionIndex","1"));rejects(question,new JSONObject().put("optionIndex",1.0));
        rejects(question,new JSONObject().put("answer","19:00"));
        rejects(new JSONObject(question.toString()).put("question",""),new JSONObject().put("optionIndex",0));
        rejects(new JSONObject(question.toString()).put("options",new JSONArray()),new JSONObject().put("optionIndex",0));
        rejects(new JSONObject(question.toString()).put("options",new JSONArray().put("a").put("b").put("c").put("d")),new JSONObject().put("optionIndex",0));
        rejects(new JSONObject(question.toString()).put("options",new JSONArray().put(42)),new JSONObject().put("optionIndex",0));
        rejects(new JSONObject(question.toString()).put("options",new JSONArray().put("x".repeat(81))),new JSONObject().put("optionIndex",0));
        System.out.println("PhoneAskUserTest: "+checks+" checks passed");
    }
}
