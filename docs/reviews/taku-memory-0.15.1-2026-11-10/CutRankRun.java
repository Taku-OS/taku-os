public class CutRankRun{
 static final double RANK_BRIEF=1.1,RANK_MESSAGE=1.2,RANK_CLASH=1.3;
 static double rank(double kind,long now){return kind+now*1e-14;}
 static String cut(String s,int n){
  s=s==null?"":s.replaceAll("\\s+"," ").trim();if(s.length()<=n)return s;
  int end=n-1;if(end>0&&Character.isHighSurrogate(s.charAt(end-1)))end--;
  return s.substring(0,end)+"…";
 }
 static int bad=0;static void check(boolean ok,String w){System.out.println((ok?"ok   ":"FAIL ")+w);if(!ok)bad++;}
 static boolean wellFormed(String s){return s.codePoints().noneMatch(cp->cp>=0xD800&&cp<=0xDFFF);}
 public static void main(String[] a){
  String[] names={"🌸小雨🌸的小号🌸🌸🌸🌸🌸🌸🌸🌸🌸🌸🌸🌸🌸🌸🌸🌸🌸🌸🌸","Tina💕💕","阿杰"};
  for(String n:names){String c=cut(n,24);check(c.length()<=24&&wellFormed(c),"label "+c+" utf16="+c.length());}
  String[] says={"明晚 19:00 已答应婷婷在西村日料吃饭🍣，还要带伞☂️还她；可以问小雨改天吗？","𠮷野家的优惠券明天到期，𠮷野家门店 21:00 关门，记得去用掉这张券","短句😊"};
  for(String x:says){String c=cut(x,32);check(c.length()<=32&&wellFormed(c),"detail "+c+" utf16="+c.length());}
  check(cut("一二三四五六七八九十",5).equals("一二三四…"),"CJK unchanged");
  long now=1794500000000L;
  double clash=rank(RANK_CLASH,now),msg=rank(RANK_MESSAGE,now),msgLater=rank(RANK_MESSAGE,now+60000),brief=rank(RANK_BRIEF,now),clashYear=rank(RANK_CLASH,now+365L*86400000L),msgYear=rank(RANK_MESSAGE,now+365L*86400000L);
  check(msgLater>msg,"newer message above older: "+msg+" < "+msgLater);
  check(clash>msgYear&&msg>rank(RANK_BRIEF,now+365L*86400000L)&&brief>0.99,"tiers hold across a year: clash "+clash+" > message a year later "+msgYear);
  System.out.println(bad==0?"ALL OK":bad+" failed");System.exit(bad);
 }
}