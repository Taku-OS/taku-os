package org.agentos.memory;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Exact port of the Hugging Face tokenizer that bekko-embedding-v1 ships (BPE with byte fallback, 256k vocabulary):
 * 1. Added tokens ("\n", tab and space runs, a few HTML tags, specials) are cut out of the raw text first, leftmost-longest.
 * 2. Every other piece: spaces become ▁, a ▁ is prepended, and the piece is split before each ▁ (Metaspace, prepend always).
 * 3. Each word starts as its characters (a character outside the vocabulary becomes its UTF-8 byte tokens) and merges are
 *    applied lowest rank first, leftmost first among equals.
 * 4. The result is wrapped as <bos> … <eos>.
 * Reads the table that tools/tokenizer_bin.py writes. Merges stay in the memory-mapped file instead of the Java heap.
 */
final class Bpe {
 static final char META='▁';
 final int bos,eos,unk;final int[] byteIds=new int[256];
 final String[] added;final int[] addedIds,addedFlags;
 final Map<Character,int[]> addedByFirst=new HashMap<>();   // first UTF-16 unit -> added token indexes, longest first
 final int[] charCps,charIds;
 final ByteBuffer table;final int merges,keysAt,ranksAt,idsAt;

 static Bpe open(File f)throws IOException{
  try(FileInputStream in=new FileInputStream(f);FileChannel ch=in.getChannel()){return new Bpe(ch.map(FileChannel.MapMode.READ_ONLY,0,ch.size()));}
 }
 Bpe(ByteBuffer b)throws IOException{
  b=b.duplicate().order(ByteOrder.LITTLE_ENDIAN);
  byte[] magic=new byte[8];b.get(magic);if(!new String(magic,StandardCharsets.US_ASCII).equals("TKBPE001"))throw new IOException("Not a tokenizer table");
  bos=b.getInt();eos=b.getInt();unk=b.getInt();for(int i=0;i<256;i++)byteIds[i]=b.getInt();
  int n=b.getInt();added=new String[n];addedIds=new int[n];addedFlags=new int[n];
  for(int i=0;i<n;i++){addedIds[i]=b.getInt();addedFlags[i]=b.getInt();byte[] s=new byte[b.getInt()];b.get(s);added[i]=new String(s,StandardCharsets.UTF_8);}
  Map<Character,List<Integer>> first=new HashMap<>();
  for(int i=0;i<n;i++)if(!added[i].isEmpty())first.computeIfAbsent(added[i].charAt(0),k->new ArrayList<>()).add(i);
  for(Map.Entry<Character,List<Integer>> e:first.entrySet()){List<Integer> l=e.getValue();l.sort((x,y)->added[y].length()-added[x].length());addedByFirst.put(e.getKey(),l.stream().mapToInt(Integer::intValue).toArray());}
  int c=b.getInt();charCps=new int[c];charIds=new int[c];for(int i=0;i<c;i++)charCps[i]=b.getInt();for(int i=0;i<c;i++)charIds[i]=b.getInt();
  merges=b.getInt();b.position((b.position()+7)&~7);
  keysAt=b.position();ranksAt=keysAt+8*merges;idsAt=ranksAt+4*merges;
  if(idsAt+4L*merges>b.limit())throw new IOException("Truncated tokenizer table");
  table=b;
 }

 /** Token ids for text, at most max of them including <bos> and <eos> (the text is cut at the end, like truncation). */
 int[] encode(String text,int max){
  List<Integer> ids=new ArrayList<>();ids.add(bos);
  int limit=max-1;
  for(Object piece:split(text)){
   if(ids.size()>=limit)break;
   if(piece instanceof Integer){ids.add((Integer)piece);continue;}
   String s=((String)piece).replace(' ',META);if(s.charAt(0)!=META)s=META+s;
   int start=0;
   for(int i=1;i<=s.length();i++)if(i==s.length()||s.charAt(i)==META){word(s.substring(start,i),ids);start=i;if(ids.size()>=limit)break;}
  }
  while(ids.size()>limit)ids.remove(ids.size()-1);
  ids.add(eos);
  int[] r=new int[ids.size()];for(int i=0;i<r.length;i++)r[i]=ids.get(i);return r;
 }

 /** The text cut into added-token ids (Integer) and the strings between them (String, never empty). */
 List<Object> split(String text){
  List<Object> out=new ArrayList<>();int done=0,i=0,n=text.length();
  while(i<n){
   int[] cand=addedByFirst.get(text.charAt(i));int hit=-1;
   if(cand!=null)for(int k:cand)if(text.startsWith(added[k],i)){hit=k;break;}
   if(hit<0){i++;continue;}
   int from=i,to=i+added[hit].length();
   if((addedFlags[hit]&1)!=0)while(from>done&&space(text.charAt(from-1)))from--;
   if((addedFlags[hit]&2)!=0)while(to<n&&space(text.charAt(to)))to++;
   if(from>done)out.add(text.substring(done,from));
   out.add(addedIds[hit]);done=i=to;
  }
  if(done<n)out.add(text.substring(done));
  return out;
 }

 /** Unicode White_Space, as Rust's char::is_whitespace (Java's isWhitespace leaves out no-break spaces). */
 static boolean space(char c){return (c>=9&&c<=13)||c==' '||c==0x85||c==0xA0||c==0x1680||(c>=0x2000&&c<=0x200A)||c==0x2028||c==0x2029||c==0x202F||c==0x205F||c==0x3000;}
 /** One pre-tokenized word through BPE, appended to out. */
 void word(String w,List<Integer> out){
  int[] sym=new int[w.length()*4];int count=0;
  for(int i=0;i<w.length();){
   int cp=w.codePointAt(i);i+=Character.charCount(cp);
   int k=Arrays.binarySearch(charCps,cp);
   if(k>=0){sym[count++]=charIds[k];continue;}
   byte[] bytes=new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);boolean all=true;for(byte x:bytes)if(byteIds[x&0xFF]<0)all=false;
   if(all)for(byte x:bytes)sym[count++]=byteIds[x&0xFF];
   else if(count==0||sym[count-1]!=unk)sym[count++]=unk;   // fuse_unk
  }
  int[] prev=new int[count],next=new int[count];boolean[] gone=new boolean[count];
  for(int i=0;i<count;i++){prev[i]=i-1;next[i]=i+1<count?i+1:-1;}
  PriorityQueue<Long> queue=new PriorityQueue<>();   // rank << 32 | position: lowest rank, then leftmost
  for(int i=0;i+1<count;i++){int r=merge(sym[i],sym[i+1]);if(r>=0)queue.add((long)rank(r)<<32|i);}
  while(!queue.isEmpty()){
   long top=queue.poll();int pos=(int)top,want=(int)(top>>>32);
   if(gone[pos]||next[pos]<0)continue;
   int right=next[pos],m=merge(sym[pos],sym[right]);
   if(m<0||rank(m)!=want)continue;   // this pair changed since it was queued
   sym[pos]=table.getInt(idsAt+4*m);gone[right]=true;next[pos]=next[right];if(next[right]>=0)prev[next[right]]=pos;
   if(prev[pos]>=0){int r=merge(sym[prev[pos]],sym[pos]);if(r>=0)queue.add((long)rank(r)<<32|prev[pos]);}
   if(next[pos]>=0){int r=merge(sym[pos],sym[next[pos]]);if(r>=0)queue.add((long)rank(r)<<32|pos);}
  }
  for(int i=0;i>=0&&count>0;i=next[i])out.add(sym[i]);
 }
 int rank(int m){return table.getInt(ranksAt+4*m);}
 /** Index of the merge for this pair, or -1. */
 int merge(int left,int right){
  long key=((long)left<<32)|(right&0xFFFFFFFFL);int lo=0,hi=merges-1;
  while(lo<=hi){int mid=(lo+hi)>>>1;long k=table.getLong(keysAt+8*mid);if(k<key)lo=mid+1;else if(k>key)hi=mid-1;else return mid;}
  return -1;
 }
}
