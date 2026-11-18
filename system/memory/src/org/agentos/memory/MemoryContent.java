package org.agentos.memory;

import org.json.JSONObject;
import java.util.*;
import java.util.regex.*;

/** Bounded, extractive presentation facts. Never edits evidence or invokes a model. */
final class MemoryContent {
 enum Kind { MEAL, EVENT, ROUTE, TEXT }
 static final class Facts {
  Kind kind=Kind.TEXT;
  String heading="", paragraph="", meal="", day="", venue="", time="", budget="", date="", range="", from="", to="", distance="";
  int people;
  boolean review,deadlines,corrected;
 }
 private static final Pattern MEAL=Pattern.compile("(?i)\\b(breakfast|lunch|dinner)\\b|早餐|午餐|晚餐|聚餐");
 private static final Pattern DAY=Pattern.compile("(?i)\\b(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday|Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\b|(?:周|星期)[一二三四五六日天]");
 private static final Pattern TIME=Pattern.compile("(?i)\\b\\d{1,2}:[0-5]\\d(?:\\s*[AP]M)?\\b");
 private static final Pattern RANGE=Pattern.compile("(?i)^\\d{1,2}:[0-5]\\d(?:\\s*[AP]M)?\\s*[-–—至到]\\s*\\d{1,2}:[0-5]\\d(?:\\s*[AP]M)?$");
 private static final Pattern PEOPLE=Pattern.compile("(?i)\\b(one|two|three|four|five|six|seven|eight|nine|ten|[1-9][0-9]?)\\s+(?:people|persons|guests)\\b|([1-9][0-9]?)\\s*人");
 private static final Pattern BUDGET=Pattern.compile("(?i)([$€£¥￥]\\s*\\d+(?:\\.\\d+)?)\\s*(?:each|per\\s+person|/人)|(?:人均|每人)(?:预算)?[：: ]*([$€£¥￥]?\\s*\\d+(?:\\.\\d+)?\\s*元?)");
 private static final Pattern DISTANCE=Pattern.compile("(?i).*\\d+\\s*(?:min|分钟).*\\d+(?:\\.\\d+)?\\s*(?:km|公里).*");
 private static final Set<String> UI=new HashSet<>(Arrays.asList("back","navigate up","search","change view","more options","go to today","go to next day","go to previous day","new event","today","close","reorder","add stop","start","save","bookmarks","my position","zoom in","zoom out","map styles and layers","make a call","add an attachment","返回","搜索","更多选项","今天","新建活动","重新排序","添加途经点","开始","保存","书签","我的位置","放大","缩小"));
 private static final Set<String> GENERIC=new HashSet<>(Arrays.asList("calendar","messaging","messages","firefox","organic maps","browser","event","日历","短信","信息","浏览器","活动"));

 static Facts extract(JSONObject record,String appName){
  Facts f=new Facts();String raw=record.optString("text",record.optString("body"));String[] original=raw.split("\\n");List<String> lines=new ArrayList<>();
  for(String line:original){String value=clean(line);if(!value.isEmpty()&&!lines.contains(value))lines.add(value);}
  f.corrected=record.optString("analysis").equals("corrected");
  String saved=clean(record.optString("title"));
  // Route stop labels are read from the actual reorder controls, not inferred from coordinates.
  List<String> stops=new ArrayList<>();
  for(int i=1;i<lines.size();i++)if(Set.of("reorder","重新排序").contains(lines.get(i).toLowerCase(Locale.ROOT))&&useful(lines.get(i-1),appName))stops.add(lines.get(i-1));
  // Repeated identical accessibility labels were deduplicated above; use original order for route controls.
  if(stops.size()<2){stops.clear();for(int i=1;i<original.length;i++)if(Set.of("reorder","重新排序").contains(clean(original[i]).toLowerCase(Locale.ROOT))&&useful(clean(original[i-1]),appName))stops.add(clean(original[i-1]));}
  for(String line:lines)if(DISTANCE.matcher(line.replace('\u00a0',' ').replace('\u202f',' ')).matches()){f.distance=line;break;}
  if(stops.size()>=2&&!f.distance.isEmpty()){
   f.kind=Kind.ROUTE;f.from=clip(stops.get(0),70);f.to=clip(stops.get(1),70);f.heading=f.from+" → "+f.to;f.paragraph=f.distance;return corrected(f,saved);
  }
  for(int i=1;i<lines.size();i++)if(RANGE.matcher(lines.get(i)).matches()&&useful(lines.get(i-1),appName)){
   f.kind=Kind.EVENT;f.heading=clip(lines.get(i-1),100);f.range=lines.get(i);break;
  }
  for(String line:lines){
   if(f.date.isEmpty()&&(line.matches("(?i)^(January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}.*")||line.matches("^\\d{1,2}月\\d{1,2}日.*")))f.date=line;
   String prose=withoutSpeaker(line);
   if(f.paragraph.isEmpty()&&!prose.equals(saved)&&!prose.equals(f.heading)&&useful(prose,appName)&&(prose.length()>=45||prose.matches(".*[\\p{IsHan}].*")&&prose.length()>=18))f.paragraph=prose;
  }
  f.deadlines=Pattern.compile("(?i)\\b(by|before|deadline)\\b|截止|之前|前完成").matcher(raw).find();
  f.review=Pattern.compile("(?i)\\breview\\b|评审|复盘").matcher(f.heading).find();
  if(f.kind==Kind.EVENT)return corrected(f,saved);
  for(String line:lines){
   String prose=withoutSpeaker(line);Matcher meal=MEAL.matcher(prose);if(!meal.find())continue;
   f.kind=Kind.MEAL;f.meal=meal.group().toLowerCase(Locale.ROOT);f.day=day(prose.substring(Math.max(0,meal.start()-25),meal.end()));
   String afterMeal=prose.substring(meal.end());Matcher place=Pattern.compile("(?i)\\bat\\s+").matcher(afterMeal);
   while(place.find()){String candidate=afterMeal.substring(place.end()).split("[.!?;\\n。！？；]",2)[0].trim();if(!candidate.isEmpty()&&!TIME.matcher(candidate).lookingAt()){f.venue=clip(candidate,70);break;}}
   if(f.venue.isEmpty()){Matcher zh=Pattern.compile("(?:在|地点[：:])([^，。；\\n]{2,45}?)(?:聚餐|吃饭|[，。；]|$)").matcher(prose);if(zh.find())f.venue=zh.group(1).trim();}
   if(Pattern.compile("(?i)\\b(TBD|undecided|unknown)\\b|待定|未确定").matcher(f.venue).find())f.venue="";
   Matcher time=TIME.matcher(prose);if(time.find())f.time=time.group();
   Matcher count=PEOPLE.matcher(prose);if(!count.find()){count=PEOPLE.matcher(raw);if(count.find())f.people=number(count.group(1)==null?count.group(2):count.group(1));}else f.people=number(count.group(1)==null?count.group(2):count.group(1));
   Matcher budget=BUDGET.matcher(raw);if(budget.find())f.budget=(budget.group(1)==null?budget.group(2):budget.group(1)).replaceAll("\\s+","");
   f.paragraph=prose;break;
  }
  if(f.kind!=Kind.MEAL){
   if(useful(saved,appName)&&!GENERIC.contains(saved.toLowerCase(Locale.ROOT)))f.heading=clip(saved,100);
   if(f.heading.isEmpty())for(String line:lines)if(useful(line,appName)&&!line.equals(f.date)&&line.length()>=6){f.heading=clip(withoutSpeaker(line).split("(?<=[。.!?])\\s+",2)[0],100);break;}
  }
  return corrected(f,saved);
 }
 private static Facts corrected(Facts f,String saved){if(f.corrected)f.heading=saved;return f;}
 static String clean(String s){return s.replace('\u00a0',' ').replace('\u202f',' ').replaceAll("[ \\t]+"," ").trim();}
 static String withoutSpeaker(String line){return line.replaceFirst("(?i)^.{1,80}? (?:said|says),\\s*","").replaceFirst("(?i)^(?:demo conversation|示例聊天|演示聊天)[：:]\\s*","");}
 private static boolean useful(String line,String appName){String lower=line.toLowerCase(Locale.ROOT);return !line.isBlank()&&!UI.contains(lower)&&!GENERIC.contains(lower)&&!line.equalsIgnoreCase(appName)&&!line.startsWith("http")&&!line.matches("^[\\d\\s:,.↗↘•m]+$");}
 static String day(String value){Matcher m=DAY.matcher(value);return m.find()?m.group().toLowerCase(Locale.ROOT):"";}
 static String clip(String value,int max){String s=clean(value);if(s.length()<=max)return s;int end=max-1;if(end>0&&Character.isHighSurrogate(s.charAt(end-1)))end--;return s.substring(0,end)+"…";}
 private static int number(String value){String[] words={"one","two","three","four","five","six","seven","eight","nine","ten"};for(int i=0;i<words.length;i++)if(words[i].equalsIgnoreCase(value))return i+1;try{return Integer.parseInt(value);}catch(Exception ignored){return 0;}}
}
