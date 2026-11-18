package org.agentos.lab;
import org.json.*;
import java.util.Set;

/** Ordinary text models may classify memories, with the same bounded typed answer contract. */
final class MemoryDecisionJson {
 static JSONObject parse(String text,JSONObject questions)throws Exception{
  if(text==null||text.length()>24000)throw new IllegalArgumentException("Memory organization result is too large");
  JSONTokener t=new JSONTokener(text);Object value=t.nextValue();if(!(value instanceof JSONObject)||t.nextClean()!=0)throw new IllegalArgumentException("Memory organization requires complete JSON");
  JSONObject root=(JSONObject)value;if(root.length()!=1||!root.has("answers"))throw new IllegalArgumentException("Invalid memory organization result format");
  JSONObject answers=root.getJSONObject("answers");if(answers.length()!=questions.length())throw new IllegalArgumentException("Memory organization question count mismatch");
  for(String id:AiPolicy.keys(questions)){
   JSONObject q=questions.getJSONObject(id),a=answers.getJSONObject(id);String type=q.getString("type");
   if(!type.equals(a.getString("type")))throw new IllegalArgumentException("Memory organization answer type mismatch");
   Set<String> keys=type.equals("choice")?Set.of("type","choice","confidence"):Set.of("type","noul");
   if(!AiPolicy.keys(a).equals(keys))throw new IllegalArgumentException("Invalid memory organization answer field");
   if(type.equals("choice")){if(!q.getJSONObject("criteria").has(a.getString("choice")))throw new IllegalArgumentException("Memory category is outside the allowed range");probability(a,"confidence");}
   else if(type.equals("noul"))probability(a,"noul");else throw new IllegalArgumentException("Unknown memory question type");
  }return root;
 }
 static void probability(JSONObject a,String key)throws Exception{Object raw=a.get(key);if(!(raw instanceof Number))throw new IllegalArgumentException("Probability must be numeric");double v=((Number)raw).doubleValue();if(!Double.isFinite(v)||v<0||v>1)throw new IllegalArgumentException("Probability is outside the allowed range");}
 static JSONObject shape(JSONObject questions)throws Exception{JSONObject answers=new JSONObject();for(String id:AiPolicy.keys(questions)){JSONObject q=questions.getJSONObject(id);answers.put(id,q.getString("type").equals("choice")?new JSONObject().put("type","choice").put("choice",q.getJSONObject("criteria").keys().next()).put("confidence",0.0):new JSONObject().put("type","noul").put("noul",0.0));}return new JSONObject().put("answers",answers);}
}
