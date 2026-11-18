package org.agentos.memory;

import android.app.*;
import android.content.*;
import org.json.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * Speaking up without being asked, driven by memory. Two triggers:
 * - Evening: after the nightly organize, one bedtime brief written from the user card alone (what is due tomorrow and the
 *   two days after, costliest to miss first).
 * - Message: when a new notification arrives (page captures of chat lists are not used yet: several speakers on one screen
 *   were often confused in replay), a local check (no model) looks for something remembered that it
 *   touches: a person on the card with notes or dates, a habit, an agenda item sharing a name, number or words, or an item on
 *   the day the message mentions (a possible conflict). Only then is the model asked whether to say something, with the
 *   message and those few lines.
 * Model calls go through the assistant Broker like the organizer (Taku AI switch, ephemeral threads, no tools). Off by default.
 */
final class Proactive {
 static final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"memory-proactive");t.setDaemon(true);return t;});
 static final int DAILY=8;static final long GAP=10*60000L,SAME=12*3600000L;
 static boolean enabled(MemoryStore s){return s.prefs.getBoolean("nudges",false)&&s.prefs.getBoolean("enabled",false);}

 // Time trigger: bedtime reminder.
 static String eveningInput(MemoryStore s,long now)throws Exception{
  String stamp=Organizer.stamp(now);
  String card=s.agentProfileAt("(Current time: "+stamp+") Today is nearly over. Check what needs attention or advance preparation tomorrow and over the next two days.",now).optString("text");
  if(card.isEmpty())return "";
  // Today's time/deadline/completion/cancellation changes come from comparing graph revisions during nightly organization.
  StringBuilder in=new StringBuilder("Current time: ").append(stamp).append("。\n\n").append(card);
  List<String> changed=Recall.changes(s,now-86400000L,now,5);
  if(!changed.isEmpty()){in.append("\n\nThings that changed today (from memory): ");for(String x:changed)in.append("\n· ").append(x);}
  return in.toString();
 }
 /** After a nightly organize: one brief per evening (20:00 or later). */
 static void afterOrganize(Context c,MemoryStore s,long now){
  ProactiveNotice.sweepLater(c,s,now);   // Retract AI-arranged Home cards at expiry even when the switch is off.
  if(!enabled(s))return;
  ZonedDateTime at=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault());if(at.getHour()<20)return;
  String day=at.toLocalDate().toString();if(day.equals(s.kvGet("briefDay","")))return;s.kvSet("briefDay",day);
  try{JSONObject r=evening(s,now,true);JSONObject out=r.optJSONObject("result");if(out!=null&&out.optBoolean("say"))ProactiveNotice.deliver(c,s,"evening",out.optString("title"),String.join("\n",strings(out.optJSONArray("items"))),"",r.optString("input"),now);}
  catch(Exception e){android.util.Log.w("TakuMemory","Bedtime reminder was not generated",e);}
 }
 static JSONObject evening(MemoryStore s,long now,boolean run)throws Exception{
  String input=eveningInput(s,now);JSONObject r=new JSONObject().put("input",input);
  if(run&&!input.isEmpty()){JSONObject g=s.generate("brief",input);s.addProactiveUsage("brief",g);r.put("result",new JSONObject(g.getString("final"))).put("model",g.optString("model"));}
  return r;
 }

 // Event trigger: a new message touches remembered content.
 /** Called for every newly captured notification; the work happens off the capture thread. */
 static void captured(Context c,MemoryStore s,String source,String title,String text,long now){
  if(!enabled(s)||text==null||text.trim().length()<6)return;
  io.execute(()->{
   try{
    if(!budget(s,now))return;
    JSONObject r=message(s,title,text,now,false);if(!r.optBoolean("wake"))return;
    String anchor=r.optString("anchor");if(recentlySaid(s,anchor,now))return;
    JSONObject g=s.generate("nudge",r.getString("input"));s.addProactiveUsage("nudge",g);JSONObject out=new JSONObject(g.getString("final"));
    s.kvSet("nudgeAsked",""+now);
    if(!out.optBoolean("speak")||out.optString("text").isBlank())return;
    JSONObject checked=check(s,title,text,r.optJSONArray("lines"),out.getString("text"));
    if(checked.optBoolean("mistake")){android.util.Log.i("TakuMemory","Proactive reminder review did not pass: "+checked.optString("reason"));return;}
    ProactiveNotice.deliver(c,s,"message",title,out.getString("text"),anchor,r.getString("input"),now,new JSONObject().put("message",text.trim()).put("conflict",r.optBoolean("conflict")).put("package",source==null?"":source));
   }catch(Exception e){android.util.Log.w("TakuMemory","Proactive reminder did not complete",e);}
  });
 }
 static boolean budget(MemoryStore s,long now){
  if(now-Long.parseLong(s.kvGet("nudgeAsked","0"))<GAP/5)return false;   // Bursts of messages: at most one model request per two minutes.
  return s.nudgesSince(now-86400000L,"message")<DAILY;
 }
 static boolean recentlySaid(MemoryStore s,String anchor,long now){
  if(anchor.isEmpty())return false;
  try{JSONArray a=s.nudges(now-SAME,20);for(int i=0;i<a.length();i++){JSONObject n=a.getJSONObject(i);if(anchor.equals(n.optString("anchor"))||(n.optString("kind").equals("message")&&now-n.optLong("at")<GAP))return true;}}catch(Exception ignored){}
  return false;
 }
 static final Pattern DAYWORD=Pattern.compile("今天|今晚|今早|明天|明晚|明早|明儿|后天|(下|这|本)?(周|星期|礼拜)([一二三四五六日天])|(\\d{1,2})\\s?[号日]");
 static final Pattern REQUEST=Pattern.compile("？|\\?|吗|啥|什么|哪|几点|能不能|可不可以|要不要|有空|来不来|一起|帮我|帮忙|麻烦|记得|别忘|请你|你看|行不|怎么样|好不好");
 static final Pattern ALNUM=Pattern.compile("[A-Za-z]{1,3}\\d{2,}|\\d+-\\d+-\\d+|\\d{4,}");
 static final Set<String> STOP2=Set.of("明天","今天","后天","晚上","上午","下午","中午","早上","需要","一下","已经","可以","时候","这个","那个","我们","你们","他们","没有","还是","就是","一起","记得","知道","现在","一个","东西","时间","问题","谢谢","哈哈","好的","收到","周末","这周","下周");
 /**
  * Local association between a new message and memory. wake=true when something remembered is touched; input is what the
  * model sees (the message and only those lines); anchor names the memory line, so the same thing is not said twice.
  */
 static JSONObject message(MemoryStore s,String title,String text,long now,boolean run)throws Exception{
  String msg=(title==null||title.isEmpty()?"":title+"：")+text.trim();if(msg.length()>400)msg=msg.substring(0,400);
  JSONObject r=new JSONObject().put("wake",false);
  if(Privacy.instruction(msg)||Privacy.link(msg))return r.put("reason","instruction_or_link");
  String stamp=Organizer.stamp(now);
  String card=s.agentProfileAt("(Current time: "+stamp+") Just received: 「"+msg+"」",now).optString("text");
  if(card.isEmpty())return r.put("reason","no_card");
  Map<String,List<String>> sec=sections(card);
  LocalDate today=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate();
  Set<LocalDate> days=mentionedDays(msg,today);
  int[] slot=slot(msg);
  // For graphs with attendance markers, compare only those time slots; old graphs compare all timed plans.
  boolean marked=card.contains(" attendance required｜");
  Set<String> grams=grams(msg,s.userName());List<String> tokens=new ArrayList<>();Matcher am=ALNUM.matcher(msg);while(am.find())tokens.add(am.group());
  List<String> rel=new ArrayList<>();String anchor="";
  // Card people: important contacts, commitments in the next 3 days, and people related to this matter.
  Set<String> names=new LinkedHashSet<>(),soon=new HashSet<>();
  for(String l:sec.getOrDefault("Important people",List.of()))names.add(l.split("（",2)[0].trim());
  for(String l:sec.getOrDefault("People with commitments or important dates in the next 3 days",List.of())){String n=l.split("（",2)[0].trim();names.add(n);soon.add(n);}
  for(String l:sec.getOrDefault("Potentially relevant to this matter",List.of()))if(!l.startsWith("["))names.add(l.split("（",2)[0].trim());
  int mentioned=0;for(String n:names)if(n.length()>=2&&msg.contains(n))mentioned++;
  boolean list=mentioned>=3;                       // Several people on one screen indicates a chat list/group overview; do not trigger by person.
  boolean ask=REQUEST.matcher(msg).find();         // A question, invitation, or request for user action.
  for(String l:sec.getOrDefault("Potentially relevant to this matter",List.of())){
   boolean habit=l.startsWith("[Your habits]");String name=habit?"":l.split("（",2)[0].trim();
   boolean named=!list&&name.length()>=2&&msg.contains(name)&&l.length()>name.length()+6&&(ask||soon.contains(name));
   boolean likes=!list&&habit&&ask&&overlap(grams,l)>=1;
   if(named||likes){rel.add(l);if(anchor.isEmpty())anchor=habit?l:name;}
  }
  if(!list)for(String l:sec.getOrDefault("People with commitments or important dates in the next 3 days",List.of())){String name=l.split("（",2)[0].trim();if(name.length()>=2&&msg.contains(name)&&!rel.contains(l)){rel.add(l);if(anchor.isEmpty())anchor=name;}}
  for(String key:new String[]{"Plans and deadlines for the next two days","Tasks newly observed in the last day"})for(String l:sec.getOrDefault(key,List.of())){
   String body=l.contains("｜")?l.substring(l.indexOf('｜')+1):l;
   boolean token=false;for(String x:tokens)if(body.contains(x))token=true;
   boolean person=false;if(!list&&ask)for(String n:names)if(n.length()>=2&&msg.contains(n)&&body.contains(n))person=true;
   LocalDate d=lineDay(l,today);boolean sameDay=!list&&ask&&d!=null&&days.contains(d);
   // An invitation overlaps an existing plan's time: report the locally detectable potential conflict explicitly to the model.
   int at=lineMinute(l);boolean clash=sameDay&&slot!=null&&at>=0&&at>=slot[0]&&at<=slot[1]&&(!marked||l.contains(" attendance required｜"));
   if(clash){rel.add("Possible conflict: "+l);r.put("conflict",true);anchor=l.split("：",2)[0];continue;}
   if(token||person||(!list&&overlap(grams,body)>=3)||sameDay){rel.add(l);if(anchor.isEmpty()||token)anchor=l.split("：",2)[0];}
  }
  if(rel.isEmpty())return r.put("reason","nothing_touched");
  // Include other plans that day: when a flight is canceled, a same-day meeting matters.
  Set<LocalDate> hitDays=new HashSet<>();for(String l:rel){LocalDate d=lineDay(l.startsWith("Possible conflict: ")?l.substring("Possible conflict: ".length()):l,today);if(d!=null)hitDays.add(d);}
  int extra=0;for(String key:new String[]{"Plans and deadlines for the next two days"})for(String l:sec.getOrDefault(key,List.of())){LocalDate d=lineDay(l,today);if(d!=null&&hitDays.contains(d)&&!rel.contains(l)&&!rel.contains("Possible conflict: "+l)&&extra<4){rel.add(l);extra++;}}
  // Today's quotations are context only; incidental overlap in a group cannot alone wake the model.
  List<String> todays=new ArrayList<>();for(String l:sec.getOrDefault("New today",List.of())){String q=l.replaceAll(".*「|」.*","");if(!q.isEmpty()&&!msg.contains(q))todays.add(l);}
  StringBuilder in=new StringBuilder("Current time: ").append(stamp).append(".\n\nNew message").append(title==null||title.isEmpty()?"":"（"+title+"）").append("：「").append(text.trim().length()>400?text.trim().substring(0,400):text.trim()).append("」\n\nPossibly related memories: ");
  int n=0;for(String l:rel){if(n++>=8)break;in.append("\n· ").append(l);}
  if(!todays.isEmpty()){in.append("\n\nRelated quotations from earlier today (reference only): ");for(int i=0;i<Math.min(3,todays.size());i++)in.append("\n· ").append(todays.get(i));}
  r.put("wake",true).put("anchor",anchor.length()>60?anchor.substring(0,60):anchor).put("lines",new JSONArray(rel.subList(0,Math.min(8,rel.size())))).put("input",in.toString());
  if(run){JSONObject g=s.generate("nudge",in.toString());s.addProactiveUsage("nudge",g);JSONObject out=new JSONObject(g.getString("final"));r.put("result",out).put("model",g.optString("model"));
   if(out.optBoolean("speak")&&!out.optString("text").isBlank())r.put("check",check(s,title,text,r.optJSONArray("lines"),out.getString("text")));}
  return r;
 }
 /**
  * Before a message reminder goes out, a second model call looks only for the two mistakes replay grading kept finding: calling
  * a deadline, delivery or check-in time a clash, and tying two unrelated things together. About one call a day.
  */
 static JSONObject check(MemoryStore s,String title,String text,JSONArray lines,String say)throws Exception{
  StringBuilder in=new StringBuilder("New message").append(title==null||title.isEmpty()?"":"（"+title+"）").append("：「").append(text.trim().length()>400?text.trim().substring(0,400):text.trim()).append("」\n\nPossibly related memories: ");
  for(int i=0;lines!=null&&i<lines.length();i++)in.append("\n· ").append(lines.optString(i));
  in.append("\n\nProposed reminder: ").append(say);
  JSONObject g=s.generate("check",in.toString());s.addProactiveUsage("check",g);return new JSONObject(g.getString("final"));
 }
 static final Pattern CLOCK=Pattern.compile("(上午|早上|中午|下午|晚上|今晚|明晚|夜里)?\\s?(\\d{1,2})(?:[:：](\\d{2})|\\s?点(半)?)");
 static final Pattern LINE_MINUTE=Pattern.compile("^\\d{1,2}(?:月|-)\\d{1,2}(?:日 周.| [A-Za-z]{3}) (\\d{1,2}):(\\d{2})");
 /** The time of day a message asks about, as [from, to] minutes; an explicit time gets 90 minutes before and two hours after, words a broad slot. Null when it names none. */
 static int[] slot(String msg){
  Matcher m=CLOCK.matcher(msg);
  if(m.find()){int h=Integer.parseInt(m.group(2)),min=m.group(3)!=null?Integer.parseInt(m.group(3)):m.group(4)!=null?30:0;String pre=m.group(1)==null?"":m.group(1);
   if(h<=12&&(pre.equals("下午")||pre.endsWith("晚")||pre.equals("晚上")||pre.equals("夜里")||(pre.isEmpty()&&h<=7&&msg.matches(".*(晚|下午).*"))))h+=h==12?0:12;
   if(h<24){int t=h*60+min;return new int[]{t-90,t+120};}}
  if(msg.matches(".*(今晚|明晚|晚上|晚饭|夜里|晚餐).*"))return new int[]{17*60+30,23*60};
  if(msg.matches(".*(中午|午饭|午餐).*"))return new int[]{11*60+30,14*60};
  if(msg.matches(".*下午.*"))return new int[]{13*60,18*60};
  if(msg.matches(".*(上午|早上|早饭).*"))return new int[]{8*60,12*60};
  return null;
 }
 static int lineMinute(String line){Matcher m=LINE_MINUTE.matcher(line);return m.find()?Integer.parseInt(m.group(1))*60+Integer.parseInt(m.group(2)):-1;}
 static Map<String,List<String>> sections(String card){
  Map<String,List<String>> out=new LinkedHashMap<>();String cur="";
  String[] heads={"About you","Important people","People with commitments or important dates in the next 3 days","Plans and deadlines for the next two days","Tasks newly observed in the last day","Potentially relevant to this matter","New today"};
  for(String l:card.split("\n")){String h=null;for(String x:heads)if(l.startsWith(x))h=x;if(h!=null){cur=h;continue;}if(l.startsWith("· "))out.computeIfAbsent(cur,k->new ArrayList<>()).add(l.substring(2).trim());}
  return out;
 }
 static Set<String> grams(String s,String user){
  Set<String> g=new HashSet<>();Matcher m=Pattern.compile("[\\u4e00-\\u9fff]+").matcher(s);
  while(m.find()){String w=m.group();for(int i=0;i+2<=w.length();i++){String x=w.substring(i,i+2);if(Recall.STOP_CHARS.indexOf(x.charAt(0))>=0||Recall.STOP_CHARS.indexOf(x.charAt(1))>=0||STOP2.contains(x)||(user!=null&&user.contains(x)))continue;g.add(x);}}
  return g;
 }
 static int overlap(Set<String> grams,String line){int n=0;for(String g:grams)if(line.contains(g))n++;return n;}
 static final Pattern LINE_DAY=Pattern.compile("^(\\d{1,2})(?:月|-)(\\d{1,2})(?:日|(?= ))");
 static LocalDate lineDay(String line,LocalDate today){Matcher m=LINE_DAY.matcher(line);if(!m.find())return null;try{return Recall.near(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),today);}catch(RuntimeException e){return null;}}
 static Set<LocalDate> mentionedDays(String msg,LocalDate today){
  Set<LocalDate> out=new HashSet<>();Matcher m=DAYWORD.matcher(msg);
  while(m.find()){String w=m.group();
   if(w.startsWith("今"))out.add(today);
   else if(w.startsWith("明"))out.add(today.plusDays(1));
   else if(w.equals("后天"))out.add(today.plusDays(2));
   else if(m.group(3)!=null){int dow="一二三四五六日天".indexOf(m.group(3))+1;if(dow==8)dow=7;LocalDate monday=today.minusDays(today.getDayOfWeek().getValue()-1);
    LocalDate d="下".equals(m.group(1))?monday.plusDays(7+dow-1):monday.plusDays(dow-1);if(!"下".equals(m.group(1))&&d.isBefore(today))d=d.plusDays(7);out.add(d);}
   else if(m.group(4)!=null){int dom=Integer.parseInt(m.group(4));if(dom>=1&&dom<=31)try{LocalDate d=today.withDayOfMonth(Math.min(dom,today.lengthOfMonth()));if(d.isBefore(today))d=d.plusMonths(1);out.add(d);}catch(RuntimeException ignored){}}
  }
  return out;
 }
 static List<String> strings(JSONArray a){List<String> out=new ArrayList<>();for(int i=0;a!=null&&i<a.length();i++){String x=a.optString(i).trim();if(!x.isEmpty())out.add(x);}return out;}

}
