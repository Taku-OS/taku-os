package org.agentos.memory;

import android.content.Context;
import org.json.JSONObject;
import java.util.*;

/** Localized reading titles and bounded task promises, grounded in saved text. */
final class MemoryCopy {
 private final Context context;
 private final Map<String,String> apps=new HashMap<>();
 MemoryCopy(Context context){this.context=context;}
 private String s(int id,Object... args){return context.getString(id,args);}
 private MemoryContent.Facts facts(JSONObject record){
  String pkg=record.optString("source");String app=apps.computeIfAbsent(pkg,p->{try{return context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(p,0)).toString();}catch(Exception e){return p;}});
  return MemoryContent.extract(record,app);
 }
 String title(JSONObject record){return title(facts(record));}
 private String title(MemoryContent.Facts f){
  if(f.corrected&&!f.heading.isEmpty())return f.heading;
  if(f.kind==MemoryContent.Kind.MEAL){String subject=join(day(f.day),meal(f.meal));return MemoryContent.clip(f.venue.isEmpty()?s(R.string.content_meal_title,subject):s(R.string.content_meal_place,subject,f.venue),100);}
  if(!f.heading.isEmpty())return f.heading;
  return f.date.isEmpty()?s(R.string.content_saved_text):s(R.string.content_agenda,f.date);
 }
 String excerpt(JSONObject record,int max){
  MemoryContent.Facts f=facts(record);
  if(f.kind==MemoryContent.Kind.ROUTE)return MemoryContent.clip(f.distance,max);
  if(f.kind==MemoryContent.Kind.MEAL&&!mealFacts(f).isEmpty())return MemoryContent.clip(mealFacts(f)+"\n"+f.paragraph,max);
  return MemoryContent.clip(f.paragraph.isEmpty()?record.optString("text",record.optString("body")):f.paragraph,max);
 }
 String focus(JSONObject record){
  if(record.optString("decision").equals("summarize"))return "key_points";
  return switch(facts(record).kind){case MEAL->"meal_checklist";case EVENT->"event_prep";case ROUTE->"travel_outline";default->"commitment_checklist";};
 }
 String suggestionTitle(JSONObject record){
  MemoryContent.Facts f=facts(record);String title=title(f);
  if(record.optString("decision").equals("summarize"))return MemoryContent.clip(s(R.string.suggest_key_points,title),150);
  return MemoryContent.clip(switch(f.kind){case MEAL->s(R.string.suggest_meal,join(day(f.day),meal(f.meal)));case EVENT->s(R.string.suggest_event,title);case ROUTE->s(R.string.suggest_route,f.from,f.to);default->s(R.string.suggest_commitments,title);},150);
 }
 String suggestionSummary(JSONObject record){
  MemoryContent.Facts f=facts(record);
  if(record.optString("decision").equals("summarize"))return s(R.string.suggest_reference_summary,title(f));
  String detail,description;
  switch(f.kind){
   case MEAL:detail=mealFacts(f);description=s(R.string.suggest_meal_summary,meal(f.meal));break;
   case EVENT:detail=f.date.isEmpty()?clock(f.range):f.date+" · "+clock(f.range);description=s(f.deadlines?R.string.suggest_event_deadlines:R.string.suggest_event_summary);break;
   case ROUTE:detail=f.distance;description=s(R.string.suggest_route_summary);break;
   default:detail=MemoryContent.clip(f.paragraph,100);description=s(R.string.suggest_commitments_summary);break;
  }
  return MemoryContent.clip(detail.isEmpty()?description:detail+"\n"+description,300);
 }
 String action(JSONObject record){
  if(record.optString("decision").equals("summarize"))return s(R.string.action_key_points);
  MemoryContent.Facts f=facts(record);
  return switch(f.kind){case MEAL->s(R.string.action_meal,meal(f.meal));case EVENT->s(f.review?R.string.action_review:R.string.action_event);case ROUTE->s(R.string.action_route);default->s(R.string.action_commitments);};
 }
 private String mealFacts(MemoryContent.Facts f){List<String> details=new ArrayList<>();if(f.people>0)details.add(s(R.string.content_people,f.people));if(!f.time.isEmpty())details.add(clock(f.time));if(!f.budget.isEmpty())details.add(s(R.string.content_budget,f.budget));return String.join(" · ",details);}
 private String meal(String value){return s(switch(value){case "breakfast","早餐"->R.string.content_breakfast;case "dinner","晚餐"->R.string.content_dinner;case "聚餐"->R.string.content_meal;default->R.string.content_lunch;});}
 private String day(String value){if(value.isEmpty())return "";return s(switch(value){case "monday","mon","周一","星期一"->R.string.content_monday;case "tuesday","tue","周二","星期二"->R.string.content_tuesday;case "wednesday","wed","周三","星期三"->R.string.content_wednesday;case "thursday","thu","周四","星期四"->R.string.content_thursday;case "friday","fri","周五","星期五"->R.string.content_friday;case "saturday","sat","周六","星期六"->R.string.content_saturday;default->R.string.content_sunday;});}
 private String clock(String value){return chinese()?value.replaceAll("(?i)([0-9]+:[0-9]{2})\\s*AM","上午$1").replaceAll("(?i)([0-9]+:[0-9]{2})\\s*PM","下午$1"):value;}
 private boolean chinese(){return context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh");}
 private String join(String first,String second){return first.isEmpty()?second:second.isEmpty()?first:first+(chinese()?"":" ")+second;}
}
