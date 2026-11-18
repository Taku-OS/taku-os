package org.agentos.memory;
import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.LocaleList;
import org.json.JSONObject;
import java.util.Locale;

/** Real bilingual rendering rules on varied saved evidence, in the isolated test UID. */
public final class PresentationTests extends Instrumentation {
 int checks;
 void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
 Context locale(String language){Configuration cfg=new Configuration(getTargetContext().getResources().getConfiguration());cfg.setLocales(new LocaleList(Locale.forLanguageTag(language)));return getTargetContext().createConfigurationContext(cfg);}
 JSONObject record(String source,String title,String body)throws Exception{return new JSONObject().put("source",source).put("title",title).put("body",body).put("analysis","ready").put("decision","organize").put("category","工作");}
 String routeText(){return "12 min • 1.0 km\n"+"A".repeat(70)+"\nReorder\n"+"B".repeat(70)+"\nReorder";}
 public void onCreate(Bundle b){super.onCreate(b);start();}
 public void onStart(){Bundle out=new Bundle();try{
  MemoryCopy en=new MemoryCopy(locale("en")),zh=new MemoryCopy(locale("zh-CN"));
  JSONObject event=record("org.fossify.calendar","Calendar","Back\nGo to today\nChange view\nOctober 23 (Fri)\nGo to next day\nTaku design review (Demo)\n10:00 AM - 11:00 AM\nAlex: finalize English screens by Thursday 17:00. Sam: emulator checks by Thursday 18:00. Bring test results. Release owner still needs confirmation.\nNew Event");
  String before=event.toString();
  check(en.title(event).equals("Taku design review (Demo)"),"event heading replaces app name");
  check(zh.title(event).equals(en.title(event)),"source event name is not translated or invented");
  check(en.suggestionTitle(event).contains("Taku design review"),"event suggestion names the actual event");
  check(en.action(event).equals("Draft review checklist"),"specific review action");
  check(zh.action(event).equals("生成评审准备清单"),"Chinese specific review action");
  check(en.suggestionSummary(event).contains("October 23")&&en.suggestionSummary(event).contains("10:00 AM"),"saved event timing grounded");
  check(en.focus(event).equals("event_prep"),"bounded event task focus");
  check(en.excerpt(event,200).startsWith("Alex:"),"excerpt omits navigation and event heading");
  check(before.equals(event.toString()),"presentation never mutates record");
  JSONObject other=record("org.fossify.calendar","Calendar",event.getString("body").replace("Taku design review (Demo)","Partner kickoff"));
  check(en.title(other).equals("Partner kickoff"),"varied event title, not demo hardcoding");
  check(en.action(other).equals("Prepare event checklist"),"non-review event action");
  JSONObject meal=record("com.android.messaging","Alex Chen (Demo)","Alex Chen (Demo) said, Demo conversation: Saturday lunch is set for 12:30 at The Hummingbird Cafe. Four people, budget $25 each. Please bring notes.\nAlex Chen (Demo) said, Alex will reserve a table by Friday 17:00. Meeting point is TBD.\nAdd an attachment\nAlex Chen (Demo)");
  check(en.title(meal).equals("Saturday lunch · The Hummingbird Cafe"),"meal subject and actual venue");
  check(zh.title(meal).equals("周六午餐 · The Hummingbird Cafe"),"localized meal title preserves venue");
  check(en.suggestionSummary(meal).contains("4 people")&&en.suggestionSummary(meal).contains("12:30")&&en.suggestionSummary(meal).contains("$25 per person"),"meal details extracted from evidence");
  check(zh.suggestionSummary(meal).contains("4 人")&&zh.suggestionSummary(meal).contains("人均 $25"),"Chinese meal details");
  check(en.action(meal).equals("Draft lunch checklist")&&zh.action(meal).equals("生成午餐清单"),"bilingual meal action");
  check(!en.suggestionTitle(meal).equals(en.suggestionTitle(event)),"different content produces different promises");
  check(en.focus(meal).equals("meal_checklist"),"bounded meal focus");
  JSONObject dinner=record("com.android.messaging","Messages","Sunday dinner at Cedar Kitchen. 6 people, 19:00. The table is not booked.");
  check(en.title(dinner).equals("Sunday dinner · Cedar Kitchen"),"different day, meal and venue");
  check(en.action(dinner).equals("Draft dinner checklist"),"dinner action follows content");
  JSONObject timed=record("com.android.messaging","Messages","Saturday lunch at 12:30 at Willow Cafe. Four people will join us.");
  check(en.title(timed).equals("Saturday lunch · Willow Cafe"),"a time is not treated as the venue");
  JSONObject longRoute=record("app.organicmaps.web","Organic Maps",routeText());
  check(en.suggestionTitle(longRoute).length()<=160,"long content stays within surface title bounds");
  String emoji=MemoryContent.clip("🙂".repeat(100),99);
  check(!Character.isHighSurrogate(emoji.charAt(emoji.length()-2)),"truncation preserves surrogate pairs");
  JSONObject noVenue=record("com.android.messaging","Messages","Friday lunch, 3 people at TBD. We still need to choose the venue and time.");
  check(!en.title(noVenue).contains("TBD")&&en.title(noVenue).equals("Friday lunch plans"),"unknown venue not presented as confirmed place");
  JSONObject chinese=record("com.android.messaging","短信","周日 18:30 在松林餐厅聚餐，6 人，人均 120 元，地点已确定，集合点还未确定。");
  check(zh.title(chinese).contains("松林餐厅")&&zh.title(chinese).contains("周日聚餐"),"Chinese source extraction");
  check(zh.suggestionSummary(chinese).contains("6 人")&&zh.suggestionSummary(chinese).contains("120元"),"Chinese quantities grounded");
  JSONObject route=record("app.organicmaps.web","Organic Maps","Map Styles and Layers\nZoom in\nZoom out\n12 min • 1.0 km\n↗ 14 m ↘ 22 m\n20 m\n5 m\nMarina Bay Sands\nReorder\nSingapore Flyer\nReorder\nSearch\nBookmarks\nSave\nSTART");
  check(en.title(route).equals("Marina Bay Sands → Singapore Flyer"),"route stops replace map app name");
  check(en.excerpt(route,180).equals("12 min • 1.0 km"),"route excerpt has duration and distance, not controls");
  check(en.action(route).equals("Review route details"),"route-specific promise");
  JSONObject variedRoute=record("app.organicmaps.web","Organic Maps",route.getString("body").replace("Marina Bay Sands","Central station").replace("Singapore Flyer","City museum"));
  check(en.title(variedRoute).equals("Central station → City museum"),"route is not tied to acceptance locations");
  JSONObject genericTravel=record("com.android.messaging","Trip discussion","Sam will confirm tickets by Friday. The destination is still undecided.");genericTravel.put("category","出行");
  check(en.action(genericTravel).equals("List owners & deadlines")&&en.focus(genericTravel).equals("commitment_checklist"),"category alone cannot change the task promised by the button");
  JSONObject reference=record("org.mozilla.firefox","Understanding task lifecycles | Project docs","Understanding task lifecycles | Project docs\nTasks preserve their work across background transitions. This paragraph explains the lifecycle with concrete examples.");reference.put("decision","summarize");
  check(en.title(reference).contains("Understanding task lifecycles"),"meaningful existing document title retained");
  check(en.action(reference).equals("Extract key points")&&en.focus(reference).equals("key_points"),"reference-specific bounded task");
  check(en.excerpt(reference,180).startsWith("Tasks preserve"),"reference excerpt skips title");
  event.put("analysis","corrected").put("title","My corrected title");
  check(en.title(event).equals("My corrected title"),"user-corrected title wins");
  JSONObject empty=record("org.fossify.calendar","Calendar","Back\nGo to today\nChange view\nOctober 24 (Sat)\nGo to next day\nNew Event");
  check(en.title(empty).equals("October 24 (Sat) agenda"),"empty agenda does not invent event");
  out.putString("result",new JSONObject().put("passed",true).put("checks",checks).toString());finish(-1,out);
 }catch(Throwable e){out.putString("failure",e.toString());finish(0,out);}}
}
