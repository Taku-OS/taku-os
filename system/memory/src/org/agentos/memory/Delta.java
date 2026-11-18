package org.agentos.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * DeltaKV-style storage, line level: a captured page is stored as references to lines already held plus the
 * residual lines that are new. The reference is the most similar recent page with the same title; matching is
 * a port of Python difflib.SequenceMatcher (autojunk off) so device deltas equal the offline evaluation.
 * Nothing is evicted; the full page stays reconstructable from its ordered line keys.
 */
final class Delta {
 static final int REFS=60;static final double MIN_RATIO=0.3;

 static final class Result { List<Integer> fresh=new ArrayList<>();String head="",freshText="",show=""; }

 /** Matching blocks (i, j, size) between a and b, like difflib.get_matching_blocks with no junk. */
 static List<int[]> blocks(String[] a,String[] b){
  Map<String,List<Integer>> b2j=new HashMap<>();for(int j=0;j<b.length;j++)b2j.computeIfAbsent(b[j],k->new ArrayList<>()).add(j);
  List<int[]> out=new ArrayList<>();ArrayDeque<int[]> queue=new ArrayDeque<>();queue.push(new int[]{0,a.length,0,b.length});
  while(!queue.isEmpty()){
   int[] q=queue.pop();int alo=q[0],ahi=q[1],blo=q[2],bhi=q[3];
   int besti=alo,bestj=blo,best=0;Map<Integer,Integer> j2len=new HashMap<>();
   for(int i=alo;i<ahi;i++){
    Map<Integer,Integer> next=new HashMap<>();List<Integer> js=b2j.get(a[i]);
    if(js!=null)for(int j:js){if(j<blo)continue;if(j>=bhi)break;int k=j2len.getOrDefault(j-1,0)+1;next.put(j,k);if(k>best){besti=i-k+1;bestj=j-k+1;best=k;}}
    j2len=next;
   }
   if(best>0){out.add(new int[]{besti,bestj,best});if(alo<besti&&blo<bestj)queue.push(new int[]{alo,besti,blo,bestj});if(besti+best<ahi&&bestj+best<bhi)queue.push(new int[]{besti+best,ahi,bestj+best,bhi});}
  }
  out.sort((x,y)->x[0]!=y[0]?Integer.compare(x[0],y[0]):Integer.compare(x[1],y[1]));
  List<int[]> merged=new ArrayList<>();int i1=0,j1=0,k1=0;
  for(int[] m:out){if(i1+k1==m[0]&&j1+k1==m[1])k1+=m[2];else{if(k1>0)merged.add(new int[]{i1,j1,k1});i1=m[0];j1=m[1];k1=m[2];}}
  if(k1>0)merged.add(new int[]{i1,j1,k1});
  merged.add(new int[]{a.length,b.length,0});return merged;
 }
 static double ratio(String[] a,String[] b){int m=0;for(int[] x:blocks(a,b))m+=x[2];int t=a.length+b.length;return t==0?1.0:2.0*m/t;}
 /** Indices of b that are inserted or replaced relative to a. */
 static List<Integer> added(String[] a,String[] b){
  List<Integer> out=new ArrayList<>();int i=0,j=0;
  for(int[] m:blocks(a,b)){if(j<m[1])for(int k=j;k<m[1];k++)out.add(k);i=m[0]+m[2];j=m[1]+m[2];}
  return out;
 }

 /**
  * @param recent the last pages seen under the same title, oldest first
  * @param seenPairs global (previous line, line) pairs already held for this title and first line; updated in place
  */
 static Result diff(String[] lines,List<String[]> recent,Set<String> seenPairs){
  String[] best=null;double r=0;
  for(String[] p:recent){double q=ratio(p,lines);if(q>r){r=q;best=p;}}
  List<Integer> idx;
  if(best!=null&&r>=MIN_RATIO)idx=added(best,lines);else{idx=new ArrayList<>();for(int k=0;k<lines.length;k++)idx.add(k);}
  List<Integer> kept=new ArrayList<>();
  for(int k:idx)if(!seenPairs.contains(pair(k==0?"":lines[k-1],lines[k])))kept.add(k);
  for(int k=0;k<lines.length;k++)seenPairs.add(pair(k==0?"":lines[k-1],lines[k]));
  Result out=new Result();out.head=lines.length>0?lines[0]:"";out.fresh=kept;
  if(kept.isEmpty())return out;
  TreeSet<Integer> keep=new TreeSet<>(kept);for(int k:kept)for(int c:new int[]{k-1,k-2})if(c>0)keep.add(c);
  StringBuilder show=new StringBuilder(),fresh=new StringBuilder();
  for(int k:keep)if(k>0){if(show.length()>0)show.append('\n');show.append(lines[k]);}
  for(int k:kept)if(k>0){if(fresh.length()>0)fresh.append('\n');fresh.append(lines[k]);}
  out.show=show.toString();out.freshText=fresh.toString();return out;
 }
 static String pair(String prev,String line){return key(prev+"\u0001"+line);}
 static String key(String s){
  try{byte[] d=MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(int i=0;i<8;i++)b.append(String.format(Locale.ROOT,"%02x",d[i]));return b.toString();}
  catch(Exception e){throw new IllegalStateException(e);}
 }
}
