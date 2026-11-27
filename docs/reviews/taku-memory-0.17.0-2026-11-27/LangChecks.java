package org.agentos.memory;
import org.json.*;import java.util.*;
/** JVM run of the 0.17 StoreTests.languages checks that need no SQLite store, plus the pre-existing Chinese checks they sit beside. */
public final class LangChecks{
 static int checks,failed;
 static void check(boolean b,String name){checks++;if(!b){failed++;System.out.println("FAIL "+name);}}
 public static void main(String[] a)throws Exception{
  check(Lang.SUPPORTED.equals(Set.of("zh","en")),"supported languages are those with privacy rules");
  check(Proactive.DAYWORDS.keySet().equals(Lang.SUPPORTED)&&Proactive.REQUESTS.keySet().equals(Lang.SUPPORTED)&&Proactive.CLOCKS.keySet().equals(Lang.SUPPORTED)&&Recall.PRIORITY.keySet().equals(Lang.SUPPORTED),"every local rule table covers exactly the supported languages");
  check(Lang.of("明晚七点吃饭").equals(Set.of("zh"))&&Lang.of("Dinner at 7 tomorrow?").equals(Set.of("en"))&&Lang.of("婷婷 dinner 7pm").equals(Set.of("zh","en"))&&Lang.of("3305 6-1 ¥46.5").isEmpty(),"Chinese, English, mixed and letterless text are recognised");
  check(Lang.of("明日の会議は10時からです").contains("ja")&&Lang.of("내일 회의 있어요").contains("ko")&&Lang.of("Завтра встреча").contains("cyrillic"),"other scripts are recognised as other languages");
  check(Lang.of("¿Nos vemos mañana en la cena?").contains("es")&&Lang.of("Morgen ist der Termin beim Arzt und ich bin nicht da").contains("de")&&Lang.of("Hôm nay trời đẹp quá").contains("latin"),"Latin-script languages other than English are recognised: "+Lang.of("¿Nos vemos mañana en la cena?")+Lang.of("Morgen ist der Termin beim Arzt und ich bin nicht da")+Lang.of("Hôm nay trời đẹp quá"));
  check(Lang.of("Zoë booked Café Rouge for Friday, a naïve plan").equals(Set.of("en"))&&Lang.of("José said the piñata is at the party").equals(Set.of("en")),"English with borrowed accents stays English");
  check(Privacy.thirdPartySensitive("Mi madre tiene cáncer y está en el hospital")&&Privacy.selfSensitive("Mein Gehalt kommt am 10.")&&Privacy.thirdPartySensitive("엄마가 병원에 입원했어요"),"text the privacy rules cannot read is treated as sensitive");
  check(!Privacy.thirdPartySensitive("Jay wants the Aurora K75 keyboard for his birthday")&&!Privacy.thirdPartySensitive("阿杰想要极光K75键盘")&&!Privacy.selfSensitive("坐飞机一定订过道"),"supported-language text is judged by its rules as before");
  JSONObject card=Privacy.cleanProfile(new JSONObject().put("me",new JSONArray().put(new JSONObject().put("item","Siempre pido asiento de pasillo").put("applies","vuelos")).put(new JSONObject().put("item","I always book an aisle seat").put("applies","flights")))
   .put("people",new JSONArray().put(new JSONObject().put("name","Ana").put("relation","friend").put("notes",new JSONArray().put("Ana está embarazada").put("Ana's birthday is Dec 5")).put("dates",new JSONArray()))),java.time.LocalDate.now(),null);
  check(card.getJSONArray("me").length()==1&&card.getJSONArray("people").getJSONObject(0).getJSONArray("notes").length()==1&&card.getJSONArray("people").getJSONObject(0).getJSONArray("notes").getString(0).startsWith("Ana's"),"user card keeps only entries its rules can check: "+card);
  java.time.LocalDate d=java.time.LocalDate.of(2026,11,18);
  check(Proactive.mentionedDays("Dinner tomorrow?",d).contains(d.plusDays(1))&&Proactive.mentionedDays("see you Friday",d).contains(d.plusDays(2))&&Proactive.mentionedDays("next Monday works",d).contains(d.plusDays(5))&&Proactive.mentionedDays("tonight?",d).contains(d)&&Proactive.mentionedDays("the day after tomorrow",d).contains(d.plusDays(2)),"English day words are resolved");
  check(Proactive.mentionedDays("party on the 22nd",d).contains(java.time.LocalDate.of(2026,11,22))&&Proactive.mentionedDays("Dec 5 at noon",d).equals(Set.of(java.time.LocalDate.of(2026,12,5)))&&Proactive.mentionedDays("March 3rd",d).equals(Set.of(java.time.LocalDate.of(2027,3,3))),"English dates are resolved without double counting: "+Proactive.mentionedDays("Dec 5 at noon",d)+Proactive.mentionedDays("March 3rd",d));
  check(Proactive.slot("dinner at 7:30pm")[0]==19*60+30-90&&Proactive.slot("call at 7am")[0]==7*60-90&&Proactive.slot("lunch at noon")[0]==12*60-90&&Proactive.slot("drinks at 7 tonight")[0]==19*60-90&&Proactive.slot("table for 4 at 8:15")[0]==8*60+15-90,"English times are read from a message");
  check(Proactive.slot("dinner tomorrow?")[0]==17*60+30&&Proactive.slot("lunch?")[0]==11*60+30&&Proactive.slot("room for 3 people")==null&&Proactive.slot("下午3点开会")[0]==15*60-90,"English time words give a slot; Chinese is read as before");
  check(Proactive.asks("Are you free tomorrow")&&Proactive.asks("let's grab lunch")&&Proactive.asks("明晚一起去看电影吗")&&!Proactive.asks("The package was delivered"),"requests are recognised in both languages");
  Set<String> g=Proactive.grams("Can you review my PR for the payment retry fix","Alex");
  check(g.contains("review")&&g.contains("payment")&&g.contains("retry")&&!g.contains("can")&&!g.contains("you")&&Proactive.overlap(g,"Review the Payment Retry PR by Thursday")==3,"English words count toward topic overlap: "+g);
  check(Recall.dayOffsets("Ana's birthday is Dec 5",d).contains(17L)&&Recall.dayOffsets("5 December dentist",d).contains(17L)&&Recall.soon("Report due Nov 20",d,3),"English dates in card notes are understood");
  check(Recall.says(Recall.PENALTY_K,"Pay by Friday or a late fee applies")&&Recall.says(Recall.OCCASION_K,"Mom's birthday dinner")&&Recall.says(Recall.ROUTINE_K,"daily standup")&&Recall.says(Recall.PENALTY_K,"逾期作废"),"priority words are read in both languages");
  check(Recall.sentenceWords("Hangzhou Four")==null&&Recall.sentenceWords("婷婷 日料")==null&&Recall.sentenceWords("%' OR 1=1 --")==null,"keyword queries stay keyword queries");
  List<String> zw=Recall.sentenceWords("期中论文明天要带什么材料"),ew=Recall.sentenceWords("When is my dental cleaning");
  check(Recall.sentenceWords("明天要交什么材料")==null,"one content word keeps the old behaviour");
  check(zw!=null&&zw.contains("论文")&&zw.contains("材料")&&!zw.contains("什么")&&!zw.contains("明天")&&ew!=null&&ew.contains("dental")&&ew.contains("cleaning")&&!ew.contains("when"),"questions are reduced to their content words: "+zw+ew);
  List<String> nw=Recall.sentenceWords("婷婷明晚在哪吃饭");check(nw!=null&&nw.contains("婷婷")&&nw.contains("吃饭"),"names the dictionary does not know survive segmentation: "+nw);
  // pre-existing Chinese checks that need no store
  check(Proactive.mentionedDays("明晚吃饭",d).contains(d.plusDays(1))&&Proactive.mentionedDays("周五见",d).contains(d.plusDays(2))&&Proactive.mentionedDays("下周一",d).contains(d.plusDays(5))&&Proactive.mentionedDays("22号老地方",d).contains(java.time.LocalDate.of(2026,11,22)),"(existing) days a message mentions are resolved");
  check(Proactive.slot("下午3点开会")[0]==15*60-90&&Proactive.slot("晚上7:30")[0]==19*60+30-90&&Proactive.slot("明天见")==null,"(existing) times of day are read from a message");
  check(Privacy.thirdPartySensitive("Mike is thinking about quitting to open a cafe, keep it quiet")&&!Privacy.thirdPartySensitive("Mike's last day is Friday, finish the handover with him")&&Privacy.thirdPartySensitive("Mom has a checkup on Friday")&&!Privacy.thirdPartySensitive("Mom has a checkup on Friday, call her at lunch"),"(existing) English privacy rules unchanged");
  check(Privacy.thirdPartySensitive("婷婷想辞职去成都开民宿")&&!Privacy.thirdPartySensitive("周四请吃饭")&&Privacy.selfSensitive("月薪两万"),"(existing) Chinese privacy rules unchanged");
  for(String q:new String[]{"明天要交什么材料","婷婷明晚在哪吃饭","王总要我周五前交什么","去北京的航班几点起飞","上次点的麻辣香锅花了多少钱","When is my dental cleaning","What time is my ride to the airport","How much did I pay Jake"})System.out.println("  "+q+" -> "+Recall.sentenceWords(q));
  System.out.println((checks-failed)+"/"+checks+" passed");System.exit(failed==0?0:1);
 }
}
