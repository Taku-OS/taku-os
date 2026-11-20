package org.agentos.memory;

import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Off-device timing and parity of Recall's in-memory stages, old (0.15.1) against new: evidence vector top-k, node
 * keyword and vector lists, fusion and the one-hop expansion. Reads the store written by gen_big.py (node vectors in nodevec.f32).
 */
public class RecallBenchBig {
 static final String[] Q={"婷婷","体检","明天要交什么材料","驿站 取件码","slides friday","下周一前交报告","日料","房租 月底"};
 public static void main(String[] a)throws Exception{
  String base=a[0];int reps=Integer.parseInt(a[1]);int maxNodes=a.length>2?Integer.parseInt(a[2]):Integer.MAX_VALUE;
  // vectors: per row 8-byte first_seen + 512 floats, ids m%07d in order
  long bytes=Files.size(Paths.get(base+".vectors.f32"));int n=(int)(bytes/(8+512*4));
  DataInputStream vin=new DataInputStream(new BufferedInputStream(new FileInputStream(base+".vectors.f32"),1<<20));byte[] rec=new byte[8+2048];
  OldRecall.vecIds.clear();OldRecall.vecSeen.clear();OldRecall.vecs.clear();OldRecall.vecSet.clear();OldRecall.vecLoaded=true;
  Recall.resetVectors();long maxSeen=0;
  long load0=System.nanoTime();
  for(int i=0;i<n;i++){vin.readFully(rec);ByteBuffer bb=ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);long seen=bb.getLong();float[] v=new float[512];bb.asFloatBuffer().get(v);String id=String.format("m%07d",i);
   OldRecall.vecIds.add(id);OldRecall.vecSet.add(id);OldRecall.vecSeen.add(seen);OldRecall.vecs.add(v);Recall.append(id,seen,v);maxSeen=Math.max(maxSeen,seen);}
  Recall.vecLoaded=true;System.out.printf("loaded %d vectors in %.1f s; heap used %.2f GB%n",n,(System.nanoTime()-load0)/1e9,(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1e9);
  // nodes
  Map<String,List<JSONObject>> nodes=new LinkedHashMap<>();Map<String,List<float[]>> nvs=new HashMap<>();Map<String,List<Recall.NodeVer>> vers=new LinkedHashMap<>();
  DataInputStream nvIn=new DataInputStream(new BufferedInputStream(new FileInputStream(base+".nodevec.f32"),1<<20));byte[] row=new byte[2048];
  try(BufferedReader r=Files.newBufferedReader(Paths.get(base+".nodes.jsonl"))){String line;while((line=r.readLine())!=null){JSONObject o=new JSONObject(line);String nid=o.getString("nid");nvIn.readFully(row);if(Integer.parseInt(nid.substring(1))>=maxNodes)continue;JSONObject d=o.getJSONObject("data");float[] v=new float[512];ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(v);
   nodes.computeIfAbsent(nid,k->new ArrayList<>()).add(d);nvs.computeIfAbsent(nid,k->new ArrayList<>()).add(v);vers.computeIfAbsent(nid,k->new ArrayList<>()).add(new Recall.NodeVer(nid,d,v));}}
  System.out.printf("loaded %d nodes; heap used %.2f GB%n",nodes.size(),(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1e9);
  OldRecall.nodes=nodes;OldRecall.nodeVecs=nvs;Recall.nodes=nodes;Recall.nodeVecs=nvs;Recall.nodeVers=vers;
  long now=maxSeen+1;Random rnd=new Random(3);
  List<Object[]> work=new ArrayList<>();
  for(String q:Q){
   List<String> ts=new ArrayList<>();for(String t:q.trim().split("\\s+"))if(!t.isEmpty())ts.add(t.toLowerCase(Locale.ROOT));
   float[] qv=new float[512];double nn=0;for(int j=0;j<512;j++){qv[j]=(float)rnd.nextGaussian();nn+=qv[j]*qv[j];}for(int j=0;j<512;j++)qv[j]/=Math.sqrt(nn);
   float[] near=Arrays.copyOf(OldRecall.vecs.get(rnd.nextInt(n)),512);   // Also test a query vector identical to a stored record (equal scores).
   List<String> kw=new ArrayList<>();for(int i=n-1;i>=0&&kw.size()<30;i-=7)kw.add("e:"+OldRecall.vecIds.get(i));
   work.add(new Object[]{q,ts,qv,kw});work.add(new Object[]{q+" (exact)",ts,near,kw});
  }
  // parity
  int same=0;
  for(Object[] w:work){List<String> o=oldRank(castL(w[1]),(float[])w[2],castL(w[3]),now),x=newRank(castL(w[1]),(float[])w[2],castL(w[3]),now);if(o.equals(x))same++;else System.out.println("DIFF "+w[0]+"\n old "+o.subList(0,Math.min(12,o.size()))+"\n new "+x.subList(0,Math.min(12,x.size())));}
  System.out.printf("%s: %d memories, %d nodes; ranked lists identical for %d/%d queries%n",Paths.get(base).getFileName(),n,nodes.size(),same,work.size());
  // timing: warm up, then median per stage
  for(int i=0;i<3;i++)for(Object[] w:work){oldRank(castL(w[1]),(float[])w[2],castL(w[3]),now);newRank(castL(w[1]),(float[])w[2],castL(w[3]),now);}
  long[][] t=new long[8][work.size()*reps];int c=0;
  for(int r=0;r<reps;r++)for(Object[] w:work){List<String> ts=castL(w[1]);float[] qv=(float[])w[2];
   long a0=System.nanoTime();OldRecall.topK(qv,now);long a1=System.nanoTime();Recall.topK(qv,now);long a2=System.nanoTime();
   oldNodes(ts,qv,now);long a3=System.nanoTime();newNodes(ts,qv,now);long a4=System.nanoTime();
   oldRank(ts,qv,castL(w[3]),now);long a5=System.nanoTime();newRank(ts,qv,castL(w[3]),now);long a6=System.nanoTime();
   t[0][c]=a1-a0;t[1][c]=a2-a1;t[2][c]=a3-a2;t[3][c]=a4-a3;t[4][c]=a5-a4;t[5][c]=a6-a5;c++;}
  System.out.printf("  evidence vector top-30: %.2f -> %.2f ms | node lists: %.2f -> %.2f ms | whole in-memory ranking: %.2f -> %.2f ms (median)%n",med(t[0]),med(t[1]),med(t[2]),med(t[3]),med(t[4]),med(t[5]));
 }
 @SuppressWarnings("unchecked") static List<String> castL(Object o){return (List<String>)o;}
 static double med(long[] x){long[] y=x.clone();Arrays.sort(y);return y[y.length/2]/1e6;}
 /** 0.15.1 Recall.search after the keyword SQL, verbatim except for the evidence keyword list passed in. */
 static List<String> oldRank(List<String> ts,float[] qv,List<String> kw,long now){
  List<List<String>> lists=new ArrayList<>();lists.add(kw);
  lists.add(OldRecall.topK(qv,now));
  int LIST=OldRecall.LIST,HOP_SEEDS=OldRecall.HOP_SEEDS;
  List<Object[]> nv=OldRecall.nodeView(now);Map<String,Object[]> byId=new HashMap<>();for(Object[] n:nv)byId.put((String)n[0],n);
  if(!nv.isEmpty()){
   List<Object[]> kn=new ArrayList<>();
   for(Object[] n:nv){String low=OldRecall.ntext((JSONObject)n[1]).toLowerCase(Locale.ROOT);boolean all=true;for(String t:ts)if(!low.contains(t)){all=false;break;}if(all)kn.add(n);}
   kn.sort((a,b)->Long.compare(((JSONObject)b[1]).optLong("t"),((JSONObject)a[1]).optLong("t")));
   List<String> kl=new ArrayList<>();for(int i=0;i<Math.min(LIST,kn.size());i++)kl.add("n:"+kn.get(i)[0]);lists.add(kl);
   if(qv!=null){
    List<Object[]> scored=new ArrayList<>();for(Object[] n:nv)if(n[2]!=null)scored.add(new Object[]{n[0],Embedder.dot((float[])n[2],qv)});
    scored.sort((a,b)->Float.compare((float)b[1],(float)a[1]));
    List<String> vl=new ArrayList<>();for(int i=0;i<Math.min(LIST,scored.size());i++)vl.add("n:"+scored.get(i)[0]);lists.add(vl);
   }
  }
  Map<String,Double> score=new LinkedHashMap<>();
  for(List<String> l:lists)for(int r=0;r<l.size();r++)score.merge(l.get(r),1.0/(60+r),Double::sum);
  if(!nv.isEmpty()){
   List<String> seeds=new ArrayList<>();for(String x:score.keySet())if(x.startsWith("n:"))seeds.add(x);
   seeds.sort((a,b)->Double.compare(score.get(b),score.get(a)));
   for(String x:new ArrayList<>(seeds.subList(0,Math.min(HOP_SEEDS,seeds.size())))){
    String nid=x.substring(2);JSONObject v=(JSONObject)byId.get(nid)[1];Set<String> nb=OldRecall.strings(v.optJSONArray("related"));
    for(Object[] m:nv)if(OldRecall.strings(((JSONObject)m[1]).optJSONArray("related")).contains(nid))nb.add((String)m[0]);
    for(String m:nb)if(byId.containsKey(m))score.merge("n:"+m,0.5*score.get(x),Double::sum);
   }
  }
  List<String> ranked=new ArrayList<>(score.keySet());ranked.sort((a,b)->Double.compare(score.get(b),score.get(a)));return ranked;
 }
 /** The new Recall.search after the keyword SQL, in the same order of lists. */
 static List<String> newRank(List<String> ts,float[] qv,List<String> kw,long now){
  List<List<String>> lists=new ArrayList<>();lists.add(kw);lists.add(Recall.topK(qv,now));
  List<Recall.NodeVer> nv=Recall.view(now);Map<String,Recall.NodeVer> byId=new HashMap<>();for(Recall.NodeVer x:nv)byId.put(x.nid,x);
  if(!nv.isEmpty()){lists.add(Recall.nodeKeyword(nv,ts));if(qv!=null)lists.add(Recall.nodeVector(nv,qv));}
  Map<String,Double> score=new LinkedHashMap<>();
  for(List<String> l:lists)for(int r=0;r<l.size();r++)score.merge(l.get(r),1.0/(60+r),Double::sum);
  if(!nv.isEmpty())Recall.hop(score,nv,byId);
  List<String> ranked=new ArrayList<>(score.keySet());ranked.sort((a,b)->Double.compare(score.get(b),score.get(a)));return ranked;
 }
 static void oldNodes(List<String> ts,float[] qv,long now){
  List<Object[]> nv=OldRecall.nodeView(now);List<Object[]> kn=new ArrayList<>();
  for(Object[] n:nv){String low=OldRecall.ntext((JSONObject)n[1]).toLowerCase(Locale.ROOT);boolean all=true;for(String t:ts)if(!low.contains(t)){all=false;break;}if(all)kn.add(n);}
  List<Object[]> scored=new ArrayList<>();for(Object[] n:nv)if(n[2]!=null)scored.add(new Object[]{n[0],Embedder.dot((float[])n[2],qv)});scored.sort((a,b)->Float.compare((float)b[1],(float)a[1]));
 }
 static void newNodes(List<String> ts,float[] qv,long now){List<Recall.NodeVer> nv=Recall.view(now);Recall.nodeKeyword(nv,ts);Recall.nodeVector(nv,qv);}
}
