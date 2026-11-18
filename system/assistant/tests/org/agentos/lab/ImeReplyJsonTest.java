package org.agentos.lab;
import org.json.*;
/** Boundary tests on the production parser, using only synthetic reply text. */
public final class ImeReplyJsonTest {
 static int checks;
 static void check(boolean ok){if(!ok)throw new AssertionError();checks++;}
 static String wrap(String text)throws Exception{return new JSONObject().put("version",1).put("candidates",new JSONArray().put(new JSONObject().put("text",text))).toString();}
 static void reject(String raw)throws Exception{try{ImeReplyJson.parse(raw);}catch(Exception expected){checks++;return;}throw new AssertionError("Accepted malformed model output");}
 public static void main(String[] args)throws Exception{
  check(ImeReplyJson.parse(wrap(" 我确认下时间再回复～ ")).getJSONArray("candidates").getJSONObject(0).getString("text").equals("我确认下时间再回复～"));
  check(ImeReplyJson.parse("{\"version\":1,\"candidates\":[]}").getJSONArray("candidates").length()==0);
  check(ImeReplyJson.parse(wrap("😀".repeat(48))).getJSONArray("candidates").length()==1);
  reject(wrap("😀".repeat(49)));reject(wrap(" "));reject(wrap("内容\n换行"));reject(wrap("文字\u202e"));reject(wrap("文字\u2066"));
  reject("```json\n"+wrap("好呀")+"\n```");reject(wrap("好呀")+" trailing");reject(wrap("好呀")+"{}");
  reject("{\"version\":\"1\",\"candidates\":[]}");reject("{\"version\":true,\"candidates\":[]}");reject("{\"version\":2,\"candidates\":[]}");
  reject("{\"version\":1,\"candidates\":[{\"text\":123}]}");reject("{\"version\":1,\"candidates\":[{\"text\":null}]}");
  reject("{\"version\":1,\"candidates\":[{\"text\":\"你好\",\"action\":\"send\"}]}");
  reject("{\"version\":1,\"candidates\":[],\"tool_calls\":[]}");reject("{\"version\":1,\"candidates\":[");
  JSONArray candidates=new JSONArray();for(int i=0;i<4;i++)candidates.put(new JSONObject().put("text","候选"+i));reject(new JSONObject().put("version",1).put("candidates",candidates).toString());
  candidates=new JSONArray().put(new JSONObject().put("text","再确认一下")).put(new JSONObject().put("text","再确认一下"));
  check(ImeReplyJson.parse(new JSONObject().put("version",1).put("candidates",candidates).toString()).getJSONArray("candidates").length()==1);
  check(ImeReplyJson.withoutPrevious(ImeReplyJson.parse(wrap("再确认一下")),new JSONArray().put("再确认一下")).getJSONArray("candidates").length()==0);
  check(ImeReplyJson.withoutPrevious(ImeReplyJson.parse(wrap("新的回复")),new JSONArray().put("再确认一下")).getJSONArray("candidates").getJSONObject(0).getString("text").equals("新的回复"));
  System.out.println("PASS ImeReplyJsonTest: "+checks+" checks");
 }
}
