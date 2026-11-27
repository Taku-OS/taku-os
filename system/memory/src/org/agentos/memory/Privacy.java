package org.agentos.memory;

import org.json.*;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic guards on what the nightly organizer wrote, applied before a board or user card is saved and
 * again before it is shown. The organizer's prompt already asks for the same; these checks do not depend on it.
 * - User card, other people: never their condition details, body, relationship or family conflicts, money, or private
 *   plans to quit or change jobs. A health event is kept only when it comes with something the user does about it
 *   ("妈妈周五体检，中午打电话问问"); a colleague's leaving is kept when it is about the handover.
 * - User card, the user: no diagnoses, medication, income or account details. Long digit runs are masked.
 * - What the user deleted from the board or card is filtered out here too, not only asked of the organizer.
 * - Both: entries that read like instructions to the assistant are dropped, so text from a chat cannot steer it.
 * - Retention: when the user keeps only N days, entries last seen before that are dropped.
 * Each rule has a Chinese and an English pattern; English uses word boundaries and ignores case. RULES lists them by language
 * and decides which languages memory supports (Lang.SUPPORTED). Text in any other language cannot be checked, so the two
 * sensitivity tests below report it as sensitive: it fails closed.
 */
final class Privacy {
 static final Pattern INSTRUCTION=Pattern.compile("(?i)(忽略|无视|忘掉|忘记|不要理会).{0,10}(指令|规则|要求|设定|提示|上面|之前|以上|前面)|ignore\\s+(all\\s+|any\\s+|the\\s+)?(previous|prior|above|earlier)|system\\s*prompt|developer\\s*mode|jailbreak|\\b(disregard|forget|override|ignore)\\s+(all\\s+|any\\s+|the\\s+|your\\s+|my\\s+)?(previous\\s+|prior\\s+|above\\s+|earlier\\s+|preceding\\s+|system\\s+|original\\s+)?(instructions?|rules|prompts?|directions|guidelines)\\b|\\byou\\s+are\\s+now\\b|\\bpretend\\s+(to\\s+be|you\\s+are)\\b|\\bact\\s+as\\s+(a|an|the|my)\\s+(assistant|ai|system|developer|admin)\\b|\\bfrom\\s+now\\s+on,?\\s+you\\b|\\bnew\\s+instructions?\\s*[:：]|\\b(run|execute)\\s+(this|the\\s+following)\\s+(command|code|script)\\b|\\bcall\\s+the\\s+tool\\b|\\bsend\\s+(me|us|them|him|her)\\s+(all|every|the)\\b.{0,24}\\b(messages|chats|chat\\s+history|records|memories|contacts)\\b|你现在(是|扮演)|扮演一个|从现在(开始|起)你|</?\\s*(system|assistant|user|instructions?)\\s*>|\\bassistant\\s*[:：]|系统提示|执行(以下|这个)?命令|调用工具");
 static final Pattern LINK=Pattern.compile("(?i)https?://|www\\.|\\[链接\\]");
 // Other people's sensitive details: never retain.
 static final Pattern THIRD_ALWAYS=Pattern.compile("诊断|确诊|病情|病史|癌|肿瘤|抑郁|焦虑症|精神病|精神科|心理咨询|吃药|服药|药物|怀孕|流产|不孕|心脏|血压|血糖|糖尿病|骨折|中风|脑梗|过敏|减肥|体重|身材|长胖|变胖|分手|离婚|吵架|冷战|出轨|家暴|闹矛盾|工资|收入|薪水|月薪|年薪|欠款|欠钱|借钱|借了.{0,4}钱|贷款|负债|存款|房贷|破产|被裁|裁员|失业");
 // Other people's care events: retain only when tied to something the user needs to do.
 static final Pattern THIRD_CARE=Pattern.compile("体检|住院|生病|病了|看病|手术|医院|复查|不舒服");
 static final Pattern USER_ACTION=Pattern.compile("打电话|打个电话|问问|问候|关心|去看|探望|看望|陪|接送|去接|接她|接他|送她|送他|照顾|提醒|带上|带去|买");
 // Other people's departures: omit private plans; work handovers may remain.
 static final Pattern LEAVE=Pattern.compile("离职|辞职|跳槽");
 static final Pattern WORK_LEAVE=Pattern.compile("(离职|辞职).{0,8}(交接|欢送|送别|最后一天|手续)|(交接|欢送|送别).{0,8}(离职|辞职)");
 // The user's own sensitive details.
 static final Pattern SELF=Pattern.compile("诊断|确诊|病情|病史|吃药|服药|药物|抑郁|焦虑症|癌|住院|手术|怀孕|心理咨询|工资|收入|薪水|月薪|年薪|存款|余额|欠款|负债|贷款|账户|账号|卡号|密码|验证码");
 // English counterparts
 static final Pattern THIRD_ALWAYS_EN=Pattern.compile("(?i)\\b(diagnos\\w*|cancer|tumou?rs?|chemo\\w*|depress\\w*|anxiety|bipolar|adhd|schizo\\w*|psychiatr\\w*|therap(y|ist|ies)|counsel(l)?ing|rehab|medication|meds|pills?|antidepressants?|prescri\\w*|pregnan\\w*|miscarr\\w*|infertil\\w*|ivf|heart\\s+(attack|disease|condition|problems?)|blood\\s+(pressure|sugar)|hypertension|diabet\\w*|fractur\\w*|stroke|dementia|alzheimer\\w*|allerg\\w*|weight|diet(ing)?|break\\s?up|broke\\s+up|breaking\\s+up|split\\s+up|divorc\\w*|separated|argu(e|ed|es|ing|ments?)|fight(s|ing)?|fought|cheat(ed|ing)?|affair|domestic\\s+violence|abus\\w*|salary|salaries|income|pay\\s?(rise|raise|cut)|paycheck|debts?|owe[sd]?|owing|borrow(ed|ing)?\\s+money|loans?|mortgage|savings|bankrupt\\w*|laid\\s+off|layoffs?|got\\s+fired|been\\s+fired|unemployed|lost\\s+(his|her|their)\\s+job)\\b");
 static final Pattern THIRD_CARE_EN=Pattern.compile("(?i)\\b(check-?ups?|physical\\s+exam|medical\\s+exam|hospital\\w*|surgery|operation|sick|ill|unwell|not\\s+feeling\\s+well|doctor'?s?|clinic|dentist)\\b");
 static final Pattern USER_ACTION_EN=Pattern.compile("(?i)\\b(call|phone|ring|text|check\\s+(in\\s+)?on|ask\\s+(her|him|them|how)|visit|see\\s+(her|him|them)|go\\s+see|pick\\s+(her|him|them)\\s+up|pick\\s+up|drop\\s+(her|him|them)\\s+off|take\\s+(her|him|them)|drive\\s+(her|him|them)|accompany|go\\s+with|look\\s+after|take\\s+care\\s+of|remind|bring|buy|get\\s+(her|him|them)|send|help)\\b");
 static final Pattern LEAVE_EN=Pattern.compile("(?i)\\b(quit(s|ting)?|resign\\w*|leav(e|ing)\\s+(the|his|her|their)\\s+(job|company|firm)|job[-\\s]hunt\\w*|looking\\s+for\\s+a\\s+new\\s+job|new\\s+job|(two|2)\\s+weeks'?\\s+notice|(hand(ing)?|put(ting)?)\\s+in\\s+(his|her|their)\\s+notice)\\b");
 static final Pattern WORK_LEAVE_EN=Pattern.compile("(?i)\\b(hand\\s?over|hand-?off|farewell|send-?off|leaving\\s+(party|drinks|lunch)|last\\s+day|transition)\\b");
 static final Pattern SELF_EN=Pattern.compile("(?i)\\b(diagnos\\w*|medication|meds|prescri\\w*|therap(y|ist)|counsel(l)?ing|depress\\w*|anxiety|cancer|hospitali[sz]\\w*|surgery|pregnan\\w*|salary|income|paycheck|pay\\s?slip|savings|(account|bank|card)\\s+balance|debts?|loans?|account\\s+(number|no\\.?)|card\\s+number|password|passcode|pin\\s+(code|number)|verification\\s+code|one-time\\s+code|otp|ssn|social\\s+security)\\b");
 /** One language's sensitive-topic rules. */
 static final class Rules{
  final Pattern thirdAlways,thirdCare,userAction,leave,workLeave,self;
  Rules(Pattern thirdAlways,Pattern thirdCare,Pattern userAction,Pattern leave,Pattern workLeave,Pattern self){this.thirdAlways=thirdAlways;this.thirdCare=thirdCare;this.userAction=userAction;this.leave=leave;this.workLeave=workLeave;this.self=self;}
 }
 /** Languages these rules can read. A new language needs all six rules here before memory treats its text as checkable. */
 static final Map<String,Rules> RULES=Map.of(
  "zh",new Rules(THIRD_ALWAYS,THIRD_CARE,USER_ACTION,LEAVE,WORK_LEAVE,SELF),
  "en",new Rules(THIRD_ALWAYS_EN,THIRD_CARE_EN,USER_ACTION_EN,LEAVE_EN,WORK_LEAVE_EN,SELF_EN));
 static boolean any(String s,java.util.function.Function<Rules,Pattern> rule){for(Rules r:RULES.values())if(rule.apply(r).matcher(s).find())return true;return false;}
 static boolean thirdPartySensitive(String s){
  if(!Lang.covered(s))return true;
  if(any(s,r->r.thirdAlways))return true;
  if(any(s,r->r.leave)&&!any(s,r->r.workLeave))return true;
  return any(s,r->r.thirdCare)&&!any(s,r->r.userAction);
 }
 /**
  * The same line for an item on the user's own agenda (memory graph). A care word ("体检", "复查") there is often the
  * user's own appointment ("小林已预约周一洁牙复查", "预约公司年度体检"): those stay. It is someone else's when a person
  * is named just before the care word ("妈妈体检", "外婆住院") or the user is not the subject; then the card rule applies.
  * Conditions and diagnoses (THIRD_ALWAYS) and private plans to quit never pass.
  */
 static boolean agendaSensitive(String s,String user,java.util.Collection<String> people){
  if(!thirdPartySensitive(s))return false;
  if(!Lang.covered(s)||any(s,r->r.thirdAlways))return true;
  if(any(s,r->r.leave)&&!any(s,r->r.workLeave))return true;
  return !ownCare(s,user,people);
 }
 static final Pattern KIN=Pattern.compile("妈|爸|母亲|父亲|奶奶|爷爷|外婆|外公|姥姥|姥爷|哥|姐|弟|妹|叔|姨|舅|姑|婆|老公|老婆|对象|女友|男友|朋友|同事|领导|老板|孩子|儿子|女儿|他|她");
 static final Pattern OWN_BOOKING=Pattern.compile("预约|挂号|到店|改约|赴约");
 static boolean ownCare(String s,String user,java.util.Collection<String> people){
  java.util.regex.Matcher m=THIRD_CARE.matcher(s);boolean any=false;
  while(m.find()){any=true;String before=s.substring(Math.max(0,m.start()-8),m.start());if(KIN.matcher(before).find())return false;
   for(String p:people){if(p==null||p.equals(user))continue;String tail=p.substring(p.lastIndexOf('-')+1);if((p.length()>=2&&before.contains(p))||(tail.length()>=2&&before.contains(tail)))return false;}}
  if(!any)return false;   // English care words keep the stricter card rule
  return (user!=null&&user.length()>=2&&s.contains(user))||OWN_BOOKING.matcher(s).find();
 }
 static boolean selfSensitive(String s){return !Lang.covered(s)||any(s,r->r.self);}
 static final Pattern CARD_NUMBER=Pattern.compile("\\d(?:[\\s-]?\\d){10,}"),BOARD_NUMBER=Pattern.compile("\\d(?:[\\s-]?\\d){14,}");

 /** API keys, bearer tokens and one-time codes; such notifications (and Taku conversation turns) are not kept. */
 static final java.util.regex.Pattern SECRET=java.util.regex.Pattern.compile("sk-[A-Za-z0-9_-]{20,}|Bearer [A-Za-z0-9._-]{20,}|\\bG-\\d{6}\\b|验证码|校验码|动态码|認証コード|確認コード|(?i:verification code|security code|authentication code|2-step verification|one-time)");
 static boolean instruction(String s){return s!=null&&INSTRUCTION.matcher(s).find();}
 static boolean link(String s){return s!=null&&LINK.matcher(s).find();}
 static String mask(String s,Pattern p){return s==null?"":p.matcher(s).replaceAll("(number omitted)");}

 /** A cleaned copy of a user card. cutoff: entries last seen before this day are dropped (null keeps all). */
 static JSONObject cleanProfile(JSONObject p,java.time.LocalDate today,java.time.LocalDate cutoff)throws Exception{
  JSONArray me=new JSONArray(),people=new JSONArray(),srcMe=p.optJSONArray("me"),srcPeople=p.optJSONArray("people");
  for(int i=0;srcMe!=null&&i<srcMe.length();i++){JSONObject x=srcMe.getJSONObject(i);String item=x.optString("item");
   if(item.isEmpty()||selfSensitive(item)||instruction(item)||instruction(x.optString("applies"))||stale(x,today,cutoff))continue;
   me.put(new JSONObject(x.toString()).put("item",mask(item,CARD_NUMBER)));}
  for(int i=0;srcPeople!=null&&i<srcPeople.length();i++){JSONObject x=srcPeople.getJSONObject(i);
   if(x.optString("name").isEmpty()||instruction(x.optString("name"))||instruction(x.optString("relation"))||stale(x,today,cutoff))continue;
   JSONObject y=new JSONObject(x.toString());
   y.put("notes",clean(x.optJSONArray("notes"),true));y.put("dates",clean(x.optJSONArray("dates"),true));
   people.put(y);}
  JSONObject out=new JSONObject().put("me",me).put("people",people);if(p.has("through"))out.put("through",p.optLong("through"));return out;
 }
 static JSONArray clean(JSONArray a,boolean third){JSONArray out=new JSONArray();for(int i=0;a!=null&&i<a.length();i++){String s=a.optString(i);if(s.isEmpty()||instruction(s)||(third&&thirdPartySensitive(s)))continue;out.put(mask(s,CARD_NUMBER));}return out;}

 // User-deleted entries: reject matching names or similar wording.
 static final Pattern DATES=Pattern.compile("\\d{1,2}月\\d{1,2}[日号]?|\\d{1,2}-\\d{1,2}|\\d{1,2}\\s?[号日]|周[一二三四五六日天]|星期[一二三四五六日天]"
  +"|(?i)\\b(jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|june?|july?|aug(ust)?|sept?(ember)?|oct(ober)?|nov(ember)?|dec(ember)?)\\.?\\s+\\d{1,2}(st|nd|rd|th)?\\b|\\b\\d{1,2}(st|nd|rd|th)?\\s+(of\\s+)?(jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|june?|july?|aug(ust)?|sept?(ember)?|oct(ober)?|nov(ember)?|dec(ember)?)\\b|\\b\\d{1,2}/\\d{1,2}\\b|\\b(mon|tues?|wed(nes)?|thu(rs)?|fri|sat(ur)?|sun)(day)?\\b|\\b(today|tonight|tomorrow|yesterday)\\b");
 static final Pattern EN_FILLER=Pattern.compile("(?i)\\b(the|a|an|to|of|for|and|on|at|in|is|are|was|be|with|my|your|his|her|their|our|this|that)\\b");
 static String norm(String s){return s==null?"":s.replaceAll("[\\s\\p{Punct}，。、；：！？“”‘’（）《》【】·…—～「」]","").toLowerCase(java.util.Locale.ROOT);}
 /** For comparing two entries: dates and filler words removed, so "11月19日请吃饭" and "11月19日爸爸生日" are not the same thing. */
 static String core(String s){return norm(EN_FILLER.matcher(DATES.matcher(s==null?"":s).replaceAll("")).replaceAll("")).replaceAll("[的了地]","");}
 static java.util.Set<String> grams(String s){java.util.Set<String> g=new java.util.HashSet<>();for(int i=0;i+2<=s.length();i++)g.add(s.substring(i,i+2));return g;}
 /** The same entry, allowing the organizer to have reworded it a little. */
 static boolean same(String a,String b){
  String x=core(a),y=core(b);if(x.isEmpty()||y.isEmpty())return norm(a).equals(norm(b))&&!norm(a).isEmpty();if(x.equals(y))return true;
  if(Math.min(x.length(),y.length())>=4&&(x.contains(y)||y.contains(x)))return true;
  java.util.Set<String> gx=grams(x),gy=grams(y);if(gx.isEmpty()||gy.isEmpty())return false;int inter=0;for(String g:gx)if(gy.contains(g))inter++;
  return inter/(double)(gx.size()+gy.size()-inter)>=0.5;
 }
 static boolean namedAs(JSONObject person,String name){String n=norm(name);if(n.isEmpty())return false;if(norm(person.optString("name")).equals(n))return true;JSONArray a=person.optJSONArray("aliases");for(int i=0;a!=null&&i<a.length();i++)if(norm(a.optString(i)).equals(n))return true;return false;}
 static JSONObject suppressBoard(JSONObject b,JSONArray sup)throws Exception{
  java.util.List<String> names=new java.util.ArrayList<>();for(int i=0;sup!=null&&i<sup.length();i++){JSONObject x=sup.optJSONObject(i);if(x!=null&&!x.optString("name").isEmpty())names.add(x.optString("name"));}
  if(names.isEmpty())return b;JSONObject out=new JSONObject(b.toString());
  for(String part:new String[]{"active","done"}){JSONArray a=b.optJSONArray(part),o=new JSONArray();
   for(int i=0;a!=null&&i<a.length();i++){JSONObject x=a.getJSONObject(i);boolean drop=false;for(String n:names){if(same(x.optString("name"),n))drop=true;JSONArray al=x.optJSONArray("aliases");for(int k=0;!drop&&al!=null&&k<al.length();k++)if(norm(al.optString(k)).equals(norm(n)))drop=true;}if(!drop)o.put(x);}
   out.put(part,o);}
  return out;
 }
 static JSONObject suppressProfile(JSONObject p,JSONArray sup)throws Exception{
  if(sup==null||sup.length()==0)return p;JSONObject out=new JSONObject(p.toString());
  JSONArray me=p.optJSONArray("me"),keepMe=new JSONArray();
  for(int i=0;me!=null&&i<me.length();i++){JSONObject x=me.getJSONObject(i);boolean drop=false;for(int k=0;k<sup.length();k++){JSONObject s=sup.optJSONObject(k);if(s!=null&&s.optString("kind").equals("me")&&same(x.optString("item"),s.optString("item")))drop=true;}if(!drop)keepMe.put(x);}
  JSONArray ps=p.optJSONArray("people"),keep=new JSONArray();
  for(int i=0;ps!=null&&i<ps.length();i++){JSONObject x=ps.getJSONObject(i);boolean drop=false;java.util.List<String> notes=new java.util.ArrayList<>();
   for(int k=0;k<sup.length();k++){JSONObject s=sup.optJSONObject(k);if(s==null||!namedAs(x,s.optString("name")))continue;if(s.optString("kind").equals("person"))drop=true;else if(s.optString("kind").equals("note"))notes.add(s.optString("note"));}
   if(drop)continue;JSONObject y=new JSONObject(x.toString());
   for(String field:new String[]{"notes","dates"}){JSONArray a=x.optJSONArray(field),o=new JSONArray();for(int k=0;a!=null&&k<a.length();k++){boolean gone=false;for(String n:notes)if(same(a.optString(k),n))gone=true;if(!gone)o.put(a.optString(k));}y.put(field,o);}
   keep.put(y);}
  return out.put("me",keepMe).put("people",keep);
 }

 /** A cleaned copy of a board: instruction-like entries dropped, card and ID numbers masked, retention applied. */
 static JSONObject cleanBoard(JSONObject b,java.time.LocalDate today,java.time.LocalDate cutoff)throws Exception{
  JSONObject out=new JSONObject();
  for(String part:new String[]{"active","done"}){JSONArray a=b.optJSONArray(part),o=new JSONArray();
   for(int i=0;a!=null&&i<a.length();i++){JSONObject x=a.getJSONObject(i);
    if(x.optString("name").isEmpty()||instruction(x.optString("name"))||instruction(x.optString("state"))||stale(x,today,cutoff))continue;
    JSONObject y=new JSONObject(x.toString());y.put("state",mask(x.optString("state"),BOARD_NUMBER));JSONArray al=new JSONArray();for(int k=0;x.optJSONArray("aliases")!=null&&k<x.optJSONArray("aliases").length();k++){String s=x.optJSONArray("aliases").optString(k);if(!instruction(s))al.put(s);}y.put("aliases",al);o.put(y);}
   out.put(part,o);}
  if(b.has("through"))out.put("through",b.optLong("through"));return out;
 }
 /** "last" is written as MM-DD; read it as the most recent such day not after today. */
 static boolean stale(JSONObject x,java.time.LocalDate today,java.time.LocalDate cutoff){
  if(cutoff==null)return false;java.time.LocalDate d=Recall.lastDay(x.optString("last"),today);return d!=null&&d.isBefore(cutoff);
 }
}
